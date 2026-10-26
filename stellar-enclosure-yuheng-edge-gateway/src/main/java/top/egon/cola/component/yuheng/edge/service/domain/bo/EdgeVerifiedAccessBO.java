package top.egon.cola.component.yuheng.edge.service.domain.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.net.URI;
import top.egon.cola.platform.tianquan.shoubing.contract.state.IdentitySecurityStateResponse;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.VerifiedJwtBO;

/** Request-only result; cryptographic proof and current owner facts remain distinct. */
public record EdgeVerifiedAccessBO(String appAlias, URI resource, @JsonIgnore VerifiedJwtBO proof,
                                   IdentitySecurityStateResponse state, EdgeOAuthTargetBO browserTarget) {
    @Override
    public String toString() { return "EdgeVerifiedAccessBO[appAlias=" + appAlias + ", proof=<redacted>]"; }
}
