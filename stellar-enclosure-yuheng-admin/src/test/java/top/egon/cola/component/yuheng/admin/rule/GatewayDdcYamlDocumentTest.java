package top.egon.cola.component.yuheng.admin.rule.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayDdcYamlDocumentTest {
    private final GatewayDdcYamlDocument document = new GatewayDdcYamlDocument();

    @Test
    void readsArchivedActivationAndChunksFromNestedAndDottedKeys() {
        String content = """
                feature:
                  checkout: true
                yuheng.rules:
                  active: activation
                  chunk.release-1:
                    "0": chunk-0
                  chunk.release-1.1: chunk-1
                """;

        assertThat(document.leafValue(content, GatewayDdcYamlDocument.ACTIVE_CONFIG_KEY)).hasValue("activation");
        assertThat(document.leafValue(content, "yuheng.rules.chunk.release-1.0")).hasValue("chunk-0");
        assertThat(document.leafValue(content, "yuheng.rules.chunk.release-1.1")).hasValue("chunk-1");
        assertThat(document.leafValue(content, "yuheng.rules.chunk.release-1.2")).isEmpty();
    }

    @Test
    void rejectsAmbiguousAndUnsupportedProperties() {
        String content = """
                yuheng.rules.active: direct
                yuheng:
                  rules:
                    active: nested
                """;

        assertThatThrownBy(() -> document.leafValue(content, GatewayDdcYamlDocument.ACTIVE_CONFIG_KEY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambiguous");
        assertThatThrownBy(() -> document.leafValue("feature: true\n", "feature.enabled"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported");
    }

    @Test
    void rejectsUnsupportedRootsAndNonStringValues() {
        for (String yaml : new String[]{"[]", "yuheng.rules.active: null", "yuheng.rules.active: true"}) {
            assertThatThrownBy(() -> document.leafValue(yaml, GatewayDdcYamlDocument.ACTIVE_CONFIG_KEY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsGatewayYamlDocumentsLargerThanSnakeYamlDefaultLimit() {
        String largeValue = "x".repeat(3_100_000);
        String content = GatewayDdcYamlDocument.ACTIVE_CONFIG_KEY + ": " + largeValue + "\n";

        assertThat(document.leafValue(content, GatewayDdcYamlDocument.ACTIVE_CONFIG_KEY)).hasValue(largeValue);
    }
}
