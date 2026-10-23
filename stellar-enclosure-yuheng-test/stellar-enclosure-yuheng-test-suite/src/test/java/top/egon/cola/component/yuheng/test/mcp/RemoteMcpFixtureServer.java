package top.egon.cola.component.yuheng.test.mcp;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import top.egon.cola.component.yuheng.test.mcp.remote.StableRemoteMcpApplication;

import java.net.URI;

final class RemoteMcpFixtureServer implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private final int port;

    private RemoteMcpFixtureServer(
            ConfigurableApplicationContext context,
            int port) {
        this.context = context;
        this.port = port;
    }

    static RemoteMcpFixtureServer start() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                StableRemoteMcpApplication.class
        ).logStartupInfo(false).run(
                "--server.port=0",
                "--spring.application.name=stellar-enclosure-yuheng-test-mcp-remote",
                "--spring.config.name=yuheng-mcp-fixture",
                "--spring.main.banner-mode=off",
                "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "org.redisson.spring.starter.RedissonAutoConfigurationV4,"
                        + "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.web.servlet.UserDetailsServiceAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
                "--egon.cola.component.id.machine-id=0",
                "--egon.cola.component.tianshu.enabled=false",
                "--management.endpoints.enabled-by-default=false"
        );
        int port = ((WebServerApplicationContext) context)
                .getWebServer()
                .getPort();
        return new RemoteMcpFixtureServer(context, port);
    }

    URI stableEndpoint() {
        return URI.create("http://127.0.0.1:" + port + "/remote/stable");
    }

    URI rcEndpoint() {
        return URI.create("http://127.0.0.1:" + port + "/remote/rc");
    }

    @Override
    public void close() {
        context.close();
    }
}
