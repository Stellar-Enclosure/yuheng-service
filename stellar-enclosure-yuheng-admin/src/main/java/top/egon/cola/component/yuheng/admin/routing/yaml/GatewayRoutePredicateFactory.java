package top.egon.cola.component.yuheng.admin.routing.yaml;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Compiles the supported YAML predicates into the existing v1 route fields. */
public final class GatewayRoutePredicateFactory {
    private static final Map<String, GatewayRoutePredicateFactory> FACTORIES = Map.of(
            "Host", new GatewayRoutePredicateFactory("host", value -> value.toLowerCase(Locale.ROOT)),
            "Method", new GatewayRoutePredicateFactory("httpMethod", value -> value.toUpperCase(Locale.ROOT)),
            "Path", new GatewayRoutePredicateFactory("pathPattern", UnaryOperator.identity()));

    private final String field;
    private final UnaryOperator<String> normalize;

    private GatewayRoutePredicateFactory(String field, UnaryOperator<String> normalize) {
        this.field = field;
        this.normalize = normalize;
    }

    public static void apply(String definition, Map<String, Object> route) {
        Objects.requireNonNull(definition, "predicate");
        int separator = definition.indexOf('=');
        if (separator <= 0 || separator == definition.length() - 1) {
            throw new IllegalArgumentException("predicate must use Name=value");
        }
        String name = definition.substring(0, separator).trim();
        GatewayRoutePredicateFactory factory = FACTORIES.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("unsupported route predicate: " + name);
        }
        String value = definition.substring(separator + 1).trim();
        if (value.isEmpty() || route.putIfAbsent(factory.field, factory.normalize.apply(value)) != null) {
            throw new IllegalArgumentException("empty or duplicate route predicate: " + name);
        }
    }
}
