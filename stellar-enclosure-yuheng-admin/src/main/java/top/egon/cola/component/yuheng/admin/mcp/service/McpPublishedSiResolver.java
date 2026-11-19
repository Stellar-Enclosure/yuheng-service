package top.egon.cola.component.yuheng.admin.mcp.service;

import io.grpc.StatusRuntimeException;
import lombok.NonNull;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiResourceDescriptor;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO.SiServerVersionVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Verifies a fresh authorized read of an exact published SI version without retaining its body. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class McpPublishedSiResolver {
    private static final String SI_PATH = "mapping.siServer";

    @NonNull
    private final DdcSiCatalogClient catalog;
    private final McpNativeSiReader nativeReader;

    /** Source-compatible explicit catalog fixture; deployment assembly uses the fixed native reader. */
    public McpPublishedSiResolver(DdcSiCatalogClient catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.nativeReader = null;
    }

    public McpPublishedSiResolver(McpNativeSiReader nativeReader) {
        this.catalog = Objects.requireNonNull(nativeReader, "nativeReader");
        this.nativeReader = nativeReader;
    }

    /** Production callers cannot select or replace the registered SERVICE source application. */
    public String resolveClosure(long owner, SiServerVersionVO server) {
        if (nativeReader == null) {
            throw new IllegalStateException("YUHENG_MCP_SI_NATIVE_BINDING_REQUIRED");
        }
        try {
            return resolveClosure(owner, nativeReader.callerAppCode(), server);
        } catch (StatusRuntimeException failure) {
            throw switch (failure.getStatus().getCode()) {
                case UNAUTHENTICATED -> new CommonException(401, "YUHENG_MCP_SI_AUTHENTICATION_INVALID",
                        "The SI service rejected the configured credential");
                case PERMISSION_DENIED -> new CommonException(403, "YUHENG_MCP_SI_ACCESS_DENIED",
                        "The selected SI resource is outside the approved publication binding");
                case FAILED_PRECONDITION -> new CommonException(422, "YUHENG_MCP_SI_VERSION_REJECTED",
                        "The selected SI version cannot be verified");
                default -> new CommonException(503, "YUHENG_MCP_SI_UNAVAILABLE",
                        "The SI authority is temporarily unavailable", true, null);
            };
        }
    }

    public String resolveClosure(long tenantId, String trustedCallerAppCode, SiServerVersionVO server) {
        Objects.requireNonNull(server, "server");
        if (tenantId <= 0 || trustedCallerAppCode == null || trustedCallerAppCode.isBlank()) {
            throw new IllegalArgumentException("YUHENG_MCP_API_MAPPING_SI_CALLER_INVALID");
        }
        if (nativeReader != null && !nativeReader.callerAppCode().equals(trustedCallerAppCode)) {
            throw new IllegalArgumentException("YUHENG_MCP_API_MAPPING_SI_CALLER_INVALID");
        }
        long versionNo = Long.parseLong(server.versionNo());
        var resolved = catalog.resolveExecutionResource(
                tenantId, server.bizCode(), server.namespaceCode(), server.envCode(), server.ownerAppCode(),
                trustedCallerAppCode, DdcSiKindEnum.MCP, server.resourceKey(), versionNo
        ).orElseThrow(() -> new McpValidationException(
                "YUHENG_MCP_API_MAPPING_SI_UNAVAILABLE", SI_PATH,
                "The selected SI version cannot be resolved under the current publication binding"));

        if (!matches(tenantId, server, resolved)) {
            log.debug("Rejected an SI response that did not match the pinned mapping");
            throw new McpValidationException("YUHENG_MCP_API_MAPPING_SI_MISMATCH", SI_PATH,
                    "The SI response does not match the selected version and dependency closure");
        }
        return resolved.getDependencyManifest().closureDigest();
    }

    private static boolean matches(long tenantId, SiServerVersionVO server, DdcSiResourceDescriptor resolved) {
        return Objects.equals(resolved.getTenantId(), tenantId)
                && resolved.getKind() == DdcSiKindEnum.MCP
                && resolved.getStatus() != null
                && resolved.getStatus().isQueryableByRuntime()
                && Objects.equals(resolved.getBizCode(), server.bizCode())
                && Objects.equals(resolved.getNamespaceCode(), server.namespaceCode())
                && Objects.equals(resolved.getEnvCode(), server.envCode())
                && Objects.equals(resolved.getOwnerAppCode(), server.ownerAppCode())
                && Objects.equals(resolved.getResourceKey(), server.resourceKey())
                && Objects.equals(resolved.getResourceId(), Long.valueOf(server.resourceId()))
                && Objects.equals(resolved.getResolvedVersionId(), Long.valueOf(server.versionId()))
                && Objects.equals(resolved.getResolvedVersionNo(), Long.valueOf(server.versionNo()))
                && server.digestSha256().equals(resolved.getDigestSha256())
                && resolved.getDependencyManifest() != null
                && resolved.isExecutionSnapshotShapeValid()
                && resolved.getSpecJson() != null
                && server.digestSha256().equals(digest(resolved.getSpecJson()));
    }

    private static String digest(String body) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
