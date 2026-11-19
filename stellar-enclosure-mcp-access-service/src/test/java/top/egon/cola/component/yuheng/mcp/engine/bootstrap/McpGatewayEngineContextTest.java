package top.egon.cola.component.yuheng.mcp.engine.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.redisson.api.RedissonClient;
import jakarta.validation.Validation;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcProperties;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.config.McpGatewayEngineConfiguration;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.lifecycle.McpGatewayEngineRuntime;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceContextComponent;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceConfiguration;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceProperties;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPublicationMountProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.converter.McpTaskPersistenceConverter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpTaskDAO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpTaskPersistenceRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpBoundOperationInvoker;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpLkgRecoveryIT;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader;
import top.egon.cola.component.yuheng.mcp.engine.rule.service.McpGatewayRuleCompilerStrategy;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.platform.tianquan.jianshen.starter.client.Rbac3AuthorizationClient;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.security.*;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;
import top.egon.cola.platform.tianquan.shoubing.starter.state.IdentityAuthorityClientResolver;
import top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpGatewayEngineContextTest {
    @TempDir Path dataDirectory;

    @Test
    void startsActualMcpBeansWithPinnedFilesDirectInvokerAndOnlyBootHttpOwnership() throws Exception {
        var publication = McpLkgRecoveryIT.publication(dataDirectory, true);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            runner(publication, validation.getValidator()).withBean("dataSource", DataSource.class, () -> mock(DataSource.class)).run(context -> {
                assertNull(context.getStartupFailure(), () -> failureChain(context.getStartupFailure()));
                assertEquals(1, context.getBeansOfType(McpGatewayEngineRuntime.class).size());
                assertInstanceOf(McpGatewayRuleCompilerStrategy.class, context.getBean("mcpGatewayRuleCompilerStrategy"));
                assertInstanceOf(McpBoundOperationInvoker.class, context.getBean("gatewayOperationInvoker"));
                assertInstanceOf(McpEngineHttpHandler.class, context.getBean("gatewayMcpHttpHandler"));
                var runtime = context.getBean(McpGatewayEngineRuntime.class);
                assertTrue(runtime.running()); assertTrue(runtime.ready()); assertEquals("2", runtime.active().version());
                assertEquals(7L, runtime.active().ownerTenantId());
                assertEquals(publication.mounts(), context.getBean(McpPublicationMountProperties.class));
                assertSame(runtime.active(), runtime.pinned(runtime.active().identity().key()));
                assertEquals(1, context.getBeansOfType(McpPublishedBindingLoader.class).size());
                assertFalse(context.containsBean("gatewayRuleActivationApplier"));
                assertFalse(context.containsBean("gatewayRuntimeMetadata"));
                assertFalse(context.containsBean("ddcServiceRegistryClient"));
                assertFalse(context.containsBean("gatewayHttpServer"));
                assertFalse(context.containsBean("mcpGatewayHttpServer"));
                assertFalse(context.containsBean("gatewayRpcServer"));
                assertFalse(context.containsBean("gatewayRpcSlotRuntime"));
                verifyNoInteractions(context.getBean("ddcSiCatalogRpcClient", DdcSiCatalogClient.class));
            });
        }
    }

    @Test
    void missingDurableStoreFailsContextInsteadOfStartingPartialRuntime() throws Exception {
        var publication = McpLkgRecoveryIT.publication(dataDirectory, false);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            baseRunner(publication, validation.getValidator()).run(context -> {
                assertNotNull(context.getStartupFailure());
                assertTrue(String.valueOf(context.getStartupFailure().getMessage()).contains("mcpTaskPersistenceRepository"),
                        "the guarded task repository is mandatory even when fixed catalogs are structurally valid");
            });
        }
    }

    @Test
    void productionPersistenceConfigurationBindsTheUnifiedIdentityAndRestoresMdc() {
        persistenceRunner(true).run(context -> {
            assertNull(context.getStartupFailure());
            McpPersistenceContextComponent persistence = context.getBean(
                    "mcpPersistenceContextComponent", McpPersistenceContextComponent.class);
            MDC.put("tenantId", "prior-tenant");
            MDC.put("userId", "prior-user");
            try {
                assertEquals("mcp-result", persistence.call(null, () -> {
                    assertEquals("9001", MDC.get("tenantId"));
                    assertEquals("mcp-persistence-test", MDC.get("userId"));
                    return "mcp-result";
                }));
                assertEquals("prior-tenant", MDC.get("tenantId"));
                assertEquals("prior-user", MDC.get("userId"));
            } catch (Exception failure) {
                throw new AssertionError("the production persistence identity context must call and restore", failure);
            } finally {
                MDC.clear();
            }
        });
    }

    @Test
    void productionPersistenceConfigurationRejectsAnOmittedDdlRoleFlag() {
        persistenceRunner(false).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void runtimeConstructorNamesExactLocalPublicationDependencies() {
        var constructors = McpGatewayEngineRuntime.class.getConstructors();
        assertEquals(1, constructors.length);
        var parameters = constructors[0].getParameters();
        assertArrayEquals(new Class<?>[]{McpPublishedBindingLoader.class, McpGatewayRuleCompilerStrategy.class,
                McpPublicationMountProperties.class, Clock.class}, constructors[0].getParameterTypes());
        assertEquals("mcpPublishedBindingLoader", parameters[0].getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class).value());
        assertEquals("mcpGatewayRuleCompilerStrategy", parameters[1].getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class).value());
        assertEquals("gatewayClock", parameters[3].getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class).value());
    }

    @Test
    void profilesHaveIdenticalKeysAndKeepNativeH2cAndReadOnlyPublicationMounts() {
        Properties base = yaml("application.yml");
        assertEquals(base.stringPropertyNames(), yaml("application-operations.yml").stringPropertyNames());
        assertEquals("${YUHENG_MCP_ENABLED:true}", base.getProperty("egon.cola.component.yuheng.engine.mcp.enabled"));
        assertEquals("h2c", base.getProperty("egon.cola.component.rpc.transport"));
        assertEquals("false", base.getProperty("server.ssl.enabled"));
        assertEquals("none", base.getProperty("server.forward-headers-strategy"));
        assertEquals("${YUHENG_MCP_BINDINGS_FILE}", base.getProperty("egon.cola.component.yuheng.mcp-engine.publication.bindings.file"));
        assertEquals("${YUHENG_MCP_BINDINGS_SHA256}", base.getProperty("egon.cola.component.yuheng.mcp-engine.publication.bindings.sha256"));
        assertEquals("${YUHENG_MCP_CATALOG_FILE}", base.getProperty("egon.cola.component.yuheng.mcp-engine.publication.active-catalog.file"));
        assertTrue(base.stringPropertyNames().stream().noneMatch(key -> key.contains(".registry.") || key.contains(".tls.")
                || key.contains("development-plaintext") || key.contains("rpc.auth.runtime.secret-key")
                || key.startsWith("egon.cola.component.yuheng.mcp-engine.listener.")
                || key.startsWith("egon.cola.component.yuheng.mcp-engine.active-health.")));
    }

    @Test
    void executableAndRoleLocalRuntimeHaveNoRetiredDirectoryOrSecondListener() {
        for (String name : List.of("McpGatewayEngineApplication", "bootstrap.config.McpGatewayEngineConfiguration",
                "bootstrap.lifecycle.McpGatewayEngineRuntime", "mcp.service.McpBoundOperationInvoker", "mcp.controller.McpProtocolController")) {
            assertDoesNotThrow(() -> Class.forName("top.egon.cola.component.yuheng.mcp.engine." + name));
        }
        for (String retired : List.of("top.egon.cola.component.yuheng.mcp.engine.http.service.McpGatewayHttpServer",
                "top.egon.cola.component.yuheng.runtime.provider.service.ProviderDirectory",
                "top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(retired));
        }
    }

    private ApplicationContextRunner runner(McpLkgRecoveryIT.TestPublicationVO publication, jakarta.validation.Validator validator) {
        return baseRunner(publication, validator)
                .withBean("mcpTaskDAO", McpTaskDAO.class, () -> mock(McpTaskDAO.class))
                .withBean("mcpTaskPersistenceRepository", McpTaskPersistenceRepository.class, () -> mock(McpTaskPersistenceRepository.class))
                .withBean("mcpTaskPersistenceConverter", McpTaskPersistenceConverter.class, () -> new McpTaskPersistenceConverter(publication.json()));
    }

    /** No external transport is opened: only the DB/Redis, native OAuth/RPC and remote SI/PDP boundaries are fixtures. */
    private ApplicationContextRunner baseRunner(McpLkgRecoveryIT.TestPublicationVO publication, jakarta.validation.Validator validator) {
        var mounts = publication.mounts();
        var persistence = persistenceProperties().setTenantId(7L).setIdentityTenantId(90001L);
        var idp = identityProperties();
        var runner = new ApplicationContextRunner().withUserConfiguration(McpGatewayEngineConfiguration.class)
                .withPropertyValues("egon.cola.component.yuheng.mcp-engine.runtime.node-id=mcp-test",
                        "egon.cola.component.yuheng.mcp-engine.publication.caller-app-code=" + mounts.callerAppCode(),
                        "egon.cola.component.yuheng.mcp-engine.publication.bindings.file=" + mounts.bindings().file(),
                        "egon.cola.component.yuheng.mcp-engine.publication.bindings.sha256=" + mounts.bindings().sha256(),
                        "egon.cola.component.yuheng.mcp-engine.publication.active-catalog.file=" + mounts.activeCatalog().file(),
                        "egon.cola.component.yuheng.mcp-engine.publication.active-catalog.sha256=" + mounts.activeCatalog().sha256(),
                        "egon.cola.component.yuheng.engine.mcp.artifact-root=" + dataDirectory)
                .withBean("gatewayClock", Clock.class, publication::clock)
                .withBean(McpPersistenceProperties.BEAN_NAME, McpPersistenceProperties.class, () -> persistence)
                .withBean("mcpPersistenceContextComponent", McpPersistenceContextComponent.class,
                        () -> new McpPersistenceContextComponent(persistence, new EgonColaMybatisPlusProperties()))
                .withBean("jacksonObjectMapper", ObjectMapper.class, publication::json)
                .withBean("meterRegistry", SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("observationRegistry", ObservationRegistry.class, ObservationRegistry::create)
                .withBean("ddcSiCatalogRpcClient", DdcSiCatalogClient.class, () -> mock(DdcSiCatalogClient.class))
                .withBean("gatewayMcpRedissonClient", RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean("idpServiceOAuth2Client", IdpServiceOAuth2Client.class, () -> mock(IdpServiceOAuth2Client.class))
                .withBean("rpcGenericInvoker", RpcGenericInvoker.class, () -> mock(RpcGenericInvoker.class))
                .withBean("rpcReferenceDefinitionResolver", RpcReferenceDefinitionResolver.class, () -> mock(RpcReferenceDefinitionResolver.class))
                .withBean("idpRpcClientCredentialInterceptorFactory", IdpRpcClientCredentialInterceptorFactory.class, () -> mock(IdpRpcClientCredentialInterceptorFactory.class))
                .withBean("idpStarterProperties", IdpStarterProperties.class, () -> idp)
                .withBean(DdcRpcProperties.BEAN_NAME, DdcRpcProperties.class, () -> new DdcRpcProperties().setEnabled(true).setBindingId("tianshu-si").setBindingVersion(1).setDefaultTimeout(java.time.Duration.ofSeconds(2)))
                .withBean("tenantIssuerAuthenticationManagerComponent", TenantIssuerAuthenticationManagerComponent.class, () -> mock(TenantIssuerAuthenticationManagerComponent.class))
                .withBean("identityAuthorityClientResolver", IdentityAuthorityClientResolver.class, () -> mock(IdentityAuthorityClientResolver.class))
                .withBean("verifiedPrincipalConverter", VerifiedPrincipalConverter.class, VerifiedPrincipalConverterImpl::new)
                .withBean("idpObservedVerificationComponent", IdpObservedVerificationComponent.class, () -> mock(IdpObservedVerificationComponent.class))
                .withBean("idpSecurityMetricsRecorder", IdpSecurityMetricsRecorder.class, () -> mock(IdpSecurityMetricsRecorder.class))
                .withBean("defaultValidator", jakarta.validation.Validator.class, () -> validator)
                .withBean("singleFlightSnapshotLoader", SingleFlightSnapshotLoader.class,
                        () -> new SingleFlightSnapshotLoader((code, identity) -> { throw new Rbac3AuthorizationClient.AuthorizationUnavailableException("fixture-current-PDP-unavailable"); }, "yuheng-mcp", publication.clock()));
        for (int index = 0; index < mounts.historicalCatalogs().size(); index++) {
            var file = mounts.historicalCatalogs().get(index);
            String prefix = "egon.cola.component.yuheng.mcp-engine.publication.historical-catalogs[" + index + "].";
            runner = runner.withPropertyValues(prefix + "file=" + file.file(), prefix + "sha256=" + file.sha256());
        }
        return runner;
    }

    private static String failureChain(Throwable failure) {
        var details = new StringBuilder();
        for (var current = failure; current != null; current = current.getCause()) details.append(current).append('\n');
        return details.toString();
    }

    private static IdpStarterProperties identityProperties() {
        var properties = new IdpStarterProperties();
        properties.setIssuer("https://identity.example.test/tenants/90001");
        properties.setResourceUri(java.net.URI.create("https://identity.example.test/resources/mcp-adapter"));
        var trusted = new IdpStarterProperties.TrustedIssuerSettings();
        trusted.setIssuer(properties.getIssuer()); trusted.setJwksUri(properties.getIssuer() + "/jwks");
        trusted.setTenantId("90001"); trusted.setRealmType("TENANT"); trusted.setRealmKey("tenant-90001"); trusted.setStatus("ACTIVE"); trusted.setTrustVersion(1);
        properties.setTrustedIssuers(Map.of("tenant", trusted));
        return properties;
    }

    private static McpPersistenceProperties persistenceProperties() {
        return new McpPersistenceProperties().setTenantId(9001L).setIdentityTenantId(9001L).setServiceUserId("yuheng-mcp-engine-test")
                .setExpectedSchemaVersion("20260922_001").setExpectedSchemaSha256("0".repeat(64)).setManagedDdlEnabled(false);
    }
    private Properties yaml(String resource) {
        var loader = new YamlPropertiesFactoryBean(); loader.setResources(new ClassPathResource(resource)); return loader.getObject();
    }

    private ApplicationContextRunner persistenceRunner(boolean includeManagedDdlFlag) {
        List<String> properties = new java.util.ArrayList<>(List.of(
                "yuheng.persistence.tenant-id=9001",
                "yuheng.persistence.identity-tenant-id=9001",
                "yuheng.persistence.service-user-id=mcp-persistence-test",
                "yuheng.persistence.expected-schema-version=20260922_001",
                "yuheng.persistence.expected-schema-sha256=" + "a".repeat(64),
                "egon.cola.component.mybatis-plus.ddl.enabled=false"));
        if (includeManagedDdlFlag) {
            properties.add("yuheng.persistence.managed-ddl-enabled=false");
        }
        return new ApplicationContextRunner()
                .withUserConfiguration(McpPersistenceConfiguration.class)
                .withPropertyValues(properties.toArray(String[]::new))
                .withBean("dataSource", DataSource.class, () -> mock(DataSource.class))
                .withBean("mcp-test-sql-session-factory", SqlSessionFactory.class,
                        () -> mock(SqlSessionFactory.class))
                .withBean("mcp-test-transaction-manager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean("egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties",
                        EgonColaMybatisPlusProperties.class, EgonColaMybatisPlusProperties::new);
    }

}
