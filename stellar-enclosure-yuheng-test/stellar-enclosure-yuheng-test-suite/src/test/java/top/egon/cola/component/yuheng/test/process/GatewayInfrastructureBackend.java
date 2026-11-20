package top.egon.cola.component.yuheng.test.process;

interface GatewayInfrastructureBackend extends AutoCloseable {

    String type();

    void start();

    void createDatabase(String database);

    String jdbcUrl(String database);

    String postgresUsername();

    String postgresPassword();

    String accessGuardRedisHost();

    int accessGuardRedisPort();

    String kafkaBootstrapServers();

    @Override
    void close();
}
