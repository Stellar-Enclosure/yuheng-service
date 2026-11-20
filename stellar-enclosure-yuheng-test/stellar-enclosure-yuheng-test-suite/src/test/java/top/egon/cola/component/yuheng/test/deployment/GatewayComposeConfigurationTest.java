package top.egon.cola.component.yuheng.test.deployment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayComposeConfigurationTest {

    private static final String MODEL_ACCESS = "stellar-enclosure-model-access-service";

    @Test
    void baseProvidesOnlyLocalDatastores() throws IOException {
        var services = services("compose.yml");

        assertThat(services.keySet()).containsExactlyInAnyOrder("postgres", "access-guard-redis", "kafka");
        assertThat(mapping(compose("compose.yml").get("secrets"))).containsOnlyKeys("postgres-password");
        assertThat(mapping(mapping(services.get("postgres")).get("environment")))
                .containsEntry("POSTGRES_PASSWORD_FILE", "/run/secrets/postgres-password")
                .doesNotContainKey("POSTGRES_PASSWORD");
        assertThat(mapping(mapping(services.get("kafka")).get("environment")))
                .containsEntry("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:9092");
        assertThat(Files.readString(repository().resolve("deployment/postgres/init/01-create-databases.sql")))
                .contains("gateway_admin", "CREATE EXTENSION IF NOT EXISTS vector")
                .doesNotContain("gateway_ddc");
    }

    @Test
    void overlaysCannotPublishApplicationOrManagementPorts() throws IOException {
        for (String file : new String[]{"compose.yml", "compose.demo.yml", "compose.ha.yml", "compose.llm.yml"}) {
            services(file).forEach((name, value) -> {
                var service = mapping(value);
                assertThat(service).as("%s service %s", file, name)
                        .doesNotContainKeys("ports", "network_mode", "container_name", "privileged");
                assertThat(mapping(service.getOrDefault("environment", Map.of())).keySet())
                        .noneMatch(key -> key.contains("REGISTRY_") || key.contains("TIANSHU_RPC")
                                || key.contains("CERTIFICATE_CHAIN") || key.contains("TLS_RELOAD"));
            });
        }
        for (String file : new String[]{"compose.mtls.yml", "compose.ha-mtls.yml", "haproxy.cfg",
                "haproxy.data-plane.cfg", "haproxy.data-plane.mtls.cfg", "tianshu-rpc-credentials.yml"}) {
            assertThat(repository().resolve("deployment").resolve(file)).doesNotExist();
        }
    }

    @Test
    void directFixtureImagesCopyExecutableArtifactsFromTheCurrentReactor() throws IOException {
        for (String file : new String[]{"compose.demo.yml", "compose.llm.yml"}) {
            var services = services(file);
            assertThat(services).isNotEmpty();
            for (var value : services.values()) {
                var build = mapping(mapping(value).get("build"));
                var context = repository().resolve("deployment").resolve((String) build.get("context")).normalize();
                assertThat(context.resolve((String) build.get("dockerfile"))).isRegularFile();
                var artifact = context.resolve((String) mapping(build.get("args")).get("APP_JAR"));
                assertThat(artifact.getFileName().toString()).endsWith("-exec.jar");
                assertThat(artifact.getParent().getParent().resolve("pom.xml")).isRegularFile();
            }
        }
    }

    @Test
    void modelOverlayUsesTheIndependentAccessService() throws IOException {
        var services = services("compose.llm.yml");
        assertThat(services.keySet()).containsExactly(MODEL_ACCESS);
        var model = mapping(services.get(MODEL_ACCESS));
        assertThat(mapping(model.get("environment")))
                .containsEntry("YUHENG_LLM_PORT", 8080)
                .containsEntry("YUHENG_LLM_MANAGEMENT_PORT", 8081)
                .containsEntry("SPRING_CONFIG_ADDITIONAL_LOCATION", "file:/run/egon-config/");
        assertThat(mapping(model.get("depends_on"))).containsOnlyKeys("postgres");
    }

    private static Map<String, Object> services(String file) throws IOException {
        return mapping(compose(file).get("services"));
    }

    private static Map<String, Object> compose(String file) throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = Files.newInputStream(repository().resolve("deployment").resolve(file))) {
            return new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("deployment/compose.yml"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Yuheng repository root not found");
    }
}
