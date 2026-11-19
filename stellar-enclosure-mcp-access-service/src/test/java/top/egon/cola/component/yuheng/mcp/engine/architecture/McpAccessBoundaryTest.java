package top.egon.cola.component.yuheng.mcp.engine.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executable, source and configuration gates for the dedicated MCP protocol service. */
class McpAccessBoundaryTest {
    private static final String ACCESS_MODULE = "stellar-enclosure-mcp-access-service";
    private static final String MAIN = "top.egon.cola.component.yuheng.mcp.engine.McpGatewayEngineApplication";
    private static final String CONTROLLER = "top.egon.cola.component.yuheng.mcp.engine.mcp.controller.McpProtocolController";
    private static final String BOUND_INVOKER = "top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpBoundOperationInvoker";
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
    private static final Set<String> FORBIDDEN_DEPENDENCIES = Set.of(
            "stellar-enclosure-yuheng-admin", "stellar-enclosure-yuheng-llm-gateway",
                "stellar-enclosure-model-access-service",
            "stellar-enclosure-yuheng-core", "stellar-enclosure-yuheng-runtime-core",
            "stellar-enclosure-yuheng-mcp-core", "stellar-enclosure-yuheng-biz-gateway",
            "stellar-enclosure-yuheng-edge-gateway", "stellar-enclosure-tianshu-http-registration-starter",
            "stellar-enclosure-tianquan-shoubing-gateway-adapter", "stellar-enclosure-tianquan-jianshen-gateway-adapter");
    private static final List<String> FORBIDDEN_CLASSES = List.of(
            "top.egon.cola.component.yuheng.runtime.config.GatewayRuntimeConfiguration",
            "top.egon.cola.component.yuheng.runtime.provider.service.ProviderDirectory",
            "top.egon.cola.component.yuheng.runtime.operation.service.EngineGatewayOperationInvoker",
            "top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier",
            "top.egon.cola.component.yuheng.runtime.http.service.GatewayHttpListener",
            "top.egon.cola.component.yuheng.mcp.engine.http.service.McpGatewayHttpServer",
            "top.egon.cola.component.yuheng.mcp.engine.http.service.McpGatewayHttpDataPlaneHandlerAdapter",
            "top.egon.cola.component.tianshu.autoconfigure.DdcRegistryAutoConfiguration",
            "top.egon.cola.component.tianshu.service.lifecycle.DdcRuntimeCoordinator",
            "top.egon.cola.component.tianshu.listener.registry.DdcRegistrySubscriptionCoordinator",
            "top.egon.cola.component.tianshu.state.DdcLeaseSessionHolder",
            "top.egon.cola.component.rpc.tianshu.client.DdcRpcClientFactory",
            "top.egon.cola.component.rpc.tianshu.client.DdcRpcClientHandle",
            "top.egon.cola.component.rpc.tianshu.security.DdcRpcClientInterceptorFactory",
            "top.egon.cola.component.rpc.tianshu.security.DdcRpcCredential",
            "top.egon.cola.component.rpc.tianshu.security.DdcRpcRequestSigner",
            "top.egon.cola.component.rpc.tianshu.registry.DdcRpcGatewayDirectory",
            "top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcConfigRuntimeServiceGrpc",
            "top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcServiceRegistryServiceGrpc",
            "top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcManagementServiceGrpc",
            "top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcAiResourceServiceGrpc");

