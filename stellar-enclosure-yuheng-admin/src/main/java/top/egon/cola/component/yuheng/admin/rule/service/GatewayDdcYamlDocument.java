package top.egon.cola.component.yuheng.admin.rule.service;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import top.egon.cola.component.tianshu.format.DdcYamlConfigFormatStrategy;
import top.egon.cola.component.tianshu.model.config.DdcConfigFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads frozen DDC rules for historical inspection without mounting a configuration client. */
public final class GatewayDdcYamlDocument {
    private static final int MAX_YAML_CODE_POINTS = 64 * 1024 * 1024;

    public static final String RESOURCE_NAME = DdcYamlConfigFormatStrategy.DEFAULT_RESOURCE_NAME;
    public static final String FORMAT = DdcConfigFormat.YAML.name();
    public static final String ACTIVE_CONFIG_KEY = "yuheng.rules.active";
    public static final String CHUNK_CONFIG_PREFIX = "yuheng.rules.chunk.";

    public Optional<String> leafValue(String content, String propertyKey) {
        if (propertyKey == null || propertyKey.endsWith(".") || propertyKey.contains("..")
                || !(propertyKey.equals(ACTIVE_CONFIG_KEY) || propertyKey.startsWith(CHUNK_CONFIG_PREFIX))) {
            throw new IllegalArgumentException("unsupported Gateway rule property: " + propertyKey);
        }

        List<Object> matches = new ArrayList<>();
        collectMatches(parse(content), segments(propertyKey), matches);
        if (matches.size() > 1) {
            throw new IllegalArgumentException("ambiguous YAML property path: " + propertyKey);
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.getFirst() instanceof String value) {
            return Optional.of(value);
        }
        throw new IllegalArgumentException("Gateway rule YAML leaf must be a string: " + propertyKey);
    }

    private Map<?, ?> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("application.yml must not be empty");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(MAX_YAML_CODE_POINTS);
        Object root;
        try {
            root = new Yaml(new SafeConstructor(options)).load(content);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("invalid application.yml", malformed);
        }
        if (root instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("application.yml root must be a map");
    }

    private void collectMatches(Map<?, ?> current, List<String> remaining, List<Object> matches) {
        for (var entry : current.entrySet()) {
            List<String> key = segments(String.valueOf(entry.getKey()));
            if (key.size() > remaining.size() || !remaining.subList(0, key.size()).equals(key)) {
                continue;
            }
            if (key.size() == remaining.size()) {
                matches.add(entry.getValue());
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                collectMatches(nested, remaining.subList(key.size(), remaining.size()), matches);
            }
        }
    }

    private List<String> segments(String value) {
        return List.of(value.split("\\.", -1));
    }
}
