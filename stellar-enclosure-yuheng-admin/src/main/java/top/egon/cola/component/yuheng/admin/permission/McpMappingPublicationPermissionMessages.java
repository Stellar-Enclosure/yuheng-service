package top.egon.cola.component.yuheng.admin.permission;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage;
/** Source label for the protected read-only operator export. */
@PermissionMessage(pageCode = "gateway.permissions", key = "api.mcpMappingPublication.export", defaultLocale = "en-US",
    defaults = @LocalizedText(locale = "en-US", text = "Export an exact SI mapping publication"))
public final class McpMappingPublicationPermissionMessages {
    private McpMappingPublicationPermissionMessages() {}
}
