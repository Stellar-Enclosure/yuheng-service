package top.egon.cola.component.yuheng.admin.mcp.service;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvocation;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInvocation;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceResponse;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;
import top.egon.cola.component.tianshu.model.si.DdcSiCatalogQuery;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiLifecycleStatusEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiResourceDescriptor;
import top.egon.cola.component.tianshu.model.si.DdcSiSearchHit;
import top.egon.cola.component.tianshu.model.si.DdcSiSelfRegistrationRequest;
import top.egon.cola.component.tianshu.model.si.DdcSiSemanticSearchQuery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A deployment-authorized, fixed-domain SI read through the existing native RPC lifecycle. */
@RequiredArgsConstructor
@Slf4j
public final class McpNativeSiReader implements DdcSiCatalogClient {
    public static final String SERVICE = "egon.tianshu.v1.DdcSiResourceGrpcService";
    public static final String GROUP = "tianshu";
    public static final String VERSION = "1.0.0";
    public static final String METHOD = SERVICE + "/ResolveSiResource";
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_WIRE_BYTES = 2_200_000;
    private static final ScopedValue<BindingVO> AUTHORIZED_READ = ScopedValue.newInstance();

    @NonNull private final RpcGenericInvoker invoker;
    @NonNull private final DdcSiProtoMapper mapper;
    @NonNull private final BindingVO binding;
    @NonNull private final RpcProcessIdentity processIdentity;

    public String callerAppCode() { return binding.callerAppCode(); }

    /** Used only by the named OAuth tenant resolver during this synchronous native callback. */
    public Optional<String> resolveIdentityTenant(RpcClientInvocation invocation) {
        if (!AUTHORIZED_READ.isBound() || AUTHORIZED_READ.get() != binding || invocation == null
                || !binding.target().equals(invocation.target()) || !SERVICE.equals(invocation.serviceName())
                || !GROUP.equals(invocation.group()) || !VERSION.equals(invocation.version())
                || !METHOD.equals(invocation.fullMethodName()) || invocation.processIdentity() == null
                || !Objects.equals(processIdentity.applicationName(), invocation.processIdentity().applicationName())
                || !Objects.equals(processIdentity.env(), invocation.processIdentity().env())) {
            throw denied();
        }
        return Optional.of(binding.identityTenantId());
    }

    @Override
    public Optional<DdcSiResourceDescriptor> resolveExecutionResource(long tenantId, String bizCode,
            String namespaceCode, String envCode, String ownerAppCode, String callerAppCode,
            DdcSiKindEnum kind, String resourceKey, Long pinnedVersionNo) {
        if (tenantId != binding.ownerTenantId() || !binding.bizCode().equals(bizCode)
                || !binding.envCode().equals(envCode) || !binding.callerAppCode().equals(callerAppCode)
                || !binding.namespaceCodes().contains(namespaceCode) || kind != DdcSiKindEnum.MCP) {
            throw denied();
        }
        coordinate(resourceKey);
        if (ownerAppCode != null) { coordinate(ownerAppCode); }
        if (pinnedVersionNo == null || pinnedVersionNo <= 0) { throw historyUnavailable(); }
        ResolveSiResourceRequest.Builder request = ResolveSiResourceRequest.newBuilder()
                .setTenantId(tenantId).setBizCode(bizCode).setNamespaceCode(namespaceCode).setEnvCode(envCode)
                .setCallerAppCode(binding.callerAppCode()).setKind(kind.getCode()).setResourceKey(resourceKey)
                .setExecutionRequired(true).setExactVersionNo(pinnedVersionNo);
        if (ownerAppCode != null) { request.setOwnerAppCode(ownerAppCode); }
        byte[] payload = request.build().toByteArray();
        if (payload.length > 16_384) { throw invalidResponse(); }
        final byte[] response;
        try {
            response = ScopedValue.where(AUTHORIZED_READ, binding).call(() -> invoker.invokeBlocking(
                    new RpcGenericInvocation(binding.target().bindingId(), SERVICE, GROUP, VERSION,
                            METHOD, payload, binding.timeoutMs())));
        } catch (EgonRpcException failure) {
            throw transportFailure(failure);
        }
        return Optional.of(decode(response, tenantId, namespaceCode, ownerAppCode, resourceKey, pinnedVersionNo));
    }

