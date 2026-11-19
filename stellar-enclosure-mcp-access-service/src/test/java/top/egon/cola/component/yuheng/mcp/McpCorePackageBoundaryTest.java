package top.egon.cola.component.yuheng.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCorePackageBoundaryTest {

    private static final Path SOURCE_ROOT = module().resolve("src/main/java/top/egon/cola/component/yuheng/mcp");

    @Test
    void rootContainsOnlyPackageInfoAfterMigration() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT));
        try (Stream<Path> files = Files.list(SOURCE_ROOT)) {
            List<String> directJavaFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(
                            ".java"
                    ))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertEquals(List.of("package-info.java"), directJavaFiles);
        }
    }

    @Test
    void capabilityPackagesExposeRoleDirectories() throws IOException {
        Set<String> required = Set.of(
                "common/protocol",
                "common/transport",
                "common/security",
                "common/telemetry",
                "app/domain",
                "app/service",
                "completion/service",
                "prompt/domain",
                "prompt/service",
                "remote/domain",
                "remote/service",
                "resource/domain",
                "resource/service",
                "resource/adapter",
                "rule/domain",
                "rule/service",
                "server/domain",
                "server/service",
                "subscription/service",
                "task/domain",
                "task/service",
                "tool/service"
        );
        for (String relativePath : required) {
            assertTrue(
                    Files.isDirectory(SOURCE_ROOT.resolve(relativePath)),
                    () -> "missing MCP target package " + relativePath
            );
        }

        for (String oldPackage : List.of(
                "app",
                "completion",
                "prompt",
                "remote",
                "resource",
                "rule",
                "server",
                "subscription",
                "task",
                "tool"
        )) {
            try (Stream<Path> files = Files.list(
                    SOURCE_ROOT.resolve(oldPackage)
            )) {
                List<String> directJavaFiles = files
                        .filter(path -> path.getFileName().toString()
                                .endsWith(".java"))
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
                assertEquals(
                        List.of(),
                        directJavaFiles,
                        () -> "legacy MCP runtime files remain in "
                                + oldPackage
                );
            }
        }

        for (String oldPackage : List.of(
                "protocol",
                "security",
                "telemetry",
                "transport",
                "server/handler"
        )) {
            assertFalse(
                    Files.exists(SOURCE_ROOT.resolve(oldPackage)),
                    () -> "old MCP package remains " + oldPackage
            );
        }
    }

    @Test
    void commonPackagesDoNotDependOnCapabilityPackages()
            throws IOException {
        Path commonRoot = SOURCE_ROOT.resolve("common");
        assertTrue(Files.isDirectory(commonRoot));
        try (Stream<Path> files = Files.walk(commonRoot)) {
            List<String> forbiddenImports = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(this::lines)
                    .filter(this::isCapabilityImport)
                    .toList();

            assertEquals(List.of(), forbiddenImports);
        }
    }

    @Test
    void commonProtocolDoesNotImportAnExecutableOrInfrastructureOwner() throws IOException {
        List<String> forbidden = List.of("top.egon.cola.component.yuheng.admin.",
                "top.egon.cola.component.yuheng.runtime.", "top.egon.cola.component.yuheng.engine.",
                "top.egon.cola.component.yuheng.mcp.engine.", "org.springframework.", "org.redisson.",
                "org.apache.ibatis.", "com.baomidou.", "java.sql.");
        try (Stream<Path> files = Files.walk(SOURCE_ROOT.resolve("common"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String declaration = line.strip();
                    if (declaration.startsWith("import ")) {
                        assertFalse(forbidden.stream().anyMatch(declaration::contains), file + ": " + declaration);
                    }
                }
            }
        }
    }

    @Test
    void sharedPurePortsHaveExactlyOneContractOwnerAndNoOldCoreCopy() throws Exception {
        List<String> ports = List.of(
                "top.egon.cola.component.yuheng.core.mcp.app.McpAppArtifactStore",
                "top.egon.cola.component.yuheng.core.mcp.remote.RemoteAuthProvider",
                "top.egon.cola.component.yuheng.core.mcp.remote.RemoteMcpClient",
                "top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort",
                "top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort",
                "top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationRequest",
                "top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult",
                "top.egon.cola.component.yuheng.core.operation.GatewayOperationCall",
                "top.egon.cola.component.yuheng.core.operation.GatewayOperationInvocation",
                "top.egon.cola.component.yuheng.core.operation.GatewayOperationInvoker");
        for (String port : ports) {
            assertOwnedBy(port, "stellar-enclosure-yuheng-contract");
            Path path = Path.of(port.replace('.', '/') + ".java");
            assertTrue(Files.isRegularFile(module().getParent().resolve("stellar-enclosure-yuheng-contract/src/main/java").resolve(path)), port);
            assertFalse(Files.exists(module().resolve("src/main/java").resolve(path)), "The executable must not copy the port: " + port);
            assertFalse(Files.exists(module().getParent().resolve("stellar-enclosure-yuheng-core/src/main/java").resolve(path)),
                    "The old generic core must not retain a competing port: " + port);
        }
        assertOwnedBy("top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest", "stellar-enclosure-mcp-access-service");
        assertOwnedBy("top.egon.cola.component.yuheng.mcp.common.transport.McpHttpResponse", "stellar-enclosure-mcp-access-service");
    }

    private void assertOwnedBy(String typeName, String artifactId) throws Exception {
        var loader = getClass().getClassLoader();
        assertEquals(1, java.util.Collections.list(loader.getResources(typeName.replace('.', '/') + ".class")).size(),
                "Exactly one actual class must supply " + typeName);
        Class<?> type = Class.forName(typeName, false, loader);
        Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (Files.isDirectory(origin)) {
            assertEquals(module().getParent().resolve(artifactId).resolve("target/classes").toRealPath(), origin,
                    "Wrong compiled module owns " + typeName);
        } else {
            try (var jar = new java.util.jar.JarFile(origin.toFile())) {
                var entry = jar.getJarEntry("META-INF/maven/top.egon/" + artifactId + "/pom.properties");
                org.junit.jupiter.api.Assertions.assertNotNull(entry, "Wrong Maven artifact owns " + typeName);
                var properties = new java.util.Properties();
                try (var input = jar.getInputStream(entry)) { properties.load(input); }
                assertEquals("top.egon", properties.getProperty("groupId"));
                assertEquals(artifactId, properties.getProperty("artifactId"));
            }
        }
    }

    private static Path module() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (path.getFileName() != null && path.getFileName().toString().equals("stellar-enclosure-mcp-access-service")
                    && Files.isRegularFile(path.resolve("pom.xml"))) return path;
            Path candidate = path.resolve("stellar-enclosure-mcp-access-service");
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) return candidate;
        }
        throw new IllegalStateException("Run against the actual MCP access Maven module");
    }

    private Stream<String> lines(Path path) {
        try {
            return Files.readAllLines(path).stream();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean isCapabilityImport(String line) {
        if (!line.startsWith(
                "import top.egon.cola.component.yuheng.mcp."
        )) {
            return false;
        }
        return List.of(
                ".app.",
                ".completion.",
                ".prompt.",
                ".remote.",
                ".resource.",
                ".rule.",
                ".server.",
                ".subscription.",
                ".task.",
                ".tool."
        ).stream().anyMatch(line::contains);
    }
}
