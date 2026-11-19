package top.egon.cola.component.yuheng.openapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.boot.test.context.FilteredClassLoader;
import top.egon.cola.component.yuheng.openapi.customizer.EgonOpenApiCustomizer;
import top.egon.cola.component.yuheng.openapi.customizer.EgonOperationCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayOpenApiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertyBindingFixture.class, GatewayOpenApiAutoConfiguration.class)
                    .withPropertyValues(
                            "egon.cola.component.yuheng.openapi.enabled=true",
                            "egon.cola.component.yuheng.openapi.biz-code=trade",
                            "egon.cola.component.yuheng.openapi.application-code=order-service",
                            "egon.cola.component.yuheng.openapi.resource-uri=https://order-service.example.test",
                            "egon.cola.component.yuheng.openapi.artifact-version=1.0.0",
                            "egon.cola.component.yuheng.openapi.build-id=build-1",
                            "egon.cola.component.yuheng.openapi.published-groups[0]=orders"
                    );

    @Test
    void exposesLiveNamedGovernanceWithoutPhysicalRegistrationWhenEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(GatewayOpenApiProperties.class);
            assertThat(context).hasSingleBean(EgonOperationCustomizer.class);
            assertThat(context).hasSingleBean(EgonOpenApiCustomizer.class);
            assertThat(context).doesNotHaveBean("gatewayOpenApiRegistrationContributor");
            assertThat(context).hasBean("egonOperationCustomizer");
            assertThat(context).hasBean("egonOpenApiCustomizer");
            var document = new OpenAPI().openapi("3.1.0");
            context.getBean("egonOpenApiCustomizer", EgonOpenApiCustomizer.class).customise(document, "orders");
            assertThat(document.getOpenapi()).isEqualTo("3.1.0");
            assertThat(document.getExtensions()).containsKey("x-egon-service");
            assertThat(context.getBean(GatewayOpenApiProperties.class).groupManifest().groups()).containsExactly("orders");
        });
    }
    @Test
    void documentationBeansStartWithoutTheRetiredRegistrationClasses() {
        contextRunner.withClassLoader(new FilteredClassLoader("top.egon.cola.component.tianshu.http.registration"))
                .withPropertyValues("egon.cola.component.yuheng.openapi.publish-to-ddc=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("egonOperationCustomizer").hasBean("egonOpenApiCustomizer")
                            .doesNotHaveBean("gatewayOpenApiRegistrationContributor");
                });
    }

    @Test
    void disabledDocumentationDoesNotContributeAnyGovernanceOrRegistrationBeans() {
        contextRunner.withPropertyValues("egon.cola.component.yuheng.openapi.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("egonOperationCustomizer")
                    .doesNotHaveBean("egonOpenApiCustomizer").doesNotHaveBean("gatewayOpenApiRegistrationContributor");
        });
    }

    @Test
    void explicitPhysicalRegistrationEnablementFailsAtConfigurationBinding() {
        contextRunner.withPropertyValues("egon.cola.component.yuheng.openapi.publish-to-ddc=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("DDC physical OpenAPI registration is retired");
        });
    }

    @Test
    void retiredTransportIsAbsentWhileSpringdocRemainsOnTheRuntimeClasspath() {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertThat(classpath).contains("springdoc-openapi-starter-common", "stellar-enclosure-yuheng-contract")
                .doesNotContain("stellar-enclosure-tianshu-http-registration-starter", "stellar-enclosure-component-rpc-tianshu-adapter");
        assertThat(org.springframework.util.ClassUtils.isPresent(
                "top.egon.cola.component.tianshu.http.registration.DdcHttpRegistrationContributor", getClass().getClassLoader())).isFalse();
    }

    @org.springframework.context.annotation.Configuration(value = "openApiPropertyBindingFixture", proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties
    static class PropertyBindingFixture {
    }

}
