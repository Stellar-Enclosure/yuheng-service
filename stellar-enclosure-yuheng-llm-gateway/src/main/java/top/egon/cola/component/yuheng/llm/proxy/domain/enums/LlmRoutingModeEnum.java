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
 * 中文说明：{@code LlmRoutingModeEnum} 定义 LLM 网关选路模式。
 * English summary: {@code LlmRoutingModeEnum} defines the LLM gateway route selection mode.
 */
public enum LlmRoutingModeEnum implements EgonEnum {

    PRIORITY_WEIGHT(10, "PRIORITY_WEIGHT", "优先级加权路由"),
    PREFIX_AFFINITY(20, "PREFIX_AFFINITY", "前缀一致性哈希亲和路由");

    private static final Map<String, LlmRoutingModeEnum> WIRE_VALUES = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(LlmRoutingModeEnum::wireValue, Function.identity()));

    private final int code;

    @EnumValue
    private final String wireValue;

    private final String message;

    LlmRoutingModeEnum(int code, String wireValue, String message) {
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
    public static LlmRoutingModeEnum fromWire(String wireValue) {
        if (wireValue == null) {
            throw new IllegalArgumentException("routingMode must not be null");
        }
        LlmRoutingModeEnum matched = WIRE_VALUES.get(wireValue);
        if (matched == null) {
            throw new IllegalArgumentException("Unsupported routingMode: " + wireValue);
        }
        return matched;
    }
}