    private DdcSiResourceDescriptor decode(byte[] response, long tenantId, String namespace,
            String owner, String resourceKey, long pinnedVersionNo) {
        if (response == null || response.length == 0 || response.length > MAX_WIRE_BYTES) { throw invalidResponse(); }
        try {
            CodedInputStream input = CodedInputStream.newInstance(response);
            input.setRecursionLimit(64);
            input.setSizeLimit(MAX_WIRE_BYTES);
            var wire = ResolveSiResourceResponse.parseFrom(input);
            input.checkLastTagWas(0);
            if (!input.isAtEnd() || !wire.hasResource()) { throw historyUnavailable(); }
            var result = mapper.fromProto(wire.getResource());
            if (result == null || !Objects.equals(result.getTenantId(), tenantId)
                    || !binding.bizCode().equals(result.getBizCode()) || !namespace.equals(result.getNamespaceCode())
                    || !binding.envCode().equals(result.getEnvCode()) || !Objects.equals(owner, result.getOwnerAppCode())
                    || result.getKind() != DdcSiKindEnum.MCP || !resourceKey.equals(result.getResourceKey())
                    || !Objects.equals(result.getResolvedVersionNo(), pinnedVersionNo)
                    || result.getStatus() == null || !result.getStatus().isQueryableByRuntime()
                    || result.getStatus() == DdcSiLifecycleStatusEnum.DRAFT
                    || result.getDependencyManifest() == null || !result.isExecutionSnapshotShapeValid()
                    || result.getSpecJson() == null || result.getSpecJson().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES
                    || result.getDigestSha256() == null || !result.getDigestSha256().matches("[0-9a-f]{64}")
                    || !result.getDigestSha256().equals(bodyDigest(result.getSpecJson()))
                    || !DdcConfigCanonicalJson.parse(result.getSpecJson().getBytes(StandardCharsets.UTF_8)).isObject()) {
                throw historyUnavailable();
            }
            return result;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalidResponse();
        }
    }

    private static StatusRuntimeException transportFailure(EgonRpcException failure) {
        // The native executor preserves the actual gRPC failure as its direct cause.
        if (failure.getCause() instanceof StatusRuntimeException status) {
            return switch (status.getStatus().getCode()) {
                case NOT_FOUND -> historyUnavailable();
                case FAILED_PRECONDITION -> hasPinnedHistoryDetail(status) ? historyUnavailable()
                        : Status.FAILED_PRECONDITION.withDescription("YUHENG_MCP_SI_CONFIGURATION_INVALID").asRuntimeException();
                case UNAUTHENTICATED -> Status.UNAUTHENTICATED.withDescription("YUHENG_MCP_SI_UNAUTHENTICATED").asRuntimeException();
                case PERMISSION_DENIED -> denied();
                case RESOURCE_EXHAUSTED -> Status.RESOURCE_EXHAUSTED.withDescription("YUHENG_MCP_SI_CAPACITY_EXHAUSTED").asRuntimeException();
                case DEADLINE_EXCEEDED -> Status.DEADLINE_EXCEEDED.withDescription("YUHENG_MCP_SI_DEADLINE_EXCEEDED").asRuntimeException();
                case CANCELLED -> Status.CANCELLED.withDescription("YUHENG_MCP_SI_CANCELLED").asRuntimeException();
                default -> Status.UNAVAILABLE.withDescription("YUHENG_MCP_SI_UNAVAILABLE").asRuntimeException();
            };
        }
        return switch (failure.getRpcErrorCode()) {
            case RPC_CANCELLED -> Status.CANCELLED.withDescription("YUHENG_MCP_SI_CANCELLED").asRuntimeException();
            case RPC_DEADLINE_EXCEEDED -> Status.DEADLINE_EXCEEDED.withDescription("YUHENG_MCP_SI_DEADLINE_EXCEEDED").asRuntimeException();
            default -> Status.UNAVAILABLE.withDescription("YUHENG_MCP_SI_UNAVAILABLE").asRuntimeException();
        };
    }

