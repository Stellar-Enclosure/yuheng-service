package top.egon.cola.component.yuheng.admin.llm.converter;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.stereotype.Component;
import top.egon.cola.component.common.core.converter.BaseConverter;
import top.egon.cola.component.yuheng.admin.llm.domain.bo.LlmCachePolicyBO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmCachePolicyDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmCacheModeEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.vo.LlmCachePolicyVO;

import java.util.Locale;

/**
 * 中文说明：{@code LlmCachePolicyConverter} 负责缓存策略在 DTO、BO 与 VO 之间的结构转换与安全默认值填充。
 * English summary: {@code LlmCachePolicyConverter} handles structural mapping and safe default normalization across cache policy DTO, BO, and VO.
 */
@Slf4j
@Component("llmCachePolicyConverter")
public class LlmCachePolicyConverter implements BaseConverter<LlmCachePolicyDTO, LlmCachePolicyBO> {

    private static final LlmCachePolicyMapping MAPPING = Mappers.getMapper(LlmCachePolicyMapping.class);

    @Override
    public LlmCachePolicyBO toTarget(LlmCachePolicyDTO source) {
        if (source == null) {
            return LlmCachePolicyBO.disabledDefault();
        }
        LlmCachePolicyBO target = MAPPING.toBusiness(source);
        if (target.getMode() == null) {
            target.setMode(LlmCacheModeEnum.NONE);
        }
        if (target.getTtlSeconds() == null) {
            target.setTtlSeconds(3600);
        }
        if (target.getMaxCacheTemperature() == null) {
            target.setMaxCacheTemperature(0.0);
        }
        if (target.getSemanticThreshold() == null) {
            target.setSemanticThreshold(0.95);
        }
        if (target.getPromptCacheOptimizationEnabled() == null) {
            target.setPromptCacheOptimizationEnabled(Boolean.FALSE);
        }
        if (StringUtils.isNotBlank(target.getEmbeddingModelKey())) {
            target.setEmbeddingModelKey(target.getEmbeddingModelKey().trim().toLowerCase(Locale.ROOT));
        } else {
            target.setEmbeddingModelKey(null);
        }
        return target;
    }

    @Override
    public LlmCachePolicyDTO toSource(LlmCachePolicyBO target) {
        if (target == null) {
            return null;
        }
        return MAPPING.toDto(target);
    }

    public LlmCachePolicyVO toView(LlmCachePolicyBO source) {
        if (source == null) {
            return MAPPING.toView(LlmCachePolicyBO.disabledDefault());
        }
        return MAPPING.toView(source);
    }
}

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
interface LlmCachePolicyMapping {

    LlmCachePolicyBO toBusiness(LlmCachePolicyDTO source);

    LlmCachePolicyDTO toDto(LlmCachePolicyBO source);

    LlmCachePolicyVO toView(LlmCachePolicyBO source);
}
