package top.egon.cola.component.yuheng.edge.service.impl;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Base64;

public final class EdgeOAuthStateDigests {
    private EdgeOAuthStateDigests() {
    }

    public static String sha256(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Digest input is required");
        return HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String fields(String... values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            if (value == null) throw new IllegalArgumentException("Digest field is required");
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256Base64Url(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Digest input is required");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256().digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    public static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid digest");
    }

    public static boolean equal(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public static long lockKey(String... fields) {
        return ByteBuffer.wrap(HexFormat.of().parseHex(fields(fields))).getLong();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
