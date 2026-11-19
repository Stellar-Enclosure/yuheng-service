package top.egon.cola.component.yuheng.llm.proxy.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.stereotype.Component;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmCachePolicyBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotProjectionBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmCapabilityEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmProtocolEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmRoutingModeEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.po.LlmChannelPO;
import top.egon.cola.component.yuheng.llm.proxy.domain.po.LlmModelPO;

/**
 * 中文说明：{@code LlmModelSnapshotConverter} 用 MapStruct 完成行/类型化配置到请求快照 BO 的不可逆投影；JSONB 内容
 * 在 repository 先做协议校验与类型解码，本转换器只负责字段映射、route 渠道合成以及可空 secretRef 保真。
 * English summary: MapStruct owns this one-way projection from the row/typed configuration to the request snapshot BO;
 * the repository validates and decodes JSONB first, while this converter maps fields, composes route channels and
 * preserves a nullable secretRef.
 */
@Slf4j
@Component("llmModelSnapshotConverter")
public class LlmModelSnapshotConverter
        implements BaseForwardConverter<LlmModelSnapshotProjectionBO, LlmModelSnapshotBO> {

    private static final LlmModelSnapshotMapping MAPPING = Mappers.getMapper(LlmModelSnapshotMapping.class);

    @Override
    public LlmModelSnapshotBO toTarget(LlmModelSnapshotProjectionBO source) {
        if (source == null) {
            log.debug("llm model snapshot projection is absent");
            return null;
        }
        return MAPPING.toTarget(source);
    }

    /**
     * 中文说明：发布一个有界、租户绑定且不可变的完整策略快照。摘要编码有版本及长度前缀，保留route顺序，
     * 仅对语义集合排序；仅包含密钥引用，不读取凭据值。缺失渠道按null纳入摘要，执行阶段仍拒绝悬空route。
     * English summary: Freezes a bounded tenant-bound policy using a versioned length-prefixed SHA-256 encoding.
     * Ordered routes remain ordered, semantic sets are sorted, and credentials are never read.
     */
    public LlmModelSnapshotBO toPublication(LlmModelSnapshotBO source, long installationTenantId) {
        if (installationTenantId <= 0) {
            throw new IllegalArgumentException("LLM_PUBLICATION_TENANT_REQUIRED");
        }
        Objects.requireNonNull(source, "source");
        PolicyEncoding encoded = new PolicyEncoding();
        encoded.value("llm-policy-snapshot-v1");
        encoded.value(installationTenantId);
        encoded.text(source.getModelKey(), 64, true);
        encoded.text(source.getName(), 128, true);
        encoded.value(source.getCreatedAt());
        encoded.required(source.getKind());
        encoded.required(source.getEnabled());
        encoded.set(source.getProtocols(), 4);
        encoded.value(source.getDimensions());
        encoded.required(source.getRoutingMode());
        encoded.text(source.getEmbeddingSpaceId(), 128, false);
        encoded.set(source.getAllowedSubjects(), 100);
        encoded.positive(source.getRevision());
        LlmCachePolicyBO cache = source.getCachePolicy();
        if (cache == null) {
            throw invalidPolicy();
        }
        encoded.required(cache.getMode());
        encoded.positive(cache.getTtlSeconds());
        encoded.decimal(cache.getMaxCacheTemperature());
        encoded.decimal(cache.getSemanticThreshold());
        encoded.text(cache.getEmbeddingModelKey(), 64, false);
        encoded.required(cache.getPromptCacheOptimizationEnabled());
        List<LlmModelSnapshotBO.RouteBO> routes = source.getRoutes();
        if (routes == null || routes.isEmpty() || routes.size() > 16) {
            throw invalidPolicy();
        }
        encoded.value(routes.size());
        for (LlmModelSnapshotBO.RouteBO route : routes) {
            if (route == null) {
                throw invalidPolicy();
            }
            encoded.text(route.getChannelKey(), 64, true);
            encoded.text(route.getUpstreamModel(), 128, true);
            encoded.required(route.getPriority());
            encoded.positive(route.getWeight());
            encoded.set(route.getCapabilities(), 5);
            LlmModelSnapshotBO.ChannelBO channel = route.getChannel();
            encoded.value(channel != null);
            if (channel == null) {
                continue;
            }
            if (!Objects.equals(route.getChannelKey(), channel.getChannelKey())) {
                throw invalidPolicy();
            }
            encoded.text(channel.getChannelKey(), 64, true);
            encoded.positive(channel.getRevision());
            encoded.required(channel.getDeployment());
            encoded.required(channel.getProtocol());
            encoded.text(channel.getBaseUrl(), 2048, true);
            encoded.text(channel.getSecretRef(), 128, false);
            encoded.required(channel.getEnabled());
            encoded.positive(channel.getConnectTimeoutMs());
            encoded.positive(channel.getHeaderTimeoutMs());
            encoded.positive(channel.getIdleTimeoutMs());
            encoded.positive(channel.getTotalTimeoutMs());
            encoded.positive(channel.getMaxConcurrent());
        }
        return source.immutableCopy(installationTenantId, encoded.digest());
    }

    /**
     * 中文说明：完整RR集合以租户及排序后的(alias,策略摘要)派生统一身份；任何alias变化使所有缓存分区轮换，
     * 包含独立semantic embedding策略依赖。只创建新副本，不修改已有请求所固定的发布。
     * English summary: A complete tenant publication hashes sorted alias/policy identities using explicit framing.
     * Every partition rotates together, including indirect embedding dependencies; already-pinned objects stay unchanged.
     */
    public static List<LlmModelSnapshotBO> freezePublicationBundle(List<LlmModelSnapshotBO> policies) {
        if (policies == null || policies.size() > 1000 || policies.stream().anyMatch(Objects::isNull)) {
            throw invalidPolicy();
        }
        if (policies.isEmpty()) {
            return List.of();
        }
        PolicyEncoding encoded = new PolicyEncoding();
        encoded.value("llm-policy-publication-bundle-v1");
        Long tenant = policies.getFirst().getInstallationTenantId();
        encoded.positive(tenant);
        encoded.value(policies.size());
        Set<String> aliases = new java.util.HashSet<>();
        for (LlmModelSnapshotBO policy : policies.stream()
                .sorted(java.util.Comparator.comparing(LlmModelSnapshotBO::getModelKey)).toList()) {
            if (!tenant.equals(policy.getInstallationTenantId()) || !aliases.add(policy.getModelKey())
                    || policy.getPublicationDigest() == null || !policy.getPublicationDigest().matches("[0-9a-f]{64}")) {
                throw invalidPolicy();
            }
            encoded.text(policy.getModelKey(), 64, true);
            encoded.value(policy.getPublicationDigest());
        }
        String digest = encoded.digest();
        return policies.stream().map(policy -> policy.immutableCopy(tenant, digest)).toList();
    }

    private static IllegalStateException invalidPolicy() {
        return new IllegalStateException("YUHENG_LLM_CONFIGURATION_INVALID");
    }

    private static final class PolicyEncoding {
        private static final int MAX_BYTES = 1_048_576;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream values = new DataOutputStream(bytes);

        void value(Object value) {
            byte[] data = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            if (data != null && data.length > MAX_BYTES - bytes.size() - Integer.BYTES) {
                throw invalidPolicy();
            }
            try {
                values.writeInt(data == null ? -1 : data.length);
                if (data != null) {
                    values.write(data);
                }
            } catch (IOException impossible) {
                throw new IllegalStateException("LLM_PUBLICATION_ENCODING_UNAVAILABLE");
            }
        }

        void required(Object value) {
            if (value == null) {
                throw invalidPolicy();
            }
            value(value);
        }

        void text(String value, int maximum, boolean required) {
            if ((required && (value == null || value.isBlank())) || (value != null && value.length() > maximum)) {
                throw invalidPolicy();
            }
            value(value);
        }

        void positive(Number value) {
            if (value == null || value.longValue() <= 0) {
                throw invalidPolicy();
            }
            value(value);
        }

        void decimal(Double value) {
            if (value == null || !Double.isFinite(value)) {
                throw invalidPolicy();
            }
            value(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
        }

        void set(Collection<?> source, int maximum) {
            if (source == null || source.size() > maximum || source.stream().anyMatch(Objects::isNull)) {
                throw invalidPolicy();
            }
            List<String> sorted = source.stream().map(Object::toString).distinct().sorted().toList();
            value(sorted.size());
            for (String element : sorted) {
                text(element, 128, true);
            }
        }

        String digest() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("LLM_PUBLICATION_DIGEST_UNAVAILABLE");
            }
        }
    }

    public LlmModelSnapshotProjectionBO toProjection(
            LlmModelPO model,
            List<LlmProtocolEnum> protocols,
            List<String> allowedSubjects,
            List<LlmModelSnapshotBO.RouteBO> routes) {
        if (model == null) {
            log.debug("llm model row is absent while composing a snapshot");
            return null;
        }
        return MAPPING.toProjection(model, protocols, allowedSubjects, routes);
    }

    public LlmModelSnapshotBO.RouteBO toRoute(
            String channelKey,
            String upstreamModel,
            Integer priority,
            Integer weight,
            Set<LlmCapabilityEnum> capabilities) {
        return MAPPING.toRoute(channelKey, upstreamModel, priority, weight, capabilities);
    }

    public LlmModelSnapshotBO.RouteBO withChannel(
            LlmModelSnapshotBO.RouteBO route,
            LlmModelSnapshotBO.ChannelBO channel) {
        if (route == null) {
            log.debug("llm route projection is absent while resolving its channel");
            return null;
        }
        return MAPPING.withChannel(route, channel);
    }

    public LlmModelSnapshotBO.ChannelBO toChannel(LlmChannelPO source) {
        if (source == null) {
            return null;
        }
        return MAPPING.toChannel(source);
    }
}

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
interface LlmModelSnapshotMapping
        extends BaseForwardConverter<LlmModelSnapshotProjectionBO, LlmModelSnapshotBO> {

    @Override
    @BeanMapping(builder = @Builder(disableBuilder = true))
    LlmModelSnapshotBO toTarget(LlmModelSnapshotProjectionBO source);

    @BeanMapping(builder = @Builder(disableBuilder = true))
    @Mapping(target = "installationTenantId", ignore = true)
    @Mapping(target = "publicationDigest", ignore = true)
    @Mapping(target = "modelKey", source = "model.modelKey")
    @Mapping(target = "name", source = "model.name")
    @Mapping(target = "createdAt", source = "model.createTime")
    @Mapping(target = "kind", source = "model.kind")
    @Mapping(target = "enabled", source = "model.enabled")
    @Mapping(target = "dimensions", source = "model.dimensions")
    @Mapping(target = "embeddingSpaceId", source = "model.embeddingSpaceId")
    @Mapping(target = "revision", source = "model.revision")
    @Mapping(target = "routingMode", expression = "java( routingMode( model.getRoutingMode() ) )")
    @Mapping(target = "cachePolicy", expression = "java( cachePolicy( model.getCachePolicy() ) )")
    @Mapping(target = "protocols", source = "protocols")
    @Mapping(target = "allowedSubjects", source = "allowedSubjects")
    @Mapping(target = "routes", source = "routes")
    LlmModelSnapshotProjectionBO toProjection(
            LlmModelPO model,
            List<LlmProtocolEnum> protocols,
            List<String> allowedSubjects,
            List<LlmModelSnapshotBO.RouteBO> routes);

    ObjectMapper POLICY_MAPPER = new ObjectMapper();

    default LlmRoutingModeEnum routingMode(LlmRoutingModeEnum value) {
        return value == null ? LlmRoutingModeEnum.PRIORITY_WEIGHT : value;
    }

    default LlmCachePolicyBO cachePolicy(String raw) {
        if (StringUtils.isBlank(raw)) {
            return LlmCachePolicyBO.disabledDefault();
        }
        try {
            LlmCachePolicyBO decoded = POLICY_MAPPER.readValue(raw, LlmCachePolicyBO.class);
            return decoded == null ? LlmCachePolicyBO.disabledDefault() : decoded;
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid gateway_llm_model cache_policy JSON", ex);
        }
    }

    @BeanMapping(builder = @Builder(disableBuilder = true))
    @Mapping(target = "channelKey", source = "channelKey")
    @Mapping(target = "upstreamModel", source = "upstreamModel")
    @Mapping(target = "priority", source = "priority")
    @Mapping(target = "weight", source = "weight")
    @Mapping(target = "capabilities", source = "capabilities")
    @Mapping(target = "channel", ignore = true)
    LlmModelSnapshotBO.RouteBO toRoute(
            String channelKey,
            String upstreamModel,
            Integer priority,
            Integer weight,
            Set<LlmCapabilityEnum> capabilities);

    @BeanMapping(builder = @Builder(disableBuilder = true))
    @Mapping(target = "channelKey", source = "route.channelKey")
    @Mapping(target = "upstreamModel", source = "route.upstreamModel")
    @Mapping(target = "priority", source = "route.priority")
    @Mapping(target = "weight", source = "route.weight")
    @Mapping(target = "capabilities", source = "route.capabilities")
    @Mapping(target = "channel", source = "channel")
    LlmModelSnapshotBO.RouteBO withChannel(LlmModelSnapshotBO.RouteBO route,
                                           LlmModelSnapshotBO.ChannelBO channel);

    @BeanMapping(builder = @Builder(disableBuilder = true))
    LlmModelSnapshotBO.ChannelBO toChannel(LlmChannelPO source);
}
