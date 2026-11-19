package top.egon.cola.component.yuheng.admin.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainResolver;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BindingResolver;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Freezes deployment-owned physical publication authority; neither drafts nor request data can supply it. */
@Configuration(proxyBeanMethods = false)
public class GatewayExposurePublicationConfiguration {

    public static final String FILE_PROPERTY = "yuheng.admin.cilium.publication-inventory-file";
    public static final String SHA256_PROPERTY = "yuheng.admin.cilium.publication-inventory-sha256";
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_BINDINGS = 1024;
    private static final Set<String> SET_FIELDS = Set.of("approvedHostnames", "approvedMethods", "approvedPathPrefixes", "readyEndpointIds");
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(8192).maxNumberLength(32).build())
                    .build())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final Map<RouteKey, PublicationBinding> routes;
    private final Map<DomainBindingKey, PublicationDomainVO> domains;

    public GatewayExposurePublicationConfiguration(Environment environment) {
        String file = environment.getProperty(FILE_PROPERTY);
        String expectedSha256 = environment.getProperty(SHA256_PROPERTY);
        if (file == null && expectedSha256 == null) {
            // History/status remain readable, while both native authority lookups stay unavailable.
            routes = Map.of();
            domains = Map.of();
            return;
        }
        if (file == null || file.isBlank() || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw invalid("CONFIGURATION_INVALID");
        }
        byte[] bytes = null;
        try {
            bytes = read(Path.of(file));
            if (!expectedSha256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
                throw invalid("DIGEST_MISMATCH");
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode document = JSON.readTree(text);
            verifyJson(document);
            InventoryVO inventory = JSON.treeToValue(document, InventoryVO.class);
            if (!"1".equals(inventory.schemaVersion()) || inventory.bindings() == null
                    || inventory.bindings().isEmpty() || inventory.bindings().size() > MAX_BINDINGS) {
                throw invalid("SHAPE_INVALID");
            }
            var validator = new GatewayHttpRouteCompiler(JSON);
            Map<RouteKey, PublicationBinding> routeIndex = new HashMap<>();
            Map<DomainKey, PublicationDomainVO> views = new HashMap<>();
            Map<DomainBindingKey, PublicationDomainVO> domainIndex = new HashMap<>();
            Map<GatewayKey, String> gatewayUids = new HashMap<>();
            for (PublicationBinding binding : inventory.bindings()) {
                if (binding == null || binding.ownerTenantId() <= 0 || binding.gatewayGroupId() <= 0) {
                    throw invalid("BINDING_INVALID");
                }
                validator.validateBinding(binding);
                RouteKey route = new RouteKey(binding.ownerTenantId(), binding.gatewayGroupId(), binding.routeId(), binding.operationId());
                if (routeIndex.putIfAbsent(route, binding) != null) { throw invalid("ROUTE_DUPLICATED"); }
                PublicationDomainVO domain = binding.publicationDomain();
                DomainKey key = new DomainKey(domain.clusterId(), domain.publicationDomainId());
                PublicationDomainVO previous = views.putIfAbsent(key, domain);
                if (previous != null && !previous.equals(domain)) { throw invalid("DOMAIN_INCONSISTENT"); }
                for (BindingVO physical : domain.bindings()) {
                    PublicationDomainVO other = domainIndex.putIfAbsent(new DomainBindingKey(domain.clusterId(), physical), domain);
                    if (other != null && !other.equals(domain)) { throw invalid("DOMAIN_OVERLAP"); }
                }
                var gateway = binding.gateway();
                String previousUid = gatewayUids.putIfAbsent(new GatewayKey(domain.clusterId(), gateway.namespace(), gateway.name()), gateway.uid());
                if (previousUid != null && !previousUid.equals(gateway.uid())) { throw invalid("GATEWAY_INCONSISTENT"); }
            }
            routes = Map.copyOf(routeIndex);
            domains = Map.copyOf(domainIndex);
        } catch (InventoryException known) {
            throw known;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException invalid) {
            // JSON exceptions may contain source fragments. Authority failures never retain those causes.
            throw invalid("INVALID");
        } finally {
            if (bytes != null) { Arrays.fill(bytes, (byte) 0); }
        }
    }

    /** Returns only the startup-frozen authority used by the publisher's exact Kubernetes allowlist. */
    public List<PublicationBinding> publicationBindings() {
        return routes.values().stream().distinct()
                .sorted(java.util.Comparator.comparingLong(PublicationBinding::ownerTenantId)
                        .thenComparingLong(PublicationBinding::gatewayGroupId)
                        .thenComparing(PublicationBinding::routeId).thenComparing(PublicationBinding::operationId))
                .toList();
    }

    @Bean("gatewayHttpRouteBindingResolver")
    public BindingResolver gatewayHttpRouteBindingResolver() {
        return (owner, group, route, operation) -> Optional.ofNullable(routes.get(new RouteKey(owner, group, route, operation)));
    }

    @Bean("gatewayUrlExposurePublicationDomainResolver")
    public PublicationDomainResolver gatewayUrlExposurePublicationDomainResolver() {
        return (cluster, binding) -> domains.get(new DomainBindingKey(cluster, binding));
    }

    private static byte[] read(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || path.getParent() == null) { throw invalid("PATH_INVALID"); }
        Path root = path.getParent().toRealPath();
        Path resolved = path.toRealPath();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) { throw invalid("PATH_INVALID"); }
        try (var input = Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                Arrays.fill(bytes, (byte) 0);
                throw invalid("SIZE_INVALID");
            }
            return bytes;
        }
    }

    /** Reject duplicates before record constructors canonicalize domain lists and sets. */
    private static void verifyJson(JsonNode node) throws IOException {
        if (node == null || !node.isObject() || !node.path("schemaVersion").isTextual()
                || !"1".equals(node.path("schemaVersion").textValue()) || !node.path("bindings").isArray()
                || node.path("bindings").isEmpty() || node.path("bindings").size() > MAX_BINDINGS) {
            throw invalid("SHAPE_INVALID");
        }
        for (JsonNode binding : node.path("bindings")) {
            JsonNode domain = binding.path("publicationDomain");
            if (!domain.isObject() || !domain.path("complete").isBoolean() || !domain.path("complete").booleanValue()
                    || !domain.path("bindings").isArray() || domain.path("bindings").isEmpty()
                    || domain.path("bindings").size() > MAX_BINDINGS) { throw invalid("DOMAIN_INCOMPLETE"); }
            Set<BindingVO> physical = new HashSet<>();
            for (JsonNode entry : domain.path("bindings")) {
                BindingVO value = JSON.treeToValue(entry, BindingVO.class);
                if (value.gatewayName().length() > 253 || !dns(value.gatewayNamespace()) || !dns(value.listenerName())
                        || Arrays.stream(value.gatewayName().split("\\.", -1)).anyMatch(label -> !dns(label))) {
                    throw invalid("DOMAIN_BINDING_INVALID");
                }
                if (!physical.add(value)) { throw invalid("DOMAIN_BINDING_DUPLICATED"); }
            }
        }
        verifyValues(node);
    }

    private static boolean dns(String value) {
        return value != null && value.matches("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?");
    }

    private static void verifyValues(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) { throw invalid("TEXT_INVALID"); }
                } else if (Character.isLowSurrogate(current)) { throw invalid("TEXT_INVALID"); }
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (SET_FIELDS.contains(field.getKey()) && field.getValue().isArray()) {
                    Set<JsonNode> values = new HashSet<>();
                    field.getValue().forEach(value -> {
                        if (!value.isTextual() || !values.add(value)) { throw invalid("SET_INVALID"); }
                    });
                }
                verifyValues(field.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(GatewayExposurePublicationConfiguration::verifyValues);
        }
    }

    private static InventoryException invalid(String code) { return new InventoryException("YUHENG_PUBLICATION_INVENTORY_" + code); }

    private record InventoryVO(String schemaVersion, List<PublicationBinding> bindings) { }
    private record RouteKey(long owner, long group, String route, String operation) { }
    private record DomainKey(String cluster, String publicationDomain) { }
    private record DomainBindingKey(String cluster, BindingVO binding) { }
    private record GatewayKey(String cluster, String namespace, String name) { }
    private static final class InventoryException extends IllegalStateException {
        private InventoryException(String code) { super(code); }
    }
}
