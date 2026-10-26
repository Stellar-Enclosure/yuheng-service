package top.egon.cola.component.yuheng.edge.controller.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceAccess;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshOperationBO;

/** NumericDate deadlines and operation status only; never a token response. */
public record EdgeRefreshOperationResult(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.EdgeRefreshOperationResult.operationId", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.EdgeRefreshOperationResult.operationId")
        String operationId,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.EdgeRefreshOperationResult.status", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.EdgeRefreshOperationResult.status")
        String status,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.EdgeRefreshOperationResult.accessExpiresAt", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.EdgeRefreshOperationResult.accessExpiresAt")
        Long accessExpiresAt,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.EdgeRefreshOperationResult.authorizationExpiresAt", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.EdgeRefreshOperationResult.authorizationExpiresAt")
        long authorizationExpiresAt,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @PermissionResource(
                key = "yuheng.edge.field.EdgeRefreshOperationResult.failureCode", type = ResourceType.FIELD,
                access = ResourceAccess.PUBLIC,
                labelPage = "yuheng.edge.protocol", labelKey = "field.EdgeRefreshOperationResult.failureCode")
        String failureCode) {
    public static EdgeRefreshOperationResult from(EdgeRefreshOperationBO operation) {
        return new EdgeRefreshOperationResult(operation.operationId().toString(), operation.status(),
                operation.accessExpiresAt() == null ? null : operation.accessExpiresAt().getEpochSecond(),
                operation.context().authorizationExpiresAt().getEpochSecond(), operation.failureCode());
    }
}
