package top.egon.cola.component.yuheng.admin.mcp.repository.filesystem;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.mcp.service.McpControlPlaneService;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Native App artifacts have a single owner outside the Yuheng Admin process. */
class McpArtifactUploadIT {

    @Test
    void nativeArtifactControllerAndFilesystemRuntimeAreAbsent() {
        for (String type : new String[]{
                "top.egon.cola.component.yuheng.admin.mcp.controller.McpAppAdminController",
                "top.egon.cola.component.yuheng.admin.mcp.repository.filesystem.FileSystemMcpAppArtifactRepository"}) {
            assertThatThrownBy(() -> Class.forName(type))
                    .as(type).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void serviceRetainsMetadataHistoryWithoutArtifactMutation() {
        assertThat(Arrays.stream(McpControlPlaneService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName))
                .contains("artifacts", "artifact")
                .doesNotContain("uploadArtifact", "registerArtifact", "revokeArtifact");
        assertThat(Arrays.stream(McpControlPlaneService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getName))
                .noneMatch(name -> name.contains("McpAppArtifactStore")
                        || name.contains("McpAppSecurityValidator"));
    }
}
