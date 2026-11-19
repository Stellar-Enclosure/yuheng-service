package top.egon.cola.component.yuheng.mcp.task.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingContextVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingPinVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.SelectionVO;

/**
 * 中文说明：任务 JSONB 中的固定版本引用，不包含凭据或临时授权。恢复时仍须由 loader 重核当前权威。
 * 所有数值身份按规范十进制字符串编码，避免 JSONB/不同 Jackson 数字实现改变身份。
 * English summary: A task's immutable JSONB binding reference, never credentials or a cached authorization decision.
 * The loader must reauthorize it on resume; numeric identities use canonical decimal strings.
 */
public record McpTaskBindingVO(@NotNull @Valid SelectionVO selection, @NotNull @Valid BindingPinVO pin) {
    public static final String PAYLOAD_KEY = "executionBinding";
    public static final String SCHEMA_VERSION = "mcp-task-binding-v1";
    private static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "selection", "pin");
    private static final Set<String> SELECTION_KEYS = Set.of("ownerTenantId", "serverCode", "toolId");
    private static final Set<String> PIN_KEYS = Set.of("bindingId", "bindingVersion", "resourceId", "versionId",
        "versionNo", "siDigestSha256", "publicationId", "publicationArtifactSha256", "dependencyClosureDigest");

    public McpTaskBindingVO {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(pin, "pin");
    }

    /**
     * 中文说明：只从 loader 已受理的上下文捕获固定引用，不把请求字段当成 authority。
     * English summary: Captures an admitted loader context, without treating request fields as authority.
     * @param binding 已受理的上下文；the admitted context.
     * @return 固定引用；the immutable reference.
     */
    public static McpTaskBindingVO from(BindingContextVO binding) {
        Objects.requireNonNull(binding, "binding");
        return new McpTaskBindingVO(binding.selection(), binding.pin());
    }

    /**
     * 中文说明：返回可直接存入原 input_payload 的不可变 JSON 对象。
     * English summary: Returns an immutable JSON object for the existing input_payload column.
     * @return 版本化固定引用；the versioned binding reference.
     */
    public Map<String, Object> toPayload() {
        return Map.of("schemaVersion", SCHEMA_VERSION, "selection",
            Map.of("ownerTenantId", Long.toString(selection.ownerTenantId()), "serverCode", selection.serverCode(),
                "toolId", selection.toolId()),
            "pin",
            Map.of("bindingId", pin.bindingId(), "bindingVersion", Long.toString(pin.bindingVersion()), "resourceId",
                pin.resourceId(), "versionId", pin.versionId(), "versionNo", Long.toString(pin.versionNo()),
                "siDigestSha256", pin.siDigestSha256(), "publicationId", pin.publicationId(),
                "publicationArtifactSha256", pin.publicationArtifactSha256(), "dependencyClosureDigest",
                pin.dependencyClosureDigest()));
    }

    /**
     * 中文说明：严格读取历史载荷中的固定引用；缺失、额外字段和数值类型漂移均拒绝，不查询当前版本兜底。
     * English summary: Strictly reads the persisted reference. Missing/extra fields or numeric type drift never fall
     * back.
     * @param input 完整任务输入；the complete task input.
     * @return 经形状验证的引用，尚非授权结果；the shape-validated reference, not an authorization result.
     */
    public static McpTaskBindingVO require(Map<String, Object> input) {
        Object raw = input == null ? null : input.get(PAYLOAD_KEY);
        if (raw == null) {
            throw new IllegalStateException("MCP_TASK_BINDING_REQUIRED");
        }
        try {
            Map<?, ?> root = object(raw, ROOT_KEYS);
            if (!SCHEMA_VERSION.equals(text(root, "schemaVersion"))) {
                throw invalid();
            }
            Map<?, ?> selection = object(root.get("selection"), SELECTION_KEYS);
            Map<?, ?> pin = object(root.get("pin"), PIN_KEYS);
            return new McpTaskBindingVO(new SelectionVO(positive(selection, "ownerTenantId"),
                                            text(selection, "serverCode"), text(selection, "toolId")),
                new BindingPinVO(text(pin, "bindingId"), positive(pin, "bindingVersion"), text(pin, "resourceId"),
                    text(pin, "versionId"), positive(pin, "versionNo"), text(pin, "siDigestSha256"),
                    text(pin, "publicationId"), text(pin, "publicationArtifactSha256"),
                    text(pin, "dependencyClosureDigest")));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static Map<?, ?> object(Object value, Set<String> keys) {
        if (!(value instanceof Map<?, ?> object) || !object.keySet().equals(keys)) {
            throw invalid();
        }
        return object;
    }

    private static String text(Map<?, ?> source, String key) {
        if (!(source.get(key) instanceof String value) || value.isBlank()) {
            throw invalid();
        }
        return value;
    }

    private static long positive(Map<?, ?> source, String key) {
        String value = text(source, key);
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        return Long.parseLong(value);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("MCP_TASK_BINDING_INVALID");
    }
}
