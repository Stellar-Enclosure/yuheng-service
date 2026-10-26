package top.egon.cola.component.yuheng.edge.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceAccess;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.controller.domain.EdgeRefreshOperationResult;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedAccessBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedRefreshProofBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeCsrfProofService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityStateService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCookieService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeRefreshOperationService;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRefreshOperationRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRevocationIntentRepository;

@RestController
@RequestMapping("/apps/{appAlias}/auth")
@RequiredArgsConstructor
public class EdgeIdentityController {
    private final EdgeIdentityStateService identities;
    private final EdgeOAuthCookieService cookies;
    private final EdgeCsrfProofService proofs;
    private final EdgeRefreshOperationService refresh;
    private final EdgeRefreshOperationRepository operations;
    private final EdgeRevocationIntentRepository revocations;

    @GetMapping("/identity")
    @Operation(operationId = "edgeIdentity", summary = "Read the current verified JWT identity")
    public Mono<ResponseEntity<IdentityResult>> identity(@PathVariable String appAlias, ServerWebExchange exchange) {
        requireNoQuery(exchange);
        EdgeVerifiedAccessBO access = exchange.getAttribute(EdgeIdentityStateService.ACCESS_ATTRIBUTE);
        if (access == null || access.browserTarget() == null || !appAlias.equals(access.appAlias())) {
            return Mono.error(new EdgeOAuthStateException("EDGE_ACCESS_REJECTED"));
        }
        var versions = access.state().getSecurityVersion();
        var result = new IdentityResult(access.browserTarget().issuer(), access.proof().getTenantId(), access.proof().getSubject(),
                appAlias, access.browserTarget().clientId(), access.state().getAuthorizationExpiresAt(),
                access.state().getAuthenticationExpiresAt(), Map.of("realm", Long.toString(versions.getRealm()),
                    "client", Long.toString(versions.getClient()), "subject", Long.toString(versions.getSubject())),
                access.browserTarget().issuer() + "/logout");
        return Mono.just(ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result));
    }

    @GetMapping("/csrf")
    @Operation(operationId = "edgeCsrf", summary = "Obtain a purpose-bound signed double-submit proof")
    public Mono<ResponseEntity<EdgeCsrfProofService.Proof>> csrf(@PathVariable String appAlias, ServerWebExchange exchange) {
        requireNoQuery(exchange);
        var proof = proof(exchange, appAlias);
        String token = refreshCookie(exchange, appAlias);
        return identities.requireBoundCredential(proof, token).then(Mono.fromSupplier(() -> {
            var context = proof.context();
            var csrf = proofs.issue(EdgeCsrfProofService.context(context.target(), context.subject(), context.authorizationId(),
                    context.authenticationJti()), context.authorizationExpiresAt());
            cookies.writeCsrf(exchange, appAlias, csrf.token(), csrf.expiresAt());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(csrf);
        }));
    }

    @PostMapping("/refresh")
    @Operation(operationId = "edgeRefresh", summary = "Refresh one current application credential generation")
    public Mono<Void> refresh(@PathVariable String appAlias, @RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        requireNoQuery(exchange);
        if (!body.keySet().equals(Set.of("operationId")) || !(body.get("operationId") instanceof String value)) {
            return Mono.error(new EdgeOAuthStateException("EDGE_REQUEST_INVALID"));
        }
        UUID operationId;
        try {
            operationId = UUID.fromString(value);
            if (!operationId.toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            return Mono.error(new EdgeOAuthStateException("EDGE_REQUEST_INVALID"));
        }
        return refresh.refresh(exchange, operationId, proof(exchange, appAlias));
    }

    @GetMapping("/refresh-operations/{operationId}")
    @Operation(operationId = "edgeRefreshStatus", summary = "Read an owned operation status without recovering credentials")
    public Mono<ResponseEntity<EdgeRefreshOperationResult>> status(@PathVariable String appAlias, @PathVariable UUID operationId,
                                                                  ServerWebExchange exchange) {
        requireNoQuery(exchange);
        return operations.status(operationId, proof(exchange, appAlias).context()).flatMap(found -> found
                .map(value -> Mono.just(ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(EdgeRefreshOperationResult.from(value))))
                .orElseGet(() -> Mono.error(new EdgeOAuthStateException("EDGE_OPERATION_NOT_FOUND"))));
    }

    @PostMapping("/logout")
    @Operation(operationId = "edgeLogout", summary = "Close the current application grant and persist remote revocation delivery")
    public Mono<ResponseEntity<LogoutResult>> logout(@PathVariable String appAlias, ServerWebExchange exchange) {
        requireNoQuery(exchange);
        var proof = proof(exchange, appAlias);
        String token = refreshCookie(exchange, appAlias);
        return identities.requireBoundCredential(proof, token).then(identities.auditRefreshProof(proof, "EDGE_LOGOUT_VERIFIED"))
                .then(revocations.revoke(proof.context(), token, "LOGOUT", "EDGE_LOGOUT_REQUESTED"))
                .map(intent -> {
                    cookies.clearCredentials(exchange, appAlias);
                    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(
                            new LogoutResult(intent.intentId().toString(), "LOCAL_CLOSED", "SUCCEEDED".equals(intent.status()) ? "CONFIRMED" : "DEAD_LETTER".equals(intent.status()) ? "UNKNOWN" : "PENDING", proof.context().target().issuer() + "/logout"));
                });
    }

    private String refreshCookie(ServerWebExchange exchange, String alias) {
        return cookies.refresh(exchange, alias).orElseThrow(() -> new EdgeOAuthStateException("EDGE_REFRESH_REQUIRED"));
    }

    private static EdgeVerifiedRefreshProofBO proof(ServerWebExchange exchange, String alias) {
        EdgeVerifiedRefreshProofBO proof = exchange.getAttribute(EdgeIdentityStateService.REFRESH_PROOF_ATTRIBUTE);
        if (proof == null || !alias.equals(proof.context().target().appAlias())) throw new EdgeOAuthStateException("EDGE_ACCESS_REJECTED");
        return proof;
    }

    private static void requireNoQuery(ServerWebExchange exchange) {
        if (!exchange.getRequest().getQueryParams().isEmpty()) throw new EdgeOAuthStateException("EDGE_REQUEST_INVALID");
    }

    public record IdentityResult(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.issuer", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.issuer")
        String issuer,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.tenantId", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.tenantId")
        String tenantId,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.subject", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.subject")
        String subject,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.appAlias", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.appAlias")
        String appAlias,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.clientId", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.clientId")
        String clientId,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.authorizationExpiresAt", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.authorizationExpiresAt")
        long authorizationExpiresAt,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.authenticationExpiresAt", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.authenticationExpiresAt")
        long authenticationExpiresAt,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.securityVersion", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.securityVersion")
        Map<String, String> securityVersion,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.IdentityResult.ssoLogoutUri", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.IdentityResult.ssoLogoutUri")
        String ssoLogoutUri) { }
    public record LogoutResult(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.LogoutResult.operationId", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.LogoutResult.operationId")
        String operationId,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.LogoutResult.status", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.LogoutResult.status")
        String status,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.LogoutResult.remoteState", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.LogoutResult.remoteState")
        String remoteState,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.LogoutResult.ssoLogoutUri", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.LogoutResult.ssoLogoutUri")
        String ssoLogoutUri) { }
}
