package top.egon.cola.component.yuheng.mcp.engine.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInvocation;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcProperties;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcSiResourceGrpcServiceGrpc;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;
import top.egon.cola.component.yuheng.core.mcp.remote.RemoteAuthProvider;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService.ApprovalIntentResolver;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService.ApprovalIntentVO;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityDigests;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvoker;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPublicationMountProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpBoundOperationInvoker;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingContextVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.SelectionVO;
import top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TenantIssuerAuthenticationManagerComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.VerifiedPrincipalConverter;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientTenantResolver;
import top.egon.cola.platform.tianquan.shoubing.starter.state.IdentityAuthorityClientResolver;

import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.MpMcpRuntimeTaskStore;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.support.McpGatewayPersistenceContext;
import top.egon.cola.component.yuheng.mcp.engine.mcp.converter.McpTaskPersistenceConverter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpTaskPersistenceRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpAuditPublisher;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRuntimeHealthIndicator;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.McpRuntimeProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpTaskOperationExecutor;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpTaskWorker;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.MicrometerMcpTelemetry;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.RedisMcpSessionStore;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.remote.ReactorNettyRemoteMcpClient;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent;
import top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder;
import top.egon.cola.component.yuheng.mcp.remote.service.McpRemoteClientPool;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskService;
import top.egon.cola.component.yuheng.mcp.common.telemetry.McpTelemetry;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceContextComponent;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceProperties;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.mcp.engine.rule.service.McpGatewayRuleCompilerStrategy;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.lifecycle.McpGatewayEngineRuntime;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.FileSystemMcpAppArtifactReader;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security.MpMcpApprovalAdapter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security.Rbac3McpAuthorizationAdapter;
import top.egon.cola.component.yuheng.mcp.app.service.AppUiResourceDriver;
import top.egon.cola.component.yuheng.mcp.app.service.McpAppRuntime;
import top.egon.cola.component.yuheng.mcp.app.domain.McpAppSecurityValidator;
import top.egon.cola.component.yuheng.mcp.completion.service.DictionaryCompletionProvider;
import top.egon.cola.component.yuheng.mcp.completion.service.McpCompletionHandler;
import top.egon.cola.component.yuheng.mcp.completion.service.OperationCompletionProvider;
import top.egon.cola.component.yuheng.mcp.prompt.service.McpPromptDriver;
import top.egon.cola.component.yuheng.mcp.prompt.service.McpPromptsGetHandler;
import top.egon.cola.component.yuheng.mcp.prompt.service.McpPromptsListHandler;
import top.egon.cola.component.yuheng.mcp.prompt.service.OperationPromptDriver;
import top.egon.cola.component.yuheng.mcp.prompt.service.StaticPromptDriver;
import top.egon.cola.component.yuheng.mcp.prompt.domain.StrictPromptTemplate;
import top.egon.cola.component.yuheng.mcp.remote.service.McpDialectTranslator;
import top.egon.cola.component.yuheng.mcp.remote.service.McpNamespaceRouter;
import top.egon.cola.component.yuheng.mcp.remote.service.RemoteMcpCompletionProvider;
import top.egon.cola.component.yuheng.mcp.remote.service.RemoteMcpPromptDriver;
import top.egon.cola.component.yuheng.mcp.remote.service.RemoteMcpResourceDriver;
import top.egon.cola.component.yuheng.mcp.remote.service.RemoteMcpToolDriver;
import top.egon.cola.component.yuheng.mcp.resource.adapter.DatabaseSchemaResourceDriver;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourceCatalog;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourceDriver;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourceTemplatesListHandler;
import top.egon.cola.component.yuheng.mcp.resource.domain.McpResourceUriValidator;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourcesListHandler;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourcesReadHandler;
import top.egon.cola.component.yuheng.mcp.resource.adapter.ObjectStorageResourceDriver;
import top.egon.cola.component.yuheng.mcp.resource.adapter.OperationResourceDriver;
import top.egon.cola.component.yuheng.mcp.resource.adapter.StaticBlobResourceDriver;
import top.egon.cola.component.yuheng.mcp.resource.adapter.StaticTextResourceDriver;
import top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityGate;
import top.egon.cola.component.yuheng.mcp.server.service.McpMethodDispatcher;
import top.egon.cola.component.yuheng.mcp.server.service.McpMethodHandler;
import top.egon.cola.component.yuheng.mcp.server.service.handler.McpDiscoverHandler;
import top.egon.cola.component.yuheng.mcp.server.service.handler.McpInitializeHandler;
import top.egon.cola.component.yuheng.mcp.server.service.handler.McpInitializedHandler;
import top.egon.cola.component.yuheng.mcp.server.service.handler.McpPingHandler;
import top.egon.cola.component.yuheng.mcp.subscription.service.McpResourceSubscribeHandler;
import top.egon.cola.component.yuheng.mcp.subscription.service.McpSubscriptionService;
import top.egon.cola.component.yuheng.mcp.subscription.service.McpSubscriptionsListenHandler;
import top.egon.cola.component.yuheng.mcp.task.service.McpTasksCancelHandler;
import top.egon.cola.component.yuheng.mcp.task.service.McpTasksGetHandler;
import top.egon.cola.component.yuheng.mcp.task.service.McpTasksUpdateHandler;
import top.egon.cola.component.yuheng.mcp.tool.service.McpResultBinder;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolCatalog;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolsCallHandler;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolsListHandler;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Assembles the dedicated MCP execution process. */
@Slf4j
@Configuration(value = "mcpGatewayEngineConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties({McpGatewayEngineProperties.class, McpRuntimeProperties.class,
        McpPublicationMountProperties.class})
