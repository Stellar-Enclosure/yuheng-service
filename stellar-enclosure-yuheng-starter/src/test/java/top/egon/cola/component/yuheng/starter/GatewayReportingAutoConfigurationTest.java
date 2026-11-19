package top.egon.cola.component.yuheng.starter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.yuheng.contract.reporting.GatewayDefinitionIdentity;
import top.egon.cola.component.yuheng.contract.reporting.GatewayDefinitionSourceTypeEnum;
import top.egon.cola.component.yuheng.contract.reporting.GatewayInterfaceDefinitionReport;
import top.egon.cola.component.yuheng.starter.reporting.GatewayReportHttpClient;
import top.egon.cola.component.yuheng.starter.reporting.GatewayReportingCoordinator;
import top.egon.cola.component.rpc.config.EgonRpcAutoConfig;
import top.egon.cola.component.rpc.contract.catalog.RpcContractCatalog;
import top.egon.cola.component.yuheng.starter.discovery.GatewayDefinitionContributor;
import top.egon.cola.component.yuheng.starter.discovery.rpc.RpcGatewayDefinitionContributor;
import top.egon.cola.component.yuheng.starter.reporting.GatewayDefinitionReportFactory;
import top.egon.cola.component.yuheng.starter.reporting.GatewayReportingState;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayReportingAutoConfigurationTest {

    @BeforeAll
    static void initializeTestMachineIdentity() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            GatewayReportingAutoConfiguration.class
                    ))
                    .withBean(
                            GatewayReportingCoordinator.class,
                            () -> mock(GatewayReportingCoordinator.class)
                    );

    @Test
    void remainsDisabledByDefault() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(GatewayDefinitionIdentity.class)
                .doesNotHaveBean(GatewayReportHttpClient.class)
                .doesNotHaveBean("gatewayDefinitionIdentityHttpRegistrationContributor")
                .doesNotHaveBean("gatewayDefinitionIdentityRpcMetadataContributor"));
    }

    @Test
    void runsAfterRpcContractCatalogAutoConfiguration() {
        AutoConfigureAfter ordering = GatewayReportingAutoConfiguration.class
                .getAnnotation(AutoConfigureAfter.class);

        assertThat(ordering).isNotNull();
        assertThat(ordering.value()).contains(EgonRpcAutoConfig.class);
    }

    @Test
    void publishesStableIdentityWhenEnabled() {
        contributorRunner()
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean(
                                    "gatewayDefinitionIdentityHttpRegistrationContributor"
                            )
                            .doesNotHaveBean(
                                    "gatewayDefinitionIdentityRpcMetadataContributor"
                            );
                    assertThat(context)
                            .hasSingleBean(GatewayDefinitionIdentity.class);
                    assertThat(context)
                            .hasSingleBean(GatewayDefinitionReportFactory.class)
                            .hasSingleBean(GatewayDefinitionReportFactory.BuiltReport.class)
                            .hasSingleBean(GatewayReportHttpClient.class)
                            .hasSingleBean(GatewayReportingState.class)
                            .hasSingleBean(GatewayReportingCoordinator.class);
                    GatewayDefinitionIdentity identity = context.getBean(
                            GatewayDefinitionIdentity.class
                    );
                    GatewayDefinitionReportFactory.BuiltReport built = context.getBean(
                            GatewayDefinitionReportFactory.BuiltReport.class
                    );
                    assertThat(identity.buildId()).isEqualTo("build-1");
                    assertThat(identity.artifactVersion()).isEqualTo("1.0.0");
                    assertThat(identity.definitionSetId()).matches("[0-9a-f]{64}");
                    assertThat(built.report().definitionSetId())
                            .isEqualTo(identity.definitionSetId());
                    assertThat(built.payload()).isNotEmpty();
                    assertThat(context).doesNotHaveBean("rpcGatewayDefinitionContributor");
                });
    }

    @Test
    void retainsNativeRpcContractCatalogDiscovery() {
        RpcContractCatalog catalog = mock(RpcContractCatalog.class);
        when(catalog.contracts()).thenReturn(List.of());

        contributorRunner()
                .withBean(RpcContractCatalog.class, () -> catalog)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasBean("rpcGatewayDefinitionContributor")
                            .hasSingleBean(GatewayDefinitionIdentity.class)
                            .doesNotHaveBean("gatewayDefinitionIdentityRpcMetadataContributor");
                    assertThat(context.getBean("rpcGatewayDefinitionContributor"))
                            .isInstanceOf(RpcGatewayDefinitionContributor.class);
                    verify(catalog).contracts();
                });
    }

    @Test
    void retainsIndependentDefinitionContributors() {
        GatewayDefinitionContributor contributor = mock(GatewayDefinitionContributor.class);
        GatewayDefinitionContributor.DiscoveredInterfaceGroup httpDefinition =
                new GatewayDefinitionContributor.DiscoveredInterfaceGroup(
                        "trade", "Trade", null,
                        "inventory", "Inventory", null,
                        new GatewayInterfaceDefinitionReport.InterfaceGroup(
                                "inventory-http", "Inventory HTTP", null,
                                GatewayDefinitionSourceTypeEnum.OPENAPI31,
                                "example.InventoryController", "HTTP",
                                Map.of(), List.of()
                        )
                );
        when(contributor.discover()).thenReturn(List.of(httpDefinition));

        contributorRunner()
                .withBean("httpDefinitionContributor", GatewayDefinitionContributor.class,
                        () -> contributor)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(GatewayDefinitionReportFactory.BuiltReport.class)
                            .hasSingleBean(GatewayReportHttpClient.class)
                            .doesNotHaveBean("gatewayDefinitionIdentityHttpRegistrationContributor");
                    GatewayDefinitionReportFactory.BuiltReport built = context.getBean(
                            GatewayDefinitionReportFactory.BuiltReport.class
                    );
                    assertThat(built.report().businessDomains()).singleElement()
                            .satisfies(business -> assertThat(business.entityDomains())
                                    .singleElement().satisfies(entity ->
                                            assertThat(entity.interfaceGroups())
                                                    .containsExactly(httpDefinition.interfaceGroup())));
                    verify(contributor).discover();
                });
    }

    private ApplicationContextRunner contributorRunner() {
        return runner.withPropertyValues(
                        "egon.cola.component.yuheng.reporting.enabled=true",
                        "egon.cola.component.yuheng.reporting.admin-base-url="
                                + "http://127.0.0.1:18080",
                        "egon.cola.component.yuheng.reporting.biz-code=test-biz",
                        "egon.cola.component.yuheng.reporting."
                                + "application-code=inventory",
                        "egon.cola.component.yuheng.reporting."
                                + "application-name=Inventory",
                        "egon.cola.component.yuheng.reporting.env=test",
                        "egon.cola.component.yuheng.reporting.namespace=default",
                        "egon.cola.component.yuheng.reporting."
                                + "artifact-version=1.0.0",
                        "egon.cola.component.yuheng.reporting.build-id=build-1",
                        "egon.cola.component.yuheng.reporting.access-key=ak",
                        "egon.cola.component.yuheng.reporting.secret-key=sk"
                );
    }

}
