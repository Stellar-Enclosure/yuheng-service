package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.SecurityFailure;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedIdentityVO;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.IdpAuthorizationObservationContextBO;

/** One Reactor request owns its proof, durable denial state and terminal dependency failure. */
@JsonIgnoreType
@Slf4j
public final class McpRequestObservationComponent implements AutoCloseable {
    private final IdpAuthorizationObservationContextBO denial = new IdpAuthorizationObservationContextBO();
    private VerifiedIdentityVO verified;
    private SecurityFailure securityFailure;
    private boolean closed;

    public synchronized void bind(VerifiedIdentityVO identity) {
        requireAvailable();
        if (verified != null || identity == null || !identity.belongsTo(this)) throw unavailable(null);
        verified = identity;
    }
    public synchronized VerifiedIdentityVO verifiedIdentity() {
        requireAvailable();
        if (verified == null) throw unavailable(null);
        return verified;
    }
    public synchronized boolean active() { return !closed; }
    public synchronized SecurityFailure unavailable(Throwable cause) {
        if (securityFailure != null && securityFailure.httpStatus() == 503) return securityFailure;
        var failure = SecurityFailure.unavailable();
        if (cause != null && cause != failure) failure.initCause(cause);
        if (!closed) securityFailure = failure;
        return failure;
    }
    public synchronized SecurityFailure fail(SecurityFailure failure) {
        if (!closed && (securityFailure == null || failure.httpStatus() == 503 && securityFailure.httpStatus() != 503)) securityFailure = failure;
        return securityFailure == null ? failure : securityFailure;
    }
    public synchronized Throwable preserveSecurityFailure(Throwable original) {
        if (securityFailure == null) return original;
        if (securityFailure != original) securityFailure.addSuppressed(original);
        return securityFailure;
    }
    public void confirmDenial() {
        VerifiedIdentityVO identity = verifiedIdentity();
        if (!denial.denyOnce(identity::observeDenied)) {
            throw unavailable(null);
        }
        requireAvailable();
    }
    public synchronized boolean denialConfirmed() { return !closed && denial.isConfirmed(); }
    public synchronized void requireAvailable() {
        if (securityFailure != null) throw securityFailure;
        if (closed) throw SecurityFailure.unavailable();
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        verified = null;
        securityFailure = null;
        denial.close();
    }
}
