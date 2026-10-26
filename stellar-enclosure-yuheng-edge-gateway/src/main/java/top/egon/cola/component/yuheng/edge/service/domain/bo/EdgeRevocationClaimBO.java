package top.egon.cola.component.yuheng.edge.service.domain.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Available only to the revocation worker and never returned by a controller. */
public record EdgeRevocationClaimBO(EdgeRevocationIntentBO intent, @JsonIgnore String tokenValue) {
    @Override
    public String toString() {
        return "EdgeRevocationClaimBO[intentId=" + intent.intentId() + ", tokenValue=<redacted>]";
    }
}
