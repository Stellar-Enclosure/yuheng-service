package top.egon.cola.component.yuheng.admin.mcp.application;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.rpc.ErrorInfo;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelKey;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelFactory;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvocation;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericTargetCache;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInvocation;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationExecutor;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategyFactory;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcSiResourceGrpcServiceGrpc;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceResponse;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.model.si.DdcSiDependencyManifestVO;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiLifecycleStatusEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiResourceDescriptor;
import top.egon.cola.component.tianshu.model.si.DdcSiSourceTypeEnum;
import top.egon.cola.component.yuheng.admin.mcp.config.McpNativeSiReaderConfiguration;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO.SiServerVersionVO;
import top.egon.cola.component.yuheng.admin.mcp.service.McpNativeSiReader;
import top.egon.cola.component.yuheng.admin.mcp.service.McpPublishedSiResolver;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceTokenRequest;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientTenantResolver;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpNativeSiReaderTest {
    private static final RpcProcessIdentity PROCESS = new RpcProcessIdentity("stellar-enclosure-yuheng-admin", "prod", "fixture", 1L, "fixture");
    private static final RpcServiceTargetVO TARGET = new RpcServiceTargetVO("mcp-si", 1, "platform-prod", "tianshu-si-rpc", 19080, Set.of(McpNativeSiReader.METHOD));
    private static final DdcSiProtoMapper MAPPER = Mappers.getMapper(DdcSiProtoMapper.class);

    @Test
    void securedNativeReaderImplementsTheExistingCatalogPort() throws Exception {
        Class<?> reader = Class.forName(
                "top.egon.cola.component.yuheng.admin.mcp.service.McpNativeSiReader");
        assertThat(DdcSiCatalogClient.class.isAssignableFrom(reader)).isTrue();
    }

    @Test
    void pinnedReadUsesFixedNativeCoordinatesAndCapabilityExistsOnlyDuringItsCallback() throws Exception {
        RpcGenericInvoker invoker = mock(RpcGenericInvoker.class);
        McpNativeSiReader reader = reader(invoker, 1000);
        AtomicReference<RpcClientInvocation> captured = new AtomicReference<>();
        when(invoker.invokeBlocking(any())).thenAnswer(call -> {
            RpcGenericInvocation request = call.getArgument(0);
            assertThat(request.bindingId()).isEqualTo("mcp-si");
            assertThat(request.fullMethodName()).isEqualTo(McpNativeSiReader.METHOD);
            assertThat(request.group()).isEqualTo("tianshu");
            assertThat(request.version()).isEqualTo("1.0.0");
            var context = RpcClientInvocation.generic(request, PROCESS, "logical-call", TARGET);
            captured.set(context);
            assertThat(reader.resolveIdentityTenant(context)).contains("700");
            var wire = ResolveSiResourceRequest.parseFrom(request.requestPayload());
            assertThat(wire.getTenantId()).isEqualTo(7);
            assertThat(wire.getCallerAppCode()).isEqualTo("yuheng-reader");
            assertThat(wire.getExecutionRequired()).isTrue();
            assertThat(wire.getExactVersionNo()).isEqualTo(3);
            assertThat(wire.getPinnedVersionNo()).isZero();
            assertThat(wire.getRequestId()).isEmpty();
            var foreign = new RpcServiceTargetVO("other", 1, "platform-prod", "tianshu-other-rpc", 19080, Set.of(McpNativeSiReader.METHOD));
            assertDenied(() -> reader.resolveIdentityTenant(RpcClientInvocation.generic(request, PROCESS, "other-call", foreign)));
            return response(snapshot());
        });

        assertThat(new McpPublishedSiResolver(reader).resolveClosure(7, reference())).hasSize(64);
        assertDenied(() -> reader.resolveIdentityTenant(captured.get()));
        verify(invoker).invokeBlocking(any());
    }

    @Test
    void callerAndOwnerCannotExpandTheDeploymentAuthorityOrUseUnpinnedOrLegacyReads() {
        RpcGenericInvoker invoker = mock(RpcGenericInvoker.class);
        var reader = reader(invoker, 1000);
        assertDenied(() -> resolve(reader, 8, "commerce", "tooling", "prod", "yuheng-reader", 3L));
        assertDenied(() -> resolve(reader, 7, "other", "tooling", "prod", "yuheng-reader", 3L));
        assertDenied(() -> resolve(reader, 7, "commerce", "other", "prod", "yuheng-reader", 3L));
        assertDenied(() -> resolve(reader, 7, "commerce", "tooling", "dev", "yuheng-reader", 3L));
        assertDenied(() -> resolve(reader, 7, "commerce", "tooling", "prod", "spoofed", 3L));
        assertThatThrownBy(() -> resolve(reader, 7, "commerce", "tooling", "prod", "yuheng-reader", null))
                .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
        assertThatThrownBy(() -> reader.resolveResource(7, "commerce", "tooling", "prod", "inventory", DdcSiKindEnum.MCP, "inventory-mcp", 3))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new McpPublishedSiResolver(reader).resolveClosure(7, "spoofed", reference()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(invoker);
    }

    @Test
    void namespaceOwnershipKeepsItsAbsenceInsteadOfInventingAnOwner() throws Exception {
        RpcGenericInvoker invoker = mock(RpcGenericInvoker.class);
        when(invoker.invokeBlocking(any())).thenAnswer(call -> {
            var request = ResolveSiResourceRequest.parseFrom(((RpcGenericInvocation) call.getArgument(0)).requestPayload());
            assertThat(request.hasOwnerAppCode()).isFalse();
            return response(snapshot().setOwnerAppCode(null));
        });
        assertThat(reader(invoker, 1000).resolveExecutionResource(7, "commerce", "tooling", "prod", null,
                "yuheng-reader", DdcSiKindEnum.MCP, "inventory-mcp", 3L)).isPresent();
    }

    @Test
    void malformedMissingAndWrongVersionMaterialNeverReturnsAClosure() throws Exception {
        for (byte[] response : List.of(new byte[]{(byte) 0xff}, new byte[2_200_001], ResolveSiResourceResponse.getDefaultInstance().toByteArray(),
                response(snapshot().setDependencyManifest(null)), response(snapshot().setResolvedVersionNo(4L)),
                response(snapshot().setSpecJson("{\"wrong\":true}")))) {
            RpcGenericInvoker invoker = mock(RpcGenericInvoker.class);
            when(invoker.invokeBlocking(any())).thenReturn(response);
            assertThatThrownBy(() -> resolve(reader(invoker, 1000), 7, "commerce", "tooling", "prod", "yuheng-reader", 3L))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAUTHENTICATED", "PERMISSION_DENIED", "UNAVAILABLE", "DEADLINE_EXCEEDED", "CANCELLED", "FAILED_PRECONDITION", "RESOURCE_EXHAUSTED"})
    void transportStatusIsDistinctSanitizedAndDoesNotLeaveACapability(Status.Code code) {
        RpcGenericInvoker invoker = mock(RpcGenericInvoker.class);
        when(invoker.invokeBlocking(any())).thenThrow(new EgonRpcException(EgonRpcErrorCode.RPC_INTERNAL,
                "raw-secret", Status.fromCode(code).withDescription("raw-secret").asRuntimeException()));
        var reader = reader(invoker, 1000);
        assertThatThrownBy(() -> resolve(reader, 7, "commerce", "tooling", "prod", "yuheng-reader", 3L))
                .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                    assertThat(failure.getStatus().getCode()).isEqualTo(code);
                    assertThat(failure.getMessage()).doesNotContain("raw-secret");
                });
        assertDenied(() -> reader.resolveIdentityTenant(RpcClientInvocation.generic(
                new RpcGenericInvocation("mcp-si", McpNativeSiReader.SERVICE, "tianshu", "1.0.0", McpNativeSiReader.METHOD, new byte[]{1}, 1000), PROCESS, "later", TARGET)));
        verify(invoker).invokeBlocking(any());
    }

    @Test
    void fixedHistoryDiagnosticUsesStructuredTianshuDetailsInsteadOfTheStatusMessage() {
        Any pinnedHistory = Any.pack(ErrorInfo.newBuilder().setDomain("tianshu.si")
                .setReason("SI_PINNED_HISTORY_UNAVAILABLE").putMetadata("retryable", "false").build());
        Any foreignDomain = Any.pack(ErrorInfo.newBuilder().setDomain("another.service")
                .setReason("SI_PINNED_HISTORY_UNAVAILABLE").build());
        Any malformed = Any.newBuilder().setTypeUrl(pinnedHistory.getTypeUrl())
                .setValue(ByteString.copyFrom(new byte[]{(byte) 0xff})).build();
        for (Any detail : List.of(pinnedHistory, foreignDomain, malformed)) {
            var error = StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                    .setCode(Status.Code.FAILED_PRECONDITION.value()).setMessage("SI RPC operation failed")
                    .addDetails(detail).build());
            RpcGenericInvoker invoker = mock(RpcGenericInvoker.class);
            when(invoker.invokeBlocking(any())).thenThrow(new EgonRpcException(EgonRpcErrorCode.RPC_INTERNAL, "failure", error));
            var receiver = reader(invoker, 1000);
            assertThatThrownBy(() -> resolve(receiver, 7, "commerce", "tooling", "prod", "yuheng-reader", 3L))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                        assertThat(failure.getStatus().getDescription()).isEqualTo(detail.equals(pinnedHistory)
                                ? "SI_PINNED_HISTORY_UNAVAILABLE" : "YUHENG_MCP_SI_CONFIGURATION_INVALID");
                    });
            verify(invoker).invokeBlocking(any());
        }
    }

    @Test
    void optionalResolverIsAbsentWithoutExplicitDeploymentOptIn() {
        new ApplicationContextRunner().withUserConfiguration(McpNativeSiReaderConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("mcpPublishedSiResolver").doesNotHaveBean("idpRpcClientTenantResolver");
        });
        new ApplicationContextRunner().withUserConfiguration(McpNativeSiReaderConfiguration.class)
                .withPropertyValues("yuheng.admin.mcp.si-reader.enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void actualAdminBaseAllowsStrictNativeRpcBindingWhenEnabled() throws Exception {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader().load("real-yuheng-base",
                new org.springframework.core.io.ClassPathResource("application.yml"));
        new ApplicationContextRunner().withUserConfiguration(RpcPropertiesFixture.class)
                .withInitializer(context -> source.forEach(context.getEnvironment().getPropertySources()::addLast))
                .withPropertyValues("egon.cola.component.rpc.enabled=true").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EgonRpcProperties.class);
                    assertThat(context.getBean(EgonRpcProperties.class).getTransport()).isEqualTo("h2c");
                });
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(EgonRpcProperties.class)
    static class RpcPropertiesFixture { }

    @Test
    void explicitSpringAssemblyValidatesTheWholeBindingWithoutCreatingAPrincipalOrABeanCycle() {
        new ApplicationContextRunner().withUserConfiguration(McpNativeSiReaderConfiguration.class, ConfigFixture.class)
                .withPropertyValues(deploymentProperties()).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(McpNativeSiReader.class)
                            .hasBean("mcpPublishedSiResolver").hasBean("idpRpcClientTenantResolver");
                    assertThat(context.getBean("mcpNativeSiReaderBinding", McpNativeSiReader.BindingVO.class).identityTenantId()).isEqualTo("700");
                    var resolver = context.getBean("idpRpcClientTenantResolver", IdpRpcClientTenantResolver.class);
                    assertDenied(() -> resolver.resolve(null, null));
                    assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
                });
    }

    @Test
    void explicitSpringAssemblyRejectsUnapprovedBindingVersionAndUnknownTransportFields() {
        for (String invalid : List.of("yuheng.admin.mcp.si-reader.binding-version=2",
                "yuheng.admin.mcp.si-reader.target=dns:///foreign-service")) {
            new ApplicationContextRunner().withUserConfiguration(McpNativeSiReaderConfiguration.class, ConfigFixture.class)
                    .withPropertyValues(deploymentProperties()).withPropertyValues(invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void realNativeTcpSendsOneBoundServiceCredentialAndNoExtraAttempt() throws Exception {
        try (NativeFixture fixture = new NativeFixture(false)) {
            var value = resolve(fixture.reader, 7, "commerce", "tooling", "prod", "yuheng-reader", 3L);
            assertThat(value).isPresent();
            assertThat(fixture.calls.get()).isEqualTo(1);
            assertThat(fixture.authorization.get()).containsExactly("Bearer fixture-service-token");
            verify(fixture.oauth).authorize(new IdpServiceTokenRequest("si-reader-700", "yuheng-reader-app",
                    URI.create("https://tianshu.example.test"), ServiceTokenContext.TENANT, "700", Set.of("fixture:si:read")));
        }
    }

    @Test
    void realNativeTcpUnavailableHasNoImplicitSecondAttempt() throws Exception {
        try (NativeFixture fixture = new NativeFixture(true)) {
            assertThatThrownBy(() -> resolve(fixture.reader, 7, "commerce", "tooling", "prod", "yuheng-reader", 3L))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void realNativeTcpHonorsCallerDeadlineAndCancelsTheServer() throws Exception {
        try (NativeFixture fixture = new NativeFixture(false, true)) {
            Context.CancellableContext context = Context.current().withDeadlineAfter(1000, TimeUnit.MILLISECONDS,
                    fixture.deadlineTimer);
            try {
                assertThatThrownBy(() -> context.call(() -> resolve(fixture.reader, 7, "commerce", "tooling", "prod", "yuheng-reader", 3L)))
                        .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> assertThat(failure.getStatus().getCode())
                                .isIn(Status.Code.DEADLINE_EXCEEDED, Status.Code.CANCELLED));
                assertThat(fixture.cancelled.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.calls.get()).isEqualTo(1);
            } finally { context.cancel(null); }
        }
    }

    private static McpNativeSiReader reader(RpcGenericInvoker invoker, long timeout) {
        return new McpNativeSiReader(invoker, MAPPER, binding(timeout), PROCESS);
    }

    private static McpNativeSiReader.BindingVO binding(long timeout) {
        return new McpNativeSiReader.BindingVO(TARGET, 7, "700", "yuheng-reader", "commerce", "prod", Set.of("tooling"), timeout);
    }

    private static java.util.Optional<DdcSiResourceDescriptor> resolve(McpNativeSiReader reader, long owner,
            String biz, String namespace, String env, String caller, Long version) {
        return reader.resolveExecutionResource(owner, biz, namespace, env, "inventory", caller, DdcSiKindEnum.MCP, "inventory-mcp", version);
    }

    private static SiServerVersionVO reference() throws Exception {
        return new SiServerVersionVO("81", "82", "3", snapshot().getDigestSha256(), "commerce", "tooling", "prod", "inventory", "inventory-mcp");
    }

    private static DdcSiResourceDescriptor snapshot() throws Exception {
        String body = "{\"protocolVersion\":\"2025-11-25\"}";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        var root = new DdcSiDependencyManifestVO.VersionIdentityVO("7", "81", "82", "3", DdcSiKindEnum.MCP, "prod", digest);
        return new DdcSiResourceDescriptor().setTenantId(7L).setBizCode("commerce").setNamespaceCode("tooling").setEnvCode("prod")
                .setOwnerAppCode("inventory").setResourceKey("inventory-mcp").setKind(DdcSiKindEnum.MCP).setResourceId(81L)
                .setResolvedVersionId(82L).setResolvedVersionNo(3L).setActiveVersionNo(5).setStatus(DdcSiLifecycleStatusEnum.PUBLISHED)
                .setSourceType(DdcSiSourceTypeEnum.CONSOLE)
                .setSpecJson(body).setDigestSha256(digest).setDependencyManifest(DdcSiDependencyManifestVO.create(root, null, List.of(), List.of()));
    }

    private static byte[] response(DdcSiResourceDescriptor resource) { return ResolveSiResourceResponse.newBuilder().setResource(MAPPER.toProto(resource)).build().toByteArray(); }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StatusRuntimeException.class,
                failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    private static final class NativeFixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<List<String>> authorization = new AtomicReference<>();
        final CountDownLatch cancelled = new CountDownLatch(1);
        final java.util.concurrent.ScheduledExecutorService deadlineTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        final IdpServiceOAuth2Client oauth = mock(IdpServiceOAuth2Client.class);
        final Server server;
        final RpcConsumerChannelPool pool;
        final RpcReferenceStrategyFactory strategies;
        final RpcGenericTargetCache cache;
        final RpcInvocationExecutor executor;
        final jakarta.validation.ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
        final McpNativeSiReader reader;

        NativeFixture(boolean unavailable) throws Exception { this(unavailable, false); }

        NativeFixture(boolean unavailable, boolean hang) throws Exception {
            var service = new DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceImplBase() {
                @Override public void resolveSiResource(ResolveSiResourceRequest request, StreamObserver<ResolveSiResourceResponse> observer) {
                    calls.incrementAndGet();
                    assertThat(request.getExecutionRequired()).isTrue();
                    assertThat(request.getExactVersionNo()).isEqualTo(3);
                    if (hang) { ((ServerCallStreamObserver<ResolveSiResourceResponse>) observer).setOnCancelHandler(cancelled::countDown); return; }
                    if (unavailable) { observer.onError(Status.UNAVAILABLE.asRuntimeException()); return; }
                    try { observer.onNext(ResolveSiResourceResponse.parseFrom(response(snapshot()))); observer.onCompleted(); }
                    catch (Exception failure) { observer.onError(Status.INTERNAL.asRuntimeException()); }
                }
            };
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(ServerInterceptors.intercept(service,
                    new ServerInterceptor() {
                    @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                        var values = headers.getAll(RpcMetadataKeys.AUTHORIZATION);
                        authorization.set(values == null ? List.of() : java.util.stream.StreamSupport.stream(values.spliterator(), false).toList());
                        assertThat(headers.get(RpcMetadataKeys.SERVICE)).isEqualTo(McpNativeSiReader.SERVICE);
                        assertThat(headers.get(RpcMetadataKeys.GROUP)).isEqualTo("tianshu");
                        assertThat(headers.get(RpcMetadataKeys.VERSION)).isEqualTo("1.0.0");
                        assertThat(headers.get(RpcMetadataKeys.ATTEMPT)).isEqualTo("1");
                        assertThat(headers.get(Metadata.Key.of("x-envoy-max-retries", Metadata.ASCII_STRING_MARSHALLER))).isNull();
                        return next.startCall(call, headers);
                    }})).build().start();
            pool = new RpcConsumerChannelPool(new RpcConsumerChannelFactory(4_194_304, 1000) {
                @Override public io.grpc.ManagedChannel create(RpcChannelKey key) {
                    // Fixture changes only TCP destination; the production-approved authority is retained.
                    return NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).overrideAuthority(TARGET.authority())
                            .usePlaintext().disableRetry().disableServiceConfigLookUp().build();
                }
            }, Duration.ofMillis(100));
            var rpc = new EgonRpcProperties();
            rpc.getConsumer().setDefaultTimeoutMs(1500).setBindings(Map.of("mcp-si", new EgonRpcProperties.RpcServiceBindingConfig()
                    .setVersion(1).setNamespace("platform-prod").setService("tianshu-si-rpc").setPort(19080).setMethods(Set.of(McpNativeSiReader.METHOD))));
            strategies = new RpcReferenceStrategyFactory(pool);
            cache = new RpcGenericTargetCache(new RpcReferenceDefinitionResolver(rpc), strategies, 4, Duration.ofSeconds(10));
            executor = new RpcInvocationExecutor(new RpcStatusExceptionMapper());
            var properties = credentialProperties();
            when(oauth.authorize(any())).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "fixture-service-token",
                    Instant.now().minusSeconds(1), Instant.now().plusSeconds(60), Set.of("fixture:si:read")));
            AtomicReference<McpNativeSiReader> owner = new AtomicReference<>();
            var credentials = IdpRpcClientCredentialInterceptorFactory.createConfigured(oauth, properties, validators.getValidator(),
                    (invocation, authentication) -> owner.get().resolveIdentityTenant(invocation));
            var invoker = new RpcGenericInvoker(cache, executor, PROCESS, List.of(credentials));
            reader = new McpNativeSiReader(invoker, MAPPER, binding(1500), PROCESS);
            owner.set(reader);
        }

        @Override public void close() throws Exception {
            executor.close(); cache.close(); strategies.close(); pool.close();
            server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
            deadlineTimer.shutdownNow(); validators.close();
        }
    }

    private static IdpStarterProperties credentialProperties() {
        var properties = new IdpStarterProperties();
        properties.getRpc().setEnabled(true);
        properties.getRpc().getConsumer().setEnabled(true).setBindings(Map.of("mcp-si", new IdpStarterProperties.RpcConsumerBindingProperties()
                .setServiceName(McpNativeSiReader.SERVICE).setGroup("tianshu").setVersion("1.0.0").setFullMethodName(McpNativeSiReader.METHOD)
                .setProcessIdentity(Map.of("applicationName", PROCESS.applicationName(), "env", PROCESS.env()))
                .setMode(IdpStarterProperties.RpcConsumerModeEnum.SERVICE).setScopeContext(ServiceTokenContext.TENANT)
                .setTenantRegistrationIds(Map.of("700", "si-reader-700")).setAppId("yuheng-reader-app")
                .setResourceUri(URI.create("https://tianshu.example.test")).setScopes(Set.of("fixture:si:read"))
                .setTransportBindingId("mcp-si").setTransportBindingVersion(1L)));
        return properties;
    }

    private static String[] deploymentProperties() {
        String prefix = "yuheng.admin.mcp.si-reader.";
        return new String[]{prefix + "enabled=true", prefix + "authorization-mode=LOCAL_AUTHORIZED_PUBLICATION",
                prefix + "binding-id=mcp-si", prefix + "binding-version=1", prefix + "owner-tenant-id=7",
                prefix + "identity-tenant-id=700", prefix + "caller-app-code=yuheng-reader", prefix + "biz-code=commerce",
                prefix + "env-code=prod", prefix + "namespace-codes=tooling", prefix + "resource-uri=https://tianshu.example.test",
                prefix + "required-scopes=fixture:si:read", prefix + "timeout-ms=1000"};
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConfigFixture {
        @Bean("rpcProcessIdentity") RpcProcessIdentity process() { return PROCESS; }
        @Bean("idpStarterProperties") IdpStarterProperties idp() { return credentialProperties(); }
        @Bean("idpServiceOAuth2Client") IdpServiceOAuth2Client oauth() { return mock(IdpServiceOAuth2Client.class); }
        @Bean(name = "fixtureValidatorFactory", destroyMethod = "close")
        jakarta.validation.ValidatorFactory validatorFactory() { return Validation.buildDefaultValidatorFactory(); }
        @Bean("rpcReferenceDefinitionResolver") RpcReferenceDefinitionResolver definitions() {
            var rpc = new EgonRpcProperties();
            rpc.getConsumer().setBindings(Map.of("mcp-si", new EgonRpcProperties.RpcServiceBindingConfig()
                    .setVersion(1).setNamespace("platform-prod").setService("tianshu-si-rpc").setPort(19080)
                    .setMethods(Set.of(McpNativeSiReader.METHOD))));
            return new RpcReferenceDefinitionResolver(rpc);
        }
        @Bean("idpRpcClientCredentialInterceptorFactory") IdpRpcClientCredentialInterceptorFactory credentials(
                @Qualifier("idpServiceOAuth2Client") IdpServiceOAuth2Client oauth,
                @Qualifier("idpStarterProperties") IdpStarterProperties properties,
                @Qualifier("fixtureValidatorFactory") jakarta.validation.ValidatorFactory validators,
                @Qualifier("idpRpcClientTenantResolver") IdpRpcClientTenantResolver tenantResolver) {
            return IdpRpcClientCredentialInterceptorFactory.createConfigured(oauth, properties, validators.getValidator(), tenantResolver);
        }
        @Bean("rpcGenericInvoker") RpcGenericInvoker invoker(
                @Qualifier("idpRpcClientCredentialInterceptorFactory") ObjectProvider<IdpRpcClientCredentialInterceptorFactory> credentials) {
            credentials.getObject();
            return mock(RpcGenericInvoker.class);
        }
    }
}
