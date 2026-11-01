package top.egon.cola.component.yuheng.mcp.engine.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 中文说明：MCP 进程独立的身份、监听和出站运行参数，不重新映射旧 MCP 能力配置。
 * English summary: Immutable process bootstrap; legacy engine.mcp capability/storage settings remain separate.
 * 用法 / Usage: Bind and validate before constructing listeners, providers or durable state.
 */
@Validated
@ConfigurationProperties(prefix = "egon.cola.component.yuheng.mcp-engine", ignoreUnknownFields = false)
public record McpGatewayEngineProperties(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String yuhengGroupCode,
        @NotBlank @Size(max = 128) String env,
        @NotBlank @Size(max = 128) String namespace,
        @NotBlank @Size(max = 128) String nodeId,
        @NotBlank @Size(max = 128) String instanceId,
        @NotBlank String dataDirectory,
        @NotNull @Valid @DefaultValue ListenerProperties listener,
        @Min(0) @Max(65535) @DefaultValue("18085") int managementPort,
        @NotNull @Valid @DefaultValue OutboundProperties outbound,
        @NotNull @Valid @DefaultValue ActiveHealthProperties activeHealth,
        @Size(max = 256) List<@NotNull @Valid ServiceScopeBindingVO> serviceScopeBindings
) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public McpGatewayEngineProperties {
        yuhengGroupCode = yuhengGroupCode == null ? null : yuhengGroupCode.trim();
        env = env == null ? null : env.trim();
        namespace = namespace == null ? null : namespace.trim();
        nodeId = nodeId == null ? null : nodeId.trim();
        instanceId = instanceId == null ? null : instanceId.trim();
        if (dataDirectory != null && !dataDirectory.isBlank()) {
            Path directory = Path.of(dataDirectory.trim());
            for (Path segment : directory) {
                if ("..".equals(segment.toString())) {
                    throw new IllegalArgumentException("MCP data directory must not traverse parents");
                }
            }
            directory = directory.normalize();
            if (directory.toString().isBlank() || directory.equals(directory.getRoot())) {
                throw new IllegalArgumentException("MCP data directory must name a dedicated directory");
            }
            dataDirectory = directory.toString();
        }
        if (listener != null && managementPort != 0 && listener.port() == managementPort) {
            throw new IllegalArgumentException("MCP data and management ports must differ");
        }
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
     * 中文说明：保留只配置原身份、监听和出站参数的调用方。
     * English summary: Preserves callers that do not configure SERVICE scope translation.
     */
    public McpGatewayEngineProperties(String yuhengGroupCode, String env, String namespace,
            String nodeId, String instanceId, String dataDirectory, ListenerProperties listener,
            int managementPort, OutboundProperties outbound, ActiveHealthProperties activeHealth) {
        this(yuhengGroupCode, env, namespace, nodeId, instanceId, dataDirectory, listener,
                managementPort, outbound, activeHealth, List.of());
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

    /**
     * 中文说明：只有一个 MCP 数据监听器；管理 HTTP 使用 Spring Boot 独立端口。
     * English summary: One MCP data listener, separate from the Boot management endpoint.
     */
    public record ListenerProperties(
            @DefaultValue("true") boolean enabled,
            @NotBlank @DefaultValue("0.0.0.0") String host,
            @Min(0) @Max(65535) @DefaultValue("18084") int port,
            @Positive @Max(Integer.MAX_VALUE) @DefaultValue("2097152") long maximumRequestBytes,
            @NotNull @DurationMin(millis = 1) @DefaultValue("10s") Duration drainTimeout,
            @NotNull @Valid @DefaultValue TlsProperties tls
    ) {
    }

    /**
     * 中文说明：复用已有 HTTP 连接池、RPC Channel 和 Operation 调用的边界。
     * English summary: Independent outbound pool, RPC shutdown and operation request limits.
     */
    public record OutboundProperties(
            @Positive @DefaultValue("512") int maxConnections,
            @Positive @DefaultValue("1024") int pendingAcquireMaxCount,
            @NotNull @DurationMin(millis = 1) @DefaultValue("30s") Duration idleTimeout,
            @NotNull @DurationMin(millis = 1) @DefaultValue("5s") Duration requestTimeout,
            @Positive @DefaultValue("1073741824") long maximumRequestBytes,
            @Positive @DefaultValue("4194304") long maximumResponseBytes,
            @NotNull @DurationMin(millis = 1) @DefaultValue("5s") Duration channelDrainTimeout,
            @NotNull @Valid @DefaultValue TlsProperties rpcTls,
            @Valid TlsProperties httpTls
    ) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public OutboundProperties { }

        /**
         * 中文说明：兼容仅配置原 HTTP 连接池与 RPC 传输的调用方。
         * English summary: Preserves callers that configure only the existing HTTP pool and RPC transport.
         */
        public OutboundProperties(int maxConnections, int pendingAcquireMaxCount, Duration idleTimeout,
                Duration requestTimeout, long maximumRequestBytes, long maximumResponseBytes,
                Duration channelDrainTimeout, TlsProperties rpcTls) {
            this(maxConnections, pendingAcquireMaxCount, idleTimeout, requestTimeout, maximumRequestBytes,
                    maximumResponseBytes, channelDrainTimeout, rpcTls, null);
        }
    }

    /**
     * 中文说明：TLS 配置不能默认为明文，开发明文必须显式开启。
     * English summary: Requires an explicit transport mode; actual file validation uses shared transport security.
     */
    public record TlsProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("false") boolean developmentPlaintext,
            String certificateChainPath,
            String privateKeyPath,
            String trustCertificateCollectionPath,
            @DefaultValue("false") boolean clientCertificateRequired
    ) {
        @AssertTrue(message = "TLS or explicit development plaintext is required")
        public boolean isTransportModeExplicit() {
            return enabled || developmentPlaintext;
        }

        @AssertTrue(message = "enabled TLS requires certificate/key and optional client trust material")
        public boolean isKeyMaterialConfigured() {
            return !enabled || certificateChainPath != null && !certificateChainPath.isBlank()
                    && privateKeyPath != null && !privateKeyPath.isBlank()
                    && (!clientCertificateRequired || trustCertificateCollectionPath != null
                    && !trustCertificateCollectionPath.isBlank());
        }
    }

    /**
     * 中文说明：保持既有 Provider 主动探测默认值，但状态与调度属于 MCP 进程。
     * English summary: Existing active-probe policy knobs with process-local scheduling and health state.
     */
    public record ActiveHealthProperties(
            @DefaultValue("false") boolean enabled,
            @NotNull @DurationMin(millis = 1) @DefaultValue("10s") Duration interval,
            @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.2") double jitterRatio,
            @NotNull @DurationMin(millis = 1) @DefaultValue("2s") Duration timeout,
            @Positive @DefaultValue("16") int maximumConcurrency,
            @Positive @DefaultValue("2") int failureThreshold,
            @Positive @DefaultValue("2") int successThreshold,
            @NotBlank @DefaultValue("GET") String httpMethod,
            @NotBlank @DefaultValue("/actuator/health") String httpPath,
            @NotEmpty @DefaultValue("200") List<@Min(100) @Max(599) Integer> httpSuccessStatuses,
            @DefaultValue("") String rpcServiceName,
            @DefaultValue("true") boolean rpcConnectFallback
    ) {
        public ActiveHealthProperties {
            httpSuccessStatuses = httpSuccessStatuses == null ? List.of() : List.copyOf(httpSuccessStatuses);
        }
    }
}
