package top.egon.cola.component.yuheng.mcp.engine.permission;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.*;

/** 原生协议入口清单，现有验证与流式输出继续拥有执行边界。 / Native protocol metadata; authentication and streaming stay in their existing adapters. */
@PermissionMessage(pageCode="gateway.mcp.permissions", key="api.current", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="MCP current protocol endpoint"))
@PermissionMessage(pageCode="gateway.mcp.permissions", key="api.legacy", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="MCP legacy protocol endpoint"))
@PermissionMessage(pageCode="gateway.mcp.permissions", key="api.metadata", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="MCP metadata protocol endpoint"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.mcp.permissions",key="api.health",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="MCP health endpoint"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.mcp.permissions",key="api.info",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="MCP info endpoint"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.mcp.permissions",key="api.metrics",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="MCP metrics endpoint"))
public final class NativeProtocolPermissionMetadata {
    private NativeProtocolPermissionMetadata() { }

    @RequestMapping(path="/mcp/{serverCode}", method={RequestMethod.GET,RequestMethod.POST,RequestMethod.DELETE})
    @PermissionResource(key="yuheng.mcp.api.current", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.mcp.permissions", labelKey="api.current",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void current() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/legacy/mcp/{serverCode}", method={RequestMethod.GET,RequestMethod.POST,RequestMethod.DELETE})
    @PermissionResource(key="yuheng.mcp.api.legacy", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.mcp.permissions", labelKey="api.legacy",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void legacy() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/.well-known/oauth-protected-resource/mcp/{serverCode}", method={RequestMethod.GET})
    @PermissionResource(key="yuheng.mcp.api.metadata", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.mcp.permissions", labelKey="api.metadata",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void metadata() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/health","/actuator/health/**"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.mcp.api.health",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.mcp.permissions",labelKey="api.health",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void health() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/info"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.mcp.api.info",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.mcp.permissions",labelKey="api.info",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void info() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/metrics","/actuator/metrics/**"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.mcp.api.metrics",type=ResourceType.API,access=ResourceAccess.INTERNAL,
            labelPage="gateway.mcp.permissions",labelKey="api.metrics",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void metrics() { throw new UnsupportedOperationException("metadata only"); }
}
