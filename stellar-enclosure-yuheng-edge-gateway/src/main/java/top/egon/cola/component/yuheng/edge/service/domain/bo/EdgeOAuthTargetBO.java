package top.egon.cola.component.yuheng.edge.service.domain.bo;

import java.net.URI;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;

/** Fixed deployment target; browser supplied resource/issuer values never construct this object. */
public record EdgeOAuthTargetBO(String appAlias, String registrationId, String issuer,
                                String tenantId, String clientId, String resource) {
    public EdgeOAuthTargetBO {
        if (appAlias == null || !appAlias.matches("[A-Za-z0-9_-]{1,128}")
                || registrationId == null || !registrationId.matches("[A-Za-z0-9_-]{1,128}")
                || tenantId == null || !tenantId.matches("(?:PLATFORM|[1-9][0-9]{0,18})")
                || clientId == null || clientId.isBlank() || clientId.length() > 256) {
            throw new IllegalArgumentException("Invalid EDGE OAuth target");
        }
        requireCanonicalHttps(issuer);
        requireCanonicalHttps(resource);
    }

    public String digest() {
        return EdgeOAuthStateDigests.fields("edge-oauth-target-v1", appAlias, registrationId,
                issuer, tenantId, clientId, resource);
    }

    public static void requireCanonicalHttps(String value) {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException("Invalid OAuth URI");
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !uri.equals(uri.normalize())
                || !value.equals(uri.toASCIIString()) || value.contains("%")) {
            throw new IllegalArgumentException("Invalid OAuth URI");
        }
    }
}
