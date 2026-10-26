package top.egon.cola.component.yuheng.edge.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import top.egon.cola.component.yuheng.edge.config.domain.bo.EdgeRegistrationResourceBindingBO;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
@org.springframework.validation.annotation.Validated
@Component("edgeOAuthProperties")
@ConfigurationProperties(prefix = "egon.yuheng.edge")
public class EdgeOAuthProperties {
    @Valid @NotNull @Builder.Default private Upstreams upstreams = new Upstreams();
    @NotNull private URI publicOrigin;
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") private String defaultRegistrationId;
    @NotNull @Size(min = 1, max = 64) @Builder.Default private List<String> allowedReturnPaths = new ArrayList<>(List.of("/"));
    @Valid @NotNull @Size(min = 1, max = 64) @Builder.Default
    private Map<@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String, @NotNull @Valid EdgeRegistrationResourceBindingBO> resources = new LinkedHashMap<>();

    @PostConstruct
    public void validate() {
        if (upstreams == null) throw new IllegalStateException("EDGE upstream configuration is required");
        validateOrigin(upstreams.getAuthorizationServer());
        validateOrigin(upstreams.getBizGateway());
        validateOrigin(upstreams.getMcpGateway());
        validateOrigin(publicOrigin);
        if (publicOrigin.getPath() != null && !publicOrigin.getPath().isEmpty()) throw new IllegalStateException("EDGE public origin cannot contain a path");
        if (defaultRegistrationId == null || !defaultRegistrationId.matches("[A-Za-z0-9_-]{1,128}")
                || resources == null || resources.isEmpty() || resources.size() > 64 || !resources.containsKey(defaultRegistrationId)
                || resources.entrySet().stream().anyMatch(entry -> entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_-]{1,128}")
                    || entry.getValue() == null || !entry.getValue().isBindingValid() || entry.getValue().getResourceServerId() == null
                    || entry.getValue().getResourceServerId().isBlank())) {
            throw new IllegalStateException("EDGE fixed application catalog is invalid");
        }
        if (allowedReturnPaths == null || allowedReturnPaths.isEmpty() || allowedReturnPaths.size() > 64
                || allowedReturnPaths.stream().anyMatch(path -> path == null || !path.matches("/[A-Za-z0-9/._~-]{0,2047}")
                    || path.startsWith("//") || java.util.Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals("..")))) {
            throw new IllegalStateException("EDGE return paths must be fixed local paths");
        }
    }

    private static void validateOrigin(URI origin) {
        if (origin == null || !"https".equals(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !origin.equals(origin.normalize())) throw new IllegalStateException("EDGE upstream origin is invalid");
    }

    @Override
    public String toString() { return "EdgeOAuthProperties[publicOrigin=" + publicOrigin + ", upstreams=<configured>, applications=<configured>]"; }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    public static class Upstreams {
        @NotNull private URI authorizationServer;
        @NotNull private URI bizGateway;
        @NotNull private URI mcpGateway;
    }
}
