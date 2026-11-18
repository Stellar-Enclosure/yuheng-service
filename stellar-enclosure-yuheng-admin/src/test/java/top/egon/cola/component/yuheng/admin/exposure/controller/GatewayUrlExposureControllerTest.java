package top.egon.cola.component.yuheng.admin.exposure.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.egon.cola.component.yuheng.admin.config.GatewayAdminSecurityConfiguration;
import top.egon.cola.component.yuheng.admin.config.GatewayAiJsonConfiguration;
import top.egon.cola.component.yuheng.admin.exposure.converter.GatewayUrlExposureViewConverter;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.command.GatewayUrlExposureStateCommand;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.exception.GatewayUrlExposureException;
import top.egon.cola.component.yuheng.admin.exposure.domain.vo.GatewayUrlExposureVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureService;
import top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminExceptionHandler;
import top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminWebMvcConfiguration;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.AuthorizationSnapshotLoader;
import top.egon.cola.platform.tianquan.jianshen.starter.security.Rbac3BearerAuthenticationFilter;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceIdentityPrincipal;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.security.AccessTokenVerification;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpBearerAuthenticationFilter;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpEndpointAuthenticationPolicy;
import top.egon.cola.platform.tianquan.shoubing.starter.security.ServiceAccessTokenVerifier;
import top.egon.cola.platform.tianquan.shoubing.starter.security.UserAccessTokenVerifier;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.TokenAuditProofBO;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC, production filters and springdoc; verifier result and application service are controlled. */
class GatewayUrlExposureControllerTest {
    private static final String ROOT = "/api/v1/yuheng/admin/exposures";
    private static final String TAG = "\"exposure-810001-v12\"";
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private static GatewayUrlExposureService service;
    private static ObjectMapper json;

