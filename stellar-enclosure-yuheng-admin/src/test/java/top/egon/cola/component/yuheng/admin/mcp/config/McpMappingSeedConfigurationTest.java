package top.egon.cola.component.yuheng.admin.mcp.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.egon.cola.component.yuheng.admin.mcp.application.McpMappingSeedArtifactTest.descriptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO;
import top.egon.cola.component.yuheng.admin.mcp.service.*;

class McpMappingSeedConfigurationTest {
    @TempDir Path dir;
    @Test
    void disabledConfigurationDoesNotCreateAuthority() {
        new ApplicationContextRunner().withUserConfiguration(McpMappingSeedConfiguration.class).run(c -> {
            assertThat(c).doesNotHaveBean(McpRegisteredSiIdentityFileSource.class);
            assertThat(c).doesNotHaveBean(McpMappingSeedService.class);
        });
    }
    @Test
    void deploymentFilePinAndExactOwnerGroupPreventCallerRetarget() throws Exception {
        Path file = dir.resolve("identity.json");
        byte[] bytes = new ObjectMapper().writeValueAsBytes(descriptor());
        Files.write(file, bytes);
        var source =
            new McpRegisteredSiIdentityFileSource(file, McpMappingSeedArtifactVO.sha256(bytes), 7, new ObjectMapper());
        assertThat(source.require(7, "10", "orders-registration").resourceId()).isEqualTo("201");
        assertThatThrownBy(() -> source.require(8, "10", "orders-registration")).hasMessageContaining("outside");
        assertThatThrownBy(() -> source.require(7, "11", "orders-registration")).hasMessageContaining("outside");
        assertThatThrownBy(() -> new McpRegisteredSiIdentityFileSource(file, "0".repeat(64), 7, new ObjectMapper()))
            .hasMessage("YUHENG_MCP_MAPPING_SEED_REGISTRATION_UNAVAILABLE");
        assertThatThrownBy(()
                               -> new McpRegisteredSiIdentityFileSource(
                                   file, McpMappingSeedArtifactVO.sha256(bytes), 8, new ObjectMapper()))
            .hasMessage("YUHENG_MCP_MAPPING_SEED_REGISTRATION_UNAVAILABLE");
    }
    @Test
    void pinnedButUnapprovedOrDuplicateRegistrationFileStillFails() throws Exception {
        var json = new ObjectMapper();
        var root = descriptor();
        root.put("authority", "SELF_DECLARED");
        Path file = dir.resolve("bad.json");
        byte[] bytes = json.writeValueAsBytes(root);
        Files.write(file, bytes);
        String hash = McpMappingSeedArtifactVO.sha256(bytes);
        assertThatThrownBy(() -> new McpRegisteredSiIdentityFileSource(file, hash, 7, json))
            .hasMessage("YUHENG_MCP_MAPPING_SEED_REGISTRATION_UNAVAILABLE");
        root = descriptor();
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("registrations"))
            .add(root.path("registrations").get(0));
        bytes = json.writeValueAsBytes(root);
        Files.write(file, bytes);
        String duplicateHash = McpMappingSeedArtifactVO.sha256(bytes);
        assertThatThrownBy(() -> new McpRegisteredSiIdentityFileSource(file, duplicateHash, 7, json))
            .hasMessage("YUHENG_MCP_MAPPING_SEED_REGISTRATION_UNAVAILABLE");
    }
    @Test
    void publisherRoleCannotLoadSeedAuthority() throws Exception {
        var context = mock(GatewayPersistenceContextComponent.class);
        when(context.installationTenantId()).thenReturn(7L);
        var properties = new McpMappingSeedConfiguration.Properties()
                             .setRegistrationDescriptorPath(dir.resolve("unused").toString())
                             .setRegistrationDescriptorSha256("0".repeat(64));
        var env =
            new org.springframework.mock.env.MockEnvironment().withProperty("yuheng.admin.exposure.role", "PUBLISHER");
        assertThatThrownBy(
            () -> new McpMappingSeedConfiguration().identities(properties, context, new ObjectMapper(), env))
            .hasMessage("YUHENG_MCP_MAPPING_SEED_ADMIN_ROLE_REQUIRED");
    }
}
