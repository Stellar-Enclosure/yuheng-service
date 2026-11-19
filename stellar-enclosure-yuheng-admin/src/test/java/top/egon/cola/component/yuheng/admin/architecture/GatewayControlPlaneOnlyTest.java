package top.egon.cola.component.yuheng.admin.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import top.egon.cola.component.yuheng.admin.exposure.controller.GatewayUrlExposureController;
import top.egon.cola.component.yuheng.admin.mcp.controller.McpServerController;
import top.egon.cola.component.yuheng.admin.release.controller.GatewayReleaseController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayControlPlaneOnlyTest {
    private static final Path ADMIN = Path.of("").toAbsolutePath();
    private static final Path REPOSITORY = ADMIN.getParent();
    private static final Set<String> RETIRED_MODULES = Set.of(
            "stellar-enclosure-yuheng-biz-gateway",
            "stellar-enclosure-yuheng-edge-gateway",
            "stellar-enclosure-yuheng-runtime-core",
            "stellar-enclosure-yuheng-core");
    private static final Set<String> CONTROL_PLANE_MODULES = Set.of(
            "stellar-enclosure-yuheng-admin",
            "stellar-enclosure-mcp-access-service",
            "stellar-enclosure-model-access-service");

    @Test
    void reactorKeepsOnlyTheControlPlaneAndDedicatedProtocolApplications() throws Exception {
        String reactor = Files.readString(REPOSITORY.resolve("pom.xml"));
        List<String> retired = RETIRED_MODULES.stream().filter(module ->
                reactor.contains("<module>" + module + "</module>")
                        || Files.isRegularFile(REPOSITORY.resolve(module).resolve("pom.xml"))).sorted().toList();

        assertThat(retired).as("retired application or runtime modules").isEmpty();
        assertThat(reactor).contains(CONTROL_PLANE_MODULES.stream()
                .map(module -> "<module>" + module + "</module>").toArray(String[]::new));
    }

    @Test
    void permissionProducersPointOnlyToRetainedExecutableRoles() throws Exception {
        var config = new ObjectMapper().readTree(REPOSITORY.resolve("permission-producers.json").toFile());
        assertThat(config.path("producers").findValuesAsText("module"))
                .containsExactlyInAnyOrderElementsOf(CONTROL_PLANE_MODULES);
    }

    @Test
    void adminRuntimeHasNoDdcWriterOrGeneralProxy() {
        List<String> formerRuntime = List.of(
                "top.egon.cola.component.yuheng.admin.rule.service.GatewayDdcRulePublisher",
                "top.egon.cola.component.yuheng.admin.release.controller.scheduled.GatewayRuleChunkGarbageCollector",
                "top.egon.cola.component.yuheng.core.provider.ProviderServiceRegistry",
                "top.egon.cola.component.yuheng.runtime.provider.service.ProviderDirectory",
                "top.egon.cola.component.yuheng.engine.GatewayEngineApplication",
                "top.egon.cola.component.yuheng.mcp.engine.http.service.McpGatewayHttpServer");
        ClassLoader loader = getClass().getClassLoader();

        assertThat(formerRuntime.stream()
                .filter(type -> loader.getResource(type.replace('.', '/') + ".class") != null)
                .toList()).as("retired runtime types on the Admin classpath").isEmpty();
    }

    @Test
    void adminCannotWirePhysicalRegistrationOrProviderDirectory() throws Exception {
        try (var files = Files.walk(ADMIN.resolve("src/main/java"))) {
            var forbidden = List.of("DdcManagementClient", "ProviderDirectory", "ProviderServiceRegistry",
                    "DdcServiceRegistryClient", "GatewayDdcRulePublisher", "DdcInstanceMetadataContributor");
            var violations = new ArrayList<String>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (line.startsWith("import ") && forbidden.stream().anyMatch(line::contains)) {
                        violations.add(ADMIN.relativize(file) + " " + line);
                    }
                }
            }
            assertThat(violations).as("production imports assembling retired capabilities").isEmpty();
        }
    }

    @Test
    void adminKeepsExposureAndReleaseEndpointsAndRetiresNativeMcpWrites() {
        assertThat(pathsOf(GatewayUrlExposureController.class, RequestMethod.GET)).isNotEmpty();
        assertThat(pathsOf(GatewayReleaseController.class, RequestMethod.POST))
                .contains("/yuheng-groups/{gatewayGroupId}/releases");
        assertThat(pathsOf(McpServerController.class, RequestMethod.GET)).contains("/{id}");
        assertThat(pathsOf(McpServerController.class, RequestMethod.POST)).containsExactly("/{id}/validate");
        assertThat(pathsOf(McpServerController.class, RequestMethod.PUT)).isEmpty();
        assertThat(pathsOf(McpServerController.class, RequestMethod.PATCH)).isEmpty();
        assertThat(pathsOf(McpServerController.class, RequestMethod.DELETE)).isEmpty();
    }

    private static Set<String> pathsOf(Class<?> controller, RequestMethod requestMethod) {
        return Arrays.stream(controller.getDeclaredMethods())
                .map(method -> AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class))
                .filter(mapping -> mapping != null && Arrays.asList(mapping.method()).contains(requestMethod))
                .flatMap(mapping -> Arrays.stream(mapping.path()))
                .collect(Collectors.toSet());
    }
}