    @BeforeAll static void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Fixture.class);
        context.refresh();
        service = context.getBean(GatewayUrlExposureService.class);
        json = context.getBean("jacksonObjectMapper", ObjectMapper.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    @BeforeEach void resetState() { reset(service); SecurityContextHolder.clearContext(); }

    @Test void exposureStateHasItsOwnPublishedAdminRestBoundary() {
        assertThat(GatewayUrlExposureController.class.getAnnotation(RequestMapping.class).value()).containsExactly(ROOT);
    }

    @Test void bodylessReadUsesVerifiedActorAndPreservesStringIdsNullEvidenceAndMicrosecondUtc() throws Exception {
        var view = new GatewayUrlExposureViewConverter().toView(row().setId(9_007_199_254_740_993L));
        when(service.get(any(), any())).thenReturn(new GatewayUrlExposureService.QueryResult(view, TAG));
        mvc.perform(read(ROOT + "/810001").cookie(new Cookie("session", "irrelevant-cookie")))
                .andExpect(status().isOk()).andExpect(header().string("ETag", TAG))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().exists("x-egon-request-id"))
                .andExpect(jsonPath("$.exposureId").value("9007199254740993"))
                .andExpect(jsonPath("$.desiredGeneration").value("8")).andExpect(jsonPath("$.observedGeneration").value("7"))
                .andExpect(jsonPath("$.kubernetesRoute").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.conditions").isEmpty())
                .andExpect(jsonPath("$.lastErrorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-17T22:40:00.000000Z"))
                .andExpect(jsonPath("$.ownerTenantId").doesNotExist()).andExpect(jsonPath("$.observation").doesNotExist());
        var actor = ArgumentCaptor.forClass(AdminActor.class);
        verify(service).get(actor.capture(), any());
        assertThat(actor.getValue().actorId()).isEqualTo("publisher");
        assertThat(actor.getValue().oauthScopes()).containsExactly("yuheng:read");
        assertThat(actor.getValue().scopes()).containsExactly("yuheng:read");
    }

    @Test void writeOnlyReplacementHasComplete202And200ViewsLocationAndCorrectRetryHeaders() throws Exception {
        var view = new GatewayUrlExposureViewConverter().toView(row());
        when(service.putState(any(), any())).thenReturn(new GatewayUrlExposureService.CommandResult(202, view, TAG),
                new GatewayUrlExposureService.CommandResult(200, view, TAG));
        mvc.perform(change().content("{\"state\":\"CLOSED\",\"changeReason\":\"  emergency close  \"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", ROOT + "/810001"))
                .andExpect(header().string("Retry-After", "2")).andExpect(header().string("ETag", TAG))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().exists("x-egon-request-id"))
                .andExpect(jsonPath("$.desiredState").value("CLOSED")).andExpect(jsonPath("$.releaseId").value("820001"));
        mvc.perform(change().content(body())).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After")).andExpect(header().string("Location", ROOT + "/810001"));
        var command = ArgumentCaptor.forClass(GatewayUrlExposureStateCommand.class);
        verify(service, org.mockito.Mockito.times(2)).putState(any(), command.capture());
        assertThat(command.getAllValues().getFirst().changeReason()).isEqualTo("emergency close");
        assertThat(command.getAllValues().getFirst().exposureId()).isEqualTo(810001L);
    }

    @Test void malformedPathHeadersAndIdempotencyFailBeforeApplicationInvocation() throws Exception {
        for (String id : List.of("0", "01", "-1", "+1", "9223372036854775808")) {
            mvc.perform(put(ROOT + "/" + id + "/state").header("Authorization", "Bearer write")
                            .header("If-Match", TAG).header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("YUHENG_EXPOSURE_REQUEST_INVALID"));
        }
        for (String tag : List.of("*", "W/" + TAG, TAG + "," + TAG, "\"exposure-810002-v12\"",
                "\"exposure-810001-v01\"", "\"exposure-810001-v9223372036854775808\"")) {
            mvc.perform(put(ROOT + "/810001/state").header("Authorization", "Bearer write")
                            .header("If-Match", tag).header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(put(ROOT + "/810001/state").header("Authorization", "Bearer write")
                        .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("YUHENG_EXPOSURE_PRECONDITION_REQUIRED"));
        mvc.perform(change().header("If-Match", TAG).content(body())).andExpect(status().isBadRequest());
        mvc.perform(change().header("Idempotency-Key", "other").content(body())).andExpect(status().isBadRequest());
        mvc.perform(put(ROOT + "/810001/state").header("Authorization", "Bearer write").header("If-Match", TAG)
                        .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void bodyRejectsUnknownCoercedDuplicateTrailingAndInvalidEnumWithoutChangingGlobalDefaults() throws Exception {
        for (String body : List.of("{\"state\":\"CLOSED\",\"changeReason\":\"x\",\"ownerTenantId\":7}",
                "{\"state\":\"CLOSED\",\"changeReason\":99}", "{\"state\":\"CLOSED\",\"changeReason\":true}",
                "{\"state\":\"CLOSED\",\"state\":\"OPEN\",\"changeReason\":\"x\"}", body() + " {}",
                "{\"state\":\"PAUSED\",\"changeReason\":\"x\"}")) {
            mvc.perform(change().content(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("YUHENG_EXPOSURE_REQUEST_INVALID"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        for (String body : List.of("{\"state\":null,\"changeReason\":\"x\"}", "{\"state\":\"CLOSED\",\"changeReason\":\"   \"}")) {
            mvc.perform(change().content(body)).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("YUHENG_ADMIN_VALIDATION_FAILED")).andExpect(jsonPath("$.errors").isNotEmpty());
        }
        mvc.perform(change().contentType(MediaType.TEXT_PLAIN).content(body())).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
        assertThat(json.isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).isFalse();
    }

    @Test void getAcceptsNeitherBodyNorQueryAndPutRejectsCallerBindingQuery() throws Exception {
        mvc.perform(read(ROOT + "/810001?ownerTenantId=8")).andExpect(status().isBadRequest());
        mvc.perform(read(ROOT + "/810001").content("{\"ownerTenantId\":8}")).andExpect(status().isBadRequest());
        mvc.perform(read(ROOT + "/810001").header("Transfer-Encoding", "chunked")).andExpect(status().isBadRequest());
        mvc.perform(change().queryParam("gatewayName", "foreign").content(body())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void cookieAloneInvalidBearerAndWrongScopeDoNotReachService() throws Exception {
        mvc.perform(get(ROOT + "/810001").cookie(new Cookie("session", "opaque")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("YUHENG_ADMIN_UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(ROOT + "/810001").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("YUHENG_ADMIN_UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(get(ROOT + "/810001").header("Authorization", "Bearer other"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("YUHENG_EXPOSURE_ACCESS_DENIED"));
        verifyNoInteractions(service);
    }

    @Test void typedFailuresPreserveSafeStatusAndRevisionWithoutStorageDetails() throws Exception {
        when(service.putState(any(), any())).thenThrow(GatewayUrlExposureException.preconditionFailed(15));
        mvc.perform(change().content(body())).andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.currentRevision").value(15)).andExpect(jsonPath("$.code").value("YUHENG_EXPOSURE_PRECONDITION_FAILED"));
        doThrow(GatewayUrlExposureException.withdrawn(16)).when(service).putState(any(), any());
        mvc.perform(change().content(body())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("YUHENG_EXPOSURE_WITHDRAWN"));
        when(service.get(any(), any())).thenThrow(new GatewayAdminNotFoundException("Not found"));
        mvc.perform(read(ROOT + "/810001")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("YUHENG_ADMIN_NOT_FOUND"));
        doThrow(GatewayUrlExposureException.controlUnavailable(new IllegalStateException("private-db-secret")))
                .when(service).get(any(), any());
        var response = mvc.perform(read(ROOT + "/810001")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private-db-secret");
    }

    @Test void closedConvergenceNeedsAllCurrentEvidenceWithoutRewritingStoredObservation() {
        var converter = new GatewayUrlExposureViewConverter();
        var row = row().setObservedState("CLOSED").setObservedGeneration(8L).setReconcileStatus("SYNCED").setObservation(observation(false));
        assertThat(converter.toView(row).getReconcileStatus()).isEqualTo("SYNCED");
        assertThat(converter.toView(row).getKubernetesRoute().getControllerName()).isNull();
        assertThat(converter.toView(row).getKubernetesRoute().getConditionObservedGeneration()).isNull();
        ((ObjectNode) row.getObservation().path("conditions").get(0)).put("observedGeneration", "7");
        var stale = converter.toView(row);
        assertThat(stale.getReconcileStatus()).isEqualTo("PENDING");
        assertThat(stale.getObservedGeneration()).isEqualTo("8");
        assertThat(stale.getObservedState()).isEqualTo("CLOSED");
        assertThat(stale.getLastErrorCode()).isNull();
        assertThat(row.getReconcileStatus()).isEqualTo("SYNCED");
        assertThat(row.getObservation().path("kubernetesRoute").path("controllerName").asText()).isEqualTo("io.cilium/gateway-controller");
    }

    @Test void attachedOldParentsAreNotReachabilityProofAndPrivateJsonNeverLeaks() throws Exception {
        var row = row().setDesiredState(GatewayUrlExposureStateEnum.OPEN).setObservedState("OPEN")
                .setObservedGeneration(8L).setReconcileStatus("SYNCED").setObservation(observation(true));
        var converter = new GatewayUrlExposureViewConverter();
        assertThat(converter.toView(row).getReconcileStatus()).isEqualTo("PENDING");
        ((ObjectNode) row.getObservation().path("kubernetesRoute")).put("conditionObservedGeneration", "4");
        var projected = converter.toView(row);
        assertThat(projected.getKubernetesRoute().getControllerName()).isNull();
        assertThat(projected.getKubernetesRoute().getConditionObservedGeneration()).isNull();
        assertThat(projected.getKubernetesRoute().getAttached()).isTrue();
        assertThat(json.writeValueAsString(projected)).doesNotContain("privateProof", "internal-owner", "gatewayUid", "tenantId");
    }

    @Test void malformedNonNullEvidenceNeverBecomesAnAbsentButConvergedRoute() {
        var converter = new GatewayUrlExposureViewConverter();
        var row = row().setObservedState("CLOSED").setObservedGeneration(8L).setReconcileStatus("SYNCED");
        var malformedRoute = observation(false);
        ((ObjectNode) malformedRoute.path("kubernetesRoute")).put("uid", "foreign-route-uid");
        row.setObservation(malformedRoute);
        assertThat(converter.toView(row).getKubernetesRoute()).isNull();
        assertThat(converter.toView(row).getReconcileStatus()).isEqualTo("PENDING");
        var malformedCondition = observation(false);
        malformedCondition.withArray("conditions").addObject().put("type", "Detached").put("status", "invalid");
        row.setObservation(malformedCondition);
        assertThat(converter.toView(row).getReconcileStatus()).isEqualTo("PENDING");
        row.setObservation(observation(false).put("schemaVersion", 4_294_967_297L));
        assertThat(converter.toView(row).getReconcileStatus()).isEqualTo("PENDING");
        assertThat(row.getObservedGeneration()).isEqualTo(8L);
        assertThat(row.getObservedState()).isEqualTo("CLOSED");
    }

    @Test void currentOpenEvidenceAndWriteOnlyFieldRulesHaveExplicitPositivePaths() throws Exception {
        var row = row().setDesiredState(GatewayUrlExposureStateEnum.OPEN).setObservedState("OPEN")
                .setObservedGeneration(8L).setReconcileStatus("SYNCED").setObservation(observation(true));
        var conditions = ((ObjectNode) row.getObservation()).putArray("conditions");
        for (String type : List.of("Accepted", "ResolvedRefs", "Programmed", "DataplaneConverged")) {
            conditions.addObject().put("type", type).put("status", "True").put("reason", "Verified")
                    .put("message", "Current writer evidence").put("observedGeneration", "8")
                    .put("lastTransitionTime", "2026-11-17T22:40:02.000000Z");
        }
        assertThat(new GatewayUrlExposureViewConverter().toView(row).getReconcileStatus()).isEqualTo("SYNCED");
        for (Class<?> type : List.of(GatewayUrlExposureVO.class, GatewayUrlExposureVO.KubernetesRouteVO.class,
                GatewayUrlExposureVO.ConditionVO.class)) {
            for (var field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                var rule = field.getAnnotation(top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions.class);
                assertThat(rule.plainRead().mode()).isEqualTo(top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY);
                assertThat(rule.plainRead().codes()).containsExactlyInAnyOrder("yuheng:read", "yuheng:exposures:write");
            }
        }
    }

    @Test void actualSpringdocHasBodylessGetStrictTwoFieldRequestBearerAndErrorStatuses() throws Exception {
        var body = mvc.perform(read("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode doc = json.readTree(body);
        JsonNode get = doc.path("paths").path(ROOT + "/{exposureId}").path("get");
        JsonNode put = doc.path("paths").path(ROOT + "/{exposureId}/state").path("put");
        assertThat(get.path("operationId").asText()).isEqualTo("getYuhengExposure");
        assertThat(get.has("requestBody")).isFalse();
        assertThat(put.path("operationId").asText()).isEqualTo("replaceYuhengExposureState");
        assertThat(put.path("security").get(0).path("yuhengBearer").isArray()).isTrue();
        assertThat(put.path("security").get(0).path("yuhengBearer").size()).isZero();
        assertThat(doc.at("/components/securitySchemes/yuhengBearer/type").asText()).isEqualTo("http");
        assertThat(doc.at("/components/securitySchemes/yuhengBearer/scheme").asText()).isEqualTo("bearer");
        for (String code : List.of("200", "202", "400", "401", "403", "404", "409", "412", "415", "422", "428", "429", "503"))
            assertThat(put.path("responses").has(code)).as(code).isTrue();
        assertThat(get.path("parameters").size()).isEqualTo(1);
        assertThat(put.path("parameters").toString()).contains("If-Match", "Idempotency-Key").doesNotContain("actorId", "ownerTenantId", "oauthScopes");
        JsonNode request = doc.at("/components/schemas/GatewayUrlExposureStateRequest");
        assertThat(request.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(request.path("properties").size()).isEqualTo(2);
        assertThat(doc.at("/components/schemas/GatewayUrlExposure/properties/desiredGeneration/type").asText()).isEqualTo("string");
    }

    private static MockHttpServletRequestBuilder read(String path) { return get(path).header("Authorization", "Bearer read"); }
    private static MockHttpServletRequestBuilder change() {
        return put(ROOT + "/810001/state").header("Authorization", "Bearer write").header("If-Match", TAG)
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON);
    }
    private static String body() { return "{\"state\":\"CLOSED\",\"changeReason\":\"close\"}"; }
    private static GatewayUrlExposureBO row() {
        return new GatewayUrlExposureBO().setId(810001L).setTenantId(0L).setOwnerTenantId(7L)
                .setGatewayGroupId(800001L).setRouteId("orders-read").setHostname("api.example.com").setPathValue("/orders")
                .setHttpMethod("GET").setDesiredState(GatewayUrlExposureStateEnum.CLOSED).setObservedState("OPEN")
                .setDesiredGeneration(8L).setObservedGeneration(7L).setReconcileStatus("PENDING").setReleaseId(820001L)
                .setContentSha256("a".repeat(64)).setRouteKind("HTTPRoute").setRouteNamespace("yuheng-routes")
                .setRouteName("yuheng-exposure-810001").setRouteUid("route-uid").setVersion(12L)
                .setUpdateTime(Instant.parse("2026-11-17T22:40:00Z"));
    }
    private static ObjectNode observation(boolean attached) {
        var root = json.createObjectNode().put("schemaVersion", 1).put("privateProof", "internal-owner");
        root.putObject("kubernetesRoute").put("kind", "HTTPRoute").put("namespace", "yuheng-routes")
                .put("name", "yuheng-exposure-810001").put("uid", "route-uid").put("resourceVersion", "15683")
                .put("metadataGeneration", "5").put("controllerName", "io.cilium/gateway-controller")
                .put("conditionObservedGeneration", "5").put("attached", attached);
        var conditions = root.putArray("conditions");
        for (String type : List.of("Detached", "NoAlternateRoute", "DataplaneConverged"))
            conditions.addObject().put("type", type).put("status", "True").put("reason", "Verified")
                    .put("message", "Verified current evidence").put("observedGeneration", "8")
                    .put("lastTransitionTime", "2026-11-17T22:40:02.000000Z");
        return root;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    @Import({GatewayUrlExposureController.class, GatewayUrlExposureExceptionAdvice.class,
            GatewayAdminExceptionHandler.class, GatewayAdminWebMvcConfiguration.class, GatewayAiJsonConfiguration.class,
            GatewayAdminSecurityConfiguration.class, SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class})
    static class Fixture implements WebMvcConfigurer {
        @Bean("jacksonObjectMapper") ObjectMapper json() {
            return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
        @Bean("gatewayAdminExceptionHandler")
        top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminExceptionHandler gatewayAdminExceptionHandler() {
            return new top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminExceptionHandler();
        }
        @Bean("gatewayUrlExposureServiceImpl") GatewayUrlExposureService service() { return mock(GatewayUrlExposureService.class); }
        @Bean OpenAPI exposureOpenApi() {
            return new OpenAPI().components(new Components().addSecuritySchemes("yuhengBearer",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
        }
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new ByteArrayHttpMessageConverter());
            converters.add(new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
            converters.add(new MappingJackson2HttpMessageConverter(json()));
        }
        @Bean("idpBearerAuthenticationFilter") IdpBearerAuthenticationFilter bearer() {
            var services = mock(ServiceAccessTokenVerifier.class);
            when(services.verify(anyString())).thenAnswer(call -> {
                String token = call.getArgument(0);
                if ("invalid".equals(token)) return new AccessTokenVerification.Invalid<ServiceIdentityPrincipal>("JWT_INVALID");
                Set<String> scopes = switch (token) {
                    case "read" -> Set.of("yuheng:read");
                    case "write" -> Set.of("yuheng:exposures:write");
                    default -> Set.of("yuheng:drafts:write");
                };
                var issued = Instant.now().minusSeconds(1);
                var principal = new ServiceIdentityPrincipal("publisher", null, "publisher", "jti-1",
                        URI.create("https://api.example/yuheng"), 7, scopes, "biz", "publisher", "test", "credential-1",
                        issued, issued.plusSeconds(300), "publisher-app", ServiceTokenContext.PLATFORM);
                var proof = new TokenAuditProofBO(URI.create("https://idp.example/platform"), principal.resourceUri(),
                        "a".repeat(64), "b".repeat(64), "c".repeat(64));
                return new AccessTokenVerification.Valid<>(principal, scopes, proof, proof.issuer());
            });
            return new IdpBearerAuthenticationFilter(mock(UserAccessTokenVerifier.class), services,
                    new IdpEndpointAuthenticationPolicy(List.of(), List.of("/**"), false), json());
        }
        @Bean("rbac3BearerAuthenticationFilter") Rbac3BearerAuthenticationFilter rbac() {
            return new Rbac3BearerAuthenticationFilter(mock(AuthorizationSnapshotLoader.class), json());
        }
    }
}
