package top.egon.cola.component.yuheng.test.process;

public final class GatewayTestInfrastructure implements AutoCloseable {

    private static final String INFRASTRUCTURE_PROPERTY =
            "yuheng.live.infrastructure";

    private final GatewayInfrastructureBackend backend;

    public GatewayTestInfrastructure() {
        this(createBackend(System.getProperty(
                INFRASTRUCTURE_PROPERTY,
                "testcontainers"
        )));
    }

    GatewayTestInfrastructure(GatewayInfrastructureBackend backend) {
        this.backend = java.util.Objects.requireNonNull(
                backend,
                "backend"
        );
    }

    public String type() {
        return backend.type();
    }

    public void start() {
        backend.start();
    }

    public void createDatabase(String database) {
        backend.createDatabase(database);
    }

    public String jdbcUrl(String database) {
        return backend.jdbcUrl(database);
    }

    public String postgresUsername() {
        return backend.postgresUsername();
    }

    public String postgresPassword() {
        return backend.postgresPassword();
    }

    public String accessGuardRedisHost() {
        return backend.accessGuardRedisHost();
    }

    public int accessGuardRedisPort() {
        return backend.accessGuardRedisPort();
    }

    public String kafkaBootstrapServers() {
        return backend.kafkaBootstrapServers();
    }

    @Override
    public void close() {
        backend.close();
    }

    private static GatewayInfrastructureBackend createBackend(String type) {
        return switch (type.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "testcontainers" ->
                    new GatewayTestcontainersInfrastructure();
            case "local" -> new GatewayLocalInfrastructure();
            default -> throw new IllegalArgumentException(
                    INFRASTRUCTURE_PROPERTY
                            + " must be testcontainers or local"
            );
        };
    }
}