public class McpGatewayEngineConfiguration {

    @Bean("gatewayRemoteMcpAuthentication")
    @ConditionalOnMissingBean(RemoteAuthProvider.class)
    public RemoteAuthProvider gatewayRemoteMcpAuthentication() {
        return request -> {
            if (request.provider().authProfileReference() == null) {
                return reactor.core.publisher.Mono.just(
                        new RemoteAuthProvider.OutboundAuthentication(
                                Map.of(),
                                request.provider().tlsProfileReference()
                        )
                );
            }
            return reactor.core.publisher.Mono.error(
                    new IllegalStateException(
                            "remote MCP authentication profile resolver "
                                    + "is unavailable"
                    )
            );
        };
    }

    /**
     * 中文说明：执行 网关远程MCP客户端池 操作；该方法是 {@code McpGatewayEngineConfiguration} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the gateway remote mcp client pool operation; this method is the invocation entry point on {@code McpGatewayEngineConfiguration} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpGatewayEngineConfiguration.gatewayRemoteMcpClientPool(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param authentication 参数 authentication；parameter authentication。
     * @param objectMapper 参数 object映射器；parameter object mapper。
     * @param gatewayClock 参数 网关Clock；parameter gateway clock。
     * @param properties 参数 properties；parameter properties。
     * @return 返回 网关远程MCP客户端池 的处理结果；returns the result of the operation.
     */
    @Bean(name = "gatewayRemoteMcpClientPool", destroyMethod = "close")
    public McpRemoteClientPool gatewayRemoteMcpClientPool(
            RemoteAuthProvider authentication,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Qualifier("gatewayClock") Clock gatewayClock,
            McpRuntimeProperties properties) {
        properties.validate();
        return new McpRemoteClientPool(
                provider -> new ReactorNettyRemoteMcpClient(objectMapper),
                authentication,
                gatewayClock,
                properties.getRemote().getCallTimeout(),
                properties.getRemote().getMaximumConcurrentCalls(),
                properties.getRemote().getFailureThreshold(),
                properties.getRemote().getCircuitOpenDuration()
        );
    }

    /**
     * 中文说明：执行 网关MCP遥测 操作；该方法是 {@code McpGatewayEngineConfiguration} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the gateway mcp telemetry operation; this method is the invocation entry point on {@code McpGatewayEngineConfiguration} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpGatewayEngineConfiguration.gatewayMcpTelemetry(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param meters 参数 meters；parameter meters。
     * @param observations 参数 observations；parameter observations。
     * @param objectMapper 参数 object映射器；parameter object mapper。
     * @param gatewayClock 参数 网关Clock；parameter gateway clock。
     * @param properties 参数 properties；parameter properties。
     * @return 返回 网关MCP遥测 的处理结果；returns the result of the operation.
     */
    @Bean("gatewayMcpTelemetry")
    @ConditionalOnMissingBean(McpTelemetry.class)
    public McpTelemetry gatewayMcpTelemetry(
            MeterRegistry meters,
            ObservationRegistry observations,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Qualifier("gatewayClock") Clock gatewayClock,
            McpRuntimeProperties properties) {
        properties.validate();
        ArrayList<McpTelemetry> observers = new ArrayList<>();
        observers.add(new MicrometerMcpTelemetry(meters, observations));
        if (properties.getAudit().isEnabled()) {
            observers.add(new McpAuditPublisher(
                    objectMapper,
                    gatewayClock,
                    json -> log.info("MCP_RUNTIME_AUDIT {}", json)
            ));
        }
        return McpTelemetry.composite(observers);
    }