    @Test
    void executablePomCannotBringBackGenericRuntimeOrPhysicalRegistration() throws Exception {
        Path pom = executable().resolve("pom.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(pom.toFile());
        var artifacts = new TreeSet<String>();
        var dependencies = document.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            artifacts.add(dependency.getElementsByTagName("artifactId").item(0).getTextContent().strip());
        }
        var forbidden = new TreeSet<>(artifacts);
        forbidden.retainAll(FORBIDDEN_DEPENDENCIES);
        assertTrue(forbidden.isEmpty(), () -> pom + " links retired execution dependencies: " + forbidden);
        assertTrue(artifacts.contains("stellar-enclosure-yuheng-contract"), "The protocol ports belong to the shared contract");
        var children = document.getDocumentElement().getChildNodes();
        String artifactId = null;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && "artifactId".equals(element.getTagName())) {
                artifactId = element.getTextContent().strip();
            }
        }
        assertEquals(ACCESS_MODULE, artifactId, "The finished migration must publish the independent executable coordinate");
    }

    @Test
    void actualRuntimeClasspathCannotContainGenericRoutingOrPhysicalDirectoryTypes() {
        var loader = getClass().getClassLoader();
        var present = FORBIDDEN_CLASSES.stream()
                .filter(name -> loader.getResource(name.replace('.', '/') + ".class") != null).toList();
        assertTrue(present.isEmpty(), () -> "Actual executable classpath still exposes: " + present);
    }

    @Test
    void productionSourcesCannotImportGenericActivationSelectionOrPhysicalRegistry() throws Exception {
        List<String> forbidden = List.of("top.egon.cola.component.yuheng.runtime.",
                "top.egon.cola.component.yuheng.core.provider.",
                "top.egon.cola.component.tianshu.api.registry.", "DdcServiceRegistryClient",
                "GatewayRuleApplierRegistrar", "DdcInstanceMetadataContributor", "DdcRpcClientFactory");
        var violations = new ArrayList<String>();
        List<Path> sources = javaSources();
        assertFalse(sources.isEmpty(), "The executable must contain real production source");
        for (Path file : sources) {
            for (String line : Files.readAllLines(file)) {
                String declaration = line.strip();
                if (declaration.startsWith("import ") && forbidden.stream().anyMatch(declaration::contains)) {
                    violations.add(executable().relativize(file) + ": " + declaration);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Production imports retain the old execution path: " + violations);
    }

    @Test
    void operationInvocationCannotChooseADynamicProviderOrAcceptATargetUrl() throws Exception {
        Path bound = executable().resolve("src/main/java/" + BOUND_INVOKER.replace('.', '/') + ".java");
        Path source = bound;
        assertTrue(Files.isRegularFile(source), "An actual production invocation implementation is required");
        String implementation = Files.readString(source);
        assertAll("Fixed operation-to-Service binding must replace dynamic selection",
                () -> assertFalse(implementation.contains("ProviderSelector"), source + " still selects providers"),
                () -> assertFalse(implementation.contains("ProviderInstance"), source + " still receives physical instances"),
                () -> assertFalse(implementation.contains("providerSelector.select("), source + " still chooses a runtime endpoint"));
        assertTrue(Files.isRegularFile(bound), "The executable must own its bounded operation invoker");
        Class<?> invoker = Class.forName(BOUND_INVOKER, false, getClass().getClassLoader());
        var methods = Stream.of(invoker.getDeclaredMethods()).filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())
                && (method.getName().equals("invoke") || method.getName().equals("invokeBound"))).toList();
        assertEquals(Set.of("invoke", "invokeBound"), methods.stream().map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet()), "Typed explicit and verified-context entrypoints are required");
        for (var method : methods) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == URI.class || parameter == URL.class || parameter == String.class,
                        "The public invocation cannot take an arbitrary target address: " + method);
            }
        }
    }

    @Test
    void allExecutableProfilesRemovePhysicalRegistrationAndApplicationTls() throws Exception {
        Path resources = executable().resolve("src/main/resources");
        List<Path> profiles;
        try (var files = Files.list(resources)) {
            profiles = files.filter(file -> file.getFileName().toString().startsWith("application")
                    && file.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
        assertFalse(profiles.isEmpty(), "Actual application profiles are required");
        var violations = new ArrayList<String>();
        for (Path profile : profiles) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource(profile));
            Properties properties = yaml.getObject();
            assertNotNull(properties, profile.toString());
            assertEquals("h2c", properties.getProperty("egon.cola.component.rpc.transport"), profile.toString());
            assertEquals("false", properties.getProperty("egon.cola.component.rpc.provider.enabled"), profile.toString());
            assertEquals("true", properties.getProperty("egon.cola.platform.tianquan.shoubing.rpc.consumer.enabled"), profile.toString());
            assertEquals("false", properties.getProperty("egon.cola.platform.tianquan.shoubing.rpc.provider.enabled"), profile.toString());
            assertEquals("none", properties.getProperty("server.forward-headers-strategy"), profile.toString());
            properties.forEach((rawKey, rawValue) -> {
                String key = rawKey.toString();
                String value = rawValue.toString();
                if (key.startsWith("egon.cola.component.tianshu.rpc.")
                        || key.startsWith("egon.cola.component.tianshu.registry.")
                        || key.startsWith("egon.cola.component.tianshu.instance.")
                        || tlsSettingEnabledOrMaterial(key, value)
                        || key.startsWith("egon.cola.component.rpc.tls.")
                        || key.contains(".listener.tls.") || key.contains(".outbound.rpc-tls.")
                        || key.startsWith("spring.config.import") && value.contains("tianshu:")) {
                    violations.add(profile.getFileName() + ": " + key);
                }
            });
        }
        assertTrue(violations.isEmpty(), () -> "Retired registration or internal application TLS remains configured: " + violations);
    }

    @Test
    void realExecutableUsesADedicatedMvcProtocolEntrypointInsteadOfTheGatewayListener() throws Exception {
        String resource = MAIN.replace('.', '/') + ".class";
        var loader = getClass().getClassLoader();
        assertEquals(1, Collections.list(loader.getResources(resource)).size(), "The real executable entry must occur exactly once");
        Class<?> application = Class.forName(MAIN, false, loader);
        Path origin = Path.of(application.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        Path classes = executable().resolve("target/classes");
        boolean currentClasses = Files.isDirectory(classes) && origin.equals(classes.toRealPath());
        boolean packaged = false;
        if (Files.isRegularFile(origin) && origin.getFileName().toString().endsWith(".jar")) {
            try (var jar = new java.util.jar.JarFile(origin.toFile())) {
                String coordinate = "META-INF/maven/top.egon/" + executable().getFileName() + "/pom.properties";
                var entry = jar.getJarEntry(coordinate);
                if (entry != null) {
                    var metadata = new Properties();
                    try (var input = jar.getInputStream(entry)) { metadata.load(input); }
                    packaged = executable().getFileName().toString().equals(metadata.getProperty("artifactId"));
                }
            }
        }
        assertTrue(currentClasses || packaged, () -> "The test must use the selected executable's compiled output, got " + origin);
        var genericListeners = javaSources().stream().filter(file -> {
            try { return Files.readAllLines(file).stream().map(String::strip)
                    .anyMatch(line -> line.startsWith("import ") && line.contains(".runtime.http.service.GatewayHttpListener")); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }).toList();
        assertTrue(genericListeners.isEmpty(), () -> "The executable still installs a generic listener: " + genericListeners);
        assertEquals(ACCESS_MODULE, executable().getFileName().toString(), "The finished service cannot remain at the old executable coordinate");
        Class<?> controller = Class.forName(CONTROLLER, false, loader);
        assertNotNull(controller.getAnnotation(RestController.class), "The real MVC protocol entry must be a controller");
        var mappings = Stream.of(controller.getDeclaredMethods())
                .map(method -> AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class))
                .filter(java.util.Objects::nonNull).toList();
        assertFalse(mappings.isEmpty(), "The protocol controller must register actual MVC mappings");
        RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        List<String> roots = root == null ? List.of("") : mappingPaths(root);
        for (RequestMapping mapping : mappings) {
            for (String base : roots) for (String path : mappingPaths(mapping)) {
                String combined = base + path;
                assertFalse(combined.contains("*") || combined.contains("{*"), "A protocol adapter cannot register a catch-all: " + combined);
                assertTrue(Set.of("/mcp/{serverCode}", "/legacy/mcp/{serverCode}",
                                "/.well-known/oauth-protected-resource/mcp/{serverCode}").contains(combined),
                        () -> "The protocol entry cannot own a general business URL: " + combined);
            }
        }
    }

    @Test
    void nativeRpcAndIdpArePresentAndTheSiAdapterCannotBeTheLegacyTransport() throws Exception {
        var loader = getClass().getClassLoader();
        for (String type : List.of("top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker",
                "top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver",
                "top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory",
                "top.egon.cola.platform.tianquan.shoubing.starter.security.TenantIssuerAuthenticationManagerComponent",
                "top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcSiResourceGrpcServiceGrpc",
                "top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper")) {
            assertEquals(1, Collections.list(loader.getResources(type.replace('.', '/') + ".class")).size(),
                    "A unique formal production dependency is required: " + type);
            assertNotNull(Class.forName(type, false, loader));
        }
        Class<?> properties = Class.forName("top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcProperties", false, loader);
        assertEquals("egon.cola.component.tianshu.si.rpc", properties.getField("PREFIX").get(null));
        Set<String> retired = Set.of("getAuth", "getTls", "getRegistration", "getHeartbeat", "setAuth", "setTls");
        assertTrue(Stream.of(properties.getDeclaredMethods()).noneMatch(method -> retired.contains(method.getName())));
        Class<?> client = Class.forName("top.egon.cola.component.rpc.tianshu.client.si.DdcSiCatalogRpcClient", false, loader);
        assertEquals(1, client.getConstructors().length);
        assertEquals(List.of("top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker",
                "top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper",
                "top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO", "java.time.Duration"),
                Stream.of(client.getConstructors()[0].getParameterTypes()).map(Class::getName).toList());
    }

    @Test
    void compiledOutputAndExplicitRealArchivesHaveTheSameProtocolOnlyProjectBytes() throws Exception {
        Path classes = compiledSnapshot();
        assertTrue(Files.isRegularFile(classes.resolve(MAIN.replace('.', '/') + ".class")));
        assertTrue(Files.isRegularFile(classes.resolve(CONTROLLER.replace('.', '/') + ".class")));
        assertTrue(Files.isRegularFile(classes.resolve(BOUND_INVOKER.replace('.', '/') + ".class")));
        List<Path> projectClasses;
        try (var files = Files.walk(classes)) {
            projectClasses = files.filter(path -> path.toString().endsWith(".class")).sorted().toList();
        }
        assertFalse(projectClasses.isEmpty());
        for (Path file : projectClasses) {
            assertFalse(forbiddenClassEntry(classes.relativize(file).toString().replace('\\', '/')),
                    "Retired class remains in the actual compiler output: " + file);
        }
        String selectedArtifacts = System.getProperty("stellar.mcp.test.artifacts");
        // The normal Maven test phase precedes package. The explicit post-package invocation
        // checks genuine Maven regular/exec JARs; it never fabricates an archive as a fixture.
        if (selectedArtifacts != null) {
            assertFalse(selectedArtifacts.isBlank());
            for (String artifact : selectedArtifacts.split(",", -1)) assertArchive(Path.of(artifact).toRealPath(), classes, projectClasses);
        }
    }

    private void assertArchive(Path artifact, Path classes, List<Path> projectClasses) throws Exception {
        assertTrue(Files.isRegularFile(artifact), "Use the genuine completed Maven artifact: " + artifact);
        try (var jar = new java.util.jar.JarFile(artifact.toFile())) {
            boolean boot = jar.getJarEntry("BOOT-INF/classes/" + MAIN.replace('.', '/') + ".class") != null;
            String prefix = boot ? "BOOT-INF/classes/" : "";
            String metadataPath = "META-INF/maven/top.egon/" + ACCESS_MODULE + "/pom.properties";
            var metadata = jar.getJarEntry(metadataPath);
            if (metadata == null) metadata = jar.getJarEntry(prefix + metadataPath);
            assertNotNull(metadata, "The JAR must identify the real Maven module");
            var properties = new Properties();
            try (var input = jar.getInputStream(metadata)) { properties.load(input); }
            assertEquals("top.egon", properties.getProperty("groupId"));
            assertEquals(ACCESS_MODULE, properties.getProperty("artifactId"));
            if (boot) {
                assertEquals(MAIN, jar.getManifest().getMainAttributes().getValue("Start-Class"));
                assertEquals("org.springframework.boot.loader.launch.JarLauncher", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            }
            var archivedClasses = new TreeSet<String>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); String name = entry.getName();
                if (name.startsWith(prefix) && name.endsWith(".class") && !name.startsWith("org/springframework/boot/loader/")) {
                    String relative = name.substring(prefix.length());
                    if (!name.startsWith("BOOT-INF/lib/")) {
                        assertFalse(forbiddenClassEntry(relative), "Retired project class in " + artifact + ": " + relative);
                        archivedClasses.add(relative);
                    }
                }
                if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                    for (String forbidden : FORBIDDEN_DEPENDENCIES) {
                        assertFalse(name.substring("BOOT-INF/lib/".length()).startsWith(forbidden + "-"),
                                "Retired dependency packaged into the executable: " + name);
                    }
                    try (var nested = new java.util.zip.ZipInputStream(jar.getInputStream(entry))) {
                        for (var child = nested.getNextEntry(); child != null; child = nested.getNextEntry()) {
                            assertFalse(child.getName().startsWith("top/egon/cola/component/yuheng/admin/")
                                            || forbiddenClassEntry(child.getName()), "Retired transitive class in " + name + ": " + child.getName());
                        }
                    }
                }
            }
            var expected = new TreeSet<String>();
            for (Path file : projectClasses) {
                String relative = classes.relativize(file).toString().replace('\\', '/'); expected.add(relative);
                var entry = jar.getJarEntry(prefix + relative);
                assertNotNull(entry, "Missing compiled project class in " + artifact + ": " + relative);
                try (var input = jar.getInputStream(entry)) {
                    org.junit.jupiter.api.Assertions.assertArrayEquals(Files.readAllBytes(file), input.readAllBytes(),
                            "JAR bytes must match the actual current compiler output: " + relative);
                }
            }
            assertEquals(expected, archivedClasses, "No competing or stale project classes may hide in the archive");
            for (String profile : List.of("application.yml", "application-operations.yml")) {
                var entry = jar.getJarEntry(prefix + profile); assertNotNull(entry, profile);
                try (var input = jar.getInputStream(entry)) {
                    org.junit.jupiter.api.Assertions.assertArrayEquals(Files.readAllBytes(classes.resolve(profile)), input.readAllBytes(), profile);
                }
            }
        }
    }

    private static boolean forbiddenClassEntry(String path) {
        if (!path.endsWith(".class")) return false;
        String type = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        boolean relocatedRole = RELOCATED_ROLE_TYPES.stream().anyMatch(allowed -> type.equals(allowed) || type.startsWith(allowed + "$"));
        if (path.startsWith("top/egon/cola/component/yuheng/runtime/")
                || path.startsWith("top/egon/cola/component/yuheng/admin/") && !relocatedRole
                || path.startsWith("top/egon/cola/component/yuheng/engine/")
                || path.startsWith("top/egon/cola/component/yuheng/llm/")
                || path.startsWith("top/egon/cola/component/yuheng/core/provider/")) return true;
        return FORBIDDEN_CLASSES.stream().anyMatch(forbidden -> type.equals(forbidden) || type.startsWith(forbidden + "$"));
    }

    private static Path compiledSnapshot() throws Exception {
        String selected = System.getProperty("stellar.mcp.test.classes");
        if (selected == null) return executable().resolve("target/classes").toRealPath();
        assertNotNull(System.getProperty("stellar.mcp.test.artifacts"),
                "An external compiler snapshot must be paired with its genuine archive byte comparison");
        return Path.of(selected).toRealPath();
    }

    private static boolean tlsSettingEnabledOrMaterial(String key, String value) {
        return (key.startsWith("server.ssl.") || key.startsWith("management.server.ssl."))
                && !(key.endsWith(".enabled") && "false".equals(value));
    }

    private static List<String> mappingPaths(RequestMapping mapping) {
        List<String> paths = Stream.concat(Stream.of(mapping.path()), Stream.of(mapping.value())).distinct().toList();
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static List<Path> javaSources() throws Exception {
        try (var files = Files.walk(executable().resolve("src/main/java"))) {
            return files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Path executable() throws Exception {
        Path candidate = reactor().resolve(ACCESS_MODULE);
        assertTrue(Files.isRegularFile(candidate.resolve("pom.xml")), "The dedicated executable must exist; no old-module fallback");
        return candidate;
    }

    private static Path reactor() throws Exception {
        Path path = Path.of("").toAbsolutePath().normalize();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("pom.xml")) && Files.isDirectory(path.resolve("stellar-enclosure-yuheng-contract"))) return path;
            path = path.getParent();
        }
        throw new IllegalStateException("The test must run from the actual Yuheng reactor or one of its modules");
    }
}
