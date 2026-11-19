package top.egon.cola.component.yuheng.admin.mcp.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.Arrays;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class McpControlPlaneServiceConstructorTest {

    @Test
    void nativeAssetWritesAreAbsentFromTheServiceBoundary() {
        assertThat(Arrays.stream(McpControlPlaneService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("createServer", "updateServer", "deleteServer",
                        "putCapability", "deleteCapability", "putProvider", "deleteProvider",
                        "putMount", "deleteMount", "uploadArtifact", "registerArtifact", "revokeArtifact");
    }

    @BeforeAll
    static void bindTheProcessWideEngine() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    @Test
    void selectsTheProductionConstructorForSpringInjection() {
        var constructors = Arrays.stream(
                        McpControlPlaneService.class.getDeclaredConstructors()
                )
                .filter(constructor -> constructor.isAnnotationPresent(
                        Autowired.class
                ))
                .toList();

        assertThat(constructors).singleElement()
                .satisfies(constructor -> assertThat(
                        constructor.getParameterCount()
                ).isEqualTo(10));
        assertThat(constructors.getFirst().getParameters()).allSatisfy(parameter ->
                assertThat(parameter.getAnnotation(Qualifier.class)).isNotNull());
        assertThat(Arrays.stream(constructors.getFirst().getParameterTypes()).map(Class::getName))
                .noneMatch(name -> name.contains("McpAppArtifactStore")
                        || name.contains("McpAppSecurityValidator")
                        || name.contains("GatewayDraftJpaRepository"));
    }

    @Test
    void taskBridgeRemainsUntilItsIndependentExecutionMigration() {
        assertThat(Arrays.stream(McpControlPlaneService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName))
                .contains("tasks", "task", "cancelTask");
    }

    @Test
    void nativeProviderValidationNeverConsultsCatalogOrRemoteProtocols() {
        var catalog = mock(top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository.class);
        var validator = new McpValidationService(catalog, new com.fasterxml.jackson.databind.ObjectMapper());
        var provider = new top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeRemoteProvider(
                "provider-1", "remote", "Remote",
                top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect.STABLE_2025_11_25,
                "STREAMABLE_HTTP", "https://remote.invalid/mcp", null, null, "a".repeat(64), true);
        var content = new top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent(
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(provider), java.util.List.of());

        assertThatThrownBy(() -> validator.requireValid(content))
                .isInstanceOf(top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException.class)
                .hasMessage("native MCP assets cannot be published by Yuheng");
        verifyNoInteractions(catalog);
    }
}
