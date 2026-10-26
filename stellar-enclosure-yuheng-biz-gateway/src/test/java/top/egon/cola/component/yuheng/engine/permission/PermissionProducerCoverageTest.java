package top.egon.cola.component.yuheng.engine.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import org.springframework.web.bind.annotation.RequestMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Checks compiler-only inventory against native entrypoints without starting a listener. */
@EnabledIfSystemProperty(named="permission.producer.enabled",matches="true")
class PermissionProducerCoverageTest {
    @Test void dynamicRouteTablePreservesCustomMethodsAndDeniesUnregisteredMethodOrPath() {
        var route = new top.egon.cola.component.yuheng.core.route.RuntimeHttpRoute("route", "operation", "group",
                Set.of(top.egon.cola.component.yuheng.contract.protocol.AccessZone.PUBLIC), "*", Set.of("CONNECT", "CUSTOM"), "/dynamic/{id}", true,
                new top.egon.cola.component.yuheng.core.provider.ProviderServiceKey("test-biz", "test-app", "local", "default",
                        top.egon.cola.component.yuheng.core.provider.ProviderProtocolType.HTTP,"orders","default","v1","http"),
                Set.of(), 1, top.egon.cola.component.yuheng.core.route.GatewayResponseMode.TRANSPARENT, Map.of());
        var routes = new top.egon.cola.component.yuheng.core.route.HttpRouteCompiler().compile(List.of(route));
        assertThat(routes.match("api.example", "CONNECT", "/dynamic/1", top.egon.cola.component.yuheng.contract.protocol.AccessZone.PUBLIC)).isPresent();
        assertThat(routes.match("api.example", "CUSTOM", "/dynamic/1", top.egon.cola.component.yuheng.contract.protocol.AccessZone.PUBLIC)).isPresent();
        assertThat(routes.match("api.example", "GET", "/dynamic/1", top.egon.cola.component.yuheng.contract.protocol.AccessZone.PUBLIC)).isEmpty();
        assertThat(routes.match("api.example", "CUSTOM", "/unknown/1", top.egon.cola.component.yuheng.contract.protocol.AccessZone.PUBLIC)).isEmpty();
    }

    @Test void nativeInventoryIsExactAndDoesNotCreateRuntimeBeansOrSyntheticHeadRoutes() throws Exception {
        var fragment = new PermissionManifestReader().read(new ObjectMapper().readValue(Files.readString(
                Path.of("target/classes/META-INF/permissions/fragment-v1.json")), new TypeReference<Map<String,Object>>() {}));
        assertThat(fragment.producer().id()).isEqualTo("yuheng-biz-java");
        assertThat(fragment.producer().expectedModules()).containsExactly("stellar-enclosure-yuheng-biz-gateway");
        assertThat(fragment.producer().scannedModules()).containsExactly("stellar-enclosure-yuheng-biz-gateway");
        assertThat(fragment.producer().ownedKeyPrefixes()).containsExactly("yuheng.biz.");
        assertThat(fragment.resources()).hasSize(5);
        assertThat(fragment.permissionDefinitions()).isEmpty();
        Map<String,PermissionManifest.ResourceDefinition> resources = new TreeMap<>();
        fragment.resources().forEach(resource -> { assertThat(resources.put(resource.key(),resource)).isNull();
            assertThat(resource.type()).isEqualTo(ResourceType.API);
            assertThat(resource.access()).isIn(ResourceAccess.PUBLIC,ResourceAccess.INTERNAL);
            assertThat(resource.requirement()).isNull();
            assertThat(fragment.messages()).anyMatch(message -> message.pageCode().equals(resource.i18n().pageCode()) && message.key().equals(resource.i18n().labelKey())); });
        assertThat(NativeProtocolPermissionMetadata.class.getAnnotation(org.springframework.stereotype.Component.class)).isNull();
        for (var method : NativeProtocolPermissionMetadata.class.getDeclaredMethods()) {
            var resource=resources.get(method.getAnnotation(PermissionResource.class).key());
            var mapping=method.getAnnotation(RequestMapping.class);
            if (mapping==null) continue;
            assertThat(resource.target().get("paths")).isEqualTo(Arrays.stream(mapping.path()).sorted().toList());
            assertThat(resource.target().get("methods")).isEqualTo(Arrays.stream(mapping.method()).map(Enum::name).sorted().toList());
        }
        var nativeRoute=resources.get("yuheng.biz.api.http");
        assertThat(nativeRoute.target()).containsExactlyInAnyOrderEntriesOf(Map.of("nativeRouteType", "top.egon.cola.component.yuheng.core.route.RuntimeHttpRoute", "pathSource", "pathPattern", "methodSource", "methods", "protocol", "HTTP"));
        assertThat(top.egon.cola.component.yuheng.core.route.RuntimeHttpRoute.class.getMethod("pathPattern").getReturnType()).isEqualTo(String.class);
        assertThat(top.egon.cola.component.yuheng.core.route.RuntimeHttpRoute.class.getMethod("methods").getGenericReturnType().getTypeName()).isEqualTo("java.util.Set<java.lang.String>");
    }
}
