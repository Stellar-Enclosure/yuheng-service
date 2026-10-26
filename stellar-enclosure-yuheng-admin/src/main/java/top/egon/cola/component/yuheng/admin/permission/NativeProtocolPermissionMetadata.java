package top.egon.cola.component.yuheng.admin.permission;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

/** Signed report transport metadata; the existing HMAC filter owns authentication and replay checks. */
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.permissions",key="api.health",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="ADMIN health endpoint"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.permissions",key="api.info",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="ADMIN info endpoint"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.permissions",key="api.metrics",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="ADMIN metrics endpoint"))
public final class NativeProtocolPermissionMetadata {
    private NativeProtocolPermissionMetadata() { }

    @RequestMapping(path="/api/v1/yuheng/openapi/interface-definitions/reports",method=RequestMethod.POST)
    @PermissionResource(key="yuheng.api.reporting.submit",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.permissions",labelKey="api.reporting.definition")
    public static void submit() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/api/v1/yuheng/openapi/interface-definitions/reports/{reportId}",method={RequestMethod.GET,RequestMethod.HEAD})
    @PermissionResource(key="yuheng.api.reporting.status",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.permissions",labelKey="api.reporting.definition")
    public static void status() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/health","/actuator/health/**"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.api.health",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.permissions",labelKey="api.health",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void health() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/info"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.api.info",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.permissions",labelKey="api.info",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void info() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/metrics","/actuator/metrics/**"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.api.metrics",type=ResourceType.API,access=ResourceAccess.INTERNAL,
            labelPage="gateway.permissions",labelKey="api.metrics",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void metrics() { throw new UnsupportedOperationException("metadata only"); }
}
