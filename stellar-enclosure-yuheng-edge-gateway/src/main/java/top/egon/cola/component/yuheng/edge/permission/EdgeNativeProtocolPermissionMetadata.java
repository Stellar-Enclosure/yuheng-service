package top.egon.cola.component.yuheng.edge.permission;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceAccess;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

/** Compiler-only descriptions of the native security chain; never a controller, bean, authorization adapter or invocation target. */
public final class EdgeNativeProtocolPermissionMetadata {
    private EdgeNativeProtocolPermissionMetadata() { }

    @RequestMapping(path = {"/apps/{appAlias}/auth/login"}, method = {RequestMethod.GET})
    @PermissionResource(key = "yuheng.edge.protocol.login", type = ResourceType.API,
            access = ResourceAccess.PUBLIC, allowedPrincipals = PermissionManifest.PrincipalType.USER, labelPage = "yuheng.edge.protocol", labelKey = "api.login")
    public static void login() { throw new UnsupportedOperationException("Compiler metadata only"); }

    @RequestMapping(path = {"/apps/{appAlias}/auth/callback"}, method = {RequestMethod.GET})
    @PermissionResource(key = "yuheng.edge.protocol.callback", type = ResourceType.API,
            access = ResourceAccess.PUBLIC, allowedPrincipals = PermissionManifest.PrincipalType.USER, labelPage = "yuheng.edge.protocol", labelKey = "api.callback")
    public static void callback() { throw new UnsupportedOperationException("Compiler metadata only"); }

    @RequestMapping(path = {"/apps/{appAlias}/auth/identity"}, method = {RequestMethod.GET, RequestMethod.HEAD})
    @PermissionResource(key = "yuheng.edge.protocol.identity", type = ResourceType.API,
            access = ResourceAccess.INTERNAL, allowedPrincipals = PermissionManifest.PrincipalType.USER, labelPage = "yuheng.edge.protocol", labelKey = "api.identity")
    public static void identity() { throw new UnsupportedOperationException("Compiler metadata only"); }

    @RequestMapping(path = {"/apps/{appAlias}/auth/csrf"}, method = {RequestMethod.GET})
    @PermissionResource(key = "yuheng.edge.protocol.csrf", type = ResourceType.API,
            access = ResourceAccess.INTERNAL, allowedPrincipals = PermissionManifest.PrincipalType.USER, labelPage = "yuheng.edge.protocol", labelKey = "api.csrf")
    public static void csrf() { throw new UnsupportedOperationException("Compiler metadata only"); }

    @RequestMapping(path = {"/apps/{appAlias}/auth/refresh"}, method = {RequestMethod.POST})
    @PermissionResource(key = "yuheng.edge.protocol.refresh", type = ResourceType.API,
            access = ResourceAccess.INTERNAL, allowedPrincipals = PermissionManifest.PrincipalType.USER, labelPage = "yuheng.edge.protocol", labelKey = "api.refresh")
    public static void refresh() { throw new UnsupportedOperationException("Compiler metadata only"); }

    @RequestMapping(path = {"/apps/{appAlias}/auth/refresh-operations/{operationId}"}, method = {RequestMethod.GET})
    @PermissionResource(key = "yuheng.edge.protocol.refreshStatus", type = ResourceType.API,
            access = ResourceAccess.INTERNAL, allowedPrincipals = PermissionManifest.PrincipalType.USER, labelPage = "yuheng.edge.protocol", labelKey = "api.refreshStatus")
    public static void refreshStatus() { throw new UnsupportedOperationException("Compiler metadata only"); }

    @RequestMapping(path = {"/apps/{appAlias}/auth/logout"}, method = {RequestMethod.POST})
    @PermissionResource(key = "yuheng.edge.protocol.logout", type = ResourceType.API,
            access = ResourceAccess.INTERNAL, allowedPrincipals = PermissionManifest.PrincipalType.USER, labelPage = "yuheng.edge.protocol", labelKey = "api.logout")
    public static void logout() { throw new UnsupportedOperationException("Compiler metadata only"); }

    @RequestMapping(path = {"/actuator/health", "/actuator/health/**"}, method = {RequestMethod.GET})
    @PermissionResource(key = "yuheng.edge.protocol.health", type = ResourceType.API,
            access = ResourceAccess.PUBLIC, allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE}, labelPage = "yuheng.edge.protocol", labelKey = "api.health")
    public static void health() { throw new UnsupportedOperationException("Compiler metadata only"); }

    @RequestMapping(path = {"/api/v1/tianshu/i18n/public/bundle", "/apps/edge-tianshu/api/v1/tianshu/i18n/public/bundle"}, method = {RequestMethod.GET})
    @PermissionResource(key = "yuheng.edge.protocol.publicI18n", type = ResourceType.API,
            access = ResourceAccess.PUBLIC, allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE}, labelPage = "yuheng.edge.protocol", labelKey = "api.publicI18n")
    public static void publicI18n() { throw new UnsupportedOperationException("Compiler metadata only"); }
}
