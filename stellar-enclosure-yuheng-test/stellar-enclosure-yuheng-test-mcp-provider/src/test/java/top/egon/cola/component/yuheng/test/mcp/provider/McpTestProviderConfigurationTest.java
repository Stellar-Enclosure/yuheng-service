package top.egon.cola.component.yuheng.test.mcp.provider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class McpTestProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void keepsBearerAuthenticationIndependentFromTheRetiredDdcSwitch() {
        contextRunner.withPropertyValues(
                        "TIANSHU_ENABLED=false",
                        "TIANQUAN_SHOUBING_OAUTH_ISSUER=https://issuer.example.test",
                        "TIANQUAN_SHOUBING_JWK_SET_URI=https://issuer.example.test/jwks",
                        "MCP_TEST_PROVIDER_PORT=18152"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("server.port")).isEqualTo("18152");
                    assertThat(environment.getProperty("egon.cola.platform.tianquan.shoubing.enabled"))
                            .isEqualTo("true");
                    assertThat(environment.getProperty("egon.cola.platform.tianquan.shoubing.issuer"))
                            .isEqualTo("https://issuer.example.test");
                    assertThat(environment.getProperty("egon.cola.component.tianshu.registry.enabled")).isNull();
                    assertThat(environment.getProperty("egon.cola.component.tianshu.rpc.target")).isNull();
                    assertThat(environment.getProperty("egon.cola.component.yuheng.openapi.publish-to-tianshu"))
                            .isNull();
                    assertThat(environment.getProperty("spring.config.import[0]")).doesNotContain("tianshu:");
                    assertThat(environment.getProperty("spring.config.import[1]")).isNull();
                });
    }
}