    private static boolean hasPinnedHistoryDetail(StatusRuntimeException failure) {
        try {
            var status = StatusProto.fromThrowable(failure);
            if (status == null || status.getDetailsCount() != 1 || !status.getDetails(0).is(ErrorInfo.class)) {
                return false;
            }
            ErrorInfo detail = status.getDetails(0).unpack(ErrorInfo.class);
            return "tianshu.si".equals(detail.getDomain()) && "SI_PINNED_HISTORY_UNAVAILABLE".equals(detail.getReason());
        } catch (InvalidProtocolBufferException | IllegalArgumentException invalid) {
            log.debug("Ignoring malformed structured SI status");
            return false;
        }
    }

    private static String bodyDigest(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void coordinate(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || !value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new IllegalArgumentException("YUHENG_MCP_SI_BINDING_INVALID");
        }
    }

    private static StatusRuntimeException denied() { return Status.PERMISSION_DENIED.withDescription("YUHENG_MCP_SI_SCOPE_DENIED").asRuntimeException(); }
    private static StatusRuntimeException historyUnavailable() { return Status.FAILED_PRECONDITION.withDescription("SI_PINNED_HISTORY_UNAVAILABLE").asRuntimeException(); }
    private static StatusRuntimeException invalidResponse() { return Status.FAILED_PRECONDITION.withDescription("YUHENG_MCP_SI_RESPONSE_INVALID").asRuntimeException(); }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("YUHENG_MCP_SI_EXECUTION_READ_ONLY"); }

    @Override public List<DdcSiResourceDescriptor> listResources(DdcSiCatalogQuery query) { throw unsupported(); }
    @Override public Optional<DdcSiResourceDescriptor> resolveResource(long tenantId, String bizCode, String namespaceCode,
            String envCode, String appCode, DdcSiKindEnum kind, String resourceKey, Integer pinnedVersionNo) { throw unsupported(); }
    @Override public List<DdcSiSearchHit> semanticSearch(DdcSiSemanticSearchQuery query) { throw unsupported(); }
    @Override public DdcSiResourceDescriptor registerManagedResource(DdcSiSelfRegistrationRequest request) { throw unsupported(); }

    /** Immutable, explicit deployment authority; reference payloads cannot expand this domain. */
    public record BindingVO(RpcServiceTargetVO target, long ownerTenantId, String identityTenantId,
            String callerAppCode, String bizCode, String envCode, Set<String> namespaceCodes, long timeoutMs) {
        public BindingVO {
            Objects.requireNonNull(target, "target");
            if (!target.methods().equals(Set.of(METHOD)) || ownerTenantId <= 0 || timeoutMs < 1 || timeoutMs > 5000
                    || identityTenantId == null || !identityTenantId.matches("[1-9][0-9]{0,18}")
                    || namespaceCodes == null || namespaceCodes.isEmpty() || namespaceCodes.size() > 64) {
                throw new IllegalArgumentException("YUHENG_MCP_SI_BINDING_INVALID");
            }
            try { Long.parseLong(identityTenantId); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("YUHENG_MCP_SI_BINDING_INVALID"); }
            coordinate(callerAppCode); coordinate(bizCode); coordinate(envCode);
            namespaceCodes.forEach(McpNativeSiReader::coordinate);
            namespaceCodes = Set.copyOf(namespaceCodes);
        }
    }
}
