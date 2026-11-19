package top.egon.cola.component.yuheng.admin.mcp.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.release.service.GatewayReleaseService;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.openapi.annotation.EgonGatewayPolicy;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.*;

/** Read-only operator projection; enabled only in the managed Admin role. */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController("mcpMappingPublicationController")
@Conditional(McpMappingPublicationController.Enabled.class)
@RequestMapping("/api/v1/yuheng/admin/mcp/groups/{groupId}/mapping-publications")
@PreAuthorize("hasAuthority('SCOPE_yuheng:mcp:write') and hasAnyAuthority('CAP_yuheng:mcp:write','CAP_*')")
public class McpMappingPublicationController {
    public static final String PREFIX = "yuheng.admin.mcp.publication-export";
    public static final String MEDIA_TYPE = "application/vnd.stellar.yuheng-mcp-mapping-publication+json";
    private static final String POSITIVE = "[1-9][0-9]{0,18}";
    @Qualifier("gatewayReleaseService") private final GatewayReleaseService releases;
    @Qualifier("gatewayPersistenceContextComponent") private final GatewayPersistenceContextComponent context;
    @GetMapping(value = "/{releaseId}/si/{resourceId}", produces = MEDIA_TYPE)
    @EgonGatewayPolicy(exposure = EgonGatewayPolicy.Exposure.INTERNAL)
    @PermissionResource(key = "yuheng.api.mcpMappingPublication.export", type = ResourceType.API,
        labelPage = "gateway.permissions", labelKey = "api.mcpMappingPublication.export",
        allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
        oauthScopes = GatewayPermissionCodes.MCP_WRITE)
    @RequiresPermissions(codes = GatewayPermissionCodes.MCP_WRITE)
    @Operation(operationId = "admin.mcpMappingPublicationController.export",
        summary = "Export one exact SI mapping publication",
        description = "Returns deterministic reviewed evidence from a successful native release, after current source, "
            + "SI and effective-policy checks. Both exact version selectors are required. Includes only the "
            + "selected SI and no sourceContent, Route, credential or unrelated mapping.")
    @ApiResponse(responseCode = "200", description = "Immutable target-scoped evidence with source release provenance",
        content = @Content(mediaType = MEDIA_TYPE, schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> export(@PathVariable @Pattern(regexp = POSITIVE) String groupId,
        @PathVariable @Pattern(regexp = POSITIVE) String releaseId,
        @PathVariable @Pattern(regexp = POSITIVE) String resourceId,
        @RequestParam @Pattern(regexp = POSITIVE) String versionId,
        @RequestParam @Pattern(regexp = POSITIVE) String versionNo, AdminActor actor) {
        var evidence = releases.exportBoundMapping(
            context.installationTenantId(), groupId, releaseId, resourceId, versionId, versionNo, actor);
        byte[] bytes = evidence.canonicalBytes();
        String digest =
            Base64.getEncoder().encodeToString(HexFormat.of().parseHex(McpMappingSeedArtifactVO.sha256(bytes)));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(MEDIA_TYPE))
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"mapping-publication-" + evidence.publicationId() + "-" + resourceId + ".json\"")
            .header("Content-Digest", "sha-256=:" + digest + ":")
            .header("X-Mcp-Mapping-Content-Sha256", evidence.contentSha256())
            .header("X-Mcp-Mapping-Artifact-Sha256", evidence.artifactSha256())
            .eTag("\"mapping-" + evidence.artifactSha256() + "\"")
            .body(bytes);
    }
    public static final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty(PREFIX + ".enabled", Boolean.class, false)
                && "ADMIN".equals(context.getEnvironment().getProperty("yuheng.admin.exposure.role", "ADMIN"));
        }
    }
}
