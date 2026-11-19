package top.egon.cola.component.yuheng.admin.mcp.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.egon.cola.component.yuheng.admin.mcp.config.McpMappingSeedConfiguration;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpMappingSeedFreezeCommand;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO;
import top.egon.cola.component.yuheng.admin.mcp.service.McpMappingSeedService;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.shared.domain.*;
import top.egon.cola.component.yuheng.openapi.annotation.EgonGatewayPolicy;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.*;

/** Authenticated operator export. This endpoint approves immutable composition evidence, never public ingress. */
@Slf4j
@RequiredArgsConstructor
@RestController("mcpMappingSeedController")
@ConditionalOnProperty(prefix = McpMappingSeedConfiguration.PREFIX, name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/yuheng/admin/mcp/groups/{groupId}/mapping-seeds")
@PreAuthorize("hasAuthority('SCOPE_yuheng:mcp:write') and hasAnyAuthority('CAP_yuheng:mcp:write','CAP_*')")
public class McpMappingSeedController {
    public static final String MEDIA_TYPE = "application/vnd.stellar.yuheng-mcp-mapping-seed+json";
    @Qualifier("mcpMappingSeedService") private final McpMappingSeedService service;
    @PostMapping(produces = MEDIA_TYPE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @EgonGatewayPolicy(exposure = EgonGatewayPolicy.Exposure.INTERNAL)
    @PermissionResource(key = "yuheng.api.mcpMappingSeed.freeze", type = ResourceType.API,
        labelPage = "gateway.permissions", labelKey = "api.mcpMappingSeed.freeze",
        allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
        oauthScopes = GatewayPermissionCodes.MCP_WRITE)
    @RequiresPermissions(codes = GatewayPermissionCodes.MCP_WRITE)
    @Operation(operationId = "admin.mcpMappingSeedController.freeze",
        summary = "Freeze and export registered-identity MCP mapping evidence",
        description = "Returns exact canonical immutable bytes. The same Idempotency-Key re-exports a committed "
            + "receipt for seven days, after current authorization and source checks. Does not create an SI "
            + "execution version, change the draft basis, or publish a Route.")
    @ApiResponse(responseCode = "200", description = "Committed, independently SHA-pinnable seed artifact",
        content = @Content(mediaType = MEDIA_TYPE, schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> freeze(@PathVariable String groupId,
        @Valid @RequestBody McpMappingSeedFreezeCommand command, @RequestHeader("Idempotency-Key") String key,
        AdminActor actor) {
        McpMappingSeedArtifactVO artifact = service.freeze(groupId, command, key, actor, RequestAuditContext.current());
        byte[] bytes = artifact.canonicalBytes();
        String digest =
            Base64.getEncoder().encodeToString(HexFormat.of().parseHex(McpMappingSeedArtifactVO.sha256(bytes)));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(MEDIA_TYPE))
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"mapping-seed-" + artifact.publicationId() + ".json\"")
            .header("Content-Digest", "sha-256=:" + digest + ":")
            .header("X-Mcp-Mapping-Content-Sha256", artifact.contentSha256())
            .header("X-Mcp-Mapping-Artifact-Sha256", artifact.artifactSha256())
            .eTag("\"seed-" + artifact.artifactSha256() + "\"")
            .body(bytes);
    }
}
