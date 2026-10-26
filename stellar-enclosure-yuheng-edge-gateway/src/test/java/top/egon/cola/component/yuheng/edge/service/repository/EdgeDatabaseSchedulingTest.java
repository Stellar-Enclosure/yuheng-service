package top.egon.cola.component.yuheng.edge.service.repository;

import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeDatabaseSchedulingTest {
    @Test
    void executesJdbcWorkOnTheOwnedBoundedSchedulerEvenForAnEventLoopSubscriber() {
        var scheduler = Schedulers.newBoundedElastic(1, 2, "edge-oauth-db-test");
        try {
            var database = new EdgeOAuthStateDatabase(mock(NamedParameterJdbcTemplate.class), mock(TransactionTemplate.class), scheduler);
            String thread = Mono.defer(() -> database.execute(() -> {
                database.jdbc();
                assertThat(Schedulers.isInNonBlockingThread()).isFalse();
                return Thread.currentThread().getName();
            })).subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(3));
            assertThat(thread).startsWith("edge-oauth-db-test-");
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void rejectsDirectJdbcAccessFromANonBlockingThread() {
        var scheduler = Schedulers.newBoundedElastic(1, 2, "edge-oauth-db-test");
        try {
            var database = new EdgeOAuthStateDatabase(mock(NamedParameterJdbcTemplate.class), mock(TransactionTemplate.class), scheduler);
            assertThatThrownBy(() -> Mono.fromCallable(database::jdbc).subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(3))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not run on the event loop");
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void databaseReadinessFailsClosedWithoutExposingConnectionFailureDetails() throws Exception {
        var scheduler = Schedulers.newBoundedElastic(1, 2, "edge-oauth-db-test");
        try {
            DataSource source = mock(DataSource.class);
            when(source.getConnection()).thenThrow(new SQLException("connection-value-must-not-escape"));
            var database = new EdgeOAuthStateDatabase(mock(NamedParameterJdbcTemplate.class), mock(TransactionTemplate.class), scheduler);
            var health = new EdgeOAuthStateConfiguration().edgeOAuthStateHealthIndicator(source, database)
                    .health().block(Duration.ofSeconds(3));
            assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
            assertThat(health.getDetails()).containsEntry("state", "DATABASE_UNAVAILABLE");
            assertThat(health.toString()).doesNotContain("connection-value-must-not-escape");
        } finally {
            scheduler.dispose();
        }
    }
}
