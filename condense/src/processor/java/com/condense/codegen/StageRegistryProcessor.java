package com.condense.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Build-time registry generator. Not loaded in the native image.
 */
@SupportedAnnotationTypes("com.condense.annotation.DeclarativeStage")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class StageRegistryProcessor extends AbstractProcessor {

    static final String ANNOTATION = "com.condense.annotation.DeclarativeStage";
    static final String REGISTRY = "com.condense.filter.pipeline.config.GeneratedStageRegistry";
    private static final Set<String> CAPABILITIES = Set.of("REDUCE", "RESHAPE", "REWRITE");
    private boolean written;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || written) {
            return false;
        }
        TypeElement annotationType = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
        if (annotationType == null) {
            return false;
        }
        List<StageSpec> stages = new ArrayList<>();
        boolean failed = false;
        for (Element element : roundEnv.getElementsAnnotatedWith(annotationType)) {
            if (element.getKind() != ElementKind.CLASS || !(element instanceof TypeElement type)) {
                error(element, "@DeclarativeStage is only valid on classes");
                failed = true;
                continue;
            }
            StageSpec spec = readSpec(type);
            if (spec == null) {
                failed = true;
                continue;
            }
            stages.add(spec);
        }
        if (failed) {
            return true;
        }
        stages.sort(Comparator.comparing(stage -> stage.aliases.getFirst()));
        if (rejectDuplicateAliases(stages)) {
            return true;
        }
        try {
            writeRegistry(stages);
            writeInventory(stages);
            written = true;
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR, "Failed to write GeneratedStageRegistry: " + e.getMessage());
        }
        return true;
    }

    private StageSpec readSpec(TypeElement type) {
        var annotation = type.getAnnotationMirrors().stream()
            .filter(mirror -> ANNOTATION.equals(mirror.getAnnotationType().toString()))
            .findFirst()
            .orElse(null);
        if (annotation == null) {
            return null;
        }
        List<String> aliases = new ArrayList<>();
        String capability = "";
        String singleton = "";
        String factory = "";
        for (var entry : annotation.getElementValues().entrySet()) {
            String name = entry.getKey().getSimpleName().toString();
            Object value = entry.getValue().getValue();
            switch (name) {
                case "aliases" -> {
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            aliases.add(unwrap(item));
                        }
                    }
                }
                case "capability" -> capability = unwrap(value);
                case "singleton" -> singleton = unwrap(value);
                case "factory" -> factory = unwrap(value);
                default -> {
                }
            }
        }
        if (aliases.isEmpty() || aliases.stream().anyMatch(String::isBlank)) {
            error(type, "@DeclarativeStage aliases must be non-empty");
            return null;
        }
        if (!CAPABILITIES.contains(capability)) {
            error(type, "@DeclarativeStage capability must be REDUCE, RESHAPE, or REWRITE");
            return null;
        }
        boolean hasSingleton = singleton != null && !singleton.isBlank();
        boolean hasFactory = factory != null && !factory.isBlank();
        if (hasSingleton == hasFactory) {
            error(type, "@DeclarativeStage must set exactly one of singleton or factory");
            return null;
        }
        if (!implementsFilterStage(type)) {
            error(type, "@DeclarativeStage is only valid on FilterStage implementations");
            return null;
        }
        if (hasSingleton && !hasStaticField(type, singleton)) {
            error(type, "singleton field '" + singleton + "' was not found");
            return null;
        }
        if (hasFactory && !hasStaticMethod(type, factory)) {
            error(type, "factory method '" + factory + "' was not found");
            return null;
        }
        return new StageSpec(type.getQualifiedName().toString(), type.getSimpleName().toString(),
            List.copyOf(aliases), capability, hasSingleton ? singleton : "", hasFactory ? factory : "");
    }

    private boolean rejectDuplicateAliases(List<StageSpec> stages) {
        Map<String, String> claimed = new HashMap<>();
        boolean failed = false;
        for (StageSpec stage : stages) {
            for (String alias : stage.aliases) {
                String normalized = alias.trim().toLowerCase();
                String previous = claimed.put(normalized, stage.className);
                if (previous != null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Duplicate stage alias '" + alias + "' on " + stage.className
                            + " (already claimed by " + previous + ")");
                    failed = true;
                }
            }
        }
        return failed;
    }

    private void writeRegistry(List<StageSpec> stages) throws IOException {
        var file = processingEnv.getFiler().createSourceFile(REGISTRY);
        try (Writer writer = file.openWriter()) {
            writer.write(renderRegistry(stages));
        }
    }

    private void writeInventory(List<StageSpec> stages) throws IOException {
        var file = processingEnv.getFiler().createResource(
            StandardLocation.CLASS_OUTPUT, "", "META-INF/condense/stage-inventory.json");
        try (OutputStream out = file.openOutputStream()) {
            out.write(renderInventory(stages).getBytes(StandardCharsets.UTF_8));
        }
    }

    static String renderRegistry(List<StageSpec> stages) {
        StringBuilder out = new StringBuilder();
        out.append("package com.condense.filter.pipeline.config;\n\n");
        out.append("import com.condense.filter.pipeline.FilterStage;\n");
        out.append("import com.condense.trust.Capability;\n\n");
        Set<String> imports = new TreeSet<>();
        for (StageSpec stage : stages) {
            imports.add(stage.className);
        }
        for (String type : imports) {
            out.append("import ").append(type).append(";\n");
        }
        if (!imports.isEmpty()) {
            out.append('\n');
        }
        out.append("import java.util.List;\n");
        out.append("import java.util.Set;\n\n");
        out.append("/** Generated by StageRegistryProcessor. Do not edit. */\n");
        out.append("public final class GeneratedStageRegistry {\n");
        out.append("    private GeneratedStageRegistry() {}\n\n");
        List<String> allAliases = new ArrayList<>();
        for (StageSpec stage : stages) {
            allAliases.addAll(stage.aliases);
        }
        allAliases.sort(String::compareTo);
        if (allAliases.isEmpty()) {
            out.append("    public static final Set<String> ALLOWED_ALIASES = Set.of();\n\n");
        } else {
            out.append("    public static final Set<String> ALLOWED_ALIASES = Set.of(\n");
            for (int i = 0; i < allAliases.size(); i++) {
                out.append("        \"").append(escape(allAliases.get(i))).append('"');
                out.append(i + 1 < allAliases.size() ? ",\n" : "\n");
            }
            out.append("    );\n\n");
        }
        out.append("    public static String canonicalAlias(String normalized) {\n");
        out.append("        return switch (normalized) {\n");
        for (StageSpec stage : stages) {
            out.append("            case ").append(caseLabels(stage.aliases))
                .append(" -> \"").append(escape(stage.aliases.getFirst())).append("\";\n");
        }
        out.append("            default -> normalized;\n");
        out.append("        };\n");
        out.append("    }\n\n");
        out.append("    public static Capability capabilityOf(String normalized) {\n");
        out.append("        return switch (normalized) {\n");
        for (StageSpec stage : stages) {
            out.append("            case ").append(caseLabels(stage.aliases))
                .append(" -> Capability.").append(stage.capability).append(";\n");
        }
        out.append("            default -> Capability.RESHAPE;\n");
        out.append("        };\n");
        out.append("    }\n\n");
        out.append("    public static FilterStage instantiateRaw(FilterOverrideConfig.StageDef stageDef) {\n");
        out.append("        if (stageDef == null || stageDef.strategy() == null) {\n");
        out.append("            return null;\n");
        out.append("        }\n");
        out.append("        String normalized = stageDef.strategy().trim().toLowerCase(java.util.Locale.ROOT);\n");
        out.append("        return switch (normalized) {\n");
        for (StageSpec stage : stages) {
            out.append("            case ").append(caseLabels(stage.aliases)).append(" -> ");
            if (!stage.singleton.isEmpty()) {
                out.append(stage.simpleName).append('.').append(stage.singleton);
            } else {
                out.append(stage.simpleName).append('.').append(stage.factory).append("(stageDef)");
            }
            out.append(";\n");
        }
        out.append("            default -> null;\n");
        out.append("        };\n");
        out.append("    }\n\n");
        out.append("    public static void validate(String location, FilterOverrideConfig.StageDef stage, List<String> errors) {\n");
        out.append("        if (stage == null || stage.strategy() == null) {\n");
        out.append("            return;\n");
        out.append("        }\n");
        out.append("        String normalized = stage.strategy().trim().toLowerCase(java.util.Locale.ROOT);\n");
        out.append("        switch (normalized) {\n");
        for (StageSpec stage : stages) {
            if (stage.factory.isEmpty()) {
                continue;
            }
            out.append("            case ").append(caseLabels(stage.aliases)).append(" -> ")
                .append(stage.simpleName).append(".validate(location, stage, errors);\n");
        }
        out.append("            default -> {\n");
        out.append("            }\n");
        out.append("        }\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }

    static String renderInventory(List<StageSpec> stages) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"stages\": [\n");
        for (int i = 0; i < stages.size(); i++) {
            StageSpec stage = stages.get(i);
            out.append("    {\n");
            out.append("      \"canonical\": \"").append(escape(stage.aliases.getFirst())).append("\",\n");
            out.append("      \"aliases\": [");
            for (int a = 0; a < stage.aliases.size(); a++) {
                if (a > 0) {
                    out.append(", ");
                }
                out.append('"').append(escape(stage.aliases.get(a))).append('"');
            }
            out.append("],\n");
            out.append("      \"capability\": \"").append(stage.capability).append("\",\n");
            out.append("      \"className\": \"").append(escape(stage.className)).append("\"\n");
            out.append("    }");
            out.append(i + 1 < stages.size() ? ",\n" : "\n");
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    private static String caseLabels(List<String> aliases) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < aliases.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('"').append(escape(aliases.get(i))).append('"');
        }
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unwrap(Object value) {
        String text = String.valueOf(value);
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private boolean implementsFilterStage(TypeElement type) {
        for (TypeMirror iface : type.getInterfaces()) {
            String name = iface.toString();
            if (name.equals("com.condense.filter.pipeline.FilterStage") || name.endsWith(".FilterStage")) {
                return true;
            }
        }
        TypeMirror parent = type.getSuperclass();
        if (parent != null) {
            Element element = processingEnv.getTypeUtils().asElement(parent);
            if (element instanceof TypeElement parentType && implementsFilterStage(parentType)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStaticField(TypeElement type, String name) {
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                && enclosed.getSimpleName().contentEquals(name)
                && enclosed.getModifiers().contains(Modifier.STATIC)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStaticMethod(TypeElement type, String name) {
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD
                && enclosed.getSimpleName().contentEquals(name)
                && enclosed.getModifiers().contains(Modifier.STATIC)) {
                return true;
            }
        }
        return false;
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    record StageSpec(
        String className,
        String simpleName,
        List<String> aliases,
        String capability,
        String singleton,
        String factory
    ) {}
}
