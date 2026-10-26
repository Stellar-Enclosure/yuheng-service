package top.egon.cola.component.yuheng.edge.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeOAuthStateDatabase;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EdgeOAuthStateProperties.class)
@EnableScheduling
public class EdgeOAuthStateConfiguration {
    @Bean(name = "edgeOAuthDataSource", destroyMethod = "close")
    public HikariDataSource edgeOAuthDataSource(EdgeOAuthStateProperties properties) {
        var settings = properties.getDatabase();
        HikariConfig config = new HikariConfig();
        config.setPoolName("edge-oauth-state");
        config.setJdbcUrl(settings.getJdbcUrl());
        config.setUsername(settings.getUsername());
        config.setPassword(settings.getPassword());
        config.setMaximumPoolSize(settings.getMaximumPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(settings.getTransactionTimeoutSeconds() * 1000L);
        config.setValidationTimeout(Math.min(settings.getTransactionTimeoutSeconds() * 1000L, 3000L));
        config.setInitializationFailTimeout(1);
        return new HikariDataSource(config);
    }

    @Bean(name = "edgeOAuthFlyway", initMethod = "migrate")
    public Flyway edgeOAuthFlyway(@Qualifier("edgeOAuthDataSource") DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .table("flyway_edge_oauth_history").baselineOnMigrate(false)
                .cleanDisabled(true).validateOnMigrate(true).connectRetries(0).load();
    }

    @Bean(name = "edgeOAuthDatabaseScheduler", destroyMethod = "dispose")
    public Scheduler edgeOAuthDatabaseScheduler(EdgeOAuthStateProperties properties) {
        return Schedulers.newBoundedElastic(properties.getDatabase().getMaximumPoolSize(),
                properties.getDatabase().getQueuedTasks(), "edge-oauth-db", 60, true);
    }

    @Bean
    @DependsOn("edgeOAuthFlyway")
    public EdgeOAuthStateDatabase edgeOAuthStateDatabase(
            @Qualifier("edgeOAuthDataSource") DataSource dataSource,
            @Qualifier("edgeOAuthDatabaseScheduler") Scheduler scheduler, EdgeOAuthStateProperties properties) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(properties.getDatabase().getTransactionTimeoutSeconds());
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setTimeout(properties.getDatabase().getTransactionTimeoutSeconds());
        return new EdgeOAuthStateDatabase(new NamedParameterJdbcTemplate(jdbc), transactions, scheduler);
    }

    @Bean
    public EdgeOAuthStateCipher edgeOAuthStateCipher(EdgeOAuthStateProperties properties) {
        return new EdgeOAuthStateCipher(properties.getEncryption());
    }

    @Bean(name = "edgeOAuthStateHealthIndicator")
    public ReactiveHealthIndicator edgeOAuthStateHealthIndicator(
            @Qualifier("edgeOAuthDataSource") DataSource dataSource, EdgeOAuthStateDatabase database) {
        return () -> database.execute(() -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("""
                         select 1 from edge_oauth_transaction where false
                         union all select 1 from edge_oauth_refresh_operation where false
                         union all select 1 from edge_oauth_revocation_intent where false
                         union all select 1 from edge_oauth_revocation_receipt where false
                         """)) {
                statement.setQueryTimeout(3);
                try (var ignored = statement.executeQuery()) {
                    return Health.up().build();
                }
            }
        }).onErrorReturn(Health.down().withDetail("state", "DATABASE_UNAVAILABLE").build());
    }
}
