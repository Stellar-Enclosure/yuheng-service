package top.egon.cola.component.yuheng.mcp.engine.permission;

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
        assertThat(fragment.producer().id()).isEqualTo("yuheng-mcp-java");
        assertThat(fragment.producer().expectedModules()).containsExactly("stellar-enclosure-yuheng-mcp-gateway");
        assertThat(fragment.producer().scannedModules()).containsExactly("stellar-enclosure-yuheng-mcp-gateway");
        assertThat(fragment.producer().ownedKeyPrefixes()).containsExactly("yuheng.mcp.");
        assertThat(fragment.resources()).hasSize(6);
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
        assertThat(resources.get("yuheng.mcp.api.current").target().get("paths")).isEqualTo(List.of(top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler.MCP_PATH_PREFIX+"{serverCode}"));
        assertThat(resources.get("yuheng.mcp.api.legacy").target().get("paths")).isEqualTo(List.of(top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler.LEGACY_PATH_PREFIX+"{serverCode}"));
        assertThat(resources.get("yuheng.mcp.api.metadata").target().get("paths")).isEqualTo(List.of(top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler.METADATA_PATH_PREFIX+"{serverCode}"));
        var handler=org.mockito.Mockito.mock(top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        assertThat(handler.supports("/mcp/billing")).isTrue();
        assertThat(handler.supports("/legacy/mcp/billing")).isTrue();
        assertThat(handler.supports("/.well-known/oauth-protected-resource/mcp/billing")).isTrue();
        assertThat(handler.supports("/api/v1/yuheng/admin")).isFalse();
    }
}