    @Bean(name = "gatewayMcpRedissonClient", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "gatewayMcpRedissonClient")
    @ConditionalOnProperty(
            prefix = "egon.cola.component.yuheng.engine.mcp.redis",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public RedissonClient gatewayMcpRedissonClient(
            @Value(
                    "${egon.cola.component.yuheng.engine.mcp.redis.address:"
                            + "redis://127.0.0.1:6379}"
            ) String address,
            @Value(
                    "${egon.cola.component.yuheng.engine.mcp.redis."
                            + "database:0}"
            ) int database,
            @Value(
                    "${egon.cola.component.yuheng.engine.mcp.redis."
                            + "password:}"
            ) String password) {
        Config config = new Config();
        var server = config.useSingleServer()
                .setAddress(address)
                .setDatabase(database);
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }

    /** Reuses the host connection under Common cache's explicit integration name. */
    @Bean(name = "redissonClient", destroyMethod = "")
    @ConditionalOnMissingBean(name = "redissonClient")
    @ConditionalOnBean(name = "gatewayMcpRedissonClient")
    RedissonClient applicationCacheRedissonClient(
            @Qualifier("gatewayMcpRedissonClient") RedissonClient client) {
        return client;
    }

    /**
     * 中文说明：执行 网关MCP会话存储 操作；该方法是 {@code McpGatewayEngineConfiguration} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the gateway mcp session store operation; this method is the invocation entry point on {@code McpGatewayEngineConfiguration} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpGatewayEngineConfiguration.gatewayMcpSessionStore(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param redisson 参数 redisson；parameter redisson。
     * @param objectMapper 参数 object映射器；parameter object mapper。
     * @param gatewayClock 参数 网关Clock；parameter gateway clock。
     * @param keyPrefix 参数 键Prefix；parameter key prefix。
     * @param maximumStreamLength 参数 maximumStreamLength；parameter maximum stream length。
     * @return 返回 网关MCP会话存储 的处理结果；returns the result of the operation.
     */
    @Bean("gatewayMcpSessionStore")
    @ConditionalOnBean(name = "gatewayMcpRedissonClient")
    public RedisMcpSessionStore gatewayMcpSessionStore(
            @Qualifier("gatewayMcpRedissonClient") RedissonClient redisson,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Qualifier("gatewayClock") Clock gatewayClock,
            @Value(
                    "${egon.cola.component.yuheng.engine.mcp.redis."
                            + "key-prefix:yuheng:mcp:}"
            ) String keyPrefix,
            @Value(
                    "${egon.cola.component.yuheng.engine.mcp.redis."
                            + "stream-max-length:256}"
            ) int maximumStreamLength) {
        return new RedisMcpSessionStore(
                redisson,
                objectMapper,
                keyPrefix,
                maximumStreamLength,
                gatewayClock
        );
    }

    /**
     * 中文说明：执行 网关MCP运行时任务存储 操作；该方法是 {@code McpGatewayEngineConfiguration} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the gateway mcp runtime task store operation; this method is the invocation entry point on {@code McpGatewayEngineConfiguration} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpGatewayEngineConfiguration.gatewayMcpRuntimeTaskStore(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param taskPersistenceRepository 参数 受守卫任务持久化边界；parameter the guarded task persistence boundary。
     * @param taskPersistenceConverter 参数 任务列映射器；parameter the task column mapper。
     * @param persistenceContext 参数 受守卫身份上下文；parameter the guarded identity context。
     * @param batchLimit 参数 单次清理扫描最大行数；parameter the maximum rows one sweep touches。
     * @return 返回 网关MCP运行时任务存储 的处理结果；returns the result of the operation.
     */
    @Bean(name = {"gatewayMcpRuntimeTaskStore", "mcpRuntimeTaskStore"})
    public MpMcpRuntimeTaskStore gatewayMcpRuntimeTaskStore(
            @Qualifier("mcpTaskPersistenceRepository")
            McpTaskPersistenceRepository taskPersistenceRepository,
            @Qualifier("mcpTaskPersistenceConverter")
            McpTaskPersistenceConverter taskPersistenceConverter,
            @Qualifier("mcpGatewayPersistenceContext")
            McpGatewayPersistenceContext persistenceContext,
            @Value(
                    "${egon.cola.component.yuheng.engine.mcp.tasks."
                            + "batch-limit:64}"
            ) int batchLimit) {
        return new MpMcpRuntimeTaskStore(
                taskPersistenceRepository,
                taskPersistenceConverter,
                persistenceContext,
                batchLimit
        );
    }

    /**
     * 中文说明：执行 网关MCP持久化身份上下文 操作；该方法是 {@code McpGatewayEngineConfiguration} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the gateway mcp persistence identity context operation; this method is the invocation entry point on {@code McpGatewayEngineConfiguration} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpGatewayEngineConfiguration.mcpGatewayPersistenceContext(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param persistenceContextComponent 参数 受管 schema 消费者的唯一身份上下文；parameter the single identity context of the managed-schema consumer.
     * @param persistenceProperties 参数 同一部署身份的配置；parameter the configuration for that same deployment identity.
     * @return 返回 网关MCP持久化身份上下文 的处理结果；returns the result of the operation.
     */
    @Bean("mcpGatewayPersistenceContext")
    public McpGatewayPersistenceContext mcpGatewayPersistenceContext(
            @Qualifier("mcpPersistenceContextComponent")
            McpPersistenceContextComponent persistenceContextComponent,
            @Qualifier(McpPersistenceProperties.BEAN_NAME)
            McpPersistenceProperties persistenceProperties) {
        return new McpGatewayPersistenceContext(
                persistenceContextComponent,
                persistenceProperties
        );
    }

    /**
     * 中文说明：执行 网关MCP任务服务 操作；该方法是 {@code McpGatewayEngineConfiguration} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the gateway mcp task service operation; this method is the invocation entry point on {@code McpGatewayEngineConfiguration} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpGatewayEngineConfiguration.gatewayMcpTaskService(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param store 参数 存储；parameter store。
     * @param objectMapper 参数 object映射器；parameter object mapper。
     * @param gatewayClock 参数 网关Clock；parameter gateway clock。
     * @param properties 参数 properties；parameter properties。
     * @return 返回 网关MCP任务服务 的处理结果；returns the result of the operation.
     */
    @Bean("gatewayMcpTaskService")
    @ConditionalOnBean(MpMcpRuntimeTaskStore.class)
    public McpTaskService gatewayMcpTaskService(
            @Qualifier("gatewayMcpRuntimeTaskStore")
            MpMcpRuntimeTaskStore store,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Qualifier("gatewayClock") Clock gatewayClock,
            McpRuntimeProperties properties) {
        return new McpTaskService(
                store,
                objectMapper,
                gatewayClock,
                properties.getTasks().getLeaseDuration()
        );
    }

    @Bean("gatewayClock")
    @ConditionalOnMissingBean(name = "gatewayClock")
    Clock gatewayClock() {
        return Clock.systemUTC();
    }

    @Bean("mcpPublishedBindingLoader")
    McpPublishedBindingLoader publishedBindings(McpPublicationMountProperties mounts,
            @Qualifier("ddcSiCatalogRpcClient") DdcSiCatalogClient si,
            @Qualifier("gatewayClock") Clock clock) {
        return new McpPublishedBindingLoader(mounts.bindings().file(), mounts.bindings().sha256(),
                si, mounts.callerAppCode(), clock);
    }

    @Bean("mcpGatewayRuleCompilerStrategy")
    McpGatewayRuleCompilerStrategy catalogCompiler(ObjectMapper mapper,
            @Qualifier("mcpPublishedBindingLoader") McpPublishedBindingLoader bindings,
            @Qualifier(McpPersistenceProperties.BEAN_NAME) McpPersistenceProperties persistence,
            @Qualifier("gatewayClock") Clock clock) {
        return new McpGatewayRuleCompilerStrategy(mapper, bindings, persistence.getTenantId(), clock);
    }

    @Bean("mcpGatewayEngineRuntime")
    McpGatewayEngineRuntime engineRuntime(
            @Qualifier("mcpPublishedBindingLoader") McpPublishedBindingLoader bindings,
            @Qualifier("mcpGatewayRuleCompilerStrategy") McpGatewayRuleCompilerStrategy compiler,
            McpPublicationMountProperties mounts,
            @Qualifier("gatewayClock") Clock clock) {
        return new McpGatewayEngineRuntime(bindings, compiler, mounts, clock);
    }

    @Bean("mcpApprovalIntentResolver")
    ApprovalIntentResolver approvalIntentResolver(
            @Qualifier("mcpGatewayEngineRuntime") McpGatewayEngineRuntime runtime,
            @Qualifier("mcpPublishedBindingLoader") McpPublishedBindingLoader bindings,
            ObjectMapper mapper,
            @Qualifier("gatewayClock") Clock clock) {
        return new ApprovalIntentResolver() {
            @Override
            public ApprovalIntentVO issue(McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO actor,
                    String serverCode, String toolName) {
                actor.requireCurrent(clock);
                return approvalIntent(runtime, bindings, mapper, runtime.active(), serverCode, toolName);
            }

            @Override
            public ApprovalIntentVO consume(McpRequestContext actual, McpApprovalPort.ConsumptionRequest request) {
                if (!actual.server().serverCode().equals(request.serverCode())) {
                    throw new IllegalArgumentException("MCP_APPROVAL_SERVER_MISMATCH");
                }
                return approvalIntent(runtime, bindings, mapper, requestCatalog(runtime, actual),
                        request.serverCode(), request.toolName());
            }
        };
    }

    private static ApprovalIntentVO approvalIntent(McpGatewayEngineRuntime runtime, McpPublishedBindingLoader bindings,
            ObjectMapper mapper, McpGatewayCompiledRulesDTO catalog, String serverCode, String toolName) {
        McpRuntimeTool tool = approvalTool(catalog, serverCode, toolName);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("catalog", catalog.identity().key());
        source.put("toolId", tool.toolId());
        switch (tool.sourceType()) {
            case "LOCAL_OPERATION" -> {
                var selection = new SelectionVO(catalog.ownerTenantId(), serverCode, tool.toolId());
                var pin = catalog.bindingPins().get(selection);
                if (pin == null) {
                    throw new IllegalStateException("MCP_APPROVAL_BINDING_UNAVAILABLE");
                }
                var binding = bindings.requirePinned(selection, pin);
                source.put("pin", pin);
                source.put("definitionDigest", binding.operation().definitionDigest());
            }
            case "REMOTE_MCP" -> {
                var router = new McpNamespaceRouter();
                var remote = router.binding(catalog.mcpRules(), tool.remoteMountId(), "TOOL", tool.name());
                var active = runtime.active();
                var currentTool = approvalTool(active, serverCode, toolName);
                var current = router.binding(active.mcpRules(), currentTool.remoteMountId(), "TOOL", currentTool.name());
                if (!currentTool.equals(tool) || !current.equals(remote)) {
                    throw new IllegalArgumentException("MCP_APPROVAL_REMOTE_BINDING_CHANGED");
                }
                source.put("mount", tool.remoteMountId());
                source.put("provider", remote.mount().providerCode());
                source.put("fingerprint", remote.provider().capabilityFingerprint());
                source.put("remoteName", remote.remoteName());
            }
            default -> throw new IllegalArgumentException("MCP_APPROVAL_TOOL_SOURCE_UNSUPPORTED");
        }
        String digest = McpSecurityDigests.arguments(mapper, source);
        return new ApprovalIntentVO(catalog.ownerTenantId(), serverCode, toolName, tool.toolId(), tool.sourceType(), digest);
    }

    private static McpRuntimeTool approvalTool(McpGatewayCompiledRulesDTO catalog, String serverCode, String toolName) {
        if (catalog == null) {
            throw new IllegalStateException("MCP_APPROVAL_CATALOG_UNAVAILABLE");
        }
        var server = catalog.mcpRules().serversByCode().get(serverCode);
        if (server == null || !server.enabled()) {
            throw new IllegalArgumentException("MCP_APPROVAL_TOOL_NOT_AVAILABLE");
        }
        return catalog.content().tools().stream()
                .filter(tool -> tool.enabled() && serverCode.equals(tool.serverCode()) && toolName.equals(tool.name()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("MCP_APPROVAL_TOOL_NOT_AVAILABLE"));
    }

    @Bean("idpRpcClientTenantResolver")
    IdpRpcClientTenantResolver rpcTenantResolver(@Qualifier(DdcRpcProperties.BEAN_NAME) DdcRpcProperties si,
            @Qualifier(McpPersistenceProperties.BEAN_NAME) McpPersistenceProperties persistence,
            @Qualifier("idpStarterProperties") IdpStarterProperties identity) {
        String tenant = Long.toString(persistence.getIdentityTenantId());
        String configuredIssuer = identity.getIssuer();
        if (configuredIssuer == null || identity.getTrustedIssuers().values().stream()
                .noneMatch(trusted -> configuredIssuer.equals(trusted.getIssuer())
                        && tenant.equals(trusted.getTenantId()) && "TENANT".equals(trusted.getRealmType())
                        && "ACTIVE".equals(trusted.getStatus()))) {
            throw new IllegalStateException("MCP_SI_ISSUER_NOT_BOUND_TO_IDENTITY_TENANT");
        }
        URI issuer = URI.create(configuredIssuer);
        if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null || issuer.getRawUserInfo() != null
                || issuer.getRawQuery() != null || issuer.getRawFragment() != null
                || !issuer.equals(issuer.normalize()) || !configuredIssuer.equals(issuer.toASCIIString())) {
            throw new IllegalStateException("MCP_SI_ISSUER_INVALID");
        }
        return new IdpRpcClientTenantResolver() {
            @Override
            public Optional<String> resolve(RpcClientInvocation invocation,
                    org.springframework.security.core.Authentication authentication) {
                return isSiResolve(invocation, si) ? Optional.of(tenant)
                        : McpBoundOperationInvoker.resolveRpcTenant(invocation);
            }

            @Override
            public Optional<String> expectedIssuer(RpcClientInvocation invocation,
                    org.springframework.security.core.Authentication authentication) {
                return isSiResolve(invocation, si) ? Optional.of(configuredIssuer)
                        : McpBoundOperationInvoker.resolveRpcIssuer(invocation);
            }
        };
    }

    private static boolean isSiResolve(RpcClientInvocation invocation, DdcRpcProperties si) {
        var target = invocation.target();
        return target != null && si.isEnabled() && si.getBindingId().equals(target.bindingId())
                && si.getBindingVersion() == target.version()
                && DdcSiResourceGrpcServiceGrpc.getResolveSiResourceMethod().getFullMethodName()
                        .equals(invocation.fullMethodName());
    }

    @Bean("gatewayOperationInvoker")
    McpBoundOperationInvoker gatewayOperationInvoker(
            @Qualifier("mcpPublishedBindingLoader") McpPublishedBindingLoader bindings,
            ObjectMapper mapper,
            @Qualifier("idpServiceOAuth2Client") IdpServiceOAuth2Client oauth,
            @Qualifier("rpcGenericInvoker") RpcGenericInvoker rpc,
            @Qualifier("rpcReferenceDefinitionResolver") RpcReferenceDefinitionResolver references,
            @Qualifier("idpStarterProperties") IdpStarterProperties idp,
            @Qualifier("idpRpcClientCredentialInterceptorFactory") IdpRpcClientCredentialInterceptorFactory credentials,
            @Qualifier("gatewayClock") Clock clock,
            McpRuntimeProperties runtimeProperties,
            McpGatewayEngineProperties engine) {
        var outbound = engine.outbound();
        return new McpBoundOperationInvoker(bindings, mapper, oauth, rpc, references, idp, credentials, clock,
                outbound.requestTimeout(), Math.toIntExact(Math.min(outbound.maximumRequestBytes(),
                        runtimeProperties.getMaximumRequestBytes())), Math.toIntExact(outbound.maximumResponseBytes()));
    }

    @Bean("gatewayMcpIdentityAuthenticator")
    McpGatewayIdentityAuthenticator identityAuthenticator(
            @Qualifier("tenantIssuerAuthenticationManagerComponent") TenantIssuerAuthenticationManagerComponent issuers,
            @Qualifier("identityAuthorityClientResolver") IdentityAuthorityClientResolver authorities,
            @Qualifier("verifiedPrincipalConverter") VerifiedPrincipalConverter converter,
            @Qualifier("idpObservedVerificationComponent") IdpObservedVerificationComponent observations,
            @Qualifier("defaultValidator") Validator validator,
            @Qualifier("mcpGatewayEngineRuntime") McpGatewayEngineRuntime runtime,
            @Qualifier(McpPersistenceProperties.BEAN_NAME) McpPersistenceProperties persistence,
            @Qualifier("gatewayClock") Clock clock) {
        return new McpGatewayIdentityAuthenticator(issuers, authorities, converter, observations, validator,
                (server, request) -> effectiveScopes(runtime, request.getOrDefault(McpGatewayCompiledRulesDTO.class, null), server),
                clock, Long.toString(persistence.getIdentityTenantId()));
    }

    private static Set<String> effectiveScopes(McpGatewayEngineRuntime runtime,
            McpGatewayCompiledRulesDTO selected, McpRuntimeServer server) {
        var active = runtime.active();
        if (selected == null || active == null || runtime.pinned(selected.identity().key()) != selected
                || !server.equals(selected.mcpRules().serversByCode().get(server.serverCode()))) {
            return Set.of();
        }
        var currentServer = active.mcpRules().serversByCode().get(server.serverCode());
        if (currentServer == null || !currentServer.enabled() || !currentServer.resourceUri().equals(server.resourceUri())) {
            return Set.of();
        }
        var scopes = new HashSet<>(selected.serverScopes().get(server.serverCode()));
        scopes.addAll(active.serverScopes().get(server.serverCode()));
        return Set.copyOf(scopes);
    }

    @Bean("gatewayMcpAuthorization")
    McpAuthorizationPort gatewayMcpAuthorization(SingleFlightSnapshotLoader snapshots,
            @Qualifier("idpObservedVerificationComponent") IdpObservedVerificationComponent observations,
            @Qualifier("idpSecurityMetricsRecorder") IdpSecurityMetricsRecorder metrics,
            @Qualifier("defaultValidator") Validator validator,
            McpGatewayEngineProperties properties,
            @Qualifier("gatewayClock") Clock clock) {
        return Rbac3McpAuthorizationAdapter.createConfigured(snapshots, observations, metrics, validator,
                properties.serviceScopeBindings(), clock);
    }

    @Bean("gatewayMcpTaskWorker")
    @ConditionalOnBean(McpTaskService.class)
    McpTaskWorker gatewayMcpTaskWorker(@Qualifier("gatewayMcpTaskService") McpTaskService tasks,
            @Qualifier("gatewayOperationInvoker") McpBoundOperationInvoker invoker,
            ObjectMapper mapper,
            McpGatewayEngineProperties properties,
            McpRuntimeProperties runtimeProperties) {
        return new McpTaskWorker(tasks, new McpTaskOperationExecutor(invoker, mapper), properties.nodeId(),
                runtimeProperties.getTasks().getLeaseDuration(), runtimeProperties.getTasks().getPollInterval());
    }

    @Bean("mcpGatewayListenerProperties")
    McpGatewayEngineProperties.ListenerProperties listenerProperties(McpGatewayEngineProperties properties) {
        return properties.listener();
    }

    @Bean("gatewayMcpRuntimeHealthIndicator")
    McpRuntimeHealthIndicator runtimeHealth(@Qualifier("mcpGatewayEngineRuntime") McpGatewayEngineRuntime runtime,
            ObjectProvider<RedisMcpSessionStore> sessionStores, ObjectProvider<McpTaskService> tasks,
            McpRuntimeProperties properties, @Qualifier("gatewayRemoteMcpClientPool") McpRemoteClientPool remoteClients) {
        return new McpRuntimeHealthIndicator(runtime, sessionStores.getIfAvailable() != null,
                tasks.getIfAvailable() != null, Path.of(properties.getArtifactRoot()), remoteClients);
    }

    @Bean("gatewayMcpHttpHandler")
    McpEngineHttpHandler gatewayMcpHttpHandler(
            @Qualifier("mcpGatewayEngineRuntime") McpGatewayEngineRuntime runtime,
            @Qualifier("mcpPublishedBindingLoader") McpPublishedBindingLoader bindings,
            @Qualifier("gatewayOperationInvoker") McpBoundOperationInvoker operationInvoker,
            @Qualifier("gatewayMcpIdentityAuthenticator") McpGatewayIdentityAuthenticator authenticator,
            @Qualifier("gatewayMcpAuthorization") McpAuthorizationPort authorization,
            @Qualifier("gatewayMcpSessionStore") RedisMcpSessionStore sessionStore,
            ObjectProvider<McpTaskService> taskServices,
            ObjectProvider<DataSource> dataSources,
            @Qualifier("mcpApprovalAdapter") ObjectProvider<MpMcpApprovalAdapter> approvalPorts,
            @Qualifier("gatewayRemoteMcpClientPool") McpRemoteClientPool remoteClients,
            @Qualifier("gatewayMcpTelemetry") McpTelemetry mcpTelemetry,
            ObjectMapper objectMapper,
            @Qualifier("gatewayClock") Clock gatewayClock,
            McpGatewayEngineProperties properties,
            McpRuntimeProperties mcpProperties) {
        mcpProperties.validate();
        Duration sessionTtl = mcpProperties.getSessionTtl();
        Duration streamWait = mcpProperties.getStreamWait();
        Supplier<CompiledMcpRules> mcpRules = () -> {
            var current = runtime.active();
            return current == null ? null : current.mcpRules();
        };
        GatewayOperationInvoker primitiveInvoker = request -> Mono.deferContextual(context -> {
            McpRequestContext caller = context.getOrDefault(McpRequestContext.class, null);
            var snapshot = requestCatalog(runtime, caller);
            var tools = snapshot.content().tools().stream()
                    .filter(tool -> tool.serverCode().equals(caller.server().serverCode()) && tool.enabled()
                            && "LOCAL_OPERATION".equals(tool.sourceType()) && request.operationId().equals(tool.operationId()))
                    .toList();
            if (tools.size() != 1) {
                return Mono.error(new IllegalStateException("MCP_OPERATION_BINDING_NOT_UNIQUE"));
            }
            var binding = requireToolBinding(bindings, runtime, tools.getFirst(), caller);
            return operationInvoker.invokeBound(binding.selection(), binding.pin(), request);
        });
        var toolCatalog = new McpToolCatalog(mcpRules);
        DataSource dataSource = dataSources.getIfAvailable();
        MpMcpApprovalAdapter approvalAdapter = approvalPorts.getIfAvailable();
        McpApprovalPort approvals = approvalAdapter == null
                ? request -> reactor.core.publisher.Mono.just(
                McpApprovalPort.Result.UNAVAILABLE
        )
                : approvalAdapter;
        McpSecurityGate securityGate = new McpSecurityGate(
                authorization,
                approvals,
                objectMapper
        );
        McpResourceUriValidator resourceUriValidator =
                new McpResourceUriValidator();
        McpResourceCatalog resourceCatalog = new McpResourceCatalog(
                mcpRules,
                resourceUriValidator
        );
        McpDialectTranslator dialectTranslator = new McpDialectTranslator();
        McpNamespaceRouter namespaceRouter = new McpNamespaceRouter();
        RemoteMcpToolDriver remoteToolDriver = new RemoteMcpToolDriver(
                mcpRules,
                remoteClients,
                namespaceRouter,
                dialectTranslator
        );
        ArrayList<McpResourceDriver> resourceDrivers = new ArrayList<>(
                List.of(
                        new StaticTextResourceDriver(),
                        new StaticBlobResourceDriver(),
                        new OperationResourceDriver(primitiveInvoker),
                        new ObjectStorageResourceDriver(resourceUriValidator)
                )
        );
        McpAppRuntime appRuntime = new McpAppRuntime(
                mcpRules,
                new FileSystemMcpAppArtifactReader(Path.of(
                        mcpProperties.getArtifactRoot()
                )),
                new McpAppSecurityValidator()
        );
        resourceDrivers.add(new AppUiResourceDriver(appRuntime));
        resourceDrivers.add(new RemoteMcpResourceDriver(
                mcpRules,
                remoteClients,
                namespaceRouter,
                dialectTranslator
        ));
        if (dataSource != null) {
            resourceDrivers.add(new DatabaseSchemaResourceDriver(
                    (schema, objectName) -> readDatabaseSchema(
                            dataSource,
                            schema,
                            objectName,
                            objectMapper
                    ),
                    resourceUriValidator
            ));
        }
        McpSubscriptionService subscriptions = new McpSubscriptionService(
                sessionStore,
                objectMapper,
                gatewayClock,
                sessionTtl,
                streamWait
        );
        List<McpPromptDriver> promptDrivers = List.of(
                new StaticPromptDriver(new StrictPromptTemplate()),
                new OperationPromptDriver(primitiveInvoker),
                new RemoteMcpPromptDriver(
                        mcpRules,
                        remoteClients,
                        namespaceRouter,
                        dialectTranslator
                )
        );
        McpTaskService taskService = taskServices.getIfAvailable();
        ArrayList<McpMethodHandler> methodHandlers = new ArrayList<>(List.of(
                new McpInitializeHandler(),
                new McpInitializedHandler(),
                new McpPingHandler(),
                new McpDiscoverHandler(),
                new McpToolsListHandler(toolCatalog, objectMapper),
                new McpToolsCallHandler(
                        toolCatalog,
                        new McpResultBinder(objectMapper),
                        operationInvoker,
                        securityGate,
                        objectMapper,
                        taskService,
                        mcpRules,
                        remoteToolDriver,
                        (tool, request) -> requireToolBinding(bindings, runtime, tool, request)
                ),
                new McpResourcesListHandler(
                        resourceCatalog,
                        securityGate
                ),
                new McpResourceTemplatesListHandler(
                        resourceCatalog,
                        securityGate
                ),
                new McpResourcesReadHandler(
                        resourceCatalog,
                        List.copyOf(resourceDrivers),
                        securityGate
                ),
                new McpResourceSubscribeHandler(
                        resourceCatalog,
                        subscriptions,
                        securityGate
                ),
                new McpSubscriptionsListenHandler(
                        resourceCatalog,
                        subscriptions,
                        securityGate
                ),
                new McpPromptsListHandler(
                        mcpRules,
                        securityGate
                ),
                new McpPromptsGetHandler(
                        mcpRules,
                        promptDrivers,
                        securityGate
                ),
                new McpCompletionHandler(
                        mcpRules,
                        resourceCatalog,
                        List.of(
                                new DictionaryCompletionProvider(Map.of()),
                                new OperationCompletionProvider(
                                        primitiveInvoker,
                                        objectMapper
                                ),
                                new RemoteMcpCompletionProvider(
                                        mcpRules,
                                        remoteClients,
                                        namespaceRouter,
                                        dialectTranslator
                                )
                        ),
                        securityGate
                )
        ));
        if (taskService != null) {
            methodHandlers.add(new McpTasksGetHandler(
                    taskService,
                    securityGate
            ));
            methodHandlers.add(new McpTasksUpdateHandler(
                    taskService,
                    securityGate
            ));
            methodHandlers.add(new McpTasksCancelHandler(
                    taskService,
                    securityGate
            ));
        }
        McpMethodDispatcher dispatcher = new McpMethodDispatcher(
                List.copyOf(methodHandlers),
                mcpTelemetry
        );
        return McpEngineHttpHandler.createConfigured(
                runtime,
                dispatcher,
                sessionStore,
                sessionStore,
                authenticator,
                objectMapper,
                gatewayClock,
                sessionTtl,
                streamWait,
                Math.toIntExact(Math.min(
                        Math.min(
                                properties.listener().maximumRequestBytes(),
                                mcpProperties.getMaximumRequestBytes()
                        ),
                        Integer.MAX_VALUE
                ))
        );
    }

    private static McpGatewayCompiledRulesDTO requestCatalog(McpGatewayEngineRuntime runtime,
            McpRequestContext request) {
        if (request == null
                || !(request.attributes().get(McpGatewayCompiledRulesDTO.class.getName())
                        instanceof McpGatewayCompiledRulesDTO catalog)
                || runtime.pinned(catalog.identity().key()) != catalog) {
            throw new IllegalStateException("MCP_REQUEST_CATALOG_UNAVAILABLE");
        }
        return catalog;
    }

    private static BindingContextVO requireToolBinding(McpPublishedBindingLoader bindings,
            McpGatewayEngineRuntime runtime, McpRuntimeTool tool, McpRequestContext request) {
        var catalog = requestCatalog(runtime, request);
        if (!tool.serverCode().equals(request.server().serverCode())
                || !catalog.content().tools().contains(tool)) {
            throw new IllegalStateException("MCP_REQUEST_TOOL_UNAVAILABLE");
        }
        var selection = new SelectionVO(catalog.ownerTenantId(), tool.serverCode(), tool.toolId());
        var pin = catalog.bindingPins().get(selection);
        if (pin == null) {
            throw new IllegalStateException("MCP_REQUEST_TOOL_BINDING_UNAVAILABLE");
        }
        return bindings.requirePinned(selection, pin);
    }

    private String readDatabaseSchema(
            DataSource dataSource,
            String schema,
            String objectName,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper)
            throws Exception {
        ArrayList<Map<String, Object>> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getColumns(
                     connection.getCatalog(),
                     schema,
                     objectName,
                     null
             )) {
            while (result.next()) {
                LinkedHashMap<String, Object> column = new LinkedHashMap<>();
                column.put("name", result.getString("COLUMN_NAME"));
                column.put("type", result.getString("TYPE_NAME"));
                column.put("size", result.getInt("COLUMN_SIZE"));
                column.put("nullable", result.getInt("NULLABLE")
                        != java.sql.DatabaseMetaData.columnNoNulls);
                columns.add(Map.copyOf(column));
            }
        }
        if (columns.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(Map.of(
                "schema", schema,
                "object", objectName,
                "columns", List.copyOf(columns)
        ));
    }

}
