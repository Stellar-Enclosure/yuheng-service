package top.egon.cola.component.yuheng.llm.permission;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.*;

/** 原生协议入口清单，现有验证与流式输出继续拥有执行边界。 / Native protocol metadata; authentication and streaming stay in their existing adapters. */
@PermissionMessage(pageCode="gateway.llm.permissions", key="api.chat", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="LLM chat protocol endpoint"))
@PermissionMessage(pageCode="gateway.llm.permissions", key="api.embeddings", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="LLM embeddings protocol endpoint"))
@PermissionMessage(pageCode="gateway.llm.permissions", key="api.responses", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="LLM responses protocol endpoint"))
@PermissionMessage(pageCode="gateway.llm.permissions", key="api.messages", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="LLM messages protocol endpoint"))
@PermissionMessage(pageCode="gateway.llm.permissions", key="api.models", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="LLM models protocol endpoint"))
@PermissionMessage(pageCode="gateway.llm.permissions", key="api.modelPolicies", defaultLocale="en-US",
        defaults=@LocalizedText(locale="en-US", text="LLM modelPolicies protocol endpoint"))
@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage(pageCode="gateway.llm.permissions",key="api.health",defaultLocale="en-US",defaults=@top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText(locale="en-US",text="LLM health endpoint"))
public final class NativeProtocolPermissionMetadata {
    private NativeProtocolPermissionMetadata() { }

    @RequestMapping(path="/v1/chat/completions", method={RequestMethod.POST})
    @PermissionResource(key="yuheng.llm.api.chat", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.llm.permissions", labelKey="api.chat",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void chat() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/v1/embeddings", method={RequestMethod.POST})
    @PermissionResource(key="yuheng.llm.api.embeddings", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.llm.permissions", labelKey="api.embeddings",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void embeddings() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/v1/responses", method={RequestMethod.POST})
    @PermissionResource(key="yuheng.llm.api.responses", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.llm.permissions", labelKey="api.responses",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void responses() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/v1/messages", method={RequestMethod.POST})
    @PermissionResource(key="yuheng.llm.api.messages", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.llm.permissions", labelKey="api.messages",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void messages() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/v1/models", method={RequestMethod.GET})
    @PermissionResource(key="yuheng.llm.api.models", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.llm.permissions", labelKey="api.models",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void models() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path="/v1/model-policies", method={RequestMethod.GET})
    @PermissionResource(key="yuheng.llm.api.modelPolicies", type=ResourceType.API, access=ResourceAccess.PUBLIC,
            labelPage="gateway.llm.permissions", labelKey="api.modelPolicies",
            allowedPrincipals={PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE})
    public static void modelPolicies() { throw new UnsupportedOperationException("metadata only"); }

    @RequestMapping(path={"/actuator/health","/actuator/health/**"},method={RequestMethod.GET})
    @PermissionResource(key="yuheng.llm.api.health",type=ResourceType.API,access=ResourceAccess.PUBLIC,
            labelPage="gateway.llm.permissions",labelKey="api.health",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE})
    public static void health() { throw new UnsupportedOperationException("metadata only"); }
}
