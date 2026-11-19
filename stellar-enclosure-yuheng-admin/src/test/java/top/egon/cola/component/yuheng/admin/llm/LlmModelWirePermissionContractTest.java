package top.egon.cola.component.yuheng.admin.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmModelCommandDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmCacheModeEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmRoutingModeEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.vo.*;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.time.*;
import java.nio.file.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmCachePolicyDTO;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.*;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.*;
import top.egon.cola.platform.tianquan.jianshen.starter.field.*;
import top.egon.cola.platform.tianquan.jianshen.starter.permission.*;
import top.egon.cola.platform.tianquan.jianshen.starter.security.*;
import top.egon.cola.platform.tianquan.shoubing.contract.*;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.*;

class LlmModelWirePermissionContractTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test void nullRoutingAndCacheRemainExplicitKeysInTheReadProjection(){
        var model=model();var expected=json.valueToTree(model);var actual=json.valueToTree(GatewayLlmModelVO.from(model));
        assertThat(actual).isEqualTo(expected);assertThat(actual.has("routingMode")).isTrue();assertThat(actual.get("routingMode").isNull()).isTrue();
        assertThat(actual.has("cachePolicy")).isTrue();assertThat(actual.get("cachePolicy").isNull()).isTrue();
    }
    @Test void nonnullRoutingAndEveryNestedCacheValueSurviveReadProjection(){
        var cache=new LlmCachePolicyVO().setMode(LlmCacheModeEnum.SEMANTIC).setTtlSeconds(120).setMaxCacheTemperature(0.2d)
                .setSemanticThreshold(0.95d).setEmbeddingModelKey("embedding-one").setPromptCacheOptimizationEnabled(true);
        var model=model().setRoutingMode(LlmRoutingModeEnum.PREFIX_AFFINITY).setCachePolicy(cache);
        var actual=json.valueToTree(GatewayLlmModelVO.from(model));assertThat(actual).isEqualTo(json.valueToTree(model));
        assertThat(actual.get("routingMode").asText()).isEqualTo("PREFIX_AFFINITY");assertThat(actual.get("cachePolicy").get("mode").asText()).isEqualTo("SEMANTIC");
    }
    @Test void commandRoundTripPreservesExplicitNullAndNonnullValuesWithoutAddingDefaults() throws Exception {
        var nil=json.readValue("{\"routingMode\":null,\"cachePolicy\":null}",LlmModelCommandDTO.class);
        assertThat(nil.getRoutingMode()).isNull();assertThat(nil.getCachePolicy()).isNull();
        var source=json.readTree("{\"routingMode\":\"PREFIX_AFFINITY\",\"cachePolicy\":{\"mode\":\"SEMANTIC\",\"ttlSeconds\":120,\"maxCacheTemperature\":0.2,\"semanticThreshold\":0.95,\"embeddingModelKey\":\"embedding-one\",\"promptCacheOptimizationEnabled\":true}}");
        var restored=json.valueToTree(json.treeToValue(source,LlmModelCommandDTO.class));
        assertThat(restored.get("routingMode")).isEqualTo(source.get("routingMode"));assertThat(restored.get("cachePolicy")).isEqualTo(source.get("cachePolicy"));
    }
    @AfterEach void clearIdentity(){SecurityContextHolder.clearContext();}

    @Test void allNullableAndNestedFieldsDeclareExplicitFiniteReadWritePolicies() throws Exception {
        for(var type:List.of(LlmModelCommandDTO.class,GatewayLlmModelVO.class,LlmCachePolicyDTO.class,LlmCachePolicyVO.class)){
            boolean write=type.getSimpleName().endsWith("DTO");
            var fields=type==LlmModelCommandDTO.class||type==GatewayLlmModelVO.class
                    ?List.of(type.getDeclaredField("routingMode"),type.getDeclaredField("cachePolicy"))
                    :List.of(type.getDeclaredFields());
            for(var field:fields){var resource=field.getAnnotation(PermissionResource.class);var policy=field.getAnnotation(FieldPermissions.class);
                assertThat(resource).as(type.getSimpleName()+"."+field.getName()).isNotNull();assertThat(policy).isNotNull();
                assertThat(resource.oauthScopes()).containsExactly(write?GatewayPermissionCodes.LLM_WRITE:GatewayPermissionCodes.LLM_READ);
                assertThat(resource.allowedPrincipals()).containsExactly(PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE);
                assertThat(write?policy.write().codes():policy.plainRead().codes()).containsExactly(write?GatewayPermissionCodes.LLM_WRITE:GatewayPermissionCodes.LLM_READ);
                assertThat(resource.key()).isEqualTo("yuheng.field."+type.getSimpleName()+"."+field.getName());
            }
        }
    }

    @Test @EnabledIfSystemProperty(named="permission.producer.enabled",matches="true")
    void realPermissionMapperPreservesGrantedNestedPoliciesAndDeniesExplicitNullWritesWithoutGrant() throws Exception {
        var manifest=manifest();var guarded=permissionMapper(manifest);
        authenticate(manifest,Set.of(GatewayPermissionCodes.LLM_WRITE),Set.of(GatewayPermissionCodes.LLM_WRITE),Set.of());
        String body="{\"routingMode\":\"PREFIX_AFFINITY\",\"cachePolicy\":{\"mode\":\"SEMANTIC\",\"ttlSeconds\":120,\"maxCacheTemperature\":0.2,\"semanticThreshold\":0.95,\"embeddingModelKey\":\"embedding-one\",\"promptCacheOptimizationEnabled\":true}}";
        var command=guarded.readValue(body,LlmModelCommandDTO.class);assertThat(command.getRoutingMode()).isEqualTo(LlmRoutingModeEnum.PREFIX_AFFINITY);
        assertThat(command.getCachePolicy().getTtlSeconds()).isEqualTo(120);
        authenticate(manifest,Set.of(GatewayPermissionCodes.LLM_READ),Set.of(GatewayPermissionCodes.LLM_WRITE),Set.of());
        assertThatThrownBy(()->guarded.readValue("{\"routingMode\":null,\"cachePolicy\":null}",LlmModelCommandDTO.class)).hasStackTraceContaining("FIELD_ACCESS_DENIED");
        var model=model().setRoutingMode(LlmRoutingModeEnum.PREFIX_AFFINITY).setCachePolicy(new LlmCachePolicyVO().setMode(LlmCacheModeEnum.SEMANTIC)
                .setTtlSeconds(120).setMaxCacheTemperature(0.2).setSemanticThreshold(0.95).setEmbeddingModelKey("embedding-one").setPromptCacheOptimizationEnabled(true));
        authenticate(manifest,Set.of(GatewayPermissionCodes.LLM_READ),Set.of(GatewayPermissionCodes.LLM_READ),Set.of());
        var output=json.readTree(guarded.writeValueAsString(GatewayLlmModelVO.from(model)));
        assertThat(output.get("routingMode").asText()).isEqualTo("PREFIX_AFFINITY");assertThat(output.get("cachePolicy").get("mode").asText()).isEqualTo("SEMANTIC");
        authenticate(manifest,Set.of(GatewayPermissionCodes.LLM_WRITE),Set.of(GatewayPermissionCodes.LLM_WRITE),Set.of());
        var denied=json.readTree(guarded.writeValueAsString(GatewayLlmModelVO.from(model)));
        assertThat(denied.has("routingMode")).isFalse();assertThat(denied.has("cachePolicy")).isFalse();
    }

    @Test @EnabledIfSystemProperty(named="permission.producer.enabled",matches="true")
    void eachNestedInputFieldRequiresItsOwnActiveResourceBinding() throws Exception {
        var manifest=manifest();var guarded=permissionMapper(manifest);
        String body="{\"cachePolicy\":{\"mode\":\"SEMANTIC\",\"ttlSeconds\":120,\"maxCacheTemperature\":0.2,\"semanticThreshold\":0.95,\"embeddingModelKey\":\"embedding-one\",\"promptCacheOptimizationEnabled\":true}}";
        for(String field:List.of("mode","ttlSeconds","maxCacheTemperature","semanticThreshold","embeddingModelKey","promptCacheOptimizationEnabled")){
            authenticate(manifest,Set.of(GatewayPermissionCodes.LLM_WRITE),Set.of(GatewayPermissionCodes.LLM_WRITE),Set.of("yuheng.field.LlmCachePolicyDTO."+field));
            assertThatThrownBy(()->guarded.readValue(body,LlmModelCommandDTO.class)).as(field).hasStackTraceContaining("AUTH_DEPLOYMENT_BINDING_MISMATCH");
        }
    }

    private static final Instant NOW=Instant.parse("2026-10-26T00:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final String ARTIFACT="a".repeat(64);
    private static final CatalogScope SCOPE=new CatalogScope("10","20","xingyuan","yuheng","default","test");
    private static final IdentityPrincipal USER=new IdentityPrincipal("30","20","40",Set.of("https://api.example/yuheng"),NOW.minusSeconds(10),NOW.plusSeconds(300),AuthenticationContext.of("PASSWORD",NOW.minusSeconds(20)));
    private PermissionManifest manifest() throws Exception {
        var fragment=new PermissionManifestReader().read(json.readValue(Files.readString(Path.of("target/classes/META-INF/permissions/fragment-v1.json")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        var producer=fragment.producer();return new PermissionManifest(fragment.schemaVersion(),fragment.application(),new PermissionManifest.Producer(producer.id(),producer.kind(),PermissionManifest.SnapshotMode.FULL,
                producer.ownedKeyPrefixes(),producer.expectedModules(),producer.scannedModules(),false),fragment.artifact(),fragment.permissionDefinitions(),fragment.permissionReferences(),fragment.resources(),fragment.resourceReferences(),fragment.messages());
    }
    private ObjectMapper permissionMapper(PermissionManifest manifest){
        var registry=new PermissionPolicyRegistry(manifest,ARTIFACT,CLOCK,List.of(SCOPE));var users=new CurrentRbac3User();
        var scopes=new top.egon.cola.platform.tianquan.jianshen.starter.autoconfigure.Rbac3StarterProperties.UserScopePolicyProperties();scopes.setEnabled(true);
        scopes.setPermissionScopes(Map.of(GatewayPermissionCodes.LLM_WRITE,Set.of(GatewayPermissionCodes.LLM_WRITE),GatewayPermissionCodes.LLM_READ,Set.of(GatewayPermissionCodes.LLM_READ)));
        var guard=new PermissionFieldBindingGuard(registry,users,()->null,scopes);
        return new PermissionJsonMapperFactory(users,top.egon.cola.component.common.desensitize.strategy.SensitiveStrategyRegistry.defaults(),registry,guard).forTransport(json);
    }
    private void authenticate(PermissionManifest manifest,Set<String> permissions,Set<String> scopes,Set<String> omitted){
        var digests=new PermissionManifestCanonicalizer().digests(manifest);var view=new EffectivePermissionSnapshot("https://idp.example/tenants/20",USER.subject(),SCOPE,"1","2","3",
                new EffectivePermissionSnapshot.ActiveRoleContext("yuheng",List.of("50"),List.of("50")),permissions.stream().sorted().toList(),NOW.plusSeconds(30));
        var snapshot=new SystemAuthorizationSnapshot("20",USER.subject(),"60","yuheng",2,3,List.of("50"),null,null,permissions,
                manifest.resources().stream().map(PermissionManifest.ResourceDefinition::key).filter(key->!omitted.contains(key)).collect(java.util.stream.Collectors.toSet()),Map.of(),Map.of(),"checksum",NOW,view.snapshotExpiresAt(),view,
                new RuntimePolicyBinding("yuheng-admin-java","1",ARTIFACT,digests.policyDigest(),digests.manifestDigest()));
        SecurityContextHolder.getContext().setAuthentication(new Rbac3AuthenticationToken(new Rbac3UserDetails(USER,snapshot),scopes.stream().map(value->new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_"+value)).toList()));
    }

    private static LlmModelVO model(){return new LlmModelVO().setKey("model").setName("Model").setEnabled(true).setRevision(3L).setRoutes(List.of());}
}
