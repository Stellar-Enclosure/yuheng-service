package top.egon.cola.component.yuheng.admin.routing.yaml;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayYamlRouteParserTest {
    private final GatewayYamlRouteParser parser = new GatewayYamlRouteParser();

    @Test
    void parsesGatewayPredicatesIntoExistingRouteFields() {
        var document = parser.parse(yaml("""
                - id: orders
                  operation-id: operation-1
                  predicates:
                    - Host=API.EXAMPLE.COM
                    - Method=post
                    - Path=/api/biz/orders/{id}
                  access-zones: [PUBLIC]
                  priority: 20
                """));

        assertThat(document.gatewayGroup()).isEqualTo("group-1");
        assertThat(document.routes()).hasSize(1);
        assertThat(document.routes().getFirst().content()).isEqualTo(Map.of(
                "host", "api.example.com", "httpMethod", "POST",
                "pathPattern", "/api/biz/orders/{id}",
                "accessZones", List.of("PUBLIC"), "priority", 20));
    }

    @Test
    void rejectsUnknownFieldsAndAmbiguousRoutes() {
        assertThatThrownBy(() -> parser.parse(yaml("""
                - id: orders
                  operation-id: operation-1
                  predicates: [Host=api.example.com, Method=GET, 'Path=/orders/{id}']
                  access-zones: [PUBLIC]
                  filters: [StripPrefix=1]
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported route field");
        assertThatThrownBy(() -> parser.parse(yaml("""
                - id: one
                  operation-id: operation-1
                  predicates: [Host=api.example.com, Method=GET, 'Path=/orders/{id}']
                  access-zones: [PUBLIC]
                - id: two
                  operation-id: operation-1
                  predicates: [Host=api.example.com, Method=GET, 'Path=/orders/{key}']
                  access-zones: [PUBLIC]
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous route");
    }

    @Test
    void rejectsDuplicateKeysAndUnsupportedPredicate() {
        String duplicate = yaml("""
                - id: first
                  id: second
                  operation-id: operation-1
                  predicates: [Host=api.example.com, Method=GET, Path=/orders]
                  access-zones: [PUBLIC]
                """);
        assertThatThrownBy(() -> parser.parse(duplicate)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parser.parse(yaml("""
                - id: orders
                  operation-id: operation-1
                  predicates: [Host=api.example.com, Method=GET, Path=/orders, Header=x]
                  access-zones: [PUBLIC]
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported route predicate");
    }

    private String yaml(String routes) {
        return """
                egon:
                  yuheng:
                    route-spec:
                      schema-version: 1
                      gateway-group: group-1
                      routes:
                """ + routes.indent(8);
    }
}
