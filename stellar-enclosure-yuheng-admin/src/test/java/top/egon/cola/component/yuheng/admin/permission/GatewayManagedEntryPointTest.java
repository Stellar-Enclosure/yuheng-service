package top.egon.cola.component.yuheng.admin.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import top.egon.cola.component.common.desensitize.strategy.SensitiveStrategyRegistry;
import top.egon.cola.component.yuheng.admin.group.domain.dto.GatewayGroupCreateRequestDTO;
import top.egon.cola.component.yuheng.admin.group.domain.vo.GatewayGroupVO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmModelCommandDTO;
import top.egon.cola.component.yuheng.admin.config.GatewayAiJsonConfiguration;
import top.egon.cola.component.yuheng.admin.config.GatewayPermissionWebConfiguration;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import top.egon.cola.platform.tianquan.jianshen.starter.authorization.AuthorizationService;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.platform.tianquan.jianshen.starter.field.*;
import top.egon.cola.platform.tianquan.jianshen.starter.permission.*;
import top.egon.cola.platform.tianquan.jianshen.starter.security.*;
import top.egon.cola.platform.tianquan.shoubing.contract.AuthenticationContext;
import top.egon.cola.platform.tianquan.shoubing.contract.IdentityPrincipal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes the real shared API and field guards against the compiler policy, without sockets or persistence. */
@EnabledIfSystemProperty(named = "permission.producer.enabled", matches = "true")
class GatewayManagedEntryPointTest {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ARTIFACT = "a".repeat(64);
    private static final CatalogScope SCOPE = new CatalogScope("10", "20", "xingyuan", "yuheng", "default", "test");
    private static final IdentityPrincipal USER = new IdentityPrincipal("30", "20", "40", Set.of("https://api.example/yuheng"),
            NOW.minusSeconds(10), NOW.plusSeconds(300), AuthenticationContext.of("PASSWORD", NOW.minusSeconds(20)));

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void everyDirectManagedControllerCallRejectsMissingIdentityBeforeItsBody() throws Exception {
        var manifest = manifest();
        var loader = mock(SingleFlightSnapshotLoader.class);
        var manager = new Rbac3MethodAuthorizationManager(mock(AuthorizationService.class),
                new PermissionExecutionGuard(registry(manifest), new CurrentRbac3User(), loader));
        var interceptor = new AuthorizationManagerBeforeMethodInterceptor(new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) { return manager.supports(method, targetClass); }
        }, manager);
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("coverage", Map.of("egon.cola.platform.tianquan.jianshen.enabled", "true")));
        scanner.setEnvironment(env); scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        int count = 0;
        for (var candidate : scanner.findCandidateComponents("top.egon.cola.component.yuheng.admin")) {
            Class<?> type = Class.forName(candidate.getBeanClassName(), false, getClass().getClassLoader());
            if (type.getName().contains(".reporting.")) continue;
            Object target = mock(type, CALLS_REAL_METHODS);
            var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvisor(interceptor);
            Object proxy = factory.getProxy();
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(PermissionResource.class) == null) continue;
                Object[] arguments = Arrays.stream(method.getParameterTypes()).map(parameter -> {
                    if (parameter == int.class) return (Object) 1;
                    if (parameter == long.class) return (Object) 1L;
                    if (parameter == boolean.class) return (Object) false;
                    return null;
                }).toArray();
                assertThatThrownBy(() -> method.invoke(proxy, arguments)).as(method.toString())
                        .isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(AuthenticationCredentialsNotFoundException.class);
                count++;
            }
        }
        assertThat(count).isEqualTo(90); verifyNoInteractions(loader);
    }

    @Test
    void apiRequiresCurrentGrantScopeAndExecutingBuildIndependentlyOfLegacyCapabilities() throws Exception {
        var manifest = manifest(); var loader = mock(SingleFlightSnapshotLoader.class);
        var guard = new PermissionExecutionGuard(registry(manifest), new CurrentRbac3User(), loader, null, scopes());
        String key = "yuheng.api.gatewayGroup.create";
        authenticate(manifest, Set.of(GatewayPermissionCodes.READ), Set.of(GatewayPermissionCodes.GROUPS_WRITE));
        when(loader.load(any())).thenAnswer(call -> new CurrentRbac3User().require().snapshot());
        assertThatThrownBy(() -> guard.requireExecution(key)).isInstanceOf(AuthorizationDeniedException.class).hasMessage("PERMISSION_DENIED");
        authenticate(manifest, Set.of(GatewayPermissionCodes.GROUPS_WRITE), Set.of(GatewayPermissionCodes.READ));
        assertThatThrownBy(() -> guard.requireExecution(key)).isInstanceOf(AuthorizationDeniedException.class).hasMessage("PERMISSION_DENIED");
        authenticate(manifest, Set.of(GatewayPermissionCodes.GROUPS_WRITE), Set.of(GatewayPermissionCodes.GROUPS_WRITE));
        assertThatCode(() -> guard.requireExecution(key)).doesNotThrowAnyException();
        var foreign = new PermissionPolicyRegistry(manifest, "b".repeat(64), CLOCK, List.of(SCOPE));
        var stale = new PermissionExecutionGuard(foreign, new CurrentRbac3User(), loader, null, scopes());
        assertThatThrownBy(() -> stale.requireExecution(key)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void transportMapperChecksExplicitNullNestedInputReadonlyOutputAndPreservesStrictAiBinding() throws Exception {
        var manifest = manifest(); var registry = registry(manifest);
        var fields = new PermissionFieldBindingGuard(registry, new CurrentRbac3User(), () -> null, scopes());
        var factory = new PermissionJsonMapperFactory(new CurrentRbac3User(), SensitiveStrategyRegistry.defaults(), registry, fields);
        var converter = new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules());
        new GatewayAiJsonConfiguration().extendMessageConverters(List.of(converter));
        new GatewayPermissionWebConfiguration(factory, converter.getObjectMapper()).extendMessageConverters(new ArrayList<>(List.of(converter)));
        var mapper = converter.getObjectMapper();
        authenticate(manifest, Set.of(GatewayPermissionCodes.READ), Set.of(GatewayPermissionCodes.GROUPS_WRITE));
        assertThatThrownBy(() -> mapper.readValue("{\"displayName\":null}", GatewayGroupCreateRequestDTO.class)).hasStackTraceContaining("FIELD_ACCESS_DENIED");
        authenticate(manifest, Set.of(GatewayPermissionCodes.GROUPS_WRITE), Set.of(GatewayPermissionCodes.GROUPS_WRITE));
        assertThat(mapper.readValue("{\"displayName\":null}", GatewayGroupCreateRequestDTO.class).displayName()).isNull();
        assertThatThrownBy(() -> mapper.readValue("{\"ownerActorId\":\"1\"}", GatewayGroupCreateRequestDTO.class)).hasStackTraceContaining("FIELD_BINDING_UNKNOWN_OR_DUPLICATE");
        assertThatThrownBy(() -> mapper.readValue("{\"id\":\"1\"}", GatewayGroupVO.class)).hasStackTraceContaining("FIELD_ACCESS_DENIED");
        var ai = converter.getObjectMappersForType(LlmModelCommandDTO.class).get(org.springframework.http.MediaType.ALL);
        authenticate(manifest, Set.of(GatewayPermissionCodes.LLM_READ), Set.of(GatewayPermissionCodes.LLM_WRITE));
        assertThatThrownBy(() -> ai.readValue("{\"routes\":[{\"channelKey\":\"local\"}]}", LlmModelCommandDTO.class)).hasStackTraceContaining("FIELD_ACCESS_DENIED");
        authenticate(manifest, Set.of(GatewayPermissionCodes.LLM_WRITE), Set.of(GatewayPermissionCodes.LLM_WRITE));
        assertThat(ai.readValue("{\"name\":null}", LlmModelCommandDTO.class).getName()).isNull();
        assertThatThrownBy(() -> ai.readValue("{\"ownerActorId\":\"1\"}", LlmModelCommandDTO.class)).hasStackTraceContaining("FIELD_BINDING_UNKNOWN_OR_DUPLICATE");
    }

    @Test
    void jackson3DefaultCannotPrecedeThePermissionAwareHttpReader() throws Exception {
        var manifest=manifest(); var registry=registry(manifest);
        var writes=new PermissionFieldBindingGuard(registry,new CurrentRbac3User(),()->null,scopes());
        var factory=new PermissionJsonMapperFactory(new CurrentRbac3User(),SensitiveStrategyRegistry.defaults(),registry,writes);
        var converters=new ArrayList<org.springframework.http.converter.HttpMessageConverter<?>>();
        converters.add(new org.springframework.http.converter.StringHttpMessageConverter());
        converters.add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());
        converters.add(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter());
        new GatewayPermissionWebConfiguration(factory,new ObjectMapper().findAndRegisterModules()).extendMessageConverters(converters);
        assertThat(converters.get(0)).isInstanceOf(org.springframework.http.converter.StringHttpMessageConverter.class);
        assertThat(converters.get(1)).isInstanceOf(org.springframework.http.converter.ByteArrayHttpMessageConverter.class);
        assertThat(converters.get(2)).isInstanceOf(org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class);
        var reader=(org.springframework.http.converter.json.MappingJackson2HttpMessageConverter)converters.get(2);
        assertThat(reader.getObjectMapper().getRegisteredModuleIds()).contains("Rbac3FieldJacksonModule");
        authenticate(manifest,Set.of(GatewayPermissionCodes.READ),Set.of(GatewayPermissionCodes.GROUPS_WRITE));
        assertThatThrownBy(()->reader.getObjectMapper().readValue("{\"displayName\":null}",GatewayGroupCreateRequestDTO.class)).hasStackTraceContaining("FIELD_ACCESS_DENIED");
        assertThat(reader.getObjectMappersForType(LlmModelCommandDTO.class).get(org.springframework.http.MediaType.ALL).getRegisteredModuleIds()).contains("Rbac3FieldJacksonModule");
    }

    @Test
    void readFieldsNeedTheirOwnPermissionAndScopeEvenOnSuccessfulWriteResponses() throws Exception {
        var manifest = manifest(); var registry = registry(manifest);
        var fields = new PermissionFieldBindingGuard(registry, new CurrentRbac3User(), () -> null, scopes());
        var mapper = new PermissionJsonMapperFactory(new CurrentRbac3User(), SensitiveStrategyRegistry.defaults(), registry, fields).forTransport(new ObjectMapper().findAndRegisterModules());
        var response = new GatewayGroupVO("42", "group", "Group", "test", "default", null, true, 1L, NOW, NOW);
        authenticate(manifest, Set.of(GatewayPermissionCodes.READ, GatewayPermissionCodes.GROUPS_WRITE), Set.of(GatewayPermissionCodes.GROUPS_WRITE));
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).has("id")).isFalse();
        authenticate(manifest, Set.of(GatewayPermissionCodes.READ), Set.of(GatewayPermissionCodes.READ));
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).get("id").asText()).isEqualTo("42");
    }

    @Test
    void fieldCatalogExposesOnlyActualHostBindingsAndRequiresVerifiedUserAndNoForeignQuery() throws Exception {
        var manifest = manifest(); authenticate(manifest, Set.of(), Set.of()); var registry = registry(manifest);
        var users = new CurrentRbac3User(); var view = new CurrentEffectivePermissionView(users, registry, CLOCK);
        var adapter = new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter();
        adapter.setMessageConverters(List.of(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(); beans.addBean("adapter", adapter);
        var routes = new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping();
        for (Class<?> type : List.of(top.egon.cola.component.yuheng.admin.group.controller.GatewayGroupController.class,
                top.egon.cola.component.yuheng.admin.llm.controller.LlmConfigurationController.class)) {
            Object target = mock(type);
            for (Method method : type.getDeclaredMethods()) if (method.getAnnotation(PermissionResource.class) != null) {
                routes.registerMapping(org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/fixture/" + method.getName()).build(), target, method);
            }
        }
        beans.addBean("routes", routes);
        var controller = new top.egon.cola.component.yuheng.admin.auth.controller.GatewayFieldCatalogController(new PermissionFieldCatalog(registry, view, users),
                beans.getBeanProvider(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.class),
                beans.getBeanProvider(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class));
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/authorization/field-catalog");
        request.addParameter("keys", "yuheng.field.GatewayGroupCreateRequestDTO.displayName", "yuheng.field.GatewayGroupVO.id", "yuheng.field.LlmRouteBindingDTO.channelKey", "yuheng.field.McpArtifactUploadCommandDTO.artifact");
        assertThatThrownBy(() -> controller.current(request)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        top.egon.cola.platform.tianquan.shoubing.starter.security.VerifiedUserTokenCarrier.set(request, "verified-fixture");
        var response = controller.current(request); assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().data().fields().get("yuheng.field.GatewayGroupCreateRequestDTO.displayName").inputBinding()).isTrue();
        assertThat(response.getBody().data().fields().get("yuheng.field.GatewayGroupVO.id").inputBinding()).isFalse();
        assertThat(response.getBody().data().fields().get("yuheng.field.LlmRouteBindingDTO.channelKey").inputBinding()).isTrue();
        assertThat(response.getBody().data().fields().get("yuheng.field.McpArtifactUploadCommandDTO.artifact").inputBinding()).isTrue();
        request.addParameter("tenantId", "foreign"); assertThatThrownBy(() -> controller.current(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipartUploadGuardsFieldsBeforeReadingBytesAndRejectsUnknownParts() throws Exception {
        var manifest=manifest(); var guard=new PermissionFieldBindingGuard(registry(manifest),new CurrentRbac3User(),()->null,scopes());
        var service=mock(top.egon.cola.component.yuheng.admin.mcp.service.McpControlPlaneService.class);
        var controller=new top.egon.cola.component.yuheng.admin.mcp.controller.McpAppAdminController(service,guard);
        var file=mock(org.springframework.web.multipart.MultipartFile.class);
        authenticate(manifest,Set.of(GatewayPermissionCodes.MCP_READ),Set.of(GatewayPermissionCodes.MCP_WRITE));
        assertThatThrownBy(()->controller.upload("group","app","1","App","ui://app","text/html","default-src 'none'",
                Set.of("render"),Set.of(),0,0,"create",file,"intent",null)).hasStackTraceContaining("FIELD_ACCESS_DENIED");
        verifyNoInteractions(file,service);
        var request=new org.springframework.mock.web.MockMultipartHttpServletRequest();
        request.addPart(new org.springframework.mock.web.MockPart("ownerActorId",new byte[]{1}));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        authenticate(manifest,Set.of(GatewayPermissionCodes.MCP_WRITE),Set.of(GatewayPermissionCodes.MCP_WRITE));
        assertThatThrownBy(()->controller.upload("group","app","1","App","ui://app","text/html","default-src 'none'",
                Set.of("render"),Set.of(),0,0,"create",file,"intent",null)).hasStackTraceContaining("FIELD_BINDING_UNDECLARED");
        verifyNoInteractions(file,service);
    }

    @Test
    void serviceApiNeedsVerifiedIssuerFiniteScopeAndMatchingActivePolicy() throws Exception {
        var manifest=manifest(); var registry=registry(manifest);
        var client=mock(top.egon.cola.platform.tianquan.jianshen.starter.client.Rbac3AuthorizationClient.class);
        var digests=new PermissionManifestCanonicalizer().digests(manifest);
        when(client.fetchPolicy(eq("yuheng"),any())).thenReturn(new ServicePolicySnapshot(SCOPE,"1","2",
                new RuntimePolicyBinding("yuheng-admin-java","1",ARTIFACT,digests.policyDigest(),digests.manifestDigest()),
                manifest.resources().stream().map(PermissionManifest.ResourceDefinition::key).collect(java.util.stream.Collectors.toSet()),NOW.plusSeconds(30)));
        var guard=new PermissionExecutionGuard(registry,new CurrentRbac3User(),mock(SingleFlightSnapshotLoader.class),
                new PermissionServicePolicyGuard(registry,client,"yuheng",CLOCK),scopes());
        var service=new top.egon.cola.platform.tianquan.shoubing.contract.ServiceIdentityPrincipal("publisher","20","publisher","token",
                java.net.URI.create("https://api.example/yuheng"),1,Set.of(GatewayPermissionCodes.GROUPS_WRITE),"xingyuan","publisher","test","credential",
                NOW.minusSeconds(10),NOW.plusSeconds(60),"publisher",top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext.TENANT);
        SecurityContextHolder.getContext().setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(service,"",List.of()));
        assertThatThrownBy(()->guard.requireExecution("yuheng.api.gatewayGroup.create")).isInstanceOf(AuthorizationDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(new top.egon.cola.platform.tianquan.shoubing.starter.security.IdpAuthenticationToken(service,List.of(),java.net.URI.create("https://idp.example/tenants/20")));
        assertThatCode(()->guard.requireExecution("yuheng.api.gatewayGroup.create")).doesNotThrowAnyException();
        assertThatThrownBy(()->guard.requireExecution("yuheng.api.gatewayGroup.list")).isInstanceOf(AuthorizationDeniedException.class).hasMessage("SERVICE_SCOPE_DENIED");
    }

    @Test
    void localReadProjectionsKeepTheExistingLlmAndMcpJsonShape() {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var model=new top.egon.cola.component.yuheng.admin.llm.domain.vo.LlmModelVO().setKey("model").setName("Model")
                .setEnabled(true).setRevision(3L).setRoutes(List.of(new top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmRouteBindingDTO()
                        .setChannelKey("local").setUpstreamModel("internal").setCapabilities(Set.of()).setPriority(1).setWeight(1)));
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(top.egon.cola.component.yuheng.admin.llm.domain.vo.GatewayLlmModelVO.from(model)))
                .isEqualTo(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(model));
        var nativeRules=new top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent(List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(top.egon.cola.component.yuheng.admin.mcp.domain.vo.GatewayMcpRuleContentVO.from(nativeRules)))
                .isEqualTo(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(nativeRules));
    }

    private top.egon.cola.platform.tianquan.jianshen.starter.autoconfigure.Rbac3StarterProperties.UserScopePolicyProperties scopes() {
        var policy = new top.egon.cola.platform.tianquan.jianshen.starter.autoconfigure.Rbac3StarterProperties.UserScopePolicyProperties();
        policy.setEnabled(true); var values = new HashMap<String, Set<String>>();
        for (var field : GatewayPermissionCodes.class.getFields()) try { String code = (String) field.get(null); values.put(code, Set.of(code)); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        policy.setPermissionScopes(values); return policy;
    }

    private PermissionPolicyRegistry registry(PermissionManifest manifest) { return new PermissionPolicyRegistry(manifest, ARTIFACT, CLOCK, List.of(SCOPE)); }

    private PermissionManifest manifest() throws Exception {
        var fragment = new PermissionManifestReader().read(new ObjectMapper().readValue(Files.readString(
                Path.of("target/classes/META-INF/permissions/fragment-v1.json")), new TypeReference<Map<String, Object>>() { }));
        var producer = fragment.producer();
        return new PermissionManifest(fragment.schemaVersion(), fragment.application(),
                new PermissionManifest.Producer(producer.id(), producer.kind(), PermissionManifest.SnapshotMode.FULL,
                        producer.ownedKeyPrefixes(), producer.expectedModules(), producer.scannedModules(), false),
                fragment.artifact(), fragment.permissionDefinitions(), fragment.permissionReferences(), fragment.resources(), fragment.resourceReferences(), fragment.messages());
    }

    private void authenticate(PermissionManifest manifest, Set<String> permissions, Set<String> tokenScopes) {
        var digests = new PermissionManifestCanonicalizer().digests(manifest);
        var view = new EffectivePermissionSnapshot("https://idp.example/tenants/20", USER.subject(), SCOPE, "1", "2", "3",
                new EffectivePermissionSnapshot.ActiveRoleContext("yuheng", List.of("50"), List.of("50")), permissions.stream().sorted().toList(), NOW.plusSeconds(30));
        var snapshot = new SystemAuthorizationSnapshot("20", USER.subject(), "60", "yuheng", 2, 3, List.of("50"), null, null,
                permissions, manifest.resources().stream().map(PermissionManifest.ResourceDefinition::key).collect(java.util.stream.Collectors.toSet()),
                Map.of(), Map.of(), "checksum", NOW, view.snapshotExpiresAt(), view,
                new RuntimePolicyBinding("yuheng-admin-java", "1", ARTIFACT, digests.policyDigest(), digests.manifestDigest()));
        SecurityContextHolder.getContext().setAuthentication(new Rbac3AuthenticationToken(new Rbac3UserDetails(USER, snapshot),
                tokenScopes.stream().map(value -> new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_" + value)).toList()));
    }
}
