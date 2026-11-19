package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.resolver.AddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvocation;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInvocation;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;
import top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvocation;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvoker;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceTokenRequest;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties.RpcConsumerModeEnum;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.*;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Executes only a freshly authorized, deployment-pinned MCP operation. No directory or URL selection. */
@Slf4j
public final class McpBoundOperationInvoker implements GatewayOperationInvoker {
    private static final ScopedValue<RpcCapabilityVO> RPC_CAPABILITY = ScopedValue.newInstance();
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private final McpPublishedBindingLoader loader;
    private final ObjectMapper mapper;
    private final IdpServiceOAuth2Client oauth;
    private final RpcGenericInvoker rpc;
    private final RpcReferenceDefinitionResolver references;
    private final IdpStarterProperties idp;
    private final IdpRpcClientCredentialInterceptorFactory credentials;
    private final Clock clock;
    private final Duration timeout;
    private final int requestLimit;
    private final int responseLimit;
    private final HttpClient http;

    public McpBoundOperationInvoker(McpPublishedBindingLoader loader, ObjectMapper mapper,
            IdpServiceOAuth2Client oauth, RpcGenericInvoker rpc, RpcReferenceDefinitionResolver references,
            IdpStarterProperties idp, IdpRpcClientCredentialInterceptorFactory credentials,
            Clock clock, Duration timeout, int maximumRequestBytes, int maximumResponseBytes) {
        this(loader, mapper, oauth, rpc, references, idp, credentials, clock, timeout,
                maximumRequestBytes, maximumResponseBytes, null);
    }

