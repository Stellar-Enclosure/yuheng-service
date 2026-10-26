package top.egon.cola.component.yuheng.llm.permission;

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
    @Test void nativeInventoryIsExactAndDoesNotCreateRuntimeBeansOrSyntheticHeadRoutes() throws Exception {
        var fragment = new PermissionManifestReader().read(new ObjectMapper().readValue(Files.readString(
                Path.of("target/classes/META-INF/permissions/fragment-v1.json")), new TypeReference<Map<String,Object>>() {}));
        assertThat(fragment.producer().id()).isEqualTo("yuheng-llm-java");
        assertThat(fragment.producer().expectedModules()).containsExactly("stellar-enclosure-yuheng-llm-gateway");
        assertThat(fragment.producer().scannedModules()).containsExactly("stellar-enclosure-yuheng-llm-gateway");
        assertThat(fragment.producer().ownedKeyPrefixes()).containsExactly("yuheng.llm.");
        assertThat(fragment.resources()).hasSize(7);
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
        var mapping=new Mappings(); Set<String> actual=new TreeSet<>();
        for(var method:top.egon.cola.component.yuheng.llm.proxy.controller.LlmApiController.class.getDeclaredMethods()) {
            var route=mapping.route(method,top.egon.cola.component.yuheng.llm.proxy.controller.LlmApiController.class);
            if(route!=null) for(String path:route.getPatternValues()) for(var verb:route.getMethodsCondition().getMethods()) actual.add(verb.name()+" "+path);
        }
        Set<String> exported=new TreeSet<>();
        for(var resource:fragment.resources()) {
            if(resource.key().equals("yuheng.llm.api.health")) continue;
            for(Object path:(List<?>)resource.target().get("paths")) for(Object verb:(List<?>)resource.target().get("methods")) exported.add(verb+" "+path);
        }
        assertThat(exported).containsExactlyInAnyOrderElementsOf(actual);
    }
    private static class Mappings extends org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping {
        org.springframework.web.servlet.mvc.method.RequestMappingInfo route(java.lang.reflect.Method method,Class<?> type) { return getMappingForMethod(method,type); }
    }
}
