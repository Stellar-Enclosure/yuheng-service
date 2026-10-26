package top.egon.cola.component.yuheng.edge.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import top.egon.cola.component.yuheng.edge.config.EdgeSecurityConfiguration;
import top.egon.cola.component.yuheng.edge.controller.EdgeIdentityController;
import top.egon.cola.component.yuheng.edge.controller.domain.EdgeRefreshOperationResult;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeCsrfProofService;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifestReader;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceAccess;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Compares compiler output with actual WebFlux handlers and native security route constants, without starting a server. */
@EnabledIfSystemProperty(named = "permission.producer.enabled", matches = "true")
class EdgePermissionProducerCoverageTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void everyOwnedNativeEndpointHasOneExactMetadataDeclaration() throws Exception {
        var manifest = manifest();
        Map<String, PermissionManifest.ResourceDefinition> apis = new TreeMap<>();
        manifest.resources().stream().filter(value -> value.type() == ResourceType.API).forEach(value -> apis.put(value.key(), value));
        assertThat(apis).hasSize(9);
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var mappings = new Mappings();
        Map<String, String> nativeKeys = Map.of("identity", "identity", "csrf", "csrf", "refresh", "refresh", "status", "refreshStatus", "logout", "logout");
        int count = 0;
        for (var bean : scanner.findCandidateComponents("top.egon.cola.component.yuheng.edge")) {
            Class<?> controller = Class.forName(bean.getBeanClassName(), false, getClass().getClassLoader());
            assertThat(controller).isEqualTo(EdgeIdentityController.class);
            for (Method method : controller.getDeclaredMethods()) {
                var mapping = mappings.mapping(method, controller);
                if (mapping == null) continue;
                assertThat(nativeKeys).containsKey(method.getName());
                var api = apis.get("yuheng.edge.protocol." + nativeKeys.get(method.getName()));
                assertThat(api.target().get("paths")).isEqualTo(mapping.getPatternsCondition().getPatterns().stream().map(Object::toString).sorted().toList());
                var verbs = new TreeSet<String>();
                mapping.getMethodsCondition().getMethods().forEach(value -> verbs.add(value.name()));
                // Identity is the sole GET handler whose native security rule also accepts authenticated HEAD.
                if (method.getName().equals("identity")) verbs.add("HEAD");
                assertThat(api.target().get("methods")).isEqualTo(List.copyOf(verbs));
                assertThat(api.access()).isEqualTo(ResourceAccess.INTERNAL);
                count++;
            }
        }
        assertThat(count).isEqualTo(5);
        var openApi = new EdgeSecurityConfiguration().edgeOpenAPI();
        for (String operation : List.of("login", "callback")) {
            var api = apis.get("yuheng.edge.protocol." + operation);
            String path = "/apps/{appAlias}/auth/" + operation;
            assertThat(openApi.getPaths().get(path).getGet()).isNotNull();
            assertThat(api.target()).containsEntry("paths", List.of(path)).containsEntry("methods", List.of("GET"));
            assertThat(api.access()).isEqualTo(ResourceAccess.PUBLIC);
        }
        assertThat(apis.get("yuheng.edge.protocol.publicI18n").target().get("paths"))
                .isEqualTo(List.of(EdgeSecurityConfiguration.PUBLIC_I18N_PATH, EdgeSecurityConfiguration.PUBLIC_APP_I18N_PATH).stream().sorted().toList());
        assertThat(apis.get("yuheng.edge.protocol.health").target().get("paths"))
                .isEqualTo(List.of("/actuator/health", "/actuator/health/**"));
        assertThat(scanner.findCandidateComponents("top.egon.cola.component.yuheng.edge.permission")).isEmpty();
        assertThat(manifest.permissionDefinitions()).isEmpty();
        assertThat(manifest.permissionReferences()).isEmpty();
    }

    @Test
    void nativeResponseFieldsAreReadonlyAndExactlyCoverTheActualProtocolEnvelopes() throws Exception {
        var manifest = manifest();
        Map<String, PermissionManifest.ResourceDefinition> fields = new TreeMap<>();
        manifest.resources().stream().filter(value -> value.type() == ResourceType.FIELD).forEach(value -> fields.put(value.key(), value));
        Set<String> expected = new TreeSet<>();
        for (Class<?> dto : List.of(EdgeIdentityController.IdentityResult.class, EdgeIdentityController.LogoutResult.class,
                EdgeRefreshOperationResult.class, EdgeCsrfProofService.Proof.class)) {
            var description = json.getDeserializationConfig().introspect(json.constructType(dto));
            for (var component : dto.getRecordComponents()) {
                String key = "yuheng.edge.field." + dto.getSimpleName() + "." + component.getName();
                expected.add(key);
                var field = fields.get(key);
                assertThat(field).as(key).isNotNull();
                assertThat(field.access()).isEqualTo(ResourceAccess.PUBLIC);
                assertThat(field.fieldPolicy()).isNull();
                assertThat(field.target()).containsEntry("dto", dto.getCanonicalName()).containsEntry("property", component.getName());
                assertThat(description.findProperties().stream().filter(value -> value.getName().equals(component.getName())).findFirst().orElseThrow().couldDeserialize()).isFalse();
            }
        }
        assertThat(fields.keySet()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(fields).hasSize(21);
        Set<String> messages = new TreeSet<>();
        manifest.messages().forEach(value -> assertThat(messages.add(value.pageCode() + ":" + value.key())).isTrue());
        for (var resource : manifest.resources()) assertThat(messages).contains(resource.i18n().pageCode() + ":" + resource.i18n().labelKey());
    }

    private PermissionManifest manifest() throws Exception {
        var manifest = new PermissionManifestReader().read(json.readValue(Files.readString(Path.of("target/classes/META-INF/permissions/fragment-v1.json")), new TypeReference<Map<String, Object>>() { }));
        assertThat(manifest.producer().id()).isEqualTo("yuheng-edge-java");
        assertThat(manifest.producer().expectedModules()).containsExactly("stellar-enclosure-yuheng-edge-gateway");
        assertThat(manifest.producer().ownedKeyPrefixes()).containsExactly("yuheng.edge.field.", "yuheng.edge.protocol.");
        return manifest;
    }
    private static final class Mappings extends RequestMappingHandlerMapping {
        RequestMappingInfo mapping(Method method, Class<?> owner) { return getMappingForMethod(method, owner); }
    }
}
