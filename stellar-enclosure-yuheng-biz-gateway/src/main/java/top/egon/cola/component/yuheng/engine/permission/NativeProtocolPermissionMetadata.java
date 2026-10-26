package top.egon.cola.component.yuheng.engine.permission;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import top.egon.cola.component.yuheng.core.route.RuntimeHttpRoute;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.*;

/** 原生路由表拥有动态路径、方法与鉴权，元数据不生成MVC入口。 / Native route tables own dynamic paths, methods and security. */
@PermissionMessage(pageCode="gateway.biz.permissions",key="api.http",defaultLocale="en-US",defaults=@LocalizedText(locale="en-US",text="Native HTTP route dispatcher"))
@PermissionMessage(pageCode="gateway.biz.permissions",key="api.rpc",defaultLocale="en-US",defaults=@LocalizedText(locale="en-US",text="Native gRPC method dispatcher"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.biz.permissions",key="api.health",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="BIZ health endpoint"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.biz.permissions",key="api.info",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="BIZ info endpoint"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.biz.permissions",key="api.metrics",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="BIZ metrics endpoint"))
public final class NativeProtocolPermissionMetadata {
    private NativeProtocolPermissionMetadata() { }

    @PermissionNativeDispatcher(routeType=RuntimeHttpRoute.class,pathAccessor="pathPattern",methodsAccessor="methods")
    @PermissionResource(key="yuheng.biz.api.http",type=ResourceType.API,access=ResourceAccess.INTERNAL,
            labelPage="gateway.biz.permissions",labelKey="api.http",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void http() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/{service}/{method}",method=RequestMethod.POST,consumes="application/grpc")
    @PermissionResource(key="yuheng.biz.api.rpc",type=ResourceType.API,access=ResourceAccess.INTERNAL,
            labelPage="gateway.biz.permissions",labelKey="api.rpc",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void rpc() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/health","/actuator/health/**"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.biz.api.health",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.biz.permissions",labelKey="api.health",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void health() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/info"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.biz.api.info",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.biz.permissions",labelKey="api.info",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void info() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/metrics","/actuator/metrics/**"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.biz.api.metrics",type=ResourceType.API,access=ResourceAccess.INTERNAL,
            labelPage="gateway.biz.permissions",labelKey="api.metrics",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void metrics() { throw new UnsupportedOperationException("metadata only"); }
}
