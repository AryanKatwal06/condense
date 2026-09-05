package com.condense.nativeimage;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.CondenseConfig;
import com.condense.core.FilterStrategy;
import com.condense.core.TeeMode;
import com.condense.core.TrackingRepository;
import com.condense.filter.pipeline.config.FilterOverrideConfig;
import com.condense.filter.pipeline.config.FilterOverrideValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JVM gate that native-image reflection registration has not drifted from
 * the classes that actually need it. Cheap enough to run on every {@code mvn test}.
 */
class ReflectConfigDriftTest {

    private static final String RESOURCE = "/META-INF/native-image/reflect-config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reflectConfigRegistersRequiredTypesAndHasNoDuplicates() throws Exception {
        List<String> names = registeredNames();
        assertThat(names)
            .as("reflect-config.json class names must be unique")
            .doesNotHaveDuplicates();

        assertThat(names)
            .as("FilterStrategy implementations must be registered")
            .containsAll(discoverFilterStrategies());

        assertThat(names).contains(
            CommandFilter.class.getName(),
            CommandFilters.class.getName(),
            CondenseConfig.class.getName(),
            CondenseConfig.HooksConfig.class.getName(),
            CondenseConfig.TeeConfig.class.getName(),
            CondenseConfig.CommandConfig.class.getName(),
            TeeMode.class.getName(),
            FilterOverrideConfig.FileConfig.class.getName(),
            FilterOverrideConfig.FilterDef.class.getName(),
            FilterOverrideConfig.StageDef.class.getName(),
            FilterOverrideConfig.TransitionDef.class.getName(),
            FilterOverrideValidationResult.class.getName(),
            FilterOverrideValidationResult.Status.class.getName(),
            com.condense.filter.pipeline.config.DefinitionError.class.getName(),
            com.condense.filter.pipeline.config.BuiltinDefinition.class.getName(),
            com.condense.filter.pipeline.config.BuiltinDefinition.InlineTest.class.getName(),
            com.condense.filter.pipeline.config.BuiltinDefinition.Index.class.getName(),
            com.condense.analytics.GainReport.class.getName(),
            com.condense.analytics.EstimatorInfo.class.getName(),
            TrackingRepository.AggregateStats.class.getName(),
            TrackingRepository.DailyStat.class.getName(),
            TrackingRepository.WeeklyStat.class.getName(),
            TrackingRepository.TopCommand.class.getName(),
            TrackingRepository.RecentCommand.class.getName(),
            com.condense.trust.TrustFile.class.getName(),
            com.condense.trust.TrustRecord.class.getName(),
            com.condense.trust.Capability.class.getName(),
            com.condense.trust.TrustDecision.class.getName(),
            com.condense.config.ConfigTrustCommand.class.getName(),
            com.condense.doctor.DoctorCommand.class.getName(),
            com.condense.doctor.DoctorReport.class.getName(),
            com.condense.doctor.DoctorReport.HookStatus.class.getName(),
            com.condense.doctor.DoctorService.class.getName(),
            com.condense.explain.ExplainCommand.class.getName(),
            com.condense.explain.ExplainService.class.getName(),
            com.condense.explain.ExplainReport.class.getName(),
            com.condense.explain.ExplainReport.SkippedTier.class.getName(),
            com.condense.explain.ExplainReport.Gate.class.getName(),
            com.condense.explain.ExplainReport.Stage.class.getName(),
            com.condense.explain.ExplainReport.ProvenanceInfo.class.getName(),
            com.condense.explain.ExplainReport.Incident.class.getName(),
            com.condense.read.ReadCommand.class.getName(),
            com.condense.read.ReadReport.class.getName(),
            com.condense.read.ReadLevel.class.getName(),
            com.condense.read.LanguageFamily.class.getName(),
            com.condense.read.RawStringStyle.class.getName(),
            com.condense.read.LanguageDefinition.class.getName(),
            com.condense.read.LanguageDefinition.StringDef.class.getName(),
            com.condense.read.LanguageDefinition.OutlinePattern.class.getName(),
            com.condense.read.LanguageDefinition.InlineTest.class.getName(),
            com.condense.read.LanguageDefinition.Index.class.getName()
        );

        assertThat(com.condense.doctor.DoctorCommand.class.isAnnotationPresent(
                io.quarkus.arc.Unremovable.class))
            .as("DoctorCommand is created only via Picocli programmatic lookup; "
                + "without @Unremovable Quarkus strips it from the native image")
            .isTrue();
        assertThat(com.condense.explain.ExplainCommand.class.isAnnotationPresent(
                io.quarkus.arc.Unremovable.class))
            .as("ExplainCommand is created only via Picocli programmatic lookup; "
                + "without @Unremovable Quarkus strips it from the native image")
            .isTrue();
        assertThat(com.condense.read.ReadCommand.class.isAnnotationPresent(
                io.quarkus.arc.Unremovable.class))
            .as("ReadCommand is created only via Picocli programmatic lookup; "
                + "without @Unremovable Quarkus strips it from the native image")
            .isTrue();
    }

    private static List<String> registeredNames() throws Exception {
        try (InputStream in = ReflectConfigDriftTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE + " must be on the test classpath").isNotNull();
            JsonNode root = MAPPER.readTree(in);
            assertThat(root.isArray()).isTrue();
            List<String> names = new ArrayList<>();
            for (JsonNode entry : root) {
                JsonNode name = entry.get("name");
                assertThat(name)
                    .as("every reflect-config entry must have a name")
                    .isNotNull();
                names.add(name.asText());
            }
            return names;
        }
    }

    private static Set<String> discoverFilterStrategies() throws Exception {
        URI location = FilterStrategy.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path root = Path.of(location);
        assertThat(root)
            .as("FilterStrategy must resolve to a classes directory, not a JAR")
            .isDirectory();

        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> considerClass(root, p, names));
        }
        assertThat(names)
            .as("expected domain filters plus PassthroughStrategy")
            .hasSizeGreaterThanOrEqualTo(33);
        return names;
    }

    private static void considerClass(Path root, Path classFile, Set<String> names) {
        String relative = root.relativize(classFile).toString();
        String className = relative.substring(0, relative.length() - ".class".length())
            .replace('/', '.')
            .replace('\\', '.');
        if (!className.startsWith("com.condense.") || className.contains("package-info")) {
            return;
        }
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException | LinkageError e) {
            return;
        }
        if (type.isInterface() || type.isEnum() || Modifier.isAbstract(type.getModifiers())) {
            return;
        }
        if (FilterStrategy.class.isAssignableFrom(type)) {
            names.add(type.getName());
        }
    }
}
