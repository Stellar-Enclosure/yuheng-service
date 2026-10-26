package top.egon.cola.component.yuheng.edge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher.Purpose;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher.SealedSecret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeOAuthStateCipherTest {
    @TempDir Path directory;

    @Test
    void sameSecretUsesFreshNonceAndCanOnlyBeOpenedForItsBoundPurposeAndTarget() throws Exception {
        var cipher = cipher("one", Map.of("one", key("one")));
        var first = cipher.seal("one-use-verifier", Purpose.PKCE_VERIFIER, "transaction-a/target-a");
        var second = cipher.seal("one-use-verifier", Purpose.PKCE_VERIFIER, "transaction-a/target-a");
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.open(first, Purpose.PKCE_VERIFIER, "transaction-a/target-a")).isEqualTo("one-use-verifier");
        assertThatThrownBy(() -> cipher.open(first, Purpose.REVOCATION, "transaction-a/target-a"))
                .isInstanceOf(EdgeOAuthStateException.class);
        assertThatThrownBy(() -> cipher.open(first, Purpose.PKCE_VERIFIER, "transaction-b/target-a"))
                .isInstanceOf(EdgeOAuthStateException.class);
        assertThat(first.toString()).doesNotContain("one-use-verifier");
    }

    @Test
    void tamperingAndMissingKeysFailWithoutReturningAnySecret() throws Exception {
        var key = key("one");
        var cipher = cipher("one", Map.of("one", key));
        var sealed = cipher.seal("revocation-only-value", Purpose.REVOCATION, "intent/target/digest");
        byte[] damaged = sealed.ciphertext();
        damaged[damaged.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.open(new SealedSecret("one", 1, damaged), Purpose.REVOCATION, "intent/target/digest"))
                .isInstanceOf(EdgeOAuthStateException.class).hasMessage("EDGE_STATE_SECRET_INVALID");
        assertThatThrownBy(() -> cipher.open(new SealedSecret("missing", 1, sealed.ciphertext()), Purpose.REVOCATION, "intent/target/digest"))
                .isInstanceOf(EdgeOAuthStateException.class).hasMessage("EDGE_STATE_SECRET_UNAVAILABLE");
        assertThat(cipher.open(sealed, Purpose.REVOCATION, "intent/target/digest")).isEqualTo("revocation-only-value");
    }

    @Test
    void rotationRetainsOnlyExplicitlyConfiguredOldKeys() throws Exception {
        Path old = key("old");
        Path next = key("next");
        var previous = cipher("old", Map.of("old", old));
        var value = previous.seal("pending-only", Purpose.OIDC_NONCE, "transaction/target");
        var rotated = cipher("next", Map.of("old", old, "next", next));
        assertThat(rotated.open(value, Purpose.OIDC_NONCE, "transaction/target")).isEqualTo("pending-only");
        assertThat(rotated.seal("new", Purpose.OIDC_NONCE, "transaction/target").keyId()).isEqualTo("next");
        assertThatThrownBy(() -> cipher("next", Map.of("next", next)).open(value, Purpose.OIDC_NONCE, "transaction/target"))
                .isInstanceOf(EdgeOAuthStateException.class);
    }

    @Test
    void unsafeKeyPermissionsAndUnboundedPlaintextAreRejected() throws Exception {
        Path key = key("key");
        var cipher = cipher("key", Map.of("key", key));
        assertThatThrownBy(() -> cipher.seal("界".repeat(3000), Purpose.REVOCATION, "intent/target"))
                .isInstanceOf(IllegalArgumentException.class);
        Files.setPosixFilePermissions(key, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
        assertThatThrownBy(() -> cipher("key", Map.of("key", key))).isInstanceOf(IllegalStateException.class);
    }

    private EdgeOAuthStateCipher cipher(String active, Map<String, Path> keys) {
        var properties = new EdgeOAuthStateProperties.Encryption();
        properties.setActiveKeyId(active);
        properties.setKeyFiles(keys);
        return new EdgeOAuthStateCipher(properties);
    }

    private Path key(String name) throws Exception {
        Path file = directory.resolve(name);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        Files.write(file, bytes);
        Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return file;
    }
}
