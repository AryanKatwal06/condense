package com.condense.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StageProcessorCompileTest {

    @TempDir
    Path tempDir;

    @Test
    void processorEmitsGoldenRegistry() throws Exception {
        Path generated = compileFixtures(alphaSource(), betaSource());
        assertThat(read(generated)).isEqualTo(golden());
    }

    @Test
    void processorOutputIsDeterministic() throws Exception {
        Path first = compileFixtures(tempDir.resolve("run1"), alphaSource(), betaSource());
        Path second = compileFixtures(tempDir.resolve("run2"), betaSource(), alphaSource());
        assertThat(read(first)).isEqualTo(read(second));
        assertThat(read(first)).isEqualTo(golden());
    }

    @Test
    void duplicateAliasFailsCompilation() throws Exception {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean success = compile(tempDir.resolve("dup"), List.of(alphaSource(), collidingSource()), diagnostics);
        assertThat(success).isFalse();
        assertThat(diagnostics.getDiagnostics().toString()).contains("Duplicate stage alias");
    }

    private Path compileFixtures(String... sources) throws Exception {
        return compileFixtures(tempDir.resolve("ok"), sources);
    }

    private Path compileFixtures(Path work, String... sources) throws Exception {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean success = compile(work, List.of(sources), diagnostics);
        assertThat(success)
            .as(diagnostics.getDiagnostics().toString())
            .isTrue();
        Path generated = work.resolve("generated")
            .resolve("com/condense/filter/pipeline/config/GeneratedStageRegistry.java");
        assertThat(generated).exists();
        return generated;
    }

    private boolean compile(Path work, List<String> sources, DiagnosticCollector<JavaFileObject> diagnostics)
            throws Exception {
        Path src = work.resolve("src");
        Files.createDirectories(src);
        List<Path> files = new ArrayList<>();
        files.add(write(src.resolve("com/condense/annotation/DeclarativeStage.java"), annotationSource()));
        files.add(write(src.resolve("com/condense/filter/pipeline/FilterStage.java"), filterStageSource()));
        files.add(write(src.resolve("com/condense/trust/Capability.java"), capabilitySource()));
        files.add(write(src.resolve("com/condense/filter/pipeline/config/FilterOverrideConfig.java"), stageDefSource()));
        for (String source : sources) {
            String className = classNameOf(source);
            String packagePath = packagePathOf(source);
            files.add(write(src.resolve(packagePath).resolve(className + ".java"), source));
        }
        Path classes = work.resolve("classes");
        Path generated = work.resolve("generated");
        Files.createDirectories(classes);
        Files.createDirectories(generated);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
            diagnostics, null, StandardCharsets.UTF_8)
        ) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
            fileManager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of(processorClasses()));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            StringWriter out = new StringWriter();
            JavaCompiler.CompilationTask task = compiler.getTask(
                out,
                fileManager,
                diagnostics,
                List.of("-proc:only", "-processor", "com.condense.codegen.StageRegistryProcessor"),
                null,
                fileManager.getJavaFileObjectsFromPaths(files)
            );
            return Boolean.TRUE.equals(task.call());
        }
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String golden() throws Exception {
        return new String(
            StageProcessorCompileTest.class.getResourceAsStream("/codegen/generated-stage-registry.golden")
                .readAllBytes(),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String classNameOf(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("public class ")) {
                return trimmed.substring("public class ".length()).split(" ")[0];
            }
        }
        throw new AssertionError("public class not found");
    }

    private static String packagePathOf(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).replace('.', '/');
            }
        }
        return "";
    }

    private static Path write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
        return path;
    }

    private static Path processorClasses() {
        Path direct = Path.of("target", "processor-classes");
        if (Files.isDirectory(direct)) {
            return direct.toAbsolutePath();
        }
        Path nested = Path.of("condense", "target", "processor-classes");
        if (Files.isDirectory(nested)) {
            return nested.toAbsolutePath();
        }
        throw new AssertionError("processor-classes directory is missing");
    }

    private static String annotationSource() {
        return """
            package com.condense.annotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.SOURCE)
            @Target(ElementType.TYPE)
            public @interface DeclarativeStage {
                String[] aliases();
                String capability();
                String singleton() default "";
                String factory() default "";
            }
            """;
    }

    private static String filterStageSource() {
        return """
            package com.condense.filter.pipeline;
            public interface FilterStage {}
            """;
    }

    private static String capabilitySource() {
        return """
            package com.condense.trust;
            public enum Capability { REDUCE, RESHAPE, REWRITE }
            """;
    }

    private static String stageDefSource() {
        return """
            package com.condense.filter.pipeline.config;
            public final class FilterOverrideConfig {
                public record StageDef(String strategy) {}
            }
            """;
    }

    private static String alphaSource() {
        return """
            package fixture;
            import com.condense.annotation.DeclarativeStage;
            import com.condense.filter.pipeline.FilterStage;
            @DeclarativeStage(aliases = {"alpha", "alpha-alt"}, capability = "REDUCE", singleton = "INSTANCE")
            public class AlphaStage implements FilterStage {
                public static final AlphaStage INSTANCE = new AlphaStage();
            }
            """;
    }

    private static String betaSource() {
        return """
            package fixture;
            import com.condense.annotation.DeclarativeStage;
            import com.condense.filter.pipeline.FilterStage;
            import java.util.List;
            @DeclarativeStage(aliases = {"beta"}, capability = "REWRITE", factory = "fromDef")
            public class BetaStage implements FilterStage {
                public static FilterStage fromDef(Object def) { return new BetaStage(); }
                public static void validate(String location, Object def, List<String> errors) {}
            }
            """;
    }

    private static String collidingSource() {
        return """
            package fixture;
            import com.condense.annotation.DeclarativeStage;
            import com.condense.filter.pipeline.FilterStage;
            @DeclarativeStage(aliases = {"alpha"}, capability = "RESHAPE", singleton = "INSTANCE")
            public class CollidingStage implements FilterStage {
                public static final CollidingStage INSTANCE = new CollidingStage();
            }
            """;
    }
}
