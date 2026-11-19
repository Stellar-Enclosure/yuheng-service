package top.egon.cola.component.yuheng.mcp.engine.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounds the MCP runtime independently from the publication and servlet settings. */
@Validated
@ConfigurationProperties(prefix = "egon.cola.component.yuheng.mcp-engine.runtime", ignoreUnknownFields = false)
public record McpGatewayEngineProperties(
        @NotBlank @Size(max = 128) String nodeId,
        @NotNull @Valid @DefaultValue ListenerProperties listener,
        @NotNull @Valid @DefaultValue OutboundProperties outbound,
        @Size(max = 256) List<@NotNull @Valid ServiceScopeBindingVO> serviceScopeBindings
) {

    public McpGatewayEngineProperties {
        nodeId = nodeId == null ? null : nodeId.trim();
        if (serviceScopeBindings == null) {
            serviceScopeBindings = List.of();
        } else {
            if (serviceScopeBindings.size() > 256) {
                throw new IllegalArgumentException("MCP service scope bindings must not exceed 256 entries");
            }
            Set<List<String>> keys = new HashSet<>();
            for (ServiceScopeBindingVO binding : serviceScopeBindings) {
                if (binding == null || !keys.add(List.of(binding.resourceUri().toASCIIString(), binding.permission()))) {
                    throw new IllegalArgumentException("MCP service scope bindings require unique non-null resource/permission keys");
                }
            }
            serviceScopeBindings = List.copyOf(serviceScopeBindings);
        }
    }

    /**
     * 中文说明：HOST 对精确资源权限的有限业务 scope 翻译，不授予调用方身份或 scope。
     * English summary: Exact immutable HOST translation metadata; verified caller authority remains separate.
     */
    public record ServiceScopeBindingVO(
            @NotNull URI resourceUri,
            @NotBlank @Size(max = 512) @Pattern(regexp = "[\\x21-\\x7E]{1,512}") String permission,
            @NotEmpty @Size(max = 64) Set<@NotBlank @Size(max = 128)
                    @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String> scopes
    ) {
        private static final Set<String> IDENTITY_SCOPES = Set.of(
                "openid", "profile", "email", "phone", "address", "offline_access");

        public ServiceScopeBindingVO {
            if (resourceUri == null || !resourceUri.isAbsolute() || resourceUri.isOpaque()
                    || !"https".equalsIgnoreCase(resourceUri.getScheme()) || resourceUri.getHost() == null
                    || resourceUri.getRawUserInfo() != null || resourceUri.getRawQuery() != null
                    || resourceUri.getRawFragment() != null || !resourceUri.equals(resourceUri.normalize())
                    || !resourceUri.toString().equals(resourceUri.toASCIIString())
                    || resourceUri.toASCIIString().length() > 2048) {
                throw new IllegalArgumentException("MCP service scope resource must be an exact normalized ASCII HTTPS URI");
            }
            if (permission == null || permission.isEmpty() || permission.length() > 512
                    || permission.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
                throw new IllegalArgumentException("MCP service scope permission must be exact graphic ASCII of at most 512 characters");
            }
            if (scopes == null || scopes.isEmpty() || scopes.size() > 64) {
                throw new IllegalArgumentException("MCP service scope binding requires 1 to 64 business scopes");
            }
            for (String scope : scopes) {
                if (scope == null || !scope.matches("[A-Za-z0-9._:-]{1,128}") || IDENTITY_SCOPES.contains(scope)) {
                    throw new IllegalArgumentException("MCP service scope binding requires exact business scope tokens");
                }
            }
            scopes = Set.copyOf(scopes);
        }
    }

    public record ListenerProperties(
            @Positive @Max(Integer.MAX_VALUE) @DefaultValue("2097152") long maximumRequestBytes
    ) {
    }

    public record OutboundProperties(
            @NotNull @DurationMin(millis = 1) @DefaultValue("5s") Duration requestTimeout,
            @Positive @Max(Integer.MAX_VALUE) @DefaultValue("1073741824") long maximumRequestBytes,
            @Positive @Max(Integer.MAX_VALUE) @DefaultValue("4194304") long maximumResponseBytes
    ) {
    }
}
