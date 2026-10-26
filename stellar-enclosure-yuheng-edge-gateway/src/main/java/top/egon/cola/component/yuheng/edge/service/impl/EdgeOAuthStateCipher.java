package top.egon.cola.component.yuheng.edge.service.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;

/** Purpose-bound encryption only for pending PKCE/nonce and bounded revoke delivery. */
public final class EdgeOAuthStateCipher {
    public enum Purpose { PKCE_VERIFIER, OIDC_NONCE, REVOCATION }

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final String activeKeyId;
    private final Map<String, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    public EdgeOAuthStateCipher(EdgeOAuthStateProperties.Encryption properties) {
        this.activeKeyId = properties.getActiveKeyId();
        Map<String, SecretKey> loaded = new LinkedHashMap<>();
        properties.getKeyFiles().forEach((keyId, file) -> {
            try {
                var permissions = Files.getPosixFilePermissions(file);
                if (!permissions.contains(PosixFilePermission.OWNER_READ)
                        || permissions.stream().anyMatch(value -> value != PosixFilePermission.OWNER_READ
                            && value != PosixFilePermission.OWNER_WRITE)) {
                    throw new IllegalStateException("EDGE OAuth key must be owner-only");
                }
                byte[] bytes = Files.readAllBytes(file);
                try {
                    if (bytes.length != 32) throw new IllegalStateException("EDGE OAuth key must have 256 bits");
                    loaded.put(keyId, new SecretKeySpec(bytes, "AES"));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            } catch (IOException failure) {
                throw new IllegalStateException("EDGE OAuth encryption key is unavailable", failure);
            }
        });
        if (!loaded.containsKey(activeKeyId)) throw new IllegalStateException("EDGE OAuth active key is unavailable");
        this.keys = Map.copyOf(loaded);
    }

    public SealedSecret seal(String plaintext, Purpose purpose, String context) {
        if (plaintext == null || plaintext.isBlank() || plaintext.length() > 8192
                || purpose == null || context == null || context.isBlank()) {
            throw new IllegalArgumentException("EDGE OAuth secret and binding are required");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length > 8192) throw new IllegalArgumentException("EDGE OAuth secret exceeds the bounded envelope");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(activeKeyId, purpose, context));
            byte[] encrypted = cipher.doFinal(bytes);
            return new SealedSecret(activeKeyId, 1,
                    ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("EDGE OAuth encryption failed", failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public String open(SealedSecret sealed, Purpose purpose, String context) {
        if (sealed == null || purpose == null || context == null || context.isBlank()
                || sealed.version() != 1 || !keys.containsKey(sealed.keyId())) {
            throw new EdgeOAuthStateException("EDGE_STATE_SECRET_UNAVAILABLE");
        }
        byte[] envelope = sealed.ciphertext();
        if (envelope.length <= NONCE_BYTES + TAG_BITS / 8 || envelope.length > 16384) {
            throw new EdgeOAuthStateException("EDGE_STATE_SECRET_INVALID");
        }
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(sealed.keyId()),
                    new GCMParameterSpec(TAG_BITS, envelope, 0, NONCE_BYTES));
            cipher.updateAAD(aad(sealed.keyId(), purpose, context));
            plaintext = cipher.doFinal(envelope, NONCE_BYTES, envelope.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            throw new EdgeOAuthStateException("EDGE_STATE_SECRET_INVALID");
        } finally {
            Arrays.fill(envelope, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static byte[] aad(String keyId, Purpose purpose, String context) {
        return EdgeOAuthStateDigests.fields("edge-oauth-state-v1", keyId, purpose.name(), context)
                .getBytes(StandardCharsets.US_ASCII);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public byte[] signCsrf(String keyId, String canonicalPayload) {
        return mac(keyId, "edge-csrf-signing-key-v1", canonicalPayload);
    }

    public String auditReference(String issuer, String tokenId) {
        return java.util.HexFormat.of().formatHex(mac(activeKeyId, "edge-audit-reference-key-v1",
                EdgeOAuthStateDigests.fields(issuer, tokenId)));
    }

    private byte[] mac(String keyId, String purpose, String canonicalPayload) {
        if (canonicalPayload == null || canonicalPayload.length() > 2048 || !keys.containsKey(keyId)) {
            throw new EdgeOAuthStateException("EDGE_CSRF_KEY_UNAVAILABLE");
        }
        byte[] derived = null;
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(keys.get(keyId).getEncoded(), "HmacSHA256"));
            derived = derivation.doFinal(purpose.getBytes(StandardCharsets.US_ASCII));
            Mac signature = Mac.getInstance("HmacSHA256");
            signature.init(new SecretKeySpec(derived, "HmacSHA256"));
            return signature.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("EDGE CSRF proof signing is unavailable", failure);
        } finally {
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }

    public record SealedSecret(String keyId, int version, byte[] ciphertext) {
        public SealedSecret {
            if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}") || ciphertext == null) {
                throw new IllegalArgumentException("Invalid EDGE OAuth secret envelope");
            }
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() { return ciphertext.clone(); }

        @Override
        public String toString() { return "SealedSecret[keyId=" + keyId + ", ciphertext=<redacted>]"; }
    }
}
