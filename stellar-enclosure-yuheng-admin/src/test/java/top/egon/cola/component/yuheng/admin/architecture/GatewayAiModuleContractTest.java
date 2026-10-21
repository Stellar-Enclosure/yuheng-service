package top.egon.cola.component.yuheng.admin.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixes the approved Yuheng AI delivery boundary: the standalone LLM gateway
 * module and the MyBatis-Plus coordinates every executable consumer needs must
 * exist, while the pure contract/core/runtime modules stay free of persistence
 * inheritance so the AI capability cannot leak into the shared data planes.
 */
class GatewayAiModuleContractTest {

    private static final String MYBATIS_PLUS_COORDINATE =
            "stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter";

    private static final List<String> MP_INHERITANCE_MARKERS = List.of(
            "com.baomidou",
            "top.egon.cola.component.common.mybatis",
            "EgonModel",
            "@TableName"
    );

    private static final List<String> PURE_MODULE_DIRECTORIES = List.of(
            "stellar-enclosure-yuheng-contract",
            "stellar-enclosure-yuheng-core",
            "stellar-enclosure-yuheng-runtime-core"
    );

    @Test
    void approvedLlmGatewayModuleAndManagedCoordinatesArePresent()
            throws IOException {
        Path reactor = llmGatewayReactor();
        List<String> missing = new ArrayList<>();

        require(missing, reactor.resolve("stellar-enclosure-yuheng-llm-gateway/pom.xml"));
        require(missing, reactor.resolve(
                "stellar-enclosure-yuheng-llm-gateway/src/main/java/top/egon/cola/component/"
                        + "yuheng/llm/bootstrap/LlmGatewayApplication.java"));

        String aggregator = read(reactor.resolve("pom.xml"));
        assertTrue(aggregator.contains("<module>stellar-enclosure-yuheng-llm-gateway</module>"),
                "Aggregator POM does not register the stellar-enclosure-yuheng-llm-gateway module");
        assertTrue(
                aggregator.contains(
                        "<artifactId>stellar-enclosure-yuheng-llm-gateway</artifactId>"),
                "Aggregator POM does not manage the stellar-enclosure-yuheng-llm-gateway version");

        for (String consumer : List.of(
                "stellar-enclosure-yuheng-admin/pom.xml",
                "stellar-enclosure-yuheng-mcp-gateway/pom.xml",
                "stellar-enclosure-yuheng-llm-gateway/pom.xml")) {
            Path consumerPom = reactor.resolve(consumer);
            require(missing, consumerPom);
            if (Files.exists(consumerPom)
                    && !read(consumerPom).contains(MYBATIS_PLUS_COORDINATE)) {
                missing.add(consumer + " (missing managed MP starter)");
            }
        }

        assertTrue(missing.isEmpty(),
                "Approved AI module boundary is incomplete: " + missing);
    }

    @Test
    void pureModulesRemainFreeOfMybatisPlusInheritance() throws IOException {
        Path reactor = llmGatewayReactor();
        List<String> offenders = new ArrayList<>();
        for (String module : PURE_MODULE_DIRECTORIES) {
            Path sourceRoot = reactor.resolve(module + "/src/main/java");
            assertTrue(Files.isDirectory(sourceRoot),
                    "Pure module source root disappeared: " + module);
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            String source = read(path);
                            if (MP_INHERITANCE_MARKERS.stream()
                                    .anyMatch(source::contains)) {
                                offenders.add(path.toString());
                            }
                        });
            }
            String pom = read(reactor.resolve(module + "/pom.xml"));
            if (pom.contains(MYBATIS_PLUS_COORDINATE)) {
                offenders.add(module + "/pom.xml");
            }
        }
        assertTrue(offenders.isEmpty(),
                "MyBatis-Plus leaked into a pure module: " + offenders);
    }

    /** Surefire runs from the {@code stellar-enclosure-yuheng-admin} module directory. */
    private static Path llmGatewayReactor() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    private static void require(List<String> missing, Path path) {
        if (!Files.exists(path)) {
            missing.add(path.toString());
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot inspect Yuheng AI boundary file " + path,
                    failure
            );
        }
    }
}
