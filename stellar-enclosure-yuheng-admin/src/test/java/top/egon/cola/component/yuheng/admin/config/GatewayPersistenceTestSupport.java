package top.egon.cola.component.yuheng.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlManifestBO;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryResult;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.runner.ContextConsumer;

public final class GatewayPersistenceTestSupport {
    public static final String ATOMIC_URL = "yuheng.outbox.test.postgres.url";
    public static final String ATOMIC_USER = "yuheng.outbox.test.postgres.user";

    private GatewayPersistenceTestSupport() {
    }

    public static GatewayPersistenceContextComponent persistenceContext() {
        GatewayPersistenceProperties properties = new GatewayPersistenceProperties()
                .setTenantId(7001L)
                .setIdentityTenantId(9001L)
                .setServiceUserId("scheduled-test-service");
        return new GatewayPersistenceContextComponent(properties, new EgonColaMybatisPlusProperties());
    }

    /** A fresh, caller-owned loopback database; this helper neither drops nor resets any schema. */
    public static void withManagedOutbox(ContextConsumer<AssertableApplicationContext> assertions) throws Exception {
        String url = System.getProperty(ATOMIC_URL);
        String user = System.getProperty(ATOMIC_USER);
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/yuheng_it_atomic_[a-z0-9_]{1,30}")
                || user == null || user.isBlank()) {
            throw new IllegalArgumentException("Expected an explicit isolated loopback yuheng_it_atomic_ database and user");
        }
        var physical = new JdbcTemplate(new DriverManagerDataSource(url, user, ""));
        if (physical.queryForObject("SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname='public'", Long.class) != 0L
                || physical.queryForObject("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname='egon_outbox'", Long.class) != 0L) {
            throw new IllegalStateException("Expected a fresh empty atomic-test database");
        }
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        EgonColaDdlManifestBO manifest;
        try (var input = new ClassPathResource("db/egon-mp/repository-manifest.json").getInputStream()) {
            manifest = mapper.readValue(input, EgonColaDdlManifestBO.class);
        }
        var head = manifest.scripts().getLast();
        List<String> properties = new ArrayList<>(List.of(
                "egon.cola.component.id.machine-id=0",
                "egon.cola.component.mybatis-plus.ddl.enabled=true",
                "egon.cola.component.mybatis-plus.ddl.auto-migrate=false",
                "egon.cola.component.mybatis-plus.tenant-id.ignored-tables[0]=egon_cola_outbox_message",
                "egon.cola.component.mybatis-plus.sharding.enabled=true",
                "egon.cola.component.mybatis-plus.sharding.mode=SHARDING",
                "egon.cola.component.mybatis-plus.sharding.config-style=STRATEGY",
                "egon.cola.component.mybatis-plus.sharding.transaction-default-type=LOCAL",
                "egon.cola.component.mybatis-plus.sharding.data-sources[0].name=primary",
                "egon.cola.component.mybatis-plus.sharding.data-sources[0].logical-name=primary",
                "egon.cola.component.mybatis-plus.sharding.data-sources[0].role=PRIMARY",
                "egon.cola.component.mybatis-plus.sharding.data-sources[0].driver-class-name=org.postgresql.Driver",
                "egon.cola.component.mybatis-plus.sharding.data-sources[0].jdbc-url=" + url,
                "egon.cola.component.mybatis-plus.sharding.data-sources[0].username=" + user,
                "egon.cola.component.mybatis-plus.sharding.data-sources[0].password=",
                "mybatis-plus.mapper-locations=classpath*:mapper/outbox/OutboxMessageMapper.xml",
                "yuheng.persistence.tenant-id=7001", "yuheng.persistence.identity-tenant-id=9001",
                "yuheng.persistence.service-user-id=scheduled-test-service", "yuheng.persistence.managed-ddl-enabled=true",
                "yuheng.persistence.expected-schema-version=" + head.version(),
                "yuheng.persistence.expected-schema-sha256=" + head.sha256(),
                "egon.cola.component.transactional-outbox.enabled=true",
                "egon.cola.component.transactional-outbox.node-id=yuheng-atomic-test",
                "egon.cola.component.transactional-outbox.annotation.enabled=false",
                "egon.cola.component.transactional-outbox.polling.enabled=false",
                "egon.cola.component.transactional-outbox.storage.type=MYBATIS_PLUS",
                "egon.cola.component.transactional-outbox.storage.data-source-bean-name=dataSource",
                "egon.cola.component.transactional-outbox.storage.transaction-manager-bean-name=gatewayTransactionManager",
                "egon.cola.component.transactional-outbox.storage.validate-schema=true",
                "egon.cola.component.transactional-outbox.storage.mp.auto-initialize-ddl=true",
                "egon.cola.component.cache.enabled=false"));
        for (String table : List.of("gateway_group", "gateway_release", "gateway_release_content", "gateway_release_attempt", "gateway_idempotency_record", "gateway_audit_log")) {
            properties.add("egon.cola.component.mybatis-plus.sharding.tables." + table + ".type=STANDARD_TENANT_ID");
            properties.add("egon.cola.component.mybatis-plus.sharding.tables." + table + ".sharding-column=tenant_id");
        }
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                        top.egon.cola.component.common.id.autoconfigure.IdGeneratorAutoConfiguration.class,
                        org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration.class,
                        top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusAutoConfiguration.class,
                        top.egon.cola.component.common.mybatis.autoconfigure.EgonColaShardingAutoConfiguration.class,
                        com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class,
                        top.egon.cola.component.outbox.autoconfigure.OutboxMybatisPlusAutoConfiguration.class,
                        top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxAutoConfiguration.class,
                        top.egon.cola.component.outbox.autoconfigure.OutboxStateMachineAutoConfiguration.class))
                .withUserConfiguration(GatewayPersistenceConfiguration.class, GatewayManagedDdlConfiguration.class)
                .withBean("jacksonObjectMapper", ObjectMapper.class, () -> mapper)
                .withBean("atomicProofHandler", DeliveryHandler.class, () -> new DeliveryHandler() {
                    @Override public String channel() { return "yuheng-exposure"; }
                    @Override public void validateDestination(String destination) {
                        if (!"exposure-desired-state-changed".equals(destination)) { throw new IllegalArgumentException("Unexpected destination"); }
                    }
                    @Override public DeliveryResult deliver(DeliveryContext context) { return DeliveryResult.success(); }
                })
                .withPropertyValues(properties.toArray(String[]::new))
                .run(assertions);
    }
}
