package top.egon.cola.component.yuheng.llm.proxy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import top.egon.cola.component.common.core.enums.EgonEnum;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 中文说明：{@code LlmCacheModeEnum} 定义 LLM 网关响应缓存模式。
 * English summary: {@code LlmCacheModeEnum} defines the LLM gateway response cache mode.
 */
public enum LlmCacheModeEnum implements EgonEnum {

    NONE(10, "NONE", "禁用响应缓存"),
    EXACT(20, "EXACT", "精确匹配缓存"),
    SEMANTIC(30, "SEMANTIC", "语义相似度缓存");

    private static final Map<String, LlmCacheModeEnum> WIRE_VALUES = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(LlmCacheModeEnum::wireValue, Function.identity()));

    private final int code;

    @EnumValue
    private final String wireValue;

    private final String message;

    LlmCacheModeEnum(int code, String wireValue, String message) {
        this.code = code;
        this.wireValue = wireValue;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static LlmCacheModeEnum fromWire(String wireValue) {
        if (wireValue == null) {
            throw new IllegalArgumentException("cachePolicy.mode must not be null");
        }
        LlmCacheModeEnum matched = WIRE_VALUES.get(wireValue);
        if (matched == null) {
            throw new IllegalArgumentException("Unsupported cache mode: " + wireValue);
        }
        return matched;
    }
}