    /** Package-local DNS seam is used only by loopback fixtures; the original Service authority is unchanged. */
    McpBoundOperationInvoker(McpPublishedBindingLoader loader, ObjectMapper mapper,
            IdpServiceOAuth2Client oauth, RpcGenericInvoker rpc, RpcReferenceDefinitionResolver references,
            IdpStarterProperties idp, IdpRpcClientCredentialInterceptorFactory credentials,
            Clock clock, Duration timeout, int maximumRequestBytes, int maximumResponseBytes,
            AddressResolverGroup<InetSocketAddress> fixtureDns) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.references = Objects.requireNonNull(references, "references");
        this.idp = Objects.requireNonNull(idp, "idp");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeout == null || timeout.toMillis() < 1 || timeout.toMillis() > Integer.MAX_VALUE
                || maximumRequestBytes < 1 || maximumResponseBytes < 1) throw invalid("MCP_INVOCATION_LIMIT_INVALID");
        this.timeout = timeout; this.requestLimit = maximumRequestBytes; this.responseLimit = maximumResponseBytes;
        HttpClient configured = HttpClient.create().noProxy().disableRetry(true).followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(timeout.toMillis()))
                .responseTimeout(timeout).resolver(spec -> spec.queryTimeout(timeout));
        this.http = fixtureDns == null ? configured : configured.resolver(fixtureDns);
    }

    /** The compatibility port accepts only a loader-created typed binding carried by this subscription. */
    @Override
    public Mono<GatewayInvocationResult> invoke(GatewayOperationInvocation invocation) {
        return Mono.deferContextual(context -> {
            BindingContextVO binding = context.getOrDefault(BindingContextVO.class, null);
            if (binding == null) return Mono.error(invalid("MCP_VERIFIED_BINDING_REQUIRED"));
            return invokeBound(binding.selection(), binding.pin(), invocation);
        });
    }

    public Mono<GatewayInvocationResult> invokeBound(SelectionVO selection, BindingPinVO pin,
                                                    GatewayOperationInvocation invocation) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return Mono.fromCallable(() -> loader.requirePinned(selection, pin))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(binding -> {
                        if (invocation == null || !binding.operation().operationId().equals(invocation.operationId())) {
                            return Mono.error(invalid("MCP_OPERATION_BINDING_MISMATCH"));
                        }
                        BudgetVO budget = new BudgetVO(started, binding);
                        return binding.operation().protocol() == GatewayProtocol.HTTP
                                ? invokeHttp(binding, invocation, budget) : invokeRpc(binding, invocation, budget);
                    }).timeout(timeout);
        });
    }

    /** Only synchronous native factory capture sees this capability. Off-scope/re-entered calls fail closed. */
    public static Optional<String> resolveRpcTenant(RpcClientInvocation invocation) {
        if (!RPC_CAPABILITY.isBound() || invocation == null) throw invalid("MCP_VERIFIED_BINDING_REQUIRED");
        RpcCapabilityVO capability = RPC_CAPABILITY.get();
        RpcDescriptorVO descriptor = capability.binding().operation().rpcDescriptor();
        if (!capability.target().equals(invocation.target())
                || !descriptor.serviceName().equals(invocation.serviceName())
                || !descriptor.group().equals(invocation.group()) || !descriptor.version().equals(invocation.version())
                || !descriptor.fullMethodName().equals(invocation.fullMethodName())) {
            throw invalid("MCP_RPC_CREDENTIAL_BINDING_MISMATCH");
        }
        return Optional.of(capability.binding().transport().credential().identityTenantId());
    }

    /** Captured by the official credential factory before its asynchronous token task. */
    public static Optional<String> resolveRpcIssuer(RpcClientInvocation invocation) {
        resolveRpcTenant(invocation); // identical private capability and exact invocation checks
        return Optional.of(RPC_CAPABILITY.get().binding().transport().credential().issuer().toASCIIString());
    }

    private Mono<GatewayInvocationResult> invokeHttp(BindingContextVO binding,
            GatewayOperationInvocation invocation, BudgetVO budget) {
        PreparedHttpVO prepared = prepareHttp(binding, invocation);
        return Mono.fromCallable(() -> {
                    IdpServiceTokenRequest request = tokenRequest(binding.transport().credential());
                    verifyAuthorizationKey(binding.transport().credential(), request);
                    var token = oauth.authorizeBounded(request, binding.transport().credential().issuer().toASCIIString(), budget.authorizationTime());
                    if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()
                            || token.getTokenValue().chars().anyMatch(c -> c < 0x21 || c > 0x7e)) throw invalid("MCP_OUTBOUND_AUTHORIZATION_UNAVAILABLE");
                    budget.remaining();
                    return token.getTokenValue();
                }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(token -> http.headers(headers -> {
                    headers.set("Authorization", "Bearer " + token);
                    headers.set("Accept", "application/json");
                    if (prepared.body().length > 0) headers.set("Content-Type", "application/json");
                    invocation.traceHeaders().forEach(headers::set);
                }).request(HttpMethod.valueOf(prepared.method()))
                        .uri(binding.transport().httpOrigin().toASCIIString() + prepared.pathAndQuery())
                        .send(ByteBufFlux.fromInbound(Mono.just(prepared.body())))
                        .response((response, body) -> {
                            Map<String, List<String>> headers = new LinkedHashMap<>();
                            response.responseHeaders().forEach(entry -> headers.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue()));
                            return body.asByteArray().collect(ByteArrayOutputStream::new, (bytes, chunk) -> {
                                if ((long) bytes.size() + chunk.length > responseLimit) throw invalid("MCP_RESPONSE_BODY_TOO_LARGE");
                                bytes.writeBytes(chunk);
                            }).map(bytes -> new GatewayInvocationResult(response.status().code(), headers, bytes.toByteArray()));
                        }).single().timeout(budget.remaining()));
    }

    private Mono<GatewayInvocationResult> invokeRpc(BindingContextVO binding,
            GatewayOperationInvocation invocation, BudgetVO budget) {
        return Mono.fromCallable(() -> {
            RpcDescriptorVO descriptor = binding.operation().rpcDescriptor();
            if (descriptor == null || !invocation.call().pathArguments().isEmpty() || !invocation.call().queryArguments().isEmpty()) {
                throw invalid("MCP_RPC_ARGUMENTS_INVALID");
            }
            RpcServiceTargetVO target = references.resolve(binding.transport().rpcBindingId(),
                    new RpcServiceIdentity(descriptor.serviceName(), descriptor.group(), descriptor.version()),
                    Set.of(descriptor.fullMethodName())).target();
            if (target.version() != binding.transport().rpcBindingVersion()
                    || !target.namespace().equals(binding.transport().namespace())
                    || !target.service().equals(binding.transport().service()) || target.port() != binding.transport().port()
                    || !target.methods().contains(descriptor.fullMethodName())) throw invalid("MCP_RPC_TARGET_MISMATCH");
            verifyRpcCredential(binding);
            Descriptors.MethodDescriptor method = methodDescriptor(descriptor);
            byte[] json = json(invocation.call().body() == null ? Map.of() : invocation.call().body());
            var message = DynamicMessage.newBuilder(method.getInputType());
            JsonFormat.parser().merge(new String(json, StandardCharsets.UTF_8), message);
            byte[] payload = message.build().toByteArray();
            if (payload.length > requestLimit) throw invalid("MCP_REQUEST_BODY_TOO_LARGE");
            var command = new RpcGenericInvocation(target.bindingId(), descriptor.serviceName(), descriptor.group(),
                    descriptor.version(), descriptor.fullMethodName(), payload, Math.max(1, budget.remaining().toMillis()));
            // component-rpc prepares the interceptor once on this thread, before its scheduler/retry boundary.
            try (TraceContext.Scope trace = TraceContext.fromHeaders(invocation.traceHeaders()::get, false).open()) {
                var future = ScopedValue.where(RPC_CAPABILITY, new RpcCapabilityVO(binding, target))
                        .call(() -> rpc.invokeAsync(command));
                return new RpcCallVO(future, method);
            }
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(call -> Mono.fromCompletionStage(call.future())
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> {
                    verifyAuthorizationKey(binding.transport().credential(), tokenRequest(binding.transport().credential()));
                    budget.remaining();
                    if (bytes.length > responseLimit) throw invalid("MCP_RESPONSE_BODY_TOO_LARGE");
                    try {
                        byte[] body = JsonFormat.printer().omittingInsignificantWhitespace()
                                .print(DynamicMessage.parseFrom(call.method().getOutputType(), bytes)).getBytes(StandardCharsets.UTF_8);
                        if (body.length > responseLimit) throw invalid("MCP_RESPONSE_BODY_TOO_LARGE");
                        return new GatewayInvocationResult(200, Map.of("content-type", List.of("application/json")), body);
                    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
                        throw invalid("MCP_RPC_RESPONSE_INVALID");
                    }
                }).timeout(budget.remaining()));
    }

    private void verifyRpcCredential(BindingContextVO binding) {
        credentials.validateConfiguration();
        if (idp.getRpc() == null || !idp.getRpc().isEnabled() || idp.getRpc().getConsumer() == null || !idp.getRpc().getConsumer().isEnabled()) {
            throw invalid("MCP_RPC_CREDENTIAL_BINDING_MISMATCH");
        }
        RpcDescriptorVO descriptor = binding.operation().rpcDescriptor();
        List<IdpStarterProperties.RpcConsumerBindingProperties> matches = idp.getRpc().getConsumer().getBindings().values().stream()
                .filter(candidate -> descriptor.serviceName().equals(candidate.getServiceName())
                        && descriptor.group().equals(candidate.getGroup()) && descriptor.version().equals(candidate.getVersion())
                        && descriptor.fullMethodName().equals(candidate.getFullMethodName())
                        && binding.transport().rpcBindingId().equals(candidate.getTransportBindingId())
                        && Objects.equals(binding.transport().rpcBindingVersion(), candidate.getTransportBindingVersion())).toList();
        CredentialVO credential = binding.transport().credential();
        if (matches.size() != 1) throw invalid("MCP_RPC_CREDENTIAL_BINDING_MISMATCH");
        var selected = matches.getFirst();
        if (selected.getMode() != RpcConsumerModeEnum.SERVICE || selected.getScopeContext() != ServiceTokenContext.TENANT
                || !credential.appId().equals(selected.getAppId()) || !credential.audience().equals(selected.getResourceUri())
                || !credential.scopes().equals(selected.getScopes())
                || !credential.registrationId().equals(selected.getTenantRegistrationIds().get(credential.identityTenantId()))) {
            throw invalid("MCP_RPC_CREDENTIAL_BINDING_MISMATCH");
        }
        verifyAuthorizationKey(credential, tokenRequest(credential));
    }

    private void verifyAuthorizationKey(CredentialVO expected, IdpServiceTokenRequest request) {
        var actual = oauth.authorizationKey(request);
        if (actual == null || !expected.issuer().toASCIIString().equals(actual.issuer())
                || !request.registrationId().equals(actual.registrationId()) || !request.appId().equals(actual.appId())
                || !request.audience().equals(actual.audience()) || actual.context() != ServiceTokenContext.TENANT
                || !request.tenantId().equals(actual.tenantId()) || !request.scopes().equals(actual.scopes())) {
            throw invalid("MCP_OUTBOUND_AUTHORIZATION_MISMATCH");
        }
    }

    private static IdpServiceTokenRequest tokenRequest(CredentialVO credential) {
        return new IdpServiceTokenRequest(credential.registrationId(), credential.appId(), credential.audience(),
                ServiceTokenContext.TENANT, credential.identityTenantId(), credential.scopes());
    }

    private PreparedHttpVO prepareHttp(BindingContextVO binding, GatewayOperationInvocation invocation) {
        String[] identity = binding.operation().methodIdentity().split(" ", 2);
        if (identity.length != 2) throw invalid("MCP_HTTP_METHOD_INVALID");
        String template = identity[1];
        var matcher = VARIABLE.matcher(template);
        Map<String, Object> remaining = new LinkedHashMap<>(invocation.call().pathArguments());
        StringBuilder path = new StringBuilder(); Set<String> variables = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!variables.add(name) || !remaining.containsKey(name)) throw invalid("MCP_PATH_ARGUMENT_INVALID");
            String value = scalar(remaining.remove(name));
            if (value.isEmpty() || value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                    || value.indexOf('%') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0 || value.indexOf(';') >= 0) throw invalid("MCP_PATH_ARGUMENT_INVALID");
            matcher.appendReplacement(path, java.util.regex.Matcher.quoteReplacement(encode(value)));
        }
        matcher.appendTail(path);
        if (!remaining.isEmpty() || path.indexOf("{") >= 0 || path.indexOf("}") >= 0) throw invalid("MCP_PATH_ARGUMENT_INVALID");
        JsonNode schema = binding.operation().definition().path("requestSchema");
        JsonNode locations = schema.path("properties");
        validateArguments(locations.path("path"), invocation.call().pathArguments(), variables);
        validateArguments(locations.path("query"), invocation.call().queryArguments(), Set.of());
        if (invocation.call().body() != null && !locations.has("body")) throw invalid("MCP_BODY_ARGUMENT_INVALID");
        if (required(schema).contains("body") && invocation.call().body() == null) throw invalid("MCP_BODY_ARGUMENT_INVALID");
        List<String> query = new ArrayList<>();
        new TreeMap<>(invocation.call().queryArguments()).forEach((name, value) -> query.add(encode(name) + "=" + encode(scalar(value))));
        if (!query.isEmpty()) path.append('?').append(String.join("&", query));
        byte[] body = invocation.call().body() == null ? new byte[0] : json(invocation.call().body());
        URI origin = binding.transport().httpOrigin();
        URI target = URI.create(origin.toASCIIString() + path);
        if (!origin.getAuthority().equals(target.getAuthority()) || !"http".equals(target.getScheme())
                || target.getRawFragment() != null || target.toASCIIString().length() > 8192) throw invalid("MCP_HTTP_TARGET_INVALID");
        return new PreparedHttpVO(identity[0], path.toString(), body);
    }

    private void validateArguments(JsonNode schema, Map<String, Object> values, Set<String> pathVariables) {
        Set<String> allowed = new HashSet<>();
        if (schema.isObject()) schema.path("properties").fieldNames().forEachRemaining(allowed::add);
        if (!allowed.containsAll(pathVariables)) throw invalid("MCP_ARGUMENT_SCHEMA_INVALID");
        if (!allowed.containsAll(values.keySet()) || !values.keySet().containsAll(required(schema))) throw invalid("MCP_ARGUMENTS_INVALID");
        values.values().forEach(McpBoundOperationInvoker::scalar);
    }
    private static Set<String> required(JsonNode schema) {
        Set<String> result = new HashSet<>();
        for (JsonNode required : schema.path("required")) {
            if (!required.isTextual() || !result.add(required.textValue())) throw invalid("MCP_ARGUMENT_SCHEMA_INVALID");
        }
        return result;
    }
    private static String scalar(Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) throw invalid("MCP_ARGUMENTS_INVALID");
        String text = value.toString();
        if (text.length() > 4096 || !StandardCharsets.UTF_8.newEncoder().canEncode(text) || text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)
                || value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f)) throw invalid("MCP_ARGUMENTS_INVALID");
        return text;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private byte[] json(Object value) {
        try {
            var bytes = new LimitedBodyBuffer(requestLimit);
            mapper.writeValue(bytes, value);
            return bytes.toByteArray();
        } catch (java.io.IOException malformed) {
            Throwable cause = malformed;
            while (cause != null) {
                if (cause instanceof InvocationRejectedException rejected) throw rejected;
                cause = cause.getCause();
            }
            throw invalid("MCP_ARGUMENTS_INVALID");
        }
    }
    private static Descriptors.MethodDescriptor methodDescriptor(RpcDescriptorVO descriptor) throws Exception {
        var set = DescriptorProtos.FileDescriptorSet.parseFrom(descriptor.descriptorSet());
        Map<String, DescriptorProtos.FileDescriptorProto> pending = new LinkedHashMap<>();
        for (var proto : set.getFileList()) pending.put(proto.getName(), proto);
        Map<String, Descriptors.FileDescriptor> ready = new HashMap<>();
        while (!pending.isEmpty()) {
            int count = pending.size();
            for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
                var proto = iterator.next().getValue();
                if (proto.getDependencyList().stream().allMatch(ready::containsKey)) {
                    ready.put(proto.getName(), Descriptors.FileDescriptor.buildFrom(proto,
                            proto.getDependencyList().stream().map(ready::get).toArray(Descriptors.FileDescriptor[]::new)));
                    iterator.remove();
                }
            }
            if (count == pending.size()) throw invalid("MCP_RPC_DESCRIPTOR_INVALID");
        }
        var methods = ready.values().stream().flatMap(file -> file.getServices().stream())
                .filter(service -> service.getFullName().equals(descriptor.serviceName()))
                .map(service -> service.findMethodByName(descriptor.fullMethodName().substring(descriptor.fullMethodName().indexOf('/') + 1)))
                .filter(Objects::nonNull).toList();
        if (methods.size() != 1 || methods.getFirst().isClientStreaming() || methods.getFirst().isServerStreaming()) throw invalid("MCP_RPC_DESCRIPTOR_INVALID");
        return methods.getFirst();
    }
    private final class BudgetVO {
        private final long started; private final BindingContextVO binding;
        private BudgetVO(long started, BindingContextVO binding) { this.started = started; this.binding = binding; }
        private Duration authorizationTime() {
            Duration remaining = remaining();
            return remaining.compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : remaining;
        }
        private Duration remaining() {
            Duration remaining = timeout.minusNanos(System.nanoTime() - started);
            Duration validity = Duration.between(clock.instant(), binding.validUntil());
            if (validity.compareTo(remaining) < 0) remaining = validity;
            if (remaining.isNegative() || remaining.isZero()) throw invalid("MCP_INVOCATION_DEADLINE_EXCEEDED");
            return remaining;
        }
    }
    private static final class LimitedBodyBuffer extends ByteArrayOutputStream {
        private final int limit;
        private LimitedBodyBuffer(int limit) { super(Math.min(limit, 8192)); this.limit = limit; }
        @Override public synchronized void write(int value) {
            if (count >= limit) throw invalid("MCP_REQUEST_BODY_TOO_LARGE");
            super.write(value);
        }
        @Override public synchronized void write(byte[] value, int offset, int length) {
            if ((long) count + length > limit) throw invalid("MCP_REQUEST_BODY_TOO_LARGE");
            super.write(value, offset, length);
        }
    }
    private record RpcCapabilityVO(BindingContextVO binding, RpcServiceTargetVO target) { }
    private record RpcCallVO(java.util.concurrent.CompletionStage<byte[]> future, Descriptors.MethodDescriptor method) { }
    private record PreparedHttpVO(String method, String pathAndQuery, byte[] body) { }
    public static final class InvocationRejectedException extends IllegalStateException {
        private final String errorCode;
        private InvocationRejectedException(String code) { super(code); errorCode = code; }
        public String errorCode() { return errorCode; }
    }
    private static InvocationRejectedException invalid(String code) { return new InvocationRejectedException(code); }
}
