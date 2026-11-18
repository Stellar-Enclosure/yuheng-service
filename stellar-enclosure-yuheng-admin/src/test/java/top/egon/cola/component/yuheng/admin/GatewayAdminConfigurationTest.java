package top.egon.cola.component.yuheng.admin.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import top.egon.cola.component.yuheng.admin.config.properties.GatewayAdminOpenApiProperties;
import top.egon.cola.component.tianshu.api.client.DdcManagementClient;
import top.egon.cola.component.rpc.tianshu.client.DdcRpcClientFactory;
import top.egon.cola.component.rpc.tianshu.client.DdcRpcClientHandle;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.exception.GatewayUrlExposureException;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.AuthorizationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BackendReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BindingResolver;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GatewayReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GrantReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayAdminConfigurationTest {

    @Test
    void explicitReopenRequiresTheSameStillApprovedBindingAndFrozenContent() {
        var configuration = new GatewayAdminConfiguration();
        ObjectMapper json = configuration.gatewayOpenApiObjectMapper();
        var compiler = new GatewayHttpRouteCompiler(json);
        var pinned = new PublicationBinding(7, 810, "orders", "list-orders", "prod", "prod", "orders-public", 1,
                new PublicationDomainVO("cluster", "public-zone", "1",
                        List.of(new BindingVO("edge-system", "public-apps", "https-api")), true),
                new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true),
                "yuheng-routes", Set.of("api.example.com"), Set.of("GET"), Set.of(),
                List.of(new BackendReference("apps-prod", "orders", "svc-uid", "2", 80, 8080, 100, "HTTP_1_1",
                        new GrantReference("apps-prod", "allow-orders", "grant-uid", "4", "yuheng-routes", "orders"),
                        "a".repeat(64), Set.of("pod-uid"), true)),
                new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE, "https://api.example.com/orders",
                        "1", "b".repeat(64), true, List.of()));
        var approved = new AtomicReference<>(pinned);
        BindingResolver resolver = (owner, group, route, operation) ->
                owner == 7 && group == 810 && route.equals("orders") && operation.equals("list-orders")
                        ? Optional.ofNullable(approved.get()) : Optional.empty();
        var factory = new StaticListableBeanFactory();
        factory.addBean("gatewayHttpRouteBindingResolver", resolver);
        ObjectProvider<BindingResolver> bindings = factory.getBeanProvider(BindingResolver.class);
        var releases = mock(GatewayReleaseRepository.class);
        var artifact = mock(GatewayCiliumReleaseArtifact.class);
        when(releases.loadNative("51")).thenReturn(Optional.of(artifact));
        when(artifact.ownerTenantId()).thenReturn("7");
        when(artifact.gatewayGroupId()).thenReturn("810");
        String digest = "f".repeat(64);
        var snapshot = json.createObjectNode();
        snapshot.putObject("exposurePublication").putArray("exposures").addObject().put("exposureId", "101")
                .put("routeId", "orders").put("contentSha256", digest).put("expectedDesiredGeneration", "1");
        when(artifact.canonicalSnapshot()).thenReturn(snapshot);
        var manifest = json.createObjectNode();
        var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(pinned);
        var backend = (com.fasterxml.jackson.databind.node.ObjectNode) frozen.at("/backends/0");
        backend.put("port", new java.math.BigDecimal("8E+1"));
        backend.put("targetPort", new java.math.BigDecimal("8.08E+3"));
        backend.put("weight", new java.math.BigDecimal("1E+2"));
        manifest.putArray("dependencies").addObject().set("publicationBinding", frozen);
        when(artifact.chunkManifest()).thenReturn(manifest);
        var validator = configuration.gatewayExposureReopenValidator(releases, compiler, bindings, json);
        var closed = GatewayUrlExposureBO.builder().id(101L).ownerTenantId(7L).gatewayGroupId(810L)
                .routeId("orders").releaseId(51L).contentSha256(digest).desiredGeneration(9L).build();

        validator.validate(7, closed);
        closed.setContentSha256("e".repeat(64));
        assertThatThrownBy(() -> validator.validate(7, closed)).isInstanceOfSatisfying(GatewayUrlExposureException.class,
                failure -> assertThat(failure.code()).isEqualTo("YUHENG_EXPOSURE_BINDING_CONFLICT"));
        closed.setContentSha256(digest);
        com.fasterxml.jackson.databind.node.ObjectNode changed = json.valueToTree(pinned);
        changed.put("bindingVersion", 2);
        approved.set(json.convertValue(changed, PublicationBinding.class));
        assertThatThrownBy(() -> validator.validate(7, closed)).isInstanceOfSatisfying(GatewayUrlExposureException.class,
                failure -> assertThat(failure.status()).isEqualTo(409));
        approved.set(null);
        assertThatThrownBy(() -> validator.validate(7, closed)).isInstanceOf(GatewayUrlExposureException.class);
    }

    @Test
    void projectionClockIsNamedAndIndependentOfOpenApiFlag() throws Exception {
        Method factory = GatewayAdminConfiguration.class.getDeclaredMethod("gatewayProjectionClock");
        assertThat(factory.getAnnotation(org.springframework.context.annotation.Bean.class).value())
                .containsExactly("gatewayProjectionClock");
        assertThat(factory.getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class))
                .isNull();
        assertThat(new GatewayAdminConfiguration().gatewayProjectionClock().getZone())
                .isEqualTo(java.time.ZoneOffset.UTC);
    }

    @Test
    void projectionConstructorQualifiesClockAndValidatesGroupBoundary() {
        Class<?> type = top.egon.cola.component.yuheng.admin.runtime.service.GatewayProjectionService.class;
        assertThat(type.getConstructors()).hasSize(1);
        assertThat(Arrays.stream(type.getConstructors()[0].getParameters())
                .map(parameter -> parameter.getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class).value()))
                .contains("gatewayProjectionClock", "gatewayEngineRoleConsistencyStrategy", "ddcManagementClient");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(type,
                        top.egon.cola.component.yuheng.admin.runtime.service.GatewayEngineRoleConsistencyStrategy.class)
                .withBean("mpGatewayGroupRepository", top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository.class,
                        () -> mock(top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository.class))
                .withBean("gatewayReleaseService", top.egon.cola.component.yuheng.admin.release.service.GatewayReleaseService.class,
                        () -> mock(top.egon.cola.component.yuheng.admin.release.service.GatewayReleaseService.class))
                .withBean("mpGatewayReleasePublicationRepository",
                        top.egon.cola.component.yuheng.admin.release.repository.GatewayReleasePublicationRepository.class,
                        () -> mock(top.egon.cola.component.yuheng.admin.release.repository.GatewayReleasePublicationRepository.class))
                .withBean("gatewayProjectionClock", java.time.Clock.class,
                        () -> new GatewayAdminConfiguration().gatewayProjectionClock())
                .withBean("unrelatedClock", java.time.Clock.class, java.time.Clock::systemDefaultZone)
                .withBean("yuheng.admin-top.egon.cola.component.yuheng.admin.config.GatewayAdminProperties",
                        top.egon.cola.component.yuheng.admin.config.GatewayAdminProperties.class,
                        top.egon.cola.component.yuheng.admin.config.GatewayAdminProperties::new)
                .withBean(org.springframework.validation.beanvalidation.MethodValidationPostProcessor.class,
                        org.springframework.validation.beanvalidation.MethodValidationPostProcessor::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var service = context.getBean(
                            top.egon.cola.component.yuheng.admin.runtime.service.GatewayProjectionService.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runtimeConsistency(" "))
                            .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
                });
    }

    @Test
    void createsDdcManagementClientThroughTheDirectRpcFactory() {
        GatewayAdminConfiguration configuration =
                new GatewayAdminConfiguration();
        DdcRpcClientFactory factory = mock(DdcRpcClientFactory.class);
        DdcManagementClient client = mock(DdcManagementClient.class);
        DdcRpcClientHandle<DdcManagementClient> expected =
                new DdcRpcClientHandle<>(client, () -> {
                });
        when(factory.managementClient()).thenReturn(expected);

        DdcRpcClientHandle<DdcManagementClient> handle = configuration
                .gatewayDdcManagementClientHandle(factory);

        assertThat(handle).isSameAs(expected);
        assertThat(configuration.ddcManagementClient(handle)).isSameAs(client);
        verify(factory).managementClient();
    }

    @Test
    void consumesTheGatewayEngineDefaultCallEventTopic() {
        Method consumerFactory = Arrays.stream(
                        GatewayAdminConfiguration.class.getDeclaredMethods()
                )
                .filter(method -> method.getName().equals(
                        "gatewayKafkaCallEventConsumer"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(Arrays.stream(consumerFactory.getParameters())
                .map(parameter -> parameter.getAnnotation(Value.class))
                .filter(java.util.Objects::nonNull)
                .map(Value::value))
                .contains(
                        "${yuheng.admin.observability.kafka.topic:"
                                + "egon.yuheng.call.v1}"
                );
    }

    @Test
    void enablesTheTypedOpenApiConfigurationContract() {
        EnableConfigurationProperties annotation =
                GatewayAdminConfiguration.class.getAnnotation(
                        EnableConfigurationProperties.class
                );

        assertThat(annotation).isNotNull();
        assertThat(annotation.value())
                .contains(GatewayAdminOpenApiProperties.class);
    }

    @Test
    void providesOpenApiMapperWithJavaTimeSupport() throws Exception {
        assertThat(GatewayAdminConfiguration.class.getDeclaredMethod("gatewayOpenApiObjectMapper")
                .getAnnotation(org.springframework.context.annotation.Bean.class).name())
                .containsExactly("gatewayOpenApiObjectMapper", "jacksonObjectMapper");
        ObjectMapper mapper = new GatewayAdminConfiguration()
                .gatewayOpenApiObjectMapper();

        assertThat(mapper.getRegisteredModuleIds()).isNotEmpty();
        var value = mapper.readTree(mapper.writeValueAsString(
                Instant.parse("2026-08-27T00:00:00Z")));
        assertThat(value.isTextual()).isTrue();
        assertThat(value.asText()).isEqualTo("2026-08-27T00:00:00Z");
    }
}
