package top.egon.cola.component.yuheng.test.webflux;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.client.RestTemplate;
import top.egon.cola.platform.tianquan.shoubing.contract.state.IdentityAuditReceipt;
import top.egon.cola.platform.tianquan.shoubing.core.audit.TokenUsageAuditEvent;
import top.egon.cola.platform.tianquan.shoubing.core.audit.TokenUsageAuditFingerprint;
import top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder;
import top.egon.cola.platform.tianquan.shoubing.core.resource.ResourceServerStatus;
import top.egon.cola.platform.tianquan.shoubing.starter.audit.OAuthUsageObservationProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.audit.TokenUsageAuditComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.observability.IdpSecurityObservationReceiverEnum;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpJwtVerifier;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.OAuthRevocationReader;
import top.egon.cola.platform.tianquan.shoubing.starter.security.ServiceAccessTokenVerifier;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TenantIssuerAuthenticationManagerComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TrustedIssuerRegistry;
import top.egon.cola.platform.tianquan.shoubing.starter.security.UserAccessTokenVerifier;
import top.egon.cola.platform.tianquan.shoubing.starter.security.VerifiedPrincipalConverter;
import top.egon.cola.platform.tianquan.shoubing.starter.security.reactive.ReactiveOAuthRevocationReader;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.converter.TokenUsageAuditEventConverter;
import top.egon.cola.platform.tianquan.shoubing.starter.state.IdentityAuthorityClient;
import top.egon.cola.platform.tianquan.shoubing.starter.state.IdentityOAuthClientStateReader;
import top.egon.cola.platform.tianquan.shoubing.starter.state.IdentityResourceServerState;
import top.egon.cola.platform.tianquan.shoubing.starter.state.IdentityResourceServerStateReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.test.web.reactive.server.WebTestClient;
import top.egon.cola.component.tianshu.autoconfigure.properties.DdcProperties;
import top.egon.cola.component.tianshu.model.lease.DdcLeaseOperationStatus;
import top.egon.cola.component.tianshu.model.lease.DdcLeaseRole;
import top.egon.cola.component.tianshu.model.registry.DdcServiceCatalogSnapshot;
import top.egon.cola.component.tianshu.model.registry.DdcServiceKey;
import top.egon.cola.component.tianshu.model.registry.DdcServiceLeaseRequest;
import top.egon.cola.component.tianshu.model.registry.DdcServiceQuery;
import top.egon.cola.component.tianshu.model.registry.DdcServiceRegistration;
import top.egon.cola.component.tianshu.model.registry.DdcServiceSnapshot;
import top.egon.cola.component.tianshu.model.lease.DdcLeaseOperationResult;
import top.egon.cola.component.tianshu.model.lease.DdcLeaseSession;
import top.egon.cola.component.tianshu.api.registry.DdcRegistrySubscription;
import top.egon.cola.component.tianshu.service.registry.DdcServiceKeyFactory;
import top.egon.cola.component.tianshu.api.client.DdcServiceRegistryClient;
import top.egon.cola.component.tianshu.http.registration
        .DdcHttpRegistrationContributor;
import top.egon.cola.component.tianshu.http.registration.DdcHttpRegistrationRuntime;
import top.egon.cola.component.yuheng.openapi.annotation.EgonApiCatalog;
import top.egon.cola.component.yuheng.openapi.annotation.EgonGatewayPolicy;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceTokenRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = GatewayWebFluxHttpTestProviderApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://idp.example.test/jwks"
)
@AutoConfigureWebTestClient
@Import(WebFluxHttpProviderContractTest.ProviderTestConfiguration.class)
class WebFluxHttpProviderContractTest {

    @Autowired
    private DdcHttpRegistrationRuntime runtime;

    @Autowired
    private RecordingRegistry registry;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RealtimeWebSocketProbe webSocketProbe;

    @LocalServerPort
    private int port;

