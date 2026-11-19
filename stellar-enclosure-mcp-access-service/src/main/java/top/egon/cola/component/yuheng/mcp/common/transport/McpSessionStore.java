package top.egon.cola.component.yuheng.mcp.common.transport;

import org.reactivestreams.Publisher;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Shared MCP session binding. Credentials and request bodies must never be stored. */
public interface McpSessionStore {
    Publisher<Void> create(Session session, Duration ttl);
    Publisher<Session> find(String sessionId);
    Publisher<Void> touch(String sessionId, Duration ttl);
    Publisher<Boolean> delete(String sessionId);

    /** The catalog identity is persisted, rather than inferred from whichever publication is active later. */
    record Session(String sessionId, String serverCode, String subjectId, String tenantId, String clientId,
            Instant createdAt, String snapshotIdentity, String issuer, String resource) {
        /** Retains pure protocol test and historical decoding compatibility; formal admission rejects unpinned records. */
        public Session(String sessionId, String serverCode, String subjectId, String tenantId, String clientId, Instant createdAt) {
            this(sessionId, serverCode, subjectId, tenantId, clientId, createdAt, null, null, null);
        }
        public Session {
            sessionId = required(sessionId); serverCode = required(serverCode); subjectId = required(subjectId);
            tenantId = required(tenantId); clientId = required(clientId); createdAt = Objects.requireNonNull(createdAt, "createdAt");
            if (snapshotIdentity != null || issuer != null || resource != null) {
                if (snapshotIdentity == null || snapshotIdentity.isBlank() || !snapshotIdentity.equals(snapshotIdentity.strip())
                        || snapshotIdentity.length() > 2048 || snapshotIdentity.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
                    throw new IllegalArgumentException("MCP session snapshot identity is invalid");
                }
                canonical(issuer); canonical(resource);
            }
        }
        public boolean pinned() { return snapshotIdentity != null && issuer != null && resource != null; }
        private static String required(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("MCP session identity is required");
            return value.trim();
        }
        private static void canonical(String value) {
            if (value == null || value.length() > 2048) throw new IllegalArgumentException("MCP session authority binding is invalid");
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.equals(uri.normalize())
                    || !value.equals(uri.toASCIIString())) throw new IllegalArgumentException("MCP session authority binding is invalid");
        }
    }
}
