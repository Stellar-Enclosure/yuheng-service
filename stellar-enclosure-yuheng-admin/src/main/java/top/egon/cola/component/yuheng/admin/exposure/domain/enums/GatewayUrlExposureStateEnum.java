package top.egon.cola.component.yuheng.admin.exposure.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import top.egon.cola.component.common.core.enums.EgonEnum;

/** Persistent management intent; WITHDRAWN permanently retains its URL reservation. */
public enum GatewayUrlExposureStateEnum implements EgonEnum {

    OPEN(0, "OPEN", "开放"),
    CLOSED(1, "CLOSED", "关闭"),
    WITHDRAWN(2, "WITHDRAWN", "永久撤回");

    private final int code;
    @EnumValue
    private final String wireValue;
    private final String message;

    GatewayUrlExposureStateEnum(int code, String wireValue, String message) {
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

    public boolean isTerminal() {
        return this == WITHDRAWN;
    }

    @JsonCreator
    public static GatewayUrlExposureStateEnum fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("URL exposure state is required");
        }
        for (GatewayUrlExposureStateEnum state : values()) {
            if (state.wireValue.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("URL exposure state is invalid");
    }
}
