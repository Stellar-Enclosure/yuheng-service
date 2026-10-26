package top.egon.cola.component.yuheng.edge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeOAuthStatePropertiesTest {
    @Test
    void requiresPostgresqlAndBoundedIndependentWorkerCapacity() {
        var settings = settings();
        assertThatCode(settings::validate).doesNotThrowAnyException();
        settings.getDatabase().setJdbcUrl("jdbc:h2:mem:test");
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
        settings.getDatabase().setJdbcUrl("jdbc:postgresql://unused/edge");
        settings.getDatabase().setQueuedTasks(0);
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void leaseMustCoverTheSingleExchangeAndSecretsAreRedacted() {
        var settings = settings();
        settings.setOperationLease(settings.getExchangeTimeout().plusSeconds(2));
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
        settings.setOperationLease(Duration.ofSeconds(20));
        settings.setReceiptRetention(Duration.ofDays(1));
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
        assertThat(settings.toString()).doesNotContain("password-value", "unused");
        assertThat(settings.getDatabase().toString()).doesNotContain("password-value", "unused");
    }

    private static EdgeOAuthStateProperties settings() {
        var settings = new EdgeOAuthStateProperties();
        settings.getDatabase().setJdbcUrl("jdbc:postgresql://unused/edge");
        settings.getDatabase().setUsername("edge");
        settings.getDatabase().setPassword("password-value");
        settings.getEncryption().setActiveKeyId("v1");
        settings.getEncryption().setKeyFiles(Map.of("v1", Path.of("not-opened-in-this-test")));
        return settings;
    }
}
