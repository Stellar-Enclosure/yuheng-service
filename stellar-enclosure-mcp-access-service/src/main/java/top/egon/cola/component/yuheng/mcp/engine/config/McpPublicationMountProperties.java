package top.egon.cola.component.yuheng.mcp.engine.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;

/** Read-only deployment references for the MCP execution bindings and catalog projections. */
@Validated
@ConfigurationProperties(value = "egon.cola.component.yuheng.mcp-engine.publication", ignoreUnknownFields = false)
public record McpPublicationMountProperties(
        @Valid @NotNull PinnedFileVO bindings,
        @Valid @NotNull PinnedFileVO activeCatalog,
        @Size(max = 16) List<@Valid PinnedFileVO> historicalCatalogs,
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9._-]{0,63}") String callerAppCode) {

    public McpPublicationMountProperties {
        historicalCatalogs = historicalCatalogs == null ? List.of() : List.copyOf(historicalCatalogs);
    }

    public record PinnedFileVO(@NotNull Path file, @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256) {
        public PinnedFileVO {
            if (file == null || !file.isAbsolute() || !file.equals(file.normalize())) {
                throw new IllegalArgumentException("MCP_PUBLICATION_FILE_PATH_INVALID");
            }
        }
    }
}
