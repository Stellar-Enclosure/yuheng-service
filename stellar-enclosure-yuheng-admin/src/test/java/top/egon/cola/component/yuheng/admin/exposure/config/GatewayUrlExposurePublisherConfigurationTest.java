package top.egon.cola.component.yuheng.admin.exposure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import top.egon.cola.component.yuheng.admin.config.GatewayAdminTransportSecurityValidator;
import org.springframework.context.PayloadApplicationEvent;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.delivery.DeliveryHandlerRegistry;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.dispatch.OutboxDispatcher;
import top.egon.cola.component.outbox.event.OutboxCommittedEvent;
import top.egon.cola.component.outbox.event.OutboxCommittedEventListener;
import top.egon.cola.component.yuheng.admin.config.GatewayAdminSecurityConfiguration;
import top.egon.cola.component.yuheng.admin.exposure.support.outbox.GatewayUrlExposureOutboxDeliveryHandler;

import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Set;
import java.security.MessageDigest;
import java.util.HexFormat;
import top.egon.cola.component.yuheng.admin.config.GatewayExposurePublicationConfiguration;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceProperties;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GatewayReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GrantReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BackendReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.AuthorizationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GatewayUrlExposurePublisherConfigurationTest {
    private static final String CONFIGURATION = "top.egon.cola.component.yuheng.admin.exposure.config.GatewayUrlExposurePublisherConfiguration";
    @TempDir Path directory;
    private static final String ROLE = "yuheng.admin.exposure.role";

    @Test
    void defaultAdminRetainsOneChannelButCannotClaimAfterCommit() throws Exception {
        var dispatcher = mock(OutboxDispatcher.class);
        runner(dispatcher, false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(GatewayUrlExposureOutboxDeliveryHandler.Writer.class)).isEmpty();
            assertThat(context.getBean(DeliveryHandlerRegistry.class).channels()).containsExactly("yuheng-exposure");
            assertThat(context).doesNotHaveBean("gatewayKubernetesClient");
            var listener = context.getBean("outboxCommittedEventListener", OutboxCommittedEventListener.class);
            listener.onApplicationEvent(new PayloadApplicationEvent<>(context, new OutboxCommittedEvent(List.of("committed-intent"))));
            context.publishEvent(new OutboxCommittedEvent(List.of("another-intent")));
            org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.never()).submitMessageIds(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.never()).submitDue();
        });
    }

    @Test
    void adminRejectsPollingCleanupOrAnAccidentalWriter() throws Exception {
        runner(mock(OutboxDispatcher.class), true).run(context -> assertThat(context).hasFailed());
        var cleanup = quietOutbox(); cleanup.getCleanup().setEnabled(true);
        runner(mock(OutboxDispatcher.class), cleanup).run(context -> assertThat(context).hasFailed());
        runner(mock(OutboxDispatcher.class), false).withBean("rogueWriter", GatewayUrlExposureOutboxDeliveryHandler.Writer.class,
                () -> ignored -> DeliveryResult.success()).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void publisherRequiresNonWebProfilePollingAndActualAuthority() throws Exception {
        runner(mock(OutboxDispatcher.class), false).withPropertyValues(ROLE + "=PUBLISHER", "spring.profiles.active=publisher", "spring.main.web-application-type=none")
                .run(context -> assertThat(context).hasFailed());
        runner(mock(OutboxDispatcher.class), true).withPropertyValues(ROLE + "=PUBLISHER", "spring.profiles.active=publisher", "spring.main.web-application-type=servlet")
                .run(context -> assertThat(context).hasFailed());
        runner(mock(OutboxDispatcher.class), true).withPropertyValues(ROLE + "=PUBLISHER", "spring.profiles.active=publisher", "spring.main.web-application-type=none")
                .run(context -> assertThat(context).hasFailed());
        runner(mock(OutboxDispatcher.class), false).withPropertyValues(ROLE + "=UNRECOGNIZED")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void servletSecurityDoesNotBecomeAPublisherListenerDependency() {
        var condition = GatewayAdminSecurityConfiguration.class.getAnnotation(ConditionalOnWebApplication.class);
        assertThat(condition).isNotNull();
        assertThat(condition.type()).isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
    }

    @Test
    void publisherDoesNotRegisterTheThreeAdminMaintenanceJobs() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "publisher-role", java.util.Map.of(ROLE, "PUBLISHER")));
            context.register(top.egon.cola.component.yuheng.admin.bootstrap.GatewayAdminConfiguration.class,
                    top.egon.cola.component.yuheng.admin.reporting.controller.scheduled.GatewayHmacNonceReaper.class,
                    top.egon.cola.component.yuheng.admin.reporting.controller.scheduled.GatewayDefinitionLifecycleReconciler.class);
            var processor = new org.springframework.context.annotation.ConfigurationClassPostProcessor();
            processor.setEnvironment(context.getEnvironment());
            processor.postProcessBeanDefinitionRegistry((org.springframework.beans.factory.support.BeanDefinitionRegistry) context.getBeanFactory());
            assertThat(context.containsBeanDefinition("gatewayHmacNonceReaper")).isFalse();
            assertThat(context.containsBeanDefinition("gatewayDefinitionLifecycleReconciler")).isFalse();
            assertThat(context.containsBeanDefinition("gatewayObservabilityRetentionReaper")).isFalse();
        }
    }

    @Test
    void realPublisherProfileBuildsOneWriterAndAnExactFrozenTransportWithoutAWebServer() throws Exception {
        publisher().withUserConfiguration(GatewayAdminTransportSecurityValidator.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
            assertThat(org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment())
                    .bind("spring.autoconfigure.exclude", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class)).get())
                    .containsExactly("org.redisson.spring.starter.RedissonAutoConfigurationV4",
                            "top.egon.cola.platform.tianquan.shoubing.starter.client.ServiceTokenWebClientConfiguration",
                            "top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcAutoConfiguration");
            assertThat(context.getBeansOfType(GatewayUrlExposureOutboxDeliveryHandler.Writer.class)).hasSize(1);
            assertThat(context.getBean(DeliveryHandlerRegistry.class).channels()).containsExactly("yuheng-exposure");
            var client = context.getBean("gatewayKubernetesClient", top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.class);
            assertThat(client.scope().clusterId()).isEqualTo("cluster-a");
            assertThat(client.scope().routeNamespace()).isEqualTo("yuheng-routes");
            assertThat(client.scope().gateways()).containsExactly(new top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.GatewayKey("edge-system", "public-apps", "https-api"));
            assertThat(client.scope().services()).containsExactly(new top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ServiceKey("apps-prod", "synthetic-public"));
            assertThat(client.scope().grants()).containsExactly(new top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.GrantKey("apps-prod", "allow-yuheng-synthetic"));
            assertThat(context).doesNotHaveBean("gatewayAdminSecurityFilterChain");
            assertThat(context).doesNotHaveBean(GatewayAdminTransportSecurityValidator.class);
        });
    }

    @Test
    void otherwiseValidPublisherRejectsExtraWriterAndAnOverriddenLegacyJobSwitch() throws Exception {
        publisher().withBean("rogueWriter", GatewayUrlExposureOutboxDeliveryHandler.Writer.class, () -> ignored -> DeliveryResult.success())
                .run(context -> assertThat(context).hasFailed());
        publisher().withPropertyValues("yuheng.admin.openapi.enabled=true").run(context -> assertThat(context).hasFailed());
        publisher().withPropertyValues("yuheng.admin.tianshu.enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void writerUsesOnlyDeploymentOwnerAndInstallsAndRestoresTheExistingPersistenceContext() throws Exception {
        var deployment = new GatewayPersistenceProperties().setTenantId(7001L).setIdentityTenantId(9001L).setServiceUserId("publisher-test");
        var context = new GatewayPersistenceContextComponent(deployment, new EgonColaMybatisPlusProperties());
        var reconciler = mock(top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureReconciler.class);
        var result = DeliveryResult.retryableFailure("WAITING_FOR_EVIDENCE", "current generation is not confirmed");
        org.mockito.Mockito.when(reconciler.reconcile(7001, 810001)).thenAnswer(ignored -> {
            assertThat(org.slf4j.MDC.get("tenantId")).isEqualTo("7001");
            assertThat(org.slf4j.MDC.get("userId")).isEqualTo("publisher-test");
            return result;
        });
        var writer = new top.egon.cola.component.yuheng.admin.exposure.service.ExposureDesiredStateDeliveryHandler(context, reconciler);
        org.slf4j.MDC.put("tenantId", "previous-tenant"); org.slf4j.MDC.put("userId", "previous-actor");
        try {
            var event = new top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent(
                    "synthetic-event", 7001, 810001, 999,
                    top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum.OPEN, java.time.Instant.parse("2026-11-18T20:00:00Z"));
            assertThat(writer.reconcile(event)).isEqualTo(result);
            org.mockito.Mockito.verify(reconciler).reconcile(7001, 810001);
            assertThat(org.slf4j.MDC.get("tenantId")).isEqualTo("previous-tenant");
            assertThat(org.slf4j.MDC.get("userId")).isEqualTo("previous-actor");
            org.mockito.Mockito.clearInvocations(reconciler);
            var foreign = new top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent(
                    "foreign-event", 7002, 810001, 1,
                    top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum.CLOSED, java.time.Instant.parse("2026-11-18T20:00:00Z"));
            assertThat(writer.reconcile(foreign).kind()).isEqualTo(DeliveryResult.Kind.PERMANENT_FAILURE);
            verifyNoInteractions(reconciler);
        } finally { org.slf4j.MDC.remove("tenantId"); org.slf4j.MDC.remove("userId"); }
        var constructor = writer.getClass().getDeclaredConstructors()[0];
        assertThat(constructor.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class)).isNotNull();
        assertThat(constructor.getParameters()[0].getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class).value())
                .isEqualTo("gatewayPersistenceContextComponent");
        assertThat(constructor.getParameters()[1].getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class).value())
                .isEqualTo("gatewayUrlExposureReconciler");
    }

    private ApplicationContextRunner publisher() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var gateway = new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true);
        var domain = new PublicationDomainVO("cluster-a", "public-vip-domain", "topology-v1",
                List.of(new BindingVO(gateway.namespace(), gateway.name(), gateway.listenerName())), true);
        var grant = new GrantReference("apps-prod", "allow-yuheng-synthetic", "grant-uid", "4", "yuheng-routes", "synthetic-public");
        var backend = new BackendReference("apps-prod", "synthetic-public", "service-uid", "17", 80, 8080, 100,
                "HTTP_1_1", grant, "a".repeat(64), Set.of("pod-a/containerd://a"), true);
        var authorization = new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE,
                "https://api.example.test/synthetic", "1", "b".repeat(64), true, List.of());
        var binding = new PublicationBinding(7001L, 8001L, "read-route", "readItems", "prod", "knowledge", "synthetic-binding", 1L,
                domain, gateway, "yuheng-routes", Set.of("public.apps.example.com"), Set.of("GET"),
                Set.of("/api/v1/synthetic"), List.of(backend), authorization);
        var inventory = json.createObjectNode().put("schemaVersion", "1"); inventory.set("bindings", json.valueToTree(List.of(binding)));
        byte[] bytes = json.writeValueAsBytes(inventory);
        Path file = Files.createTempFile(directory, "inventory-", ".json"); Files.write(file, bytes);
        Path token = Files.createTempFile(directory, "projected-token-", ".txt"); Files.writeString(token, "synthetic.projected.token");
        var trusts = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trusts.init((java.security.KeyStore) null);
        var trust = java.util.Arrays.stream(trusts.getTrustManagers()).filter(javax.net.ssl.X509TrustManager.class::isInstance)
                .map(javax.net.ssl.X509TrustManager.class::cast).findFirst().orElseThrow();
        Path ca = Files.createTempFile(directory, "cluster-ca-", ".der"); Files.write(ca, trust.getAcceptedIssuers()[0].getEncoded());
        var deployment = new GatewayPersistenceProperties().setTenantId(7001L).setIdentityTenantId(9001L).setServiceUserId("publisher-test")
                .setExpectedSchemaVersion("20261117_004").setExpectedSchemaSha256("a".repeat(64)).setManagedDdlEnabled(true);
        return runner(mock(OutboxDispatcher.class), true).withUserConfiguration(GatewayExposurePublicationConfiguration.class)
                .withInitializer(context -> {
                    var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
                    try {
                        for (var source : loader.load("publisher-profile", new org.springframework.core.io.ClassPathResource("application-publisher.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                })
                .withPropertyValues("spring.profiles.active=publisher", "YUHENG_PUBLISHER_API_SERVER=https://kubernetes.invalid",
                        "YUHENG_PUBLISHER_CA_FILE=" + ca, "YUHENG_PUBLISHER_TOKEN_FILE=" + token, "YUHENG_PUBLISHER_NODE_ID=publisher-test",
                        GatewayExposurePublicationConfiguration.FILE_PROPERTY + "=" + file,
                        GatewayExposurePublicationConfiguration.SHA256_PROPERTY + "=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .withBean("gatewayPersistenceContextComponent", GatewayPersistenceContextComponent.class,
                        () -> new GatewayPersistenceContextComponent(deployment, new EgonColaMybatisPlusProperties()))
                .withBean("outboxMpStorageProperties", OutboxMpStorageProperties.class, OutboxMpStorageProperties::new)
                .withBean("mpGatewayUrlExposureRepository", GatewayUrlExposureRepository.class, () -> mock(GatewayUrlExposureRepository.class))
                .withBean("mpGatewayReleaseRepository", GatewayReleaseRepository.class, () -> mock(GatewayReleaseRepository.class))
                .withBean("gatewayExposureTransactionTemplate", org.springframework.transaction.support.TransactionTemplate.class,
                        () -> mock(org.springframework.transaction.support.TransactionTemplate.class))
                .withBean("gatewayHttpRouteCompiler", GatewayHttpRouteCompiler.class, () -> new GatewayHttpRouteCompiler(json))
                .withBean("gatewayProjectionClock", java.time.Clock.class, java.time.Clock::systemUTC);
    }

    private ApplicationContextRunner runner(OutboxDispatcher dispatcher, boolean polling) throws Exception {
        var properties = quietOutbox(); properties.getPolling().setEnabled(polling);
        return runner(dispatcher, properties);
    }

    private ApplicationContextRunner runner(OutboxDispatcher dispatcher, TransactionalOutboxProperties properties) throws Exception {
        properties.setNodeId("publisher-test");
        return new ApplicationContextRunner().withUserConfiguration(Class.forName(CONFIGURATION), GatewayUrlExposureOutboxDeliveryHandler.class)
                .withBean("jacksonObjectMapper", ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean("deliveryHandlerRegistry", DeliveryHandlerRegistry.class)
                .withBean("outboxDispatcher", OutboxDispatcher.class, () -> dispatcher)
                .withBean("outboxStateMachineOutboxProperties", TransactionalOutboxProperties.class, () -> properties);
    }

    private static TransactionalOutboxProperties quietOutbox() {
        var properties = new TransactionalOutboxProperties();
        properties.getPolling().setEnabled(false); properties.getCleanup().setEnabled(false);
        return properties;
    }
}
