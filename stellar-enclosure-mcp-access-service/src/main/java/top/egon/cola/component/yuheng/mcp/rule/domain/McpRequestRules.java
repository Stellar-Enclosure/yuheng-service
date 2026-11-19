package top.egon.cola.component.yuheng.mcp.rule.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Carries the immutable MCP projection inside an authenticated request only. */
public final class McpRequestRules {
    private static final String KEY = McpRequestRules.class.getName();

    private McpRequestRules() { }

    public static Map<String, Object> bind(Map<String, Object> attributes, CompiledMcpRules rules) {
        Map<String, Object> copy = new LinkedHashMap<>(attributes);
        copy.put(KEY, new BoundRules(Objects.requireNonNull(rules, "rules")));
        return Map.copyOf(copy);
    }

    public static CompiledMcpRules resolve(
            Map<String, Object> attributes, Supplier<CompiledMcpRules> fallback) {
        Object value = attributes == null ? null : attributes.get(KEY);
        if (value instanceof BoundRules bound) {
            return bound.rules;
        }
        if (value != null) {
            throw new IllegalArgumentException("invalid MCP request rules");
        }
        return Objects.requireNonNull(fallback, "fallback").get();
    }

    public static CompiledMcpRules resolveOrEmpty(
            Map<String, Object> attributes, Supplier<CompiledMcpRules> fallback) {
        CompiledMcpRules rules = resolve(attributes, fallback);
        return rules == null ? CompiledMcpRules.empty() : rules;
    }

    public static Map<String, Object> withoutRules(Map<String, Object> attributes) {
        if (!attributes.containsKey(KEY)) {
            return attributes;
        }
        Map<String, Object> copy = new LinkedHashMap<>(attributes);
        copy.remove(KEY);
        return Map.copyOf(copy);
    }

    private static final class BoundRules {
        private final CompiledMcpRules rules;

        private BoundRules(CompiledMcpRules rules) {
            this.rules = rules;
        }

        @Override
        public String toString() {
            return "McpRequestRules[bound]";
        }
    }
}
