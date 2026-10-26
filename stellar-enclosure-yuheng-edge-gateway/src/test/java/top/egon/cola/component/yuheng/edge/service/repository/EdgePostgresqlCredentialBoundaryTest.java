package top.egon.cola.component.yuheng.edge.service.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Validates external-IT input without opening a database or listener. */
class EdgePostgresqlCredentialBoundaryTest {
    @TempDir Path directory;

    @Test void credentialsComeOnlyFromThePrivateOwnerFile() throws Exception {
        Path path = directory.resolve("credentials.properties");
        Files.writeString(path, "user=test-owner\npassword=test-only-value\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        var values = EdgeOAuthStatePostgresqlIT.readCredentials(path);
        assertThat(values.stringPropertyNames()).containsExactlyInAnyOrder("user", "password");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> EdgeOAuthStatePostgresqlIT.readCredentials(path)).hasMessage("EDGE_IT_PRIVATE_CREDENTIAL_FILE_REQUIRED");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Path link = directory.resolve("link"); Files.createSymbolicLink(link, path);
        assertThatThrownBy(() -> EdgeOAuthStatePostgresqlIT.readCredentials(link)).hasMessage("EDGE_IT_PRIVATE_CREDENTIAL_FILE_REQUIRED");
    }

    @Test void unrelatedPropertiesAndSharedWritableParentsAreRejected() throws Exception {
        Path path = directory.resolve("credentials.properties");
        Files.writeString(path, "user=test-owner\npassword=test-only-value\nother=not-allowed\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> EdgeOAuthStatePostgresqlIT.readCredentials(path)).hasMessage("EDGE_IT_CREDENTIAL_PROPERTIES_INVALID");
        Files.writeString(path, "user=test-owner\npassword=test-only-value\n");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
        try {
            assertThatThrownBy(() -> EdgeOAuthStatePostgresqlIT.readCredentials(path)).hasMessage("EDGE_IT_PRIVATE_CREDENTIAL_DIRECTORY_REQUIRED");
        } finally { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
    }
}
