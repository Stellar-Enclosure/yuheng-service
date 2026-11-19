package top.egon.cola.component.yuheng.llm.proxy.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import top.egon.cola.component.yuheng.llm.config.LlmGatewayProperties;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmCacheEntryBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.dto.LlmInvocationCommandDTO;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmCacheModeEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmProtocolEnum;

/** Response-cache SPI; its shared key material never derives storage tenancy or caller identity from request JSON. */
public interface LlmCacheStrategy {
    boolean supports(LlmCacheModeEnum mode);
    LlmCacheEntryBO lookup(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot);
    void store(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot, ObjectNode response);
    void evictModel(String modelKey);

    /** A structurally complete accepted publication and independently verified caller are prerequisites to caching. */
    static boolean hasTrustedContext(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
        return command != null && snapshot != null && Boolean.FALSE.equals(command.getStream())
                && command.getPayload() != null && command.getProtocol() != null && permitsCache(command)
                && command.getCallerCacheIdentity() != null && command.getCallerCacheIdentity().matches("[0-9a-f]{64}")
                && command.getCallerSubject() != null && !command.getCallerSubject().isBlank()
                && snapshot.getInstallationTenantId() != null && snapshot.getInstallationTenantId() > 0
                && snapshot.getRevision() != null && snapshot.getRevision() > 0
                && snapshot.getPublicationDigest() != null && snapshot.getPublicationDigest().matches("[0-9a-f]{64}")
                && Objects.equals(command.getModel(), snapshot.getModelKey()) && Boolean.TRUE.equals(snapshot.getEnabled())
                && snapshot.getAllowedSubjects() != null && snapshot.getAllowedSubjects().contains(command.getCallerSubject())
                && command.getAllowedDeployments() != null && !command.getAllowedDeployments().isEmpty()
                && command.getRequiredCapabilities() != null && snapshot.getRoutes() != null && !snapshot.getRoutes().isEmpty();
    }

    private static boolean permitsCache(LlmInvocationCommandDTO command) {
        String header = command.getCacheControlHeader();
        if (header != null) { String lower = header.toLowerCase(Locale.ROOT); if (lower.contains("no-cache") || lower.contains("no-store")) return false; }
        JsonNode stream = command.getPayload().get("stream");
        return stream == null || stream.isBoolean() && !stream.booleanValue();
    }

    /** Includes complete trusted route/security facts as well as the installation's narrowed egress policy. */
    static String partition(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot,
                            LlmGatewayProperties properties, ObjectMapper mapper) {
        if (!hasTrustedContext(command, snapshot) || properties == null || !properties.isEnabled() || properties.getCache() == null || !properties.getCache().isEnabled()) return null;
        ObjectNode scope = mapper.createObjectNode();
        scope.put("schemaVersion", "model-response-cache-v2");
        scope.put("installationTenantId", Long.toString(snapshot.getInstallationTenantId()));
        scope.put("publicationDigest", snapshot.getPublicationDigest());
        scope.put("modelRevision", snapshot.getRevision().toString());
        scope.put("callerCacheIdentity", command.getCallerCacheIdentity());
        scope.put("callerSubject", command.getCallerSubject());
        scope.put("model", command.getModel()); scope.put("protocol", command.getProtocol().wireValue());
        scope.put("promptCacheOptimizationEnabled", Boolean.TRUE.equals(command.getPromptCacheOptimizationEnabled()));
        scope.set("allowedDeployments", mapper.valueToTree(command.getAllowedDeployments().stream().map(Enum::name).sorted().toList()));
        scope.set("requiredCapabilities", mapper.valueToTree(command.getRequiredCapabilities().stream().map(Enum::name).sorted().toList()));
        scope.set("allowedLocalCidrs", mapper.valueToTree(properties.getAllowedLocalCidrs().stream().sorted().toList()));
        scope.set("allowedCloudHosts", mapper.valueToTree(properties.getAllowedCloudHosts().stream().sorted().toList()));
        scope.set("cachePolicy", mapper.valueToTree(snapshot.getCachePolicy()));
        ArrayNode routes = mapper.createArrayNode();
        for (var route : snapshot.getRoutes()) {
            var channel = route == null ? null : route.getChannel();
            if (channel == null || !Boolean.TRUE.equals(channel.getEnabled()) || channel.getProtocol() != command.getProtocol()
                    || !command.getAllowedDeployments().contains(channel.getDeployment()) || route.getCapabilities() == null
                    || !route.getCapabilities().containsAll(command.getRequiredCapabilities())) continue;
            ObjectNode resolved = mapper.valueToTree(route);
            resolved.set("capabilities", mapper.valueToTree(route.getCapabilities().stream().map(Enum::name).sorted().toList()));
            routes.add(resolved);
        }
        if (routes.isEmpty()) return null;
        scope.set("eligibleRoutes", routes);
        return digest(scope, mapper);
    }

