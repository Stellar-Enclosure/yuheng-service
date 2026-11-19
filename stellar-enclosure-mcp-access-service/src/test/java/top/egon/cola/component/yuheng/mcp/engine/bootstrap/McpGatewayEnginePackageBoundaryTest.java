package top.egon.cola.component.yuheng.mcp.engine.bootstrap;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The dedicated executable owns MCP entrypoints and persistence, not a general gateway. */
class McpGatewayEnginePackageBoundaryTest {
    private static final String MODULE = "stellar-enclosure-mcp-access-service";
    private static final String MAIN = "top.egon.cola.component.yuheng.mcp.engine.McpGatewayEngineApplication";

    private static final Set<String> RELOCATED_ROLE_TYPES = Set.of(
            "top.egon.cola.component.yuheng.admin.mcp.controller.McpApprovalController",
            "top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpApprovalRequestDTO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpApprovalVO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpApprovalOwnerVO",
            "top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService",
            "top.egon.cola.component.yuheng.admin.mcp.service.impl.McpApprovalAdminServiceImpl",
            "top.egon.cola.component.yuheng.admin.mcp.controller.McpTaskAdminController",
            "top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpTaskCancelRequestDTO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpTaskCancelResultVO");

    @Test
    void cannotDependOnGenericGatewayOrAdminExecutableIngress() throws Exception {
        Path root = module().resolve("src/main/java");
        var forbidden = List.of("top.egon.cola.component.yuheng.engine.",
                "top.egon.cola.component.yuheng.runtime.", "top.egon.cola.component.yuheng.admin.",
                "top.egon.cola.component.yuheng.llm.", "top.egon.cola.component.yuheng.core.provider.");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String declaration = line.strip();
                    if (declaration.startsWith("import ")) {
                        assertFalse(forbidden.stream().anyMatch(declaration::contains)
                                && RELOCATED_ROLE_TYPES.stream().noneMatch(type -> declaration.equals("import " + type + ";")
                                        || declaration.startsWith("import " + type + ".")), file + ": " + declaration);
                    }
                }
            }
        }
        var factory = xmlFactory();
        var document = factory.newDocumentBuilder().parse(module().resolve("pom.xml").toFile());
        Set<String> forbiddenArtifacts = Set.of("stellar-enclosure-yuheng-admin", "stellar-enclosure-yuheng-biz-gateway",
                "stellar-enclosure-yuheng-edge-gateway", "stellar-enclosure-yuheng-llm-gateway",
                "stellar-enclosure-model-access-service",
                "stellar-enclosure-yuheng-runtime-core", "stellar-enclosure-yuheng-core", "stellar-enclosure-yuheng-mcp-core");
        var dependencies = document.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            var dependency = (Element) dependencies.item(i);
            String artifact = dependency.getElementsByTagName("artifactId").item(0).getTextContent().strip();
            assertFalse(forbiddenArtifacts.contains(artifact), "Forbidden executable dependency: " + artifact);
        }
    }

    @Test
    void actualApplicationScansOnlyItsOwnNarrowExecutablePackages() throws Exception {
        Class<?> application = Class.forName(MAIN, false, getClass().getClassLoader());
        var boot = application.getAnnotation(SpringBootApplication.class);
        assertNotNull(boot);
        assertEquals(Set.of("top.egon.cola.component.yuheng.mcp.engine.bootstrap",
                "top.egon.cola.component.yuheng.mcp.engine.config", "top.egon.cola.component.yuheng.mcp.engine.mcp",
                "top.egon.cola.component.yuheng.admin.mcp.controller", "top.egon.cola.component.yuheng.admin.mcp.service"),
                Set.of(boot.scanBasePackages()));
        assertEquals(0, boot.scanBasePackageClasses().length, "No competing broad type-based scan");
    }

    @Test
    void actualApplicationRegistersOnlyTheDedicatedTaskAndApprovalMappers() throws Exception {
        Class<?> application = Class.forName(MAIN, false, getClass().getClassLoader());
        MapperScan scan = application.getAnnotation(MapperScan.class);
        assertNotNull(scan);
        assertEquals(Set.of("top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpTaskDAO",
                "top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpApprovalDAO"),
                Arrays.stream(scan.basePackageClasses()).map(Class::getName).collect(Collectors.toSet()));
        assertEquals(0, scan.basePackages().length);
        assertEquals(0, scan.value().length);
    }

    @Test
    void actualMavenPackageDeclaresTheNewBootExecutableCoordinate() throws Exception {
        var document = xmlFactory().newDocumentBuilder().parse(module().resolve("pom.xml").toFile());
        var project = document.getDocumentElement();
        assertEquals(MODULE, child(project, "artifactId"));
        assertEquals("jar", child(project, "packaging"));
        var build = (Element) project.getElementsByTagName("build").item(0);
        assertEquals(MODULE, child(build, "finalName"));
        var plugins = build.getElementsByTagName("plugin");
        Element boot = null;
        for (int i = 0; i < plugins.getLength(); i++) {
            var plugin = (Element) plugins.item(i);
            if ("spring-boot-maven-plugin".equals(child(plugin, "artifactId"))) boot = plugin;
        }
        assertNotNull(boot, "The actual artifact must be packaged by the normal Spring Boot plugin");
        var config = (Element) boot.getElementsByTagName("configuration").item(0);
        assertEquals(MAIN, child(config, "mainClass"));
        assertEquals("exec", child(config, "classifier"));
        assertEquals("JAR", child(config, "layout"));
        assertTrue(Stream.of(boot.getElementsByTagName("goal")).flatMap(nodes ->
                java.util.stream.IntStream.range(0, nodes.getLength()).mapToObj(nodes::item))
                .anyMatch(node -> "repackage".equals(node.getTextContent().strip())));
    }

    @Test
    void relocatedManagementTypesHaveExactlyOneNewExecutableOwner() throws Exception {
        Path sources = module().resolve("src/main/java");
        var actual = new java.util.TreeSet<String>();
        Path admin = sources.resolve("top/egon/cola/component/yuheng/admin");
        assertTrue(Files.isDirectory(admin));
        try (var files = Files.walk(admin)) {
            files.filter(file -> file.toString().endsWith(".java") && !file.getFileName().toString().equals("package-info.java"))
                    .forEach(file -> actual.add(sources.relativize(file).toString().replace('\\', '/').replace('/', '.')
                            .replaceFirst("\\.java$", "")));
        }
        assertEquals(RELOCATED_ROLE_TYPES, actual, "Only the explicitly moved MCP role classes may retain their former namespace");
        var loader = getClass().getClassLoader();
        for (String name : RELOCATED_ROLE_TYPES) {
            String resource = name.replace('.', '/') + ".class";
            assertEquals(1, java.util.Collections.list(loader.getResources(resource)).size(), "Duplicate role owner: " + name);
            Class<?> type = Class.forName(name, false, loader);
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (Files.isDirectory(origin)) {
                assertEquals(module().resolve("target/classes").toRealPath(), origin, "Role class leaked from Admin: " + name);
            } else {
                try (var jar = new java.util.jar.JarFile(origin.toFile())) {
                    assertNotNull(jar.getJarEntry("META-INF/maven/top.egon/" + MODULE + "/pom.properties"), "Role class leaked from another artifact: " + name);
                }
            }
            assertFalse(Files.exists(module().getParent().resolve("stellar-enclosure-yuheng-admin/src/main/java").resolve(name.replace('.', '/') + ".java")),
                    "The old Admin source cannot remain a competing executable owner: " + name);
        }
    }

    private static String child(Element parent, String name) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) return element.getTextContent().strip();
        }
        return null;
    }

    private static DocumentBuilderFactory xmlFactory() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static Path module() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (path.getFileName() != null && MODULE.equals(path.getFileName().toString())
                    && Files.isRegularFile(path.resolve("pom.xml"))) return path;
            Path candidate = path.resolve(MODULE);
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) return candidate;
        }
        throw new IllegalStateException("Run against the actual MCP access Maven module");
    }
}
