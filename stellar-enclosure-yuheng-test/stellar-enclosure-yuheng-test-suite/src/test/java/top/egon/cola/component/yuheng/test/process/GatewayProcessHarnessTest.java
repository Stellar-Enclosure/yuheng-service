package top.egon.cola.component.yuheng.test.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayProcessHarnessTest {

    @Test
    void rejectsAnotherActiveProcessWithTheSameFixtureIdentity() {
        var first = probeSpec("fixture", false, null);
        assertThatThrownBy(() -> GatewayProcessHarness.validateProcessIsolation(first, List.of(first)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already running");
    }

    @Test
    void onlyTheRequestedFixtureExecutableMayReplaceItsRegularArchive() throws Exception {
        Path regular = Files.createFile(temporaryDirectory.resolve("fixture.jar"));
        Path executable = temporaryDirectory.resolve("fixture-exec.jar");
        String mainClass = GatewayProcessProbe.class.getName();

        assertThat(GatewayProcessHarness.executableArchive(regular, mainClass)).isEmpty();
        writeExecutable(executable, String.class.getName());
        assertThatThrownBy(() -> GatewayProcessHarness.executableArchive(regular, mainClass))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("unexpected launcher or start class");
        writeExecutable(executable, mainClass);
        assertThat(GatewayProcessHarness.executableArchive(regular, mainClass)).contains(executable);
    }

    private void writeExecutable(Path path, String mainClass) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", mainClass);
        try (var jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            jar.flush();
        }
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void builderProducesImmutableRedactedDiagnosticSpec() {
        GatewayProcessSpec spec = GatewayProcessSpec.builder(
                        "admin",
                        "example.AdminApplication"
                )
                .argument("server.port", 18080)
                .argument("yuheng.admin.secret-key", "do-not-log")
                .environment("DATABASE_PASSWORD", "do-not-log-either")
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        assertThat(spec.arguments()).contains(
                "--yuheng.admin.secret-key=do-not-log"
        );
        assertThat(spec.redactedArguments())
                .contains("--yuheng.admin.secret-key=******")
                .doesNotContain("--yuheng.admin.secret-key=do-not-log");
        assertThat(spec.redactedEnvironment())
                .containsEntry("DATABASE_PASSWORD", "******");
        assertThat(spec.startupTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsUnsafeProcessArtifactName() {
        assertThatThrownBy(() -> GatewayProcessSpec.builder(
                        "../outside",
                        "example.Application"
                )
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void prioritizesTheMainApplicationClasspathEntry() {
        Path modelService = temporaryDirectory.resolve("stellar-enclosure-yuheng-biz-gateway.jar");
        Path admin = temporaryDirectory.resolve("tianshu-admin.jar");
        String classPath = String.join(
                File.pathSeparator,
                modelService.toString(),
                admin.toString()
        );

        assertThat(GatewayProcessHarness.prioritizeClassPath(
                classPath,
                admin
        )).startsWith(admin + File.pathSeparator);
    }

    @Test
    void isolatesArtifactsAndRestartsAStoppedProcess() throws Exception {
        try (GatewayProcessHarness harness = new GatewayProcessHarness(
                temporaryDirectory.resolve("restart"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2))) {
            GatewayProcessHarness.ChildProcess fixtureOne = harness.start(
                    probeSpec("stellar-enclosure-yuheng-biz-gateway-1", false, null)
            );
            GatewayProcessHarness.ChildProcess fixtureTwo = harness.start(
                    probeSpec("stellar-enclosure-yuheng-biz-gateway-2", false, null)
            );
            awaitReady(harness, fixtureOne);
            awaitReady(harness, fixtureTwo);

            assertThat(fixtureOne.logFile())
                    .isNotEqualTo(fixtureTwo.logFile());
            assertThat(fixtureOne.manifestFile())
                    .isNotEqualTo(fixtureTwo.manifestFile());
            assertThat(fixtureOne.dataDirectory())
                    .isNotEqualTo(fixtureTwo.dataDirectory());

            long firstPid = fixtureOne.process().pid();
            Path firstLog = fixtureOne.logFile();
            harness.kill(fixtureOne);
            GatewayProcessHarness.ChildProcess restarted =
                    harness.restart(fixtureOne);
            awaitReady(harness, restarted);

            assertThat(restarted.process().pid()).isNotEqualTo(firstPid);
            assertThat(restarted.logFile()).isNotEqualTo(firstLog);
            assertThat(restarted.dataDirectory())
                    .isEqualTo(fixtureOne.dataDirectory());
            assertThat(firstLog).exists();
            assertThat(restarted.logFile()).exists();
        }
    }

    @Test
    void closesInReverseOrderAndPreservesForcedStopLog() throws Exception {
        Path shutdownLog = temporaryDirectory.resolve("shutdown-order.log");
        GatewayProcessHarness harness = new GatewayProcessHarness(
                temporaryDirectory.resolve("close"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        GatewayProcessHarness.ChildProcess first = harness.start(
                probeSpec("first", false, shutdownLog)
        );
        GatewayProcessHarness.ChildProcess stubborn = harness.start(
                probeSpec("stubborn", true, shutdownLog)
        );
        awaitReady(harness, first);
        awaitReady(harness, stubborn);

        harness.close();

        assertThat(first.process().isAlive()).isFalse();
        assertThat(stubborn.process().isAlive()).isFalse();
        assertThat(Files.readAllLines(shutdownLog))
                .containsExactly("stubborn", "first");
        assertThat(harness.output(stubborn)).contains("READY stubborn");
        assertThat(stubborn.logFile()).exists();
    }

    private GatewayProcessSpec probeSpec(
            String name,
            boolean blockShutdown,
            Path shutdownLog) {
        GatewayProcessSpec.Builder builder = GatewayProcessSpec.builder(
                        name,
                        GatewayProcessProbe.class.getName()
                )
                .rawArgument("--name=" + name)
                .rawArgument("--block-shutdown=" + blockShutdown)
                .startupTimeout(Duration.ofSeconds(5));
        if (shutdownLog != null) {
            builder.rawArgument("--shutdown-log=" + shutdownLog);
        }
        return builder.build();
    }

    private void awaitReady(
            GatewayProcessHarness harness,
            GatewayProcessHarness.ChildProcess process) {
        harness.awaitCondition(
                () -> harness.output(process).contains(
                        "READY " + process.name()
                ),
                Duration.ofSeconds(5),
                process.name() + " probe readiness"
        );
    }
}
