package top.egon.cola.component.yuheng.admin.permission;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage;
/** Source labels for the bounded internal operator protocol. */
@PermissionMessage(pageCode = "gateway.permissions", key = "api.mcpMappingSeed.freeze", defaultLocale = "en-US",
    defaults = @LocalizedText(locale = "en-US", text = "Freeze registered-identity MCP mapping evidence"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.expectedDraftRevision",
    defaultLocale = "en-US", defaults = @LocalizedText(locale = "en-US", text = "Expected exact draft revision"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.registrationId", defaultLocale = "en-US",
    defaults = @LocalizedText(locale = "en-US", text = "Approved registration identity"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.mappings", defaultLocale = "en-US",
    defaults = @LocalizedText(locale = "en-US", text = "Immutable tool mapping inputs"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.changeReason", defaultLocale = "en-US",
    defaults = @LocalizedText(locale = "en-US", text = "Reason for approving the mapping"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.operationId", defaultLocale = "en-US",
    defaults = @LocalizedText(locale = "en-US", text = "Source operation ID"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.operationKey", defaultLocale = "en-US",
    defaults = @LocalizedText(locale = "en-US", text = "Stable source operation key"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.definitionDigest",
    defaultLocale = "en-US", defaults = @LocalizedText(locale = "en-US", text = "Approved source definition SHA-256"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.additionalPermissions",
    defaultLocale = "en-US", defaults = @LocalizedText(locale = "en-US", text = "Additional required permissions"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.minimumRiskLevel",
    defaultLocale = "en-US", defaults = @LocalizedText(locale = "en-US", text = "Minimum risk level"))
@PermissionMessage(pageCode = "gateway.permissions", key = "field.mappingSeed.disabled", defaultLocale = "en-US",
    defaults = @LocalizedText(locale = "en-US", text = "Tool disabled"))
public final class McpMappingSeedPermissionMessages {
    private McpMappingSeedPermissionMessages() {}
}
