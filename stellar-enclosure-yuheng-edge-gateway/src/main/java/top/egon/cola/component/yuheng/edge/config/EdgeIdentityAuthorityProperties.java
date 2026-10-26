package top.egon.cola.component.yuheng.edge.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;

@Data
@Validated
@ConfigurationProperties(prefix = "egon.yuheng.edge.identity-authority")
public class EdgeIdentityAuthorityProperties {
    @NotBlank private String appId = "stellar-enclosure-yuheng-edge-gateway";
    @NotNull private Duration timeout = Duration.ofSeconds(3);
    @Valid @NotEmpty private Map<String, Binding> bindings = new LinkedHashMap<>();

    @PostConstruct
    public void validate() {
        if (appId == null || !appId.matches("[A-Za-z0-9._-]{1,128}") || timeout == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(5)) > 0
                || bindings == null || bindings.isEmpty() || bindings.size() > 192) {
            throw new IllegalStateException("EDGE identity authority settings are invalid");
        }
        bindings.forEach((id, binding) -> {
            if (binding == null || binding.registrationId == null
                    || !binding.registrationId.matches("[A-Za-z0-9._-]{1,128}")
                    || binding.issuer == null || binding.sourceResource == null || binding.resourceUri == null || binding.credentialVersion < 0) {
                throw new IllegalStateException("EDGE identity authority binding is invalid");
            }
            EdgeOAuthTargetBO.requireCanonicalHttps(binding.issuer.toASCIIString());
            EdgeOAuthTargetBO.requireCanonicalHttps(binding.sourceResource.toASCIIString());
            EdgeOAuthTargetBO.requireCanonicalHttps(binding.resourceUri.toASCIIString());
        });
    }

    @Data
    public static class Binding {
        @NotBlank private String registrationId;
        @NotNull private URI issuer;
        @NotNull private URI sourceResource;
        @NotNull private URI resourceUri;
        private long credentialVersion;
    }
}
