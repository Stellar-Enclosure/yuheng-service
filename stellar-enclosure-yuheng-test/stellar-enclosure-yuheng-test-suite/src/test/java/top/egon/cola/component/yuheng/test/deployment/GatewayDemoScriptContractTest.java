package top.egon.cola.component.yuheng.test.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayDemoScriptContractTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void helpDescribesFixtureCommandsAndExplicitPublicProbes() throws Exception {
        var result = run("demo.sh", Map.of(), "--help");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("config", "up", "down", "verify", "exposure-get", "exposure-state")
                .doesNotContain("up-control", "purge");
    }

    @Test
    void rejectsCleartextAndEmbeddedCredentialsBeforeCallingCurl() throws Exception {
        installCurlStub();
        for (String url : List.of("http://gateway.example.test/test/demo", "https://user@gateway.example.test/")) {
            var result = run("demo.sh", Map.of(), "verify", url, "200");
            assertThat(result.code()).isEqualTo(2);
            assertThat(temporaryDirectory.resolve("curl-called")).doesNotExist();
        }
    }

    @Test
    void verifiesExactPublicResponseUsingTheSuppliedBearer() throws Exception {
        installCurlStub();
        Path token = Files.writeString(temporaryDirectory.resolve("token"), "example.service.token\n");

        var result = run("demo.sh", Map.of("YUHENG_DEMO_BEARER_FILE", token.toString()),
                "verify", "https://gateway.example.test/api/providers/demo", "200");

        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("200").doesNotContain("example.service.token");
        assertThat(Files.readString(temporaryDirectory.resolve("curl-called")))
                .contains("https://gateway.example.test/api/providers/demo")
                .doesNotContain("example.service.token", "127.0.0.1", "Host:");
    }

    @Test
    void doesNotForgeOrAcceptAHeaderFromASecretFile() throws Exception {
        Path token = Files.writeString(temporaryDirectory.resolve("token"), "sample\nx-other: injected\n");
        var result = run("demo-token.sh", Map.of(), token.toString());
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.output()).doesNotContain("sample", "injected");
    }

    @Test
    void requiresAnExactExposurePreconditionBeforeCallingTheManagementApi() throws Exception {
        installCurlStub();
        Path command = Files.writeString(temporaryDirectory.resolve("close.json"),
                "{\"state\":\"CLOSED\",\"changeReason\":\"local contract check\"}");
        var result = run("demo.sh", Map.of(),
                "exposure-state", "http://yuheng-admin.fixture.svc.cluster.local:18080", "123", "\"exposure-456-v4\"", "check-1", command.toString());
        assertThat(result.code()).isEqualTo(2);
        assertThat(temporaryDirectory.resolve("curl-called")).doesNotExist();
    }

    @Test
    void writesTheReviewedStateWithStrongEtagAndIdempotencyKey() throws Exception {
        installCurlStub();
        Path token = Files.writeString(temporaryDirectory.resolve("token"), "example.service.token\n");
        Path command = Files.writeString(temporaryDirectory.resolve("close.json"),
                "{\"state\":\"CLOSED\",\"changeReason\":\"local contract check\"}");

        var result = run("demo.sh", Map.of("YUHENG_DEMO_BEARER_FILE", token.toString()),
                "exposure-state", "http://yuheng-admin.fixture.svc.cluster.local:18080/", "123", "\"exposure-123-v4\"", "check-2",
                command.toString());

        assertThat(result.code()).isZero();
        assertThat(Files.readAllLines(temporaryDirectory.resolve("curl-called")))
                .containsSubsequence("--noproxy", "*", "--proto", "=http", "--url", "http://yuheng-admin.fixture.svc.cluster.local:18080/api/v1/yuheng/admin/exposures/123/state",
                        "--request", "PUT", "--header", "If-Match: \"exposure-123-v4\"",
                        "--header", "Idempotency-Key: check-2", "--header", "Content-Type: application/json",
                        "--data-binary", "@" + command)
                .noneMatch(argument -> argument.contains("example.service.token"));
    }

    @Test
    void withholdsManagementBearerFromOtherOrigins() throws Exception {
        installCurlStub();
        Path token = Files.writeString(temporaryDirectory.resolve("token"), "example.service.token\n");
        for (String origin : List.of("https://admin.example.test", "http://admin.example.test:18080",
                "http://yuheng-admin.fixture.svc.cluster.local:19099", "http://yuheng-admin.fixture.svc.cluster.local:65536")) {
            var result = run("demo.sh", Map.of("YUHENG_DEMO_BEARER_FILE", token.toString()), "exposure-get", origin, "123");
            assertThat(result.code()).isEqualTo(2);
            assertThat(temporaryDirectory.resolve("curl-called")).doesNotExist();
        }
    }

    private void installCurlStub() throws IOException {
        Path executable = Files.writeString(temporaryDirectory.resolve("curl"), """
                #!/usr/bin/env bash
                set -eu
                printf '%s\\n' "$@" > "$DEMO_CURL_LOG"
                IFS= read -r header
                [[ "$header" == "Authorization: Bearer example.service.token" ]]
                printf '200'
                """);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
    }

    private Result run(String script, Map<String, String> environment, String... arguments) throws Exception {
        Path deployment = deploymentDirectory();
        var command = new ArrayList<>(List.of("bash", deployment.resolve("scripts").resolve(script).toString()));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("YUHENG_DEMO_BEARER_FILE");
        builder.environment().putAll(environment);
        builder.environment().put("PATH", temporaryDirectory + ":" + builder.environment().get("PATH"));
        builder.environment().put("DEMO_CURL_LOG", temporaryDirectory.resolve("curl-called").toString());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private Path deploymentDirectory() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("deployment/compose.yml"))) {
                return path.resolve("deployment");
            }
        }
        throw new IllegalStateException("Yuheng deployment directory not found");
    }

    private record Result(int code, String output) {
    }
}
