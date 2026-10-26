package top.egon.cola.component.yuheng.edge.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded PostgreSQL state and encryption for one-use OAuth operations. */
@Data
@Validated
@ConfigurationProperties(prefix = "egon.yuheng.edge.oauth-state")
public class EdgeOAuthStateProperties {
    @Valid @NotNull private Database database = new Database();
    @Valid @NotNull private Encryption encryption = new Encryption();
    @NotNull private Duration interactionLifetime = Duration.ofMinutes(5);
    @NotNull private Duration exchangeTimeout = Duration.ofSeconds(10);
    @NotNull private Duration operationLease = Duration.ofSeconds(20);
    @NotNull private Duration maximumAuthorizationLifetime = Duration.ofHours(8);
    @NotNull private Duration maximumAccessLifetime = Duration.ofMinutes(10);
    @NotNull private Duration clockSkew = Duration.ofSeconds(30);
    @NotNull private Duration diagnosticRetention = Duration.ofDays(7);
    @NotNull private Duration receiptRetention = Duration.ofDays(30);
    @NotNull private Duration recoveryInterval = Duration.ofSeconds(5);
    private int recoveryBatchSize = 100;

    @PostConstruct
    public void validate() {
        if (database == null || database.jdbcUrl == null || !database.jdbcUrl.startsWith("jdbc:postgresql:")
                || database.username == null || database.username.isBlank()
                || database.maximumPoolSize < 1 || database.maximumPoolSize > 32
                || database.queuedTasks < 1 || database.queuedTasks > 10_000
                || database.transactionTimeoutSeconds < 1 || database.transactionTimeoutSeconds > 30) {
            throw new IllegalStateException("EDGE OAuth PostgreSQL settings are invalid");
        }
        if (encryption == null || encryption.activeKeyId == null
                || !encryption.activeKeyId.matches("[A-Za-z0-9._-]{1,64}")
                || encryption.keyFiles == null || !encryption.keyFiles.containsKey(encryption.activeKeyId)
                || encryption.keyFiles.size() > 8
                || encryption.keyFiles.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || !entry.getKey().matches("[A-Za-z0-9._-]{1,64}") || entry.getValue() == null)) {
            throw new IllegalStateException("EDGE OAuth encryption keys are invalid");
        }
        if (!bounded(interactionLifetime, Duration.ofMinutes(5))
                || !bounded(exchangeTimeout, Duration.ofSeconds(30))
                || !bounded(operationLease, Duration.ofSeconds(60))
                || operationLease.compareTo(exchangeTimeout.plusSeconds(2)) <= 0
                || !bounded(maximumAuthorizationLifetime, Duration.ofHours(8))
                || !bounded(maximumAccessLifetime, Duration.ofMinutes(10))
                || !bounded(clockSkew, Duration.ofMinutes(2))
                || !bounded(diagnosticRetention, Duration.ofDays(30))
                || !bounded(receiptRetention, Duration.ofDays(180))
                || receiptRetention.compareTo(diagnosticRetention) < 0
                || !bounded(recoveryInterval, Duration.ofMinutes(1))
                || recoveryBatchSize < 1 || recoveryBatchSize > 1_000) {
            throw new IllegalStateException("EDGE OAuth state deadlines are invalid");
        }
    }

    private static boolean bounded(Duration value, Duration maximum) {
        return value != null && !value.isNegative() && !value.isZero() && value.compareTo(maximum) <= 0;
    }

    @Override
    public String toString() {
        return "EdgeOAuthStateProperties[database=<configured>, encryption=<configured>]";
    }

    @Data
    public static class Database {
        @NotBlank private String jdbcUrl;
        @NotBlank private String username;
        @NotNull private String password = "";
        private int maximumPoolSize = 8;
        private int queuedTasks = 200;
        private int transactionTimeoutSeconds = 5;

        @Override
        public String toString() {
            return "Database[jdbcUrl=<redacted>, username=<redacted>, password=<redacted>]";
        }
    }

    @Data
    public static class Encryption {
        @NotBlank private String activeKeyId;
        @NotEmpty private Map<String, Path> keyFiles = new LinkedHashMap<>();

        @Override
        public String toString() {
            return "Encryption[activeKeyId=" + activeKeyId + ", keyFiles=<redacted>]";
        }
    }
}