    /** Stable object order and numeric representation; arrays preserve the caller's exact order. */
    static String digest(JsonNode value, ObjectMapper mapper) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(normalize(value, 0)))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("LLM_CACHE_DOCUMENT_INVALID"); }
    }

    private static JsonNode normalize(JsonNode value, int depth) {
        if (depth > 64 || value == null || value.isMissingNode() || value.isPojo()) throw new IllegalArgumentException("LLM_CACHE_DOCUMENT_INVALID");
        if (value.isObject()) {
            if (value.size() > 10000) throw new IllegalArgumentException("LLM_CACHE_DOCUMENT_INVALID");
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            var names = new ArrayList<String>(); value.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            names.forEach(name -> object.set(name, normalize(value.get(name), depth + 1))); return object;
        }
        if (value.isArray()) {
            if (value.size() > 10000) throw new IllegalArgumentException("LLM_CACHE_DOCUMENT_INVALID");
            ArrayNode array = JsonNodeFactory.instance.arrayNode(); value.forEach(item -> array.add(normalize(item, depth + 1))); return array;
        }
        if (value.isNumber()) {
            if (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("LLM_CACHE_DOCUMENT_INVALID");
            BigDecimal decimal = value.decimalValue().stripTrailingZeros();
            return DecimalNode.valueOf(decimal.signum() == 0 ? BigDecimal.ZERO : decimal);
        }
        if (!value.isNull() && !value.isBoolean() && !value.isTextual()) throw new IllegalArgumentException("LLM_CACHE_DOCUMENT_INVALID");
        return value.deepCopy();
    }

    /** Legacy or malformed entries never become a hit, and list TTL refresh cannot renew an individual response. */
    static boolean fresh(LlmCacheEntryBO entry, String partition, String context, int ttl, Instant now) {
        if (entry == null || partition == null || context == null || !partition.equals(entry.getPartitionSha256())
                || !context.equals(entry.getContextSha256()) || entry.getCreatedAt() == null || entry.getExpiresAt() == null
                || entry.getCreatedAt().isAfter(now) || !entry.getExpiresAt().isAfter(now) || ttl < 1) return false;
        try { return entry.getExpiresAt().isAfter(entry.getCreatedAt())
                    && !entry.getExpiresAt().isAfter(entry.getCreatedAt().plusSeconds(ttl)); }
        catch (java.time.DateTimeException | ArithmeticException invalid) { return false; }
    }

    /** Only the final user's plain text can vary. All earlier history, system, tools and options remain exact. */
    static SemanticRequest semanticRequest(LlmInvocationCommandDTO command, ObjectMapper mapper) {
        if (command == null || command.getPayload() == null || command.getProtocol() == LlmProtocolEnum.OPENAI_EMBEDDING) return null;
        ObjectNode context = command.getPayload().deepCopy(); String text;
        if (command.getProtocol() == LlmProtocolEnum.OPENAI_RESPONSES && context.path("input").isTextual()) {
            text = context.path("input").textValue(); context.set("input", NullNode.instance);
        } else if (command.getProtocol() == LlmProtocolEnum.OPENAI_CHAT || command.getProtocol() == LlmProtocolEnum.ANTHROPIC_MESSAGES) {
            JsonNode messages = context.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) return null;
            JsonNode last = messages.get(messages.size() - 1);
            if (!last.isObject() || !last.path("role").asText().equals("user") || !last.path("content").isTextual()) return null;
            text = last.path("content").textValue(); ((ObjectNode) last).set("content", NullNode.instance);
        } else return null;
        if (text == null || text.isBlank() || text.length() > 1_048_576) return null;
        return new SemanticRequest(text, digest(context, mapper));
    }

    record SemanticRequest(String text, String contextSha256) { }
}
