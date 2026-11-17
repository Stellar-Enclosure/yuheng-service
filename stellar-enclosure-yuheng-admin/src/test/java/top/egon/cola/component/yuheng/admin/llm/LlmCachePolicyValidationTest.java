package top.egon.cola.component.yuheng.admin.llm;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.llm.converter.LlmCachePolicyConverter;
import top.egon.cola.component.yuheng.admin.llm.domain.bo.LlmCachePolicyBO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmCachePolicyDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmCacheModeEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmRoutingModeEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.vo.LlmCachePolicyVO;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCachePolicyValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("缓存策略 TTL 与语义相似度阈值超出边界时应被 Bean Validation 拒绝")
    void rejectsOutOfRangeTtlAndSemanticThreshold() {
        LlmCachePolicyDTO invalid = LlmCachePolicyDTO.builder()
                .mode(LlmCacheModeEnum.SEMANTIC)
                .ttlSeconds(5)
                .maxCacheTemperature(1.5)
                .semanticThreshold(0.50)
                .embeddingModelKey("INVALID KEY")
                .build();

        Set<ConstraintViolation<LlmCachePolicyDTO>> violations = validator.validate(invalid);
        assertThat(violations).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("合法缓存策略经 MapStruct 转换器可正确映射并填充安全默认值")
    void convertsValidCachePolicyWithDefaults() {
        LlmCachePolicyDTO dto = LlmCachePolicyDTO.builder()
                .mode(LlmCacheModeEnum.EXACT)
                .build();
        LlmCachePolicyConverter converter = new LlmCachePolicyConverter();
        LlmCachePolicyBO bo = converter.toTarget(dto);

        assertThat(bo.getMode()).isEqualTo(LlmCacheModeEnum.EXACT);
        assertThat(bo.getTtlSeconds()).isEqualTo(3600);
        assertThat(bo.getMaxCacheTemperature()).isEqualTo(0.0);
        assertThat(bo.getSemanticThreshold()).isEqualTo(0.95);
        assertThat(bo.getPromptCacheOptimizationEnabled()).isFalse();

        LlmCachePolicyVO vo = converter.toView(bo);
        assertThat(vo.getMode()).isEqualTo(LlmCacheModeEnum.EXACT);
        assertThat(LlmRoutingModeEnum.fromWire("PREFIX_AFFINITY")).isEqualTo(LlmRoutingModeEnum.PREFIX_AFFINITY);
    }
}
