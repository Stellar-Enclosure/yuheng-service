package top.egon.cola.component.yuheng.edge.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;

/** Lives only in one native callback request so post-exchange failures can revoke an issued Refresh. */
@JsonIgnoreType
public final class EdgeOAuthCallbackContext {
    private final AtomicReference<OAuth2AccessTokenResponse> response = new AtomicReference<>();
    private volatile EdgeRefreshContextBO verifiedContext;
    private volatile boolean completed;

    public void received(OAuth2AccessTokenResponse value) {
        if (value == null || !response.compareAndSet(null, value)) {
            throw new EdgeOAuthStateException("EDGE_CODE_EXCHANGE_REPEATED");
        }
    }

    public OAuth2AccessTokenResponse response() { return response.get(); }
    public EdgeRefreshContextBO verifiedContext() { return verifiedContext; }
    public void verifiedContext(EdgeRefreshContextBO context) { verifiedContext = context; }
    public boolean completed() { return completed; }
    public void complete() { completed = true; }
    public void clear() { response.set(null); verifiedContext = null; }

    @Override
    public String toString() { return "EdgeOAuthCallbackContext[response=<redacted>]"; }
}
