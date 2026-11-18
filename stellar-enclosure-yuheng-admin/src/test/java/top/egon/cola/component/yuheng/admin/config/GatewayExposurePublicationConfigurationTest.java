package top.egon.cola.component.yuheng.admin.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainResolver;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.AuthorizationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BackendReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BindingResolver;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GatewayReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GrantReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayExposurePublicationConfigurationTest {

    private static final String CONFIGURATION = "top.egon.cola.component.yuheng.admin.config.GatewayExposurePublicationConfiguration";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BindingVO PHYSICAL = new BindingVO("edge-system", "public-apps", "https-api");
    private static final String FILE = GatewayExposurePublicationConfiguration.FILE_PROPERTY;
    private static final String SHA = GatewayExposurePublicationConfiguration.SHA256_PROPERTY;
    @TempDir Path directory;
    private int fileSequence;

    @Test
    void nativePublicationRequiresTheExplicitTrustedInventoryConfiguration() throws Exception {
        Class<?> configuration = Class.forName(CONFIGURATION);
        assertThat(configuration.getAnnotation(Configuration.class)).isNotNull();
        assertThat(configuration.getDeclaredMethod("gatewayHttpRouteBindingResolver").getAnnotation(Bean.class)).isNotNull();
        assertThat(configuration.getDeclaredMethod("gatewayUrlExposurePublicationDomainResolver").getAnnotation(Bean.class)).isNotNull();
        new ApplicationContextRunner().withUserConfiguration(configuration).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("gatewayHttpRouteBindingResolver")).isNotNull();
            assertThat(context.getBean("gatewayHttpRouteBindingResolver", BindingResolver.class).resolve(7L, 9L, "route-a", "readItems")).isEmpty();
            assertThat(context.getBean("gatewayUrlExposurePublicationDomainResolver", PublicationDomainResolver.class)
                    .resolve("unregistered", new BindingVO("edge-system", "public-apps", "https-api"))).isNull();
        });
    }

    @Test
    void exactPinnedInventorySharesOneCompleteImmutableDomainAcrossRoutes() throws Exception {
        PublicationBinding first = binding("route-a", "readItems");
        PublicationBinding second = binding("route-b", "readOtherItems");
        Path file = write(inventory(first, second));
        configured(file).run(context -> {
            assertThat(context).hasNotFailed();
            var routes = context.getBean("gatewayHttpRouteBindingResolver", BindingResolver.class);
            var domains = context.getBean("gatewayUrlExposurePublicationDomainResolver", PublicationDomainResolver.class);
            var resolved = routes.resolve(7L, 9L, "route-a", "readItems").orElseThrow();
            assertThat(resolved).isEqualTo(first);
            assertThat(routes.resolve(7L, 9L, "route-b", "readOtherItems")).contains(second);
            assertThat(routes.resolve(8L, 9L, "route-a", "readItems")).isEmpty();
            assertThat(routes.resolve(7L, 10L, "route-a", "readItems")).isEmpty();
            assertThat(routes.resolve(7L, 9L, "other", "readItems")).isEmpty();
            assertThat(routes.resolve(7L, 9L, "route-a", "other")).isEmpty();
            assertThat(domains.resolve("cluster-a", PHYSICAL)).isEqualTo(first.publicationDomain());
            assertThat(domains.resolve("other-cluster", PHYSICAL)).isNull();
            assertThat(domains.resolve("cluster-a", new BindingVO("edge-system", "other", "https-api"))).isNull();
            assertThatThrownBy(() -> resolved.approvedMethods().add("POST")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> resolved.publicationDomain().bindings().clear()).isInstanceOf(UnsupportedOperationException.class);
            Files.writeString(file, "{modified-after-startup}");
            assertThat(routes.resolve(7L, 9L, "route-a", "readItems")).contains(first);
            assertThat(domains.resolve("cluster-a", PHYSICAL)).isEqualTo(first.publicationDomain());
        });
    }

    @Test
    void partialPropertiesBadPinAndUncontrolledLocationsFailStartup() throws Exception {
        Path file = write(inventory(binding("route-a", "readItems")));
        new ApplicationContextRunner().withUserConfiguration(GatewayExposurePublicationConfiguration.class)
                .withPropertyValues(FILE + "=" + file).run(context -> failure(context.getStartupFailure(), "CONFIGURATION_INVALID"));
        new ApplicationContextRunner().withUserConfiguration(GatewayExposurePublicationConfiguration.class)
                .withPropertyValues(SHA + "=" + digest(Files.readAllBytes(file)))
                .run(context -> failure(context.getStartupFailure(), "CONFIGURATION_INVALID"));
        configured(file).withPropertyValues(SHA + "=" + "0".repeat(64))
                .run(context -> failure(context.getStartupFailure(), "DIGEST_MISMATCH"));
        configured(file).withPropertyValues(SHA + "=sha256:" + digest(Files.readAllBytes(file)))
                .run(context -> failure(context.getStartupFailure(), "CONFIGURATION_INVALID"));
        configured(file).withPropertyValues(FILE + "=relative.json")
                .run(context -> failure(context.getStartupFailure(), "PATH_INVALID"));
        configured(file).withPropertyValues(FILE + "=" + directory.resolve("missing.json"))
                .run(context -> failure(context.getStartupFailure(), "INVALID"));
    }

    @Test
    void strictWireShapeRejectsUnknownDuplicateMissingCoercedAndTrailingJsonWithoutSourceLeak() throws Exception {
        String valid = inventory(binding("route-a", "readItems")).toString();
        ObjectNode unknown = inventory(binding("route-a", "readItems"));
        unknown.put("sensitive-fixture-marker", "never-log-this-source-fragment");
        ObjectNode nestedUnknown = inventory(binding("route-a", "readItems"));
        ((ObjectNode) nestedUnknown.path("bindings").get(0).path("gateway")).put("port", 443);
        ObjectNode missing = inventory(binding("route-a", "readItems"));
        ((ObjectNode) missing.path("bindings").get(0)).remove("bindingVersion");
        ObjectNode coerced = inventory(binding("route-a", "readItems"));
        ((ObjectNode) coerced.path("bindings").get(0)).put("ownerTenantId", "7");
        for (String invalid : List.of(unknown.toString(), nestedUnknown.toString(), missing.toString(), coerced.toString(),
                valid.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":1"),
                valid.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"1\",\"schemaVersion\":\"1\""), valid + " {}")) {
            Path file = write(invalid.getBytes(StandardCharsets.UTF_8));
            configured(file).run(context -> {
                failure(context.getStartupFailure(), "");
                var trace = new java.io.StringWriter();
                context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
                assertThat(trace.toString()).doesNotContain("never-log-this-source-fragment");
            });
        }
        Path malformedUtf8 = write(new byte[] { '{', (byte) 0xc3, (byte) 0x28, '}' });
        configured(malformedUtf8).run(context -> failure(context.getStartupFailure(), "INVALID"));
    }

    @Test
    void incompleteAndDuplicatePhysicalDomainBindingsAreRejectedBeforeCanonicalization() throws Exception {
        ObjectNode incomplete = inventory(binding("route-a", "readItems"));
        ((ObjectNode) incomplete.path("bindings").get(0).path("publicationDomain")).put("complete", false);
        Path first = write(incomplete);
        configured(first).run(context -> failure(context.getStartupFailure(), "DOMAIN_INCOMPLETE"));
        ObjectNode duplicated = inventory(binding("route-a", "readItems"));
        var bindings = (ArrayNode) duplicated.path("bindings").get(0).path("publicationDomain").path("bindings");
        bindings.add(bindings.get(0).deepCopy());
        Path second = write(duplicated);
        configured(second).run(context -> failure(context.getStartupFailure(), "DOMAIN_BINDING_DUPLICATED"));
    }

    @Test
    void duplicateRouteAndContradictoryOrOverlappingDomainViewsAreRejected() throws Exception {
        PublicationBinding first = binding("route-a", "readItems");
        Path duplicate = write(inventory(first, first));
        configured(duplicate).run(context -> failure(context.getStartupFailure(), "ROUTE_DUPLICATED"));
        ObjectNode changedVersion = inventory(first, binding("route-b", "readOtherItems"));
        ((ObjectNode) changedVersion.path("bindings").get(1).path("publicationDomain")).put("topologyVersion", "topology-v2");
        Path inconsistent = write(changedVersion);
        configured(inconsistent).run(context -> failure(context.getStartupFailure(), "DOMAIN_INCONSISTENT"));
        ObjectNode changedDomain = inventory(first, binding("route-b", "readOtherItems"));
        ((ObjectNode) changedDomain.path("bindings").get(1).path("publicationDomain")).put("publicationDomainId", "another-vip-domain");
        Path overlapping = write(changedDomain);
        configured(overlapping).run(context -> failure(context.getStartupFailure(), "DOMAIN_OVERLAP"));
        ObjectNode changedUid = inventory(first, binding("route-b", "readOtherItems"));
        ((ObjectNode) changedUid.path("bindings").get(1).path("gateway")).put("uid", "other-gateway-uid");
        Path inconsistentGateway = write(changedUid);
        configured(inconsistentGateway).run(context -> failure(context.getStartupFailure(), "GATEWAY_INCONSISTENT"));
    }

    @Test
    void assemblyUsesTheCompilerCapabilityValidatorAndDoesNotSilentlyDeduplicateSets() throws Exception {
        ObjectNode wrongPort = inventory(binding("route-a", "readItems"));
        ((ObjectNode) wrongPort.path("bindings").get(0).path("backends").get(0)).put("targetPort", 9090);
        Path invalidBackend = write(wrongPort);
        configured(invalidBackend).run(context -> failure(context.getStartupFailure(), "INVALID"));
        ObjectNode missingGrant = inventory(binding("route-a", "readItems"));
        ((ObjectNode) missingGrant.path("bindings").get(0).path("backends").get(0)).putNull("grant");
        Path invalidGrant = write(missingGrant);
        configured(invalidGrant).run(context -> failure(context.getStartupFailure(), "INVALID"));
        ObjectNode duplicateSet = inventory(binding("route-a", "readItems"));
        ((ArrayNode) duplicateSet.path("bindings").get(0).path("approvedMethods")).add("GET");
        Path invalidSet = write(duplicateSet);
        configured(invalidSet).run(context -> failure(context.getStartupFailure(), "SET_INVALID"));
    }

    @Test
    void boundsBytesAndBindingCountAndSupportsOnlyInVolumeAtomicProjectionLinks() throws Exception {
        Path oversized = write(new byte[1_048_577]);
        configured(oversized).run(context -> failure(context.getStartupFailure(), "SIZE_INVALID"));
        ObjectNode tooMany = inventory(binding("route-a", "readItems"));
        var array = ((ArrayNode) tooMany.path("bindings")).removeAll();
        for (int index = 0; index < 1025; index++) { array.addNull(); }
        Path count = write(tooMany);
        configured(count).run(context -> failure(context.getStartupFailure(), "SHAPE_INVALID"));
        Path volume = Files.createDirectory(directory.resolve("volume"));
        Path generation = Files.createDirectory(volume.resolve("..generation"));
        byte[] bytes = JSON.writeValueAsBytes(inventory(binding("route-a", "readItems")));
        Files.write(generation.resolve("publication.json"), bytes);
        Files.createSymbolicLink(volume.resolve("..data"), Path.of("..generation"));
        Path projected = Files.createSymbolicLink(volume.resolve("publication.json"), Path.of("..data/publication.json"));
        configured(projected).run(context -> assertThat(context).hasNotFailed());
        Path outside = write(bytes);
        Files.delete(projected);
        Files.createSymbolicLink(projected, outside);
        configured(projected).run(context -> failure(context.getStartupFailure(), "PATH_INVALID"));
    }

    private ApplicationContextRunner configured(Path file) throws Exception {
        return new ApplicationContextRunner().withUserConfiguration(GatewayExposurePublicationConfiguration.class)
                .withPropertyValues(FILE + "=" + file, SHA + "=" + digest(Files.readAllBytes(file)));
    }

    private Path write(JsonNode json) throws Exception { return write(JSON.writeValueAsBytes(json)); }
    private Path write(byte[] bytes) throws Exception {
        return Files.write(directory.resolve("inventory-" + fileSequence++ + ".json"), bytes);
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void failure(Throwable failure, String reason) {
        assertThat(failure).isNotNull().hasStackTraceContaining("YUHENG_PUBLICATION_INVENTORY_" + reason);
    }
    private static ObjectNode inventory(PublicationBinding... bindings) {
        ObjectNode node = JSON.createObjectNode().put("schemaVersion", "1");
        node.set("bindings", JSON.valueToTree(List.of(bindings)));
        return node;
    }
    private static PublicationBinding binding(String route, String operation) {
        var domain = new PublicationDomainVO("cluster-a", "public-vip-domain", "topology-v1", List.of(PHYSICAL), true);
        var gateway = new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true);
        var grant = new GrantReference("apps-prod", "allow-yuheng-synthetic", "grant-uid", "4", "yuheng-routes", "synthetic-public");
        var backend = new BackendReference("apps-prod", "synthetic-public", "service-uid", "17", 80, 8080, 100,
                "HTTP_1_1", grant, "a".repeat(64), Set.of("pod-a/containerd://a"), true);
        var authorization = new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE,
                "https://api.example.test/synthetic", "1", "b".repeat(64), true, List.of());
        return new PublicationBinding(7L, 9L, route, operation, "prod", "knowledge", "synthetic-binding", 1L,
                domain, gateway, "yuheng-routes", Set.of("public.apps.example.com"), Set.of("GET"),
                Set.of("/api/v1/synthetic"), List.of(backend), authorization);
    }
}
