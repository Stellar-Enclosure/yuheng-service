package top.egon.cola.component.yuheng.admin.exposure.domain.dto;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

import java.io.IOException;

/** Only the desired state and audited reason are caller-owned; all bindings remain server-owned. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
@Schema(name = "GatewayUrlExposureStateRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class GatewayUrlExposureStateRequestDTO {

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureStateRequestDTO.state", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.state",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.EXPOSURES_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            write = @PermissionGroup(codes = GatewayPermissionCodes.EXPOSURES_WRITE))
    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"OPEN", "CLOSED", "WITHDRAWN"})
    private GatewayUrlExposureStateEnum state;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureStateRequestDTO.changeReason", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.changeReason",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.EXPOSURES_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            write = @PermissionGroup(codes = GatewayPermissionCodes.EXPOSURES_WRITE))
    @NotBlank
    @Size(min = 1, max = 1024)
    @JsonDeserialize(using = StrictReasonDeserializer.class)
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 1024,
            description = "Audited reason; trimmed before validation. Must be a JSON string.")
    private String changeReason;

    public String getChangeReason() {
        return changeReason == null ? null : changeReason.trim();
    }

    public GatewayUrlExposureStateRequestDTO setChangeReason(String value) {
        this.changeReason = value == null ? null : value.trim();
        return this;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("YUHENG_EXPOSURE_REQUEST_INVALID");
    }

    /** Prevent Jackson's default scalar-to-string coercion at this specific input boundary. */
    public static final class StrictReasonDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText().trim();
        }
    }
}
