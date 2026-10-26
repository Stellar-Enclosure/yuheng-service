package top.egon.cola.component.yuheng.edge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeCsrfProofService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeCsrfProofServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    @TempDir Path directory;
    private EdgeOAuthStateCipher cipher;
    private EdgeCsrfProofService proofs;

    @BeforeEach
    void signingKey() throws Exception {
        Path file = directory.resolve("csrf-key");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.write(file, key);
        Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        var encryption = new EdgeOAuthStateProperties.Encryption();
        encryption.setActiveKeyId("csrf.v1");
        encryption.setKeyFiles(Map.of("csrf.v1", file));
        cipher = new EdgeOAuthStateCipher(encryption);
        proofs = new EdgeCsrfProofService(cipher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void requiresMatchingCookieHeaderAndCurrentAuthorizationBinding() {
        String context = EdgeOAuthStateDigests.sha256("application-client-family-subject");
        var proof = proofs.issue(context, NOW.plusSeconds(3600));
        assertThatCode(() -> proofs.verify(proof.token(), proof.token(), context)).doesNotThrowAnyException();
        assertThatThrownBy(() -> proofs.verify(null, proof.token(), context)).hasMessage("EDGE_CSRF_INVALID");
        assertThatThrownBy(() -> proofs.verify(proof.token(), "different", context)).hasMessage("EDGE_CSRF_INVALID");
        assertThatThrownBy(() -> proofs.verify(proof.token(), proof.token(), EdgeOAuthStateDigests.sha256("another-family")))
                .hasMessage("EDGE_CSRF_INVALID");
        assertThat(proof.toString()).doesNotContain(proof.token());
    }

    @Test
    void randomProofsAreBoundedByTheAbsoluteAuthorizationDeadline() {
        String context = EdgeOAuthStateDigests.sha256("context");
        var first = proofs.issue(context, NOW.plusSeconds(90));
        var second = proofs.issue(context, NOW.plusSeconds(90));
        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(first.expiresAt()).isEqualTo(NOW.plusSeconds(90));
        var expired = new EdgeCsrfProofService(cipher, Clock.fixed(NOW.plusSeconds(90), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.verify(first.token(), first.token(), context)).hasMessage("EDGE_CSRF_INVALID");
        assertThatThrownBy(() -> proofs.issue(context, NOW)).hasMessage("EDGE_CSRF_CONTEXT_EXPIRED");
    }

    @Test
    void modifiedTimesAndUnknownKeysCannotExtendProofValidity() {
        String context = EdgeOAuthStateDigests.sha256("context");
        var proof = proofs.issue(context, NOW.plus(Duration.ofHours(8)));
        String modified = proof.token().replace("~" + NOW.plusSeconds(300).getEpochSecond() + "~",
                "~" + NOW.plusSeconds(301).getEpochSecond() + "~");
        assertThatThrownBy(() -> proofs.verify(modified, modified, context)).hasMessage("EDGE_CSRF_INVALID");
        String unknown = proof.token().replace("~csrf.v1~", "~removed~");
        assertThatThrownBy(() -> proofs.verify(unknown, unknown, context)).hasMessage("EDGE_CSRF_INVALID");
    }
}
