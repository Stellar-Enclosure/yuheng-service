package top.egon.cola.component.yuheng.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.oauth2.client.autoconfigure.reactive.ReactiveOAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.reactive.ReactiveOAuth2ClientWebSecurityAutoConfiguration;

@SpringBootApplication(exclude = {ReactiveOAuth2ClientAutoConfiguration.class, ReactiveOAuth2ClientWebSecurityAutoConfiguration.class})
public class GatewayEdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayEdgeApplication.class, args);
    }
}
