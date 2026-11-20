package top.egon.cola.component.yuheng.test.live;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayLiveTopologyContractTest {

    @Test
    void requestLevelGammaWeightRequiresBothReviewedVersionsAndEnoughSamples() {
        assertThatCode(() -> GatewayLiveTopologyIT.requireGammaWeight("r1", "r2", 10000, Map.of("r1", 9500, "r2", 500)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWeight("r1", "r2", 100, Map.of("r1", 95, "r2", 5)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWeight("r1", "r2", 10000, Map.of("r1", 10000)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWeight("r1", "r2", 10000, Map.of("r1", 5000, "r2", 5000)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWeight("r1", "r2", 10000, Map.of("r1", 9400, "r2", 500, "unreviewed", 100)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWeight("r1", "r2", 10000, Map.of("r1", 9499, "r2", 500)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void stableWindowsRejectEvenOneCanaryAndRequireAllRequests() {
        for (String phase : new String[]{"stable-before", "stable-after"}) {
            var sample = GatewayLiveTopologyIT.requireGammaWindow(phase, 10_000, 10_000, 0, 0);
            assertThat(sample.canaryLower95()).isZero();
            assertThat(sample.canaryUpper95()).isBetween(0.0003, 0.0004);
            assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWindow(phase, 10_000, 9_999, 1, 0))
                    .isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWindow(phase, 10_000, 9_999, 0, 1))
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void weightedWindowReportsWilsonIntervalAndRejectsMissingOrUnapprovedPhase() {
        var sample = GatewayLiveTopologyIT.requireGammaWindow("weighted", 10_000, 9_500, 500, 0);
        assertThat(sample.canaryLower95()).isBetween(0.045, 0.047);
        assertThat(sample.canaryUpper95()).isBetween(0.054, 0.055);
        assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWindow("weighted", 10_000, 9_499, 500, 0))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWindow("weighted", 10_000, 9_400, 600, 1))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> GatewayLiveTopologyIT.requireGammaWindow("unreviewed", 10_000, 10_000, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void managedListenerInventoryRequiresAtLeastTwoDistinctTargets() throws Exception {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        var inventory = mapper.createArrayNode();
        inventory.addObject().put("id", "node-1").put("ip", "192.0.2.11").put("port", 443);
        inventory.addObject().put("id", "node-2").put("ip", "2001:db8::12").put("port", 30443);
        assertThat(GatewayExposureConvergenceLiveIT.approvedTargets(inventory)).hasSize(2);
        var missingTarget = inventory.deepCopy();
        missingTarget.remove(1);
        assertThatThrownBy(() -> GatewayExposureConvergenceLiveIT.approvedTargets(missingTarget))
                .isInstanceOf(IllegalArgumentException.class);
        for (String ip : new String[]{"192.0.2.11", "listener.example.test", "127.0.0.1", "0.0.0.0", "fe80::1%lo0"}) {
            var invalid = inventory.deepCopy();
            invalid.set(1, mapper.createObjectNode().put("id", "node-2").put("ip", ip).put("port", 443));
            assertThatThrownBy(() -> GatewayExposureConvergenceLiveIT.approvedTargets(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void reviewedProviderIdentitiesAreUniqueAndBounded() {
        assertThat(GatewayLiveTopologyIT.providerIds("stable-0,stable-1")).containsExactlyInAnyOrder("stable-0", "stable-1");
        for (String invalid : new String[]{"stable-0,stable-0", "stable-0,", "invalid provider", "line\nbreak"}) {
            assertThatThrownBy(() -> GatewayLiveTopologyIT.providerIds(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

}
