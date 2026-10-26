package top.egon.cola.component.yuheng.edge.service.domain.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthApplicationCatalog;
import top.egon.cola.platform.tianquan.shoubing.contract.state.RefreshContextProof;

/** A refresh/closure proof is deliberately not an Authentication or a business principal. */
public record EdgeVerifiedRefreshProofBO(EdgeOAuthApplicationCatalog.Application application,
                                         EdgeRefreshContextBO context, RefreshContextProof proof,
                                         @JsonIgnore String accessToken) {
    @Override
    public String toString() { return "EdgeVerifiedRefreshProofBO[appAlias=" + context.target().appAlias() + ", token=<redacted>]"; }
}
