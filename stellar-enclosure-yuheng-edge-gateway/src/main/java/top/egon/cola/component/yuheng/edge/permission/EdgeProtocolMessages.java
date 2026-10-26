package top.egon.cola.component.yuheng.edge.permission;

import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText;

/** Source labels for the native Edge protocol inventory. */
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.login", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Start the native OAuth login transaction"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.callback", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Consume the one-use native OAuth callback"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.identity", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Read the current verified identity envelope"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.csrf", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Issue a credential-bound CSRF proof"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.refresh", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Refresh the owned credential generation"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.refreshStatus", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Read the owned refresh operation status"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.logout", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Close the current application authorization"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.health", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Read operational health"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "api.publicI18n", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Forward the reviewed public login text bundle"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.issuer", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult issuer"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.tenantId", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult tenantId"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.subject", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult subject"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.appAlias", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult appAlias"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.clientId", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult clientId"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.authorizationExpiresAt", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult authorizationExpiresAt"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.authenticationExpiresAt", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult authenticationExpiresAt"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.securityVersion", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult securityVersion"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.IdentityResult.ssoLogoutUri", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native IdentityResult ssoLogoutUri"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.LogoutResult.operationId", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native LogoutResult operationId"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.LogoutResult.status", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native LogoutResult status"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.LogoutResult.remoteState", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native LogoutResult remoteState"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.LogoutResult.ssoLogoutUri", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native LogoutResult ssoLogoutUri"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.EdgeRefreshOperationResult.operationId", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native EdgeRefreshOperationResult operationId"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.EdgeRefreshOperationResult.status", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native EdgeRefreshOperationResult status"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.EdgeRefreshOperationResult.accessExpiresAt", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native EdgeRefreshOperationResult accessExpiresAt"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.EdgeRefreshOperationResult.authorizationExpiresAt", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native EdgeRefreshOperationResult authorizationExpiresAt"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.EdgeRefreshOperationResult.failureCode", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native EdgeRefreshOperationResult failureCode"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.Proof.headerName", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native Proof headerName"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.Proof.token", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native Proof token"))
@PermissionMessage(pageCode = "yuheng.edge.protocol", key = "field.Proof.expiresAt", defaultLocale = "en-US",
        defaults = @LocalizedText(locale = "en-US", text = "Native Proof expiresAt"))
public final class EdgeProtocolMessages { private EdgeProtocolMessages() { } }
