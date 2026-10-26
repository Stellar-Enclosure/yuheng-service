package top.egon.cola.component.yuheng.edge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeOAuthSchemaContractTest {
    @Test
    void frozenSingleMigrationContainsOnlyOwnedProtocolStateAndCannotSilentlyChange() throws Exception {
        try (var input = getClass().getResourceAsStream("/db/migration/V1__edge_oauth_state.sql")) {
            assertThat(input).isNotNull();
            byte[] bytes = input.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("f1510d62c4c2d2815014e207ad7aa2fc1b5b286e91767d72824da24ff2123869");
            String sql = new String(bytes, StandardCharsets.UTF_8);
            var tables = Pattern.compile("CREATE TABLE (\\w+)").matcher(sql).results().map(match -> match.group(1)).toList();
            assertThat(tables).containsExactly("edge_oauth_transaction", "edge_oauth_refresh_operation",
                    "edge_oauth_revocation_intent", "edge_oauth_revocation_receipt");
            assertThat(sql).contains("source_refresh_operation_id", "CONFIGURED_UPPER_BOUND", "upstream_attempts = 1",
                    "verifier_ciphertext IS NULL", "token_ciphertext IS NULL", "ON DELETE RESTRICT");
            assertThat(sql).doesNotContain("access_token BYTEA", "refresh_token BYTEA", "security_context",
                    "authorized_client", "CREATE EXTENSION", "DROP TABLE", "CASCADE");
        }
    }
}