    @Test
    void reportsAnnotatedMonoEndpointAndAutoRegisters() throws Exception {
        assertNotNull(runtime);
        assertEquals(1, registry.registrations.get());
        assertEquals(
                "stellar-enclosure-yuheng-test-http-provider",
                registry.registration.serviceKey().serviceName()
        );
        assertEquals(
                "1.0.0-live",
                registry.registration.serviceKey().version()
        );
        assertEquals(
                "webflux-http-provider-default",
                registry.registration.instanceId()
        );
        assertEquals(
                "zone-b",
                registry.registration.metadata().get("yuheng.zone")
        );
        assertTrue(registry.registration.port() > 0);

        webTestClient.mutate().defaultHeader("Authorization", "Bearer " + documentToken("yuheng.openapi.read")).build()
                .get()
                .uri("/v3/api-docs/inventory-reactive")
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .value(document -> assertGolden(
                        document,
                        "inventory-reactive"));

        webTestClient.get()
                .uri("/test/items/item-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("item-1")
                .jsonPath("$.providerId")
                .isEqualTo("webflux-http-provider-default")
                .jsonPath("$.framework").isEqualTo("webflux");

        webTestClient.get()
                .uri("/api/providers/request-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestId").isEqualTo("request-1")
                .jsonPath("$.providerId")
                .isEqualTo("webflux-http-provider-default")
                .jsonPath("$.framework").isEqualTo("webflux");
    }

    @Test
    void groupedDocumentsRequireTheGatewayOpenApiScope() throws Exception {
        webTestClient.get()
                .uri("/v3/api-docs/inventory-reactive")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.mutate().defaultHeader("Authorization", "Bearer " + documentToken("yuheng.other")).build()
                .get()
                .uri("/v3/api-docs/inventory-reactive")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void everyReactiveControllerUsesThePublishedCatalogGroup()
            throws Exception {
        assertEquals("inventory-reactive", ProviderIdentityController.class
                .getAnnotation(EgonApiCatalog.class).interfaceGroupCode());
        assertEquals("inventory-reactive", ReactiveInventoryController.class
                .getAnnotation(EgonApiCatalog.class).interfaceGroupCode());
        assertEquals("inventory-reactive", StreamingTransportController.class
                .getAnnotation(EgonApiCatalog.class).interfaceGroupCode());
        assertEquals(EgonGatewayPolicy.Exposure.EXTERNAL,
                ReactiveInventoryController.class.getMethod(
                        "item", String.class)
                        .getAnnotation(EgonGatewayPolicy.class).exposure());
    }

    @Test
    void streamsJsonSseUploadAndOpaqueBinaryWithoutDiscoveringWebSocket()
            throws Exception {
        byte[] json = ("{\"model\":\"gpt-test\",\"input\":["
                + "{\"type\":\"input_text\",\"text\":\"你好\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        webTestClient.post()
                .uri("/test/transport/json")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(byte[].class).isEqualTo(json);

        byte[] firstSseBuffer = webTestClient.get()
                .uri("/test/transport/sse")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(
                        MediaType.TEXT_EVENT_STREAM
                )
                .expectHeader().cacheControl(CacheControl.noCache())
                .returnResult(byte[].class)
                .getResponseBody()
                .next()
                .block(Duration.ofSeconds(2));
        assertNotNull(firstSseBuffer);
        assertTrue(new String(firstSseBuffer, StandardCharsets.UTF_8)
                .contains("response.output_text.delta"));

        int chunkBytes = 8 * 1024;
        int chunkCount = 257;
        MessageDigest expectedDigest = MessageDigest.getInstance("SHA-256");
        for (int index = 0; index < chunkCount; index++) {
            expectedDigest.update(uploadChunk(index, chunkBytes));
        }
        webTestClient.post()
                .uri("/test/transport/upload")
                .contentType(MediaType.parseMediaType(
                        "multipart/form-data;boundary=stellar-enclosure-yuheng-test"
                ))
                .body(BodyInserters.fromDataBuffers(
                        reactor.core.publisher.Flux.range(0, chunkCount)
                                .map(index -> DefaultDataBufferFactory
                                        .sharedInstance.wrap(uploadChunk(
                                                index,
                                                chunkBytes
                                        )))
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.bytes")
                .isEqualTo((long) chunkBytes * chunkCount)
                .jsonPath("$.chunks").value(value -> assertTrue(
                        ((Number) value).intValue() > 1
                ))
                .jsonPath("$.maxChunkBytes").value(value -> assertTrue(
                        ((Number) value).longValue()
                                < (long) chunkBytes * chunkCount
                ))
                .jsonPath("$.sha256").isEqualTo(
                        HexFormat.of().formatHex(expectedDigest.digest())
                );

        byte[] binary = webTestClient.get()
                .uri("/test/transport/binary")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("audio/mpeg")
                .expectHeader().valueEquals(
                        "Content-Disposition",
                        "attachment; filename=\"yuheng-test-audio.bin\""
                )
                .expectHeader().valueEquals("Content-Encoding", "identity")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        assertNotNull(binary);
        assertEquals(88, binary.length);
        assertEquals((byte) 0xff, binary[0]);
        assertEquals((byte) 0x80, binary[3]);

        webTestClient.mutate().defaultHeader("Authorization", "Bearer " + documentToken("yuheng.openapi.read")).build()
                .get()
                .uri("/v3/api-docs/inventory-reactive")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertFalse(body.contains(
                        "/test/transport/realtime")));
    }

    @Test
    void echoesWebSocketTextBinaryPingAndCloseFrames() throws Exception {
        RecordingWebSocketListener listener =
                new RecordingWebSocketListener();
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + port
                                + "/test/transport/realtime"),
                        listener
                )
                .get(3, TimeUnit.SECONDS);

        socket.sendText("realtime-text", true).get(3, TimeUnit.SECONDS);
        assertEquals(
                "realtime-text",
                listener.text.get(3, TimeUnit.SECONDS)
        );
        byte[] frame = new byte[] {0x00, (byte) 0xff, 0x31};
        socket.sendBinary(ByteBuffer.wrap(frame), true)
                .get(3, TimeUnit.SECONDS);
        assertTrue(Arrays.equals(
                frame,
                listener.binary.get(3, TimeUnit.SECONDS)
        ));
        socket.sendPing(ByteBuffer.wrap(new byte[] {0x01, 0x02}))
                .get(3, TimeUnit.SECONDS);
        assertTrue(Arrays.equals(
                new byte[] {0x01, 0x02},
                listener.pong.get(3, TimeUnit.SECONDS)
        ));
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "contract-complete")
                .get(3, TimeUnit.SECONDS);
        assertEquals(
                WebSocket.NORMAL_CLOSURE,
                listener.closeCode.get(3, TimeUnit.SECONDS)
        );

        RealtimeWebSocketProbe.Snapshot snapshot = webSocketProbe.snapshot();
        assertEquals(1, snapshot.sessions());
        assertEquals(1, snapshot.textFrames());
        assertEquals(1, snapshot.binaryFrames());
    }

    private byte[] uploadChunk(int index, int size) {
        byte[] chunk = new byte[size];
        for (int offset = 0; offset < size; offset++) {
            chunk[offset] = (byte) (index * 31 + offset);
        }
        return chunk;
    }

    private void assertGolden(JsonNode document, String group) {
        JsonNode golden;
        try (InputStream stream = getClass().getResourceAsStream(
                "/openapi/" + group + "-golden.json")) {
            assertNotNull(stream);
            golden = objectMapper.readTree(stream);
        } catch (java.io.IOException exception) {
            throw new AssertionError("Unable to read OpenAPI Golden", exception);
        }
        assertEquals(golden.path("group").asText(),
                document.path("x-egon-service").path("openapiGroup")
                        .asText());
        assertEquals(golden.path("openapi").asText(),
                document.path("openapi").asText());
        assertEquals(golden.path("x-egon-service"),
                document.path("x-egon-service"));
        assertEquals(values(golden.path("paths")), pathNames(document));
        assertEquals(values(golden.path("operationIds")),
                operationIds(document));
    }

    private Set<String> values(JsonNode nodes) {
        Set<String> values = new HashSet<>();
        nodes.elements().forEachRemaining(node -> values.add(node.asText()));
        return values;
    }

    private Set<String> pathNames(JsonNode document) {
        Set<String> paths = new HashSet<>();
        document.path("paths").fieldNames().forEachRemaining(paths::add);
        return paths;
    }

    private Set<String> operationIds(JsonNode document) {
        Set<String> operationIds = new HashSet<>();
        Iterator<JsonNode> paths = document.path("paths").elements();
        while (paths.hasNext()) {
            Iterator<JsonNode> methods = paths.next().elements();
            while (methods.hasNext()) {
                JsonNode operation = methods.next();
                if (operation.has("operationId")) {
                    operationIds.add(operation.path("operationId").asText());
                }
            }
        }
        return operationIds;
    }


    @Autowired
    private RSAKey documentSigningKey;

    @org.springframework.beans.factory.annotation.Value("${egon.cola.component.yuheng.openapi.resource-uri}")
    private String documentResource;

    private String documentToken(String scope) throws Exception {
        Instant now = Instant.now();
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", "https://idp.example.test/tenants/1001"); claims.put("aud", List.of(documentResource));
        claims.put("sub", "docs-client"); claims.put("client_id", "docs-client"); claims.put("jti", "901"); claims.put("tenant_id", "1001");
        claims.put("iat", now.minusSeconds(1).getEpochSecond()); claims.put("nbf", now.minusSeconds(1).getEpochSecond()); claims.put("exp", now.plusSeconds(300).getEpochSecond());
        claims.put("principal_type", "SERVICE"); claims.put("scope_context", "TENANT"); claims.put("scope", scope); claims.put("authorization_id", "801");
        claims.put("client_registration_id", "501"); claims.put("security_version", Map.of("realm", 1L, "client", 2L));
        claims.put("resource_version", 7L); claims.put("source_biz", "biz"); claims.put("source_app", "provider"); claims.put("source_env", "test");
        claims.put("credential_id", "credential-1"); claims.put("app_id", "provider-app");
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).keyID("docs-key").build(), JWTClaimsSet.parse(claims));
        jwt.sign(new RSASSASigner(documentSigningKey));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            DdcProperties.class
    })
    static class ProviderTestConfiguration {

        @Bean
        static BeanFactoryPostProcessor documentIdentity(Environment environment) throws Exception {
            var key = new RSAKeyGenerator(2048).keyID("docs-key").algorithm(JWSAlgorithm.RS256).generate();
            return factory -> {
                var clock = Clock.systemUTC();
                var issuer = URI.create("https://idp.example.test/tenants/1001");
                var resource = URI.create(environment.getRequiredProperty("egon.cola.component.yuheng.openapi.resource-uri"));
                var properties = new IdpStarterProperties();
                properties.setResourceServerId("provider-resource"); properties.setResourceUri(resource);
                properties.getRevocation().setPrimaryEndpoint("rediss://redis.example.test:6379");
                properties.getRevocation().setEpoch("fixture-epoch");
                var trust = new IdpStarterProperties.TrustedIssuerSettings();
                trust.setIssuer(issuer.toASCIIString()); trust.setJwksUri(issuer + "/.well-known/jwks.json");
                trust.setRealmType("TENANT"); trust.setRealmKey("tenant:1001"); trust.setTenantId("1001");
                trust.setStatus("ACTIVE"); trust.setTrustVersion(1);
                properties.setTrustedIssuers(Map.of("tenant-1001", trust));
                var managers = new TenantIssuerAuthenticationManagerComponent(new TrustedIssuerRegistry(properties), properties, clock);
                managers.initialize();
                var rest = (RestTemplate) ReflectionTestUtils.getField(managers, "jwksRestOperations");
                var jwks = MockRestServiceServer.bindTo(rest).build();
                jwks.expect(ExpectedCount.manyTimes(), MockRestRequestMatchers.requestTo(issuer + "/.well-known/jwks.json"))
                        .andRespond(MockRestResponseCreators.withSuccess(new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
                var validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
                factory.registerSingleton("documentValidator", validator);
                ((DefaultListableBeanFactory) factory).registerDisposableBean("documentValidator", validator::close);
                var authority = mock(IdentityAuthorityClient.class);
                when(authority.appendAudit(any())).thenAnswer(call -> {
                    TokenUsageAuditEvent event = call.getArgument(0);
                    return new IdentityAuditReceipt(java.util.UUID.randomUUID().toString(), event.eventId(),
                            TokenUsageAuditFingerprint.of(event), event.issuer().toASCIIString(), "controlled-service",
                            event.resource(), "STORED", event.occurredAt());
                });
                var audit = new TokenUsageAuditComponent(ignored -> authority, clock, validator, IdpSecurityMetricsRecorder.NOOP);
                var observations = new IdpObservedVerificationComponent(audit, Mappers.getMapper(TokenUsageAuditEventConverter.class),
                        new OAuthUsageObservationProperties(true, issuer, false, IdpSecurityObservationReceiverEnum.APPLICATION),
                        IdpSecurityMetricsRecorder.NOOP, clock, validator, resource);
                IdentityResourceServerStateReader resources = id -> Optional.of(new IdentityResourceServerState(
                        "provider-resource", resource, "biz", "provider", "test", ResourceServerStatus.ACTIVE, 7));
                var clients = mock(IdentityOAuthClientStateReader.class);
                var converter = Mappers.getMapper(VerifiedPrincipalConverter.class);
                var verifier = IdpJwtVerifier.createConfigured(null, resources, clients, "provider-resource", resource,
                        "platform", clock, managers, ignored -> OAuthRevocationReader.Result.valid(), converter, null, observations);
                var revocations = mock(ReactiveOAuthRevocationReader.class);
                when(revocations.check(any())).thenReturn(reactor.core.publisher.Mono.just(OAuthRevocationReader.Result.valid()));
                factory.registerSingleton("documentSigningKey", key);
                factory.registerSingleton("idpStarterProperties", properties);
                factory.registerSingleton("idpClock", clock);
                factory.registerSingleton("tenantIssuerAuthenticationManagerComponent", managers);
                factory.registerSingleton("identityResourceServerStateReader", resources);
                factory.registerSingleton("identityOAuthClientStateReader", clients);
                factory.registerSingleton("verifiedPrincipalConverter", converter);
                factory.registerSingleton("reactiveOAuthRevocationReader", revocations);
                factory.registerSingleton("idpObservedVerificationComponent", observations);
                factory.registerSingleton("userAccessTokenVerifier", new UserAccessTokenVerifier(verifier));
                factory.registerSingleton("serviceAccessTokenVerifier", new ServiceAccessTokenVerifier(verifier));
                factory.registerSingleton("jacksonObjectMapper", new ObjectMapper().findAndRegisterModules());
            };
        }


        @Bean
        RecordingRegistry recordingRegistry() {
            return new RecordingRegistry();
        }

        @Bean
        DdcServiceKeyFactory ddcServiceKeyFactory(DdcProperties properties) {
            return new DdcServiceKeyFactory(properties);
        }

        @Bean
        @org.springframework.core.annotation.Order(
                org.springframework.core.Ordered.LOWEST_PRECEDENCE)
        SecurityWebFilterChain providerSecurityWebFilterChain(
                ServerHttpSecurity http) {
            return http
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(authorize -> authorize
                            .anyExchange().permitAll())
                    .build();
        }

        @Bean
        IdpServiceOAuth2Client idpServiceOAuth2Client() {
            IdpServiceOAuth2Client client = mock(IdpServiceOAuth2Client.class);
            Instant issuedAt = Instant.now();
            when(client.authorize(any(IdpServiceTokenRequest.class)))
                    .thenReturn(new OAuth2AccessToken(
                            OAuth2AccessToken.TokenType.BEARER,
                            "test-tianshu-token",
                            issuedAt,
                            issuedAt.plusSeconds(300)
                    ));
            return client;
        }

        @Bean
        DdcHttpRegistrationContributor httpRegistrationContributor() {
            return new DdcHttpRegistrationContributor() {
                @Override
                public String serviceVersion() {
                    return "1.0.0-live";
                }

                @Override
                public Map<String, String> metadata() {
                    return Map.of(
                            "yuheng.definition-set-id",
                            "test-webflux-definition-set"
                    );
                }
            };
        }
    }

    static final class RecordingRegistry
            implements DdcServiceRegistryClient {

        private final AtomicInteger registrations = new AtomicInteger();

        private volatile DdcServiceRegistration registration;

        @Override
        public DdcLeaseSession register(DdcServiceRegistration registration) {
            this.registration = registration;
            int sequence = registrations.incrementAndGet();
            Instant now = Instant.now();
            return new DdcLeaseSession(
                    registration.instanceId(),
                    "lease-" + sequence,
                    DdcLeaseRole.HTTP_PROVIDER,
                    registration.leaseSeconds(),
                    registration.heartbeatIntervalSeconds(),
                    now,
                    now.plusSeconds(registration.leaseSeconds())
            );
        }

        @Override
        public DdcLeaseOperationResult heartbeat(
                DdcServiceLeaseRequest request) {
            return new DdcLeaseOperationResult(
                    DdcLeaseOperationStatus.RENEWED,
                    Instant.now().plusSeconds(30)
            );
        }

        @Override
        public DdcLeaseOperationResult deregister(
                String instanceId,
                String leaseId) {
            return new DdcLeaseOperationResult(
                    DdcLeaseOperationStatus.DELETED,
                    Instant.now()
            );
        }

        @Override
        public DdcServiceSnapshot getInstances(DdcServiceKey serviceKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DdcRegistrySubscription subscribe(
                DdcServiceKey serviceKey,
                Consumer<DdcServiceSnapshot> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DdcServiceCatalogSnapshot getServiceKeys(
                DdcServiceQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DdcRegistrySubscription subscribeServices(
                DdcServiceQuery query,
                Consumer<DdcServiceCatalogSnapshot> listener) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingWebSocketListener
            implements WebSocket.Listener {

        private final CompletableFuture<String> text =
                new CompletableFuture<>();

        private final CompletableFuture<byte[]> binary =
                new CompletableFuture<>();

        private final CompletableFuture<byte[]> pong =
                new CompletableFuture<>();

        private final CompletableFuture<Integer> closeCode =
                new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {
            if (last) {
                text.complete(data.toString());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(
                WebSocket webSocket,
                ByteBuffer data,
                boolean last) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            if (last) {
                binary.complete(copy);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(
                WebSocket webSocket,
                ByteBuffer message) {
            byte[] copy = new byte[message.remaining()];
            message.get(copy);
            pong.complete(copy);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            text.completeExceptionally(error);
            binary.completeExceptionally(error);
            pong.completeExceptionally(error);
            closeCode.completeExceptionally(error);
        }
    }
}
