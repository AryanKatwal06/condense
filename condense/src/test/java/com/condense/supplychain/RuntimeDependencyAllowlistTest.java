package com.condense.supplychain;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail {@code mvn test} when a runtime Maven coordinate is added without
 * updating the committed allowlist. Test and provided scopes are ignored.
 */
class RuntimeDependencyAllowlistTest {

    private static final String ALLOWLIST = "/supplychain/runtime-dependencies.txt";

    @Test
    void pomRuntimeDependenciesMatchCommittedAllowlist() throws Exception {
        Set<String> allowed = readAllowlist();
        Set<String> actual = readPomRuntimeCoordinates(findPom());
        assertThat(actual)
            .as("runtime dependencies in pom.xml must match %s", ALLOWLIST)
            .containsExactlyInAnyOrderElementsOf(allowed);
    }

    private static Path findPom() {
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path direct = cwd.resolve("pom.xml");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path nested = cwd.resolve("condense").resolve("pom.xml");
        if (Files.isRegularFile(nested)) {
            return nested;
        }
        throw new AssertionError("Could not find pom.xml from " + cwd.toAbsolutePath());
    }

    private static Set<String> readAllowlist() throws Exception {
        InputStream in = RuntimeDependencyAllowlistTest.class.getResourceAsStream(ALLOWLIST);
        assertThat(in).as(ALLOWLIST + " must be on the test classpath").isNotNull();
        Set<String> lines = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                lines.add(trimmed);
            }
        }
        assertThat(lines).as("allowlist must not be empty").isNotEmpty();
        return lines;
    }

    private static Set<String> readPomRuntimeCoordinates(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(pom.toFile());
        Element project = doc.getDocumentElement();
        Element dependencies = firstChild(project, "dependencies");
        assertThat(dependencies)
            .as("pom.xml must have a top-level <dependencies> element")
            .isNotNull();

        Set<String> coords = new LinkedHashSet<>();
        NodeList children = dependencies.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE || !"dependency".equals(node.getNodeName())) {
                continue;
            }
            Element dep = (Element) node;
            String scope = text(dep, "scope");
            if ("test".equals(scope) || "provided".equals(scope)) {
                continue;
            }
            String groupId = text(dep, "groupId");
            String artifactId = text(dep, "artifactId");
            String version = text(dep, "version");
            assertThat(groupId).as("dependency groupId").isNotBlank();
            assertThat(artifactId).as("dependency artifactId").isNotBlank();
            if (version.isBlank()) {
                coords.add(groupId + ":" + artifactId);
            } else {
                coords.add(groupId + ":" + artifactId + ":" + version);
            }
        }
        return coords;
    }

    private static Element firstChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element child = firstChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }
}
