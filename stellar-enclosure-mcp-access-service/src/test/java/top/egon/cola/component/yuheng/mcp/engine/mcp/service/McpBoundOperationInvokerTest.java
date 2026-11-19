package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DynamicMessage;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.generic.*;
import top.egon.cola.component.rpc.consumer.interceptor.RpcClientInvocation;
import top.egon.cola.component.rpc.consumer.invocation.RpcInvocationExecutor;
import top.egon.cola.component.rpc.consumer.reference.*;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.exception.RpcStatusExceptionMapper;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.model.si.*;
import top.egon.cola.component.yuheng.core.operation.*;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.*;
import top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientCredentialInterceptorFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Timeout(15)
class McpBoundOperationInvokerTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    private final FakeSi si = new FakeSi();
    private static final String ISSUER = "https://identity.example.test/tenants/90001";
    private static final String SERVICE_TOKEN = "synthetic-target-service-token";
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll static void ids() { SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5)); }

    @Test
    void httpUsesOnlyPinnedServiceAndAudienceAndNeverRelaysInboundBearer() throws Exception {
        Fixture fixture = fixture("orders", false);
        var loader = loader(fixture.binding()); var binding = loader.require(fixture.selection());
        var arrivals = new CopyOnWriteArrayList<Map<String,String>>();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) -> {
            arrivals.add(Map.of("uri", request.uri(), "host", request.requestHeaders().get("host"),
                    "authorization", request.requestHeaders().get("authorization")));
            return response.status(200).header("Content-Type","application/json").sendString(Mono.just("{\"ok\":true}"));
        }).bindNow();
        var oauth = oauth();
        try (var dns = dns(server.port())) {
            var invoker = invoker(loader, oauth, dns, Duration.ofSeconds(3), 4096, 4096);
            var result = invoker.invokeBound(fixture.selection(), binding.pin(), call("11", Map.of("id","order 1"), Map.of("q","a&admin=true"), null)).block();
            assertEquals(200, result.statusCode()); assertEquals("{\"ok\":true}", new String(result.body(), StandardCharsets.UTF_8));
            assertEquals(1, arrivals.size()); assertEquals("/orders/order%201?q=a%26admin%3Dtrue",arrivals.getFirst().get("uri"));
            assertEquals("orders-api.apps-prod.svc.cluster.local:8080", arrivals.getFirst().get("host"));
            assertEquals("Bearer " + SERVICE_TOKEN, arrivals.getFirst().get("authorization"));
            verify(oauth).authorizeBounded(eq(new IdpServiceTokenRequest("business-orders","mcp-access",URI.create("https://identity.example.test/resources/orders"),
                    ServiceTokenContext.TENANT,"90001",Set.of("orders:read"))), eq(ISSUER), any());
            assertEquals(2,si.reads, "loader admission and dispatch each use fixed authoritative history");
        } finally { server.disposeNow(); }
    }

    @Test
    void redirectsAndHttpFailuresAreReturnedOnceWithoutFollowingOrRetrying() throws Exception {
        Fixture fixture = fixture("orders",false); var loader = loader(fixture.binding()); var bound = loader.require(fixture.selection());
        AtomicInteger arrivals = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) -> {
            int n=arrivals.incrementAndGet(); return response.status(n==1?302:503)
                    .header("Location","http://127.0.0.1:1/unapproved").sendString(Mono.just("upstream"));
        }).bindNow();
        try(var dns=dns(server.port())) {
            var invoker=invoker(loader,oauth(),dns,Duration.ofSeconds(3),4096,4096);
            assertEquals(302,invoker.invokeBound(fixture.selection(),bound.pin(),normalCall()).block().statusCode()); assertEquals(1,arrivals.get());
            assertEquals(503,invoker.invokeBound(fixture.selection(),bound.pin(),normalCall()).block().statusCode()); assertEquals(2,arrivals.get());
        } finally {server.disposeNow();}
    }

    @Test
    void prematureConnectionCloseDoesNotTriggerTheNettyAutomaticRetry() throws Exception {
        Fixture fixture=fixture("orders",false);var loader=loader(fixture.binding());var bound=loader.require(fixture.selection());
        AtomicInteger arrivals=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)-> {
            arrivals.incrementAndGet(); request.withConnection(connection->connection.dispose()); return Mono.empty();
        }).bindNow();
        try(var dns=dns(server.port())) {
            assertThrows(RuntimeException.class,()->invoker(loader,oauth(),dns,Duration.ofSeconds(2),4096,4096)
                    .invokeBound(fixture.selection(),bound.pin(),normalCall()).block());
            assertEquals(1,arrivals.get());
        } finally {server.disposeNow();}
    }

    @Test
    void argumentsCannotSmugglePathsOrInventQueryNamesAndBadInputDoesNotAcquireCredentials() throws Exception {
        Fixture fixture=fixture("orders",false);var loader=loader(fixture.binding());var bound=loader.require(fixture.selection());
        var oauth=oauth();var invoker=invoker(loader,oauth,null,Duration.ofSeconds(2),4096,4096);
        for(String id:List.of("..","a/b","a\\b","%2fsecret","?admin=1","#fragment","a;b")) {
            assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->invoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of("id",id),Map.of(),null)).block());
        }
        assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->invoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of("id","ok"),Map.of("unknown","x"),null)).block());
        assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->invoker.invokeBound(fixture.selection(),bound.pin(),call("different",Map.of("id","ok"),Map.of(),null)).block());
        verifyNoInteractions(oauth);
    }

    @Test
    void incomingTokenAndOperationIdCannotReplaceAVerifiedPerCallBinding() throws Exception {
        Fixture fixture=fixture("orders",false);var loader=loader(fixture.binding());var bound=loader.require(fixture.selection());
        var invoker=invoker(loader,oauth(),null,Duration.ofSeconds(2),4096,4096);
        assertEquals("MCP_VERIFIED_BINDING_REQUIRED",assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,
                ()->invoker.invoke(normalCall()).block()).errorCode());
        si.denied.add("orders");
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class,()->invoker.invoke(normalCall())
                .contextWrite(context->context.put(McpPublishedBindingLoader.BindingContextVO.class,bound)).block());
        assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->McpBoundOperationInvoker.resolveRpcTenant(null));
    }

    @Test
    void requestAndResponseLimitsAndTotalTimeoutArePhysicalBoundaries() throws Exception {
        Fixture fixture=fixture("orders",false);var loader=loader(fixture.binding());var bound=loader.require(fixture.selection());
        var oauth=oauth();
        assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->invoker(loader,oauth,null,Duration.ofSeconds(2),8,8)
                .invokeBound(fixture.selection(),bound.pin(),call("11",Map.of("id","x"),Map.of(),Map.of("large","1234567890"))).block());
        verifyNoInteractions(oauth);
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->
                response.sendString(Mono.just("01234567890123456789"))).bindNow();
        try(var dns=dns(server.port())) {
            assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->invoker(loader,oauth(),dns,Duration.ofSeconds(2),4096,8)
                    .invokeBound(fixture.selection(),bound.pin(),normalCall()).block());
        } finally {server.disposeNow();}
        var slow=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->
                response.sendString(Mono.delay(Duration.ofSeconds(10)).map(n->"late"))).bindNow();
        try(var dns=dns(slow.port())) {
            long started=System.nanoTime();
            assertThrows(RuntimeException.class,()->invoker(loader,oauth(),dns,Duration.ofMillis(200),4096,4096)
                    .invokeBound(fixture.selection(),bound.pin(),normalCall()).block());
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(2))<0);
        } finally {slow.disposeNow();}
    }

    @Test
    void canonicalIssuerAndCredentialDimensionsMustMatchBeforeAnyWireCall() throws Exception {
        Fixture fixture=fixture("orders",false);var loader=loader(fixture.binding());var bound=loader.require(fixture.selection());
        var oauth=oauth();
        doAnswer(answer->ServiceAuthorizationKey.from(answer.getArgument(0),"https://wrong.example.test/issuer","client",1)).when(oauth).authorizationKey(any());
        assertEquals("MCP_OUTBOUND_AUTHORIZATION_MISMATCH",assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->
                invoker(loader,oauth,null,Duration.ofSeconds(2),4096,4096).invokeBound(fixture.selection(),bound.pin(),normalCall()).block()).errorCode());
        verify(oauth,never()).authorizeBounded(any(),any(),any());
    }

    @Test
    void nativeUnaryUsesExactDescriptorAndServiceCredentialsCapturedOnceBeforeAsyncWork() throws Exception {
        Fixture fixture=fixture("orders",true);var loader=loader(fixture.binding());var bound=loader.require(fixture.selection());
        try(var validators=Validation.buildDefaultValidatorFactory(); var nativeWire=new NativeWire();) {
            var props=idpProperties();var oauth=oauth();
            var factory=IdpRpcClientCredentialInterceptorFactory.createConfigured(oauth,props,validators.getValidator(),
                    issuerPinnedResolver());
            try(var executor=new RpcInvocationExecutor(new RpcStatusExceptionMapper());
                var cache=cache(nativeWire.channel)) {
                AtomicInteger captured=new AtomicInteger();
                AtomicReference<RpcClientInvocation> capturedInvocation = new AtomicReference<>();
                var generic=new RpcGenericInvoker(cache,executor,new RpcProcessIdentity("mcp-access","test","127.0.0.1",1,"mcp-test"),
                        List.of(invocation->{assertEquals("90001",McpBoundOperationInvoker.resolveRpcTenant(invocation).orElseThrow());captured.incrementAndGet();capturedInvocation.set(invocation);return factory.create(invocation);}));
                var invoker=new McpBoundOperationInvoker(loader,mapper,oauth,generic,references(),props,factory,clock,Duration.ofSeconds(3),4096,4096);
                var result=invoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of(),Map.of(),Map.of("name","native"))).block();
                assertEquals("{\"name\":\"provider:native\"}",new String(result.body(),StandardCharsets.UTF_8));
                assertEquals(1,captured.get());assertEquals(1,nativeWire.arrivals.get());
                assertEquals(List.of("Bearer "+SERVICE_TOKEN),nativeWire.authorizations);
                assertEquals("1",nativeWire.attempt.get());
                assertTrue(nativeWire.traceparent.get().contains("11111111111111111111111111111111"));
                assertNull(org.slf4j.MDC.get("traceId"));
                assertTrue(nativeWire.remainingMs.get()>0 && nativeWire.remainingMs.get()<=3000);
                assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->McpBoundOperationInvoker.resolveRpcTenant(capturedInvocation.get()));
                nativeWire.error.set(Status.UNAVAILABLE);
                var unavailable = assertThrows(RuntimeException.class, () -> invoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of(),Map.of(),Map.of("name","down"))).block());
                assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(unavailable).getCode());
                assertEquals(2,nativeWire.arrivals.get(), "unapproved method must not be retried");
                nativeWire.error.set(Status.PERMISSION_DENIED);
                var forbidden = assertThrows(RuntimeException.class, () -> invoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of(),Map.of(),Map.of("name","denied"))).block());
                assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(forbidden).getCode());
                assertEquals(3,nativeWire.arrivals.get());
                assertEquals(3,captured.get());
                verify(oauth,times(3)).authorizeBounded(any(),eq(ISSUER),any());
                verify(oauth,never()).authorize(any());
            }
        }
    }

    @Test
    void cancellationOfTheBoundNativeMonoCancelsTheActualServerCall() throws Exception {
        Fixture fixture=fixture("orders",true); var loader=loader(fixture.binding()); var bound=loader.require(fixture.selection());
        try(var validators=Validation.buildDefaultValidatorFactory(); var wire=new NativeWire();
            var executor=new RpcInvocationExecutor(new RpcStatusExceptionMapper()); var cache=cache(wire.channel)) {
            var props=idpProperties(); var oauth=oauth();
            var factory=IdpRpcClientCredentialInterceptorFactory.createConfigured(oauth,props,validators.getValidator(),
                    issuerPinnedResolver());
            var generic=new RpcGenericInvoker(cache,executor,new RpcProcessIdentity("mcp-access","test","127.0.0.1",1,"mcp-cancel"),List.of(factory));
            var invoker=new McpBoundOperationInvoker(loader,mapper,oauth,generic,references(),props,factory,clock,Duration.ofSeconds(3),4096,4096);
            wire.hold.set(true);
            var subscription=invoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of(),Map.of(),Map.of("name","hold")))
                    .subscribe(ignored->fail("held server must not succeed"), ignored->{});
            try {
                assertTrue(wire.held.await(2,TimeUnit.SECONDS));
                subscription.dispose();
                assertTrue(wire.cancelled.await(2,TimeUnit.SECONDS));
                assertEquals(1,wire.arrivals.get());
                assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->McpBoundOperationInvoker.resolveRpcTenant(null));
            } finally { subscription.dispose(); }
        }
    }

    @Test
    void nativeTargetOrCredentialPinMismatchNeverInvokesTheNativeTransport() throws Exception {
        Fixture fixture=fixture("orders",true);var loader=loader(fixture.binding());var bound=loader.require(fixture.selection());
        var generic=mock(RpcGenericInvoker.class);var oauth=oauth();var props=idpProperties();
        props.getRpc().getConsumer().getBindings().get("lookup").setResourceUri(URI.create("https://wrong.example.test/resource"));
        var invoker=new McpBoundOperationInvoker(loader,mapper,oauth,generic,references(),props,mock(IdpRpcClientCredentialInterceptorFactory.class),clock,Duration.ofSeconds(2),4096,4096);
        assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->invoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of(),Map.of(),Map.of())).block());
        var wrong=rpcProperties();wrong.getConsumer().getBindings().get("orders-rpc").setVersion(2);
        var finalInvoker=new McpBoundOperationInvoker(loader,mapper,oauth,generic,new RpcReferenceDefinitionResolver(wrong),idpProperties(),mock(IdpRpcClientCredentialInterceptorFactory.class),clock,Duration.ofSeconds(2),4096,4096);
        assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,()->finalInvoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of(),Map.of(),Map.of())).block());
        verifyNoInteractions(generic);
    }

    @Test
    void registrationIssuerDriftCannotReturnANativeSuccessfulOutput() throws Exception {
        Fixture fixture=fixture("orders",true); var loader=loader(fixture.binding()); var bound=loader.require(fixture.selection());
        var oauth=oauth(); AtomicInteger reads=new AtomicInteger();
        doAnswer(answer->ServiceAuthorizationKey.from(answer.getArgument(0),reads.incrementAndGet()==1?ISSUER:"https://changed.example.test/issuer","client",1))
                .when(oauth).authorizationKey(any());
        var generic=mock(RpcGenericInvoker.class);
        when(generic.invokeAsync(any())).thenReturn(CompletableFuture.completedFuture(new byte[0]));
        var invoker=new McpBoundOperationInvoker(loader,mapper,oauth,generic,references(),idpProperties(),mock(IdpRpcClientCredentialInterceptorFactory.class),clock,Duration.ofSeconds(2),4096,4096);
        assertEquals("MCP_OUTBOUND_AUTHORIZATION_MISMATCH",assertThrows(McpBoundOperationInvoker.InvocationRejectedException.class,
                ()->invoker.invokeBound(fixture.selection(),bound.pin(),call("11",Map.of(),Map.of(),Map.of())).block()).errorCode());
        verify(generic).invokeAsync(any());
    }

    private static top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientTenantResolver issuerPinnedResolver() {
        return new top.egon.cola.platform.tianquan.shoubing.starter.security.rpc.IdpRpcClientTenantResolver() {
            @Override public Optional<String> resolve(RpcClientInvocation invocation,org.springframework.security.core.Authentication authentication) {
                return McpBoundOperationInvoker.resolveRpcTenant(invocation);
            }
            @Override public Optional<String> expectedIssuer(RpcClientInvocation invocation,org.springframework.security.core.Authentication authentication) {
                return McpBoundOperationInvoker.resolveRpcIssuer(invocation);
            }
        };
    }

    private McpBoundOperationInvoker invoker(McpPublishedBindingLoader loader,IdpServiceOAuth2Client oauth,AddressResolverGroup<InetSocketAddress> dns,Duration timeout,int requestLimit,int responseLimit) {
        return new McpBoundOperationInvoker(loader,mapper,oauth,mock(RpcGenericInvoker.class),references(),idpProperties(),mock(IdpRpcClientCredentialInterceptorFactory.class),clock,timeout,requestLimit,responseLimit,dns);
    }
    private IdpServiceOAuth2Client oauth() {
        var oauth=mock(IdpServiceOAuth2Client.class);
        when(oauth.authorizationKey(any())).thenAnswer(answer->ServiceAuthorizationKey.from(answer.getArgument(0),ISSUER,"mcp-client",1));
        var token=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,SERVICE_TOKEN,clock.now,clock.now.plusSeconds(300));
        when(oauth.authorizeBounded(any(),any(),any())).thenReturn(token);when(oauth.authorize(any())).thenReturn(token);return oauth;
    }
    private GatewayOperationInvocation normalCall(){return call("11",Map.of("id","x"),Map.of(),null);}
    private GatewayOperationInvocation call(String operation,Map<String,Object> path,Map<String,Object> query,Object body) {
        return new GatewayOperationInvocation(new GatewayOperationCall(operation,path,query,body),"Bearer NEVER_FORWARD_INCOMING_MCP_TOKEN","caller","192.0.2.1",Map.of("traceparent","00-11111111111111111111111111111111-2222222222222222-01"));
    }
    private RpcReferenceDefinitionResolver references(){return new RpcReferenceDefinitionResolver(rpcProperties());}
    private EgonRpcProperties rpcProperties(){var properties=new EgonRpcProperties();properties.getConsumer().setDefaultTimeoutMs(3000).getBindings().put("orders-rpc",new EgonRpcProperties.RpcServiceBindingConfig().setVersion(1).setNamespace("apps-prod").setService("orders-api").setPort(8080).setMethods(Set.of("fixture.Orders/Lookup")));return properties;}
    private IdpStarterProperties idpProperties(){var properties=new IdpStarterProperties();properties.getRpc().setEnabled(true);properties.getRpc().getConsumer().setEnabled(true);properties.getRpc().getConsumer().getBindings().put("lookup",new IdpStarterProperties.RpcConsumerBindingProperties().setServiceName("fixture.Orders").setGroup("orders").setVersion("1.0.0").setFullMethodName("fixture.Orders/Lookup").setProcessIdentity(Map.of("applicationName","mcp-access","env","test")).setMode(IdpStarterProperties.RpcConsumerModeEnum.SERVICE).setScopeContext(ServiceTokenContext.TENANT).setAppId("mcp-access").setResourceUri(URI.create("https://identity.example.test/resources/orders")).setScopes(Set.of("orders:read")).setTenantRegistrationIds(Map.of("90001","business-orders")).setTransportBindingId("orders-rpc").setTransportBindingVersion(1L));return properties;}
    private RpcGenericTargetCache cache(ManagedChannel channel){return new RpcGenericTargetCache(references(),definition->{var strategy=mock(RpcReferenceStrategy.class);when(strategy.target()).thenReturn(definition.target());when(strategy.acquire()).thenAnswer(ignored->{var lease=mock(RpcChannelLease.class);when(lease.channel()).thenReturn(channel);return lease;});return strategy;},4,Duration.ofSeconds(1),System::currentTimeMillis);}
    private static AddressResolverGroup<InetSocketAddress> dns(int port){return new AddressResolverGroup<>(){
        @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor){return new AbstractAddressResolver<InetSocketAddress>(executor){
            @Override protected boolean doIsResolved(InetSocketAddress address){return false;}
            @Override protected void doResolve(InetSocketAddress address,Promise<InetSocketAddress> promise){promise.setSuccess(new InetSocketAddress("127.0.0.1",port));}
            @Override protected void doResolveAll(InetSocketAddress address,Promise<List<InetSocketAddress>> promise){promise.setSuccess(List.of(new InetSocketAddress("127.0.0.1",port)));}
        };}
    };}
    private static final class NativeWire implements AutoCloseable {
        private final Server server;private final ManagedChannel channel;
        private final AtomicInteger arrivals=new AtomicInteger();private final List<String> authorizations=new CopyOnWriteArrayList<>();
        private final AtomicReference<String> attempt=new AtomicReference<>(),traceparent=new AtomicReference<>();private final AtomicLong remainingMs=new AtomicLong();
        private final AtomicReference<Status> error=new AtomicReference<>(); private final AtomicBoolean hold=new AtomicBoolean();
        private final CountDownLatch held=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        private NativeWire() throws Exception {
            var proto=DescriptorProtos.FileDescriptorSet.parseFrom(descriptor(false));
            var file=com.google.protobuf.Descriptors.FileDescriptor.buildFrom(proto.getFile(0),new com.google.protobuf.Descriptors.FileDescriptor[0]);
            var method=file.getServices().getFirst().getMethods().getFirst();
            var marshaller=new MethodDescriptor.Marshaller<byte[]>(){public InputStream stream(byte[] bytes){return new ByteArrayInputStream(bytes);}public byte[] parse(InputStream input){try{return input.readAllBytes();}catch(IOException failure){throw new UncheckedIOException(failure);}}};
            var wire=MethodDescriptor.<byte[],byte[]>newBuilder().setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("fixture.Orders/Lookup").setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
            var service=ServerServiceDefinition.builder("fixture.Orders").addMethod(wire,ServerCalls.asyncUnaryCall((request,response)->{
                if (error.get()!=null) {response.onError(error.get().asRuntimeException());return;}
                if (hold.get()) {Context.current().addListener(ignored->cancelled.countDown(),Runnable::run);held.countDown();return;}
                try{var message=DynamicMessage.parseFrom(method.getInputType(),request);var result=DynamicMessage.newBuilder(method.getOutputType()).setField(method.getOutputType().findFieldByName("name"),"provider:"+message.getField(method.getInputType().findFieldByName("name"))).build();response.onNext(result.toByteArray());response.onCompleted();}catch(Exception failure){response.onError(Status.INTERNAL.asRuntimeException());}
            })).build();
            server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(ServerInterceptors.intercept(service,new ServerInterceptor(){
                @Override public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,Metadata headers,ServerCallHandler<Q,S> next){arrivals.incrementAndGet();headers.getAll(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER)).forEach(authorizations::add);attempt.set(headers.get(RpcMetadataKeys.ATTEMPT));traceparent.set(headers.get(RpcMetadataKeys.TRACEPARENT));remainingMs.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));return next.startCall(call,headers);}
            })).build().start();
            channel=NettyChannelBuilder.forAddress("127.0.0.1",server.getPort()).overrideAuthority("orders-api.apps-prod.svc.cluster.local:8080").usePlaintext().disableRetry().build();
        }
        @Override public void close()throws Exception{channel.shutdownNow().awaitTermination(2,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(2,TimeUnit.SECONDS);}
    }

    private McpPublishedBindingLoader loader(ObjectNode... bindings) throws Exception {
        return loaderDocument(bundle(bindings));
    }
    private McpPublishedBindingLoader loaderDocument(ObjectNode document) throws Exception {
        Path file = directory.toRealPath().resolve(UUID.randomUUID() + ".json");
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8); Files.write(file, bytes);
        var loader = new McpPublishedBindingLoader(file, hash(bytes), si, "mcp-access", clock); loader.reload(); return loader;
    }
    private ObjectNode bundle(ObjectNode... bindings) {
        ObjectNode root = object().put("schemaVersion", McpPublishedBindingLoader.SCHEMA_VERSION).put("validUntil", clock.now.plusSeconds(600).toString());
        ArrayNode entries = root.putArray("bindings"); for (ObjectNode binding : bindings) entries.add(binding); return root;
    }
    private Fixture fixture(String server, boolean rpc) {
        String operationId = "11", operationKey = rpc ? "RPC fixture.Orders/Lookup" : "GET /orders/{id}";
        String toolId = hash((server + '\0' + operationKey).getBytes(StandardCharsets.UTF_8));
        ObjectNode definition = object().put("summary", "lookup"); definition.putArray("tags");
        ObjectNode requestSchema = object().put("type", "object");
        if (!rpc) {
            ObjectNode locations = requestSchema.putObject("properties");
            locations.putObject("path").put("type", "object").putObject("properties").putObject("id").put("type", "string");
            locations.putObject("query").put("type", "object").putObject("properties").putObject("q").put("type", "string");
            locations.putObject("body").put("type", "object");
        }
        definition.set("requestSchema", requestSchema); definition.set("responseSchema", object().put("type", "object"));
        definition.putArray("errorSchema");
        byte[] descriptor = rpc ? descriptor(false) : null;
        if (rpc) definition.putObject("descriptorSnapshot").put("sha256", hash(descriptor)); else definition.putNull("descriptorSnapshot");
        ObjectNode exposure = definition.putObject("attributes").putObject("mcpExposure").put("registerMcp", true)
                .put("mcpServerCode", server).put("mcpName", "lookup").put("riskLevel", "LOW").put("idempotent", true);
        exposure.putArray("requiredPermissions").add("orders:read"); definition.put("externalAccessible", false);
        String definitionJson = definition.toString(), definitionDigest = hash(definitionJson.getBytes(StandardCharsets.UTF_8));
        ObjectNode source = object().put("operationId", operationId).put("operationKey", operationKey).put("definitionDigest", definitionDigest);
        ObjectNode tool = object().put("toolId", toolId).put("serverCode", server).put("name", "lookup").put("description", "lookup")
                .put("sourceType", "LOCAL_OPERATION").put("operationId", operationId).put("operationProtocol", rpc ? "RPC" : "HTTP")
                .putNull("remoteMountId").put("inputSchema", requestSchema.toString()).put("outputSchema", "{\"type\":\"object\"}")
                .put("riskLevel", "LOW").put("idempotent", true).put("enabled", true);
        tool.putObject("annotations"); tool.putArray("requiredPermissions").add("orders:read");
        ObjectNode registered = object().put("registrationId", "91").put("resourceId", "201").put("bizCode", "commerce")
                .put("namespaceCode", "default").put("envCode", "test").put("ownerAppCode", "owner-app").put("resourceKey", server)
                .put("kind", "MCP").put("registrationRevision", "0").put("registrationDescriptorSha256", "a".repeat(64));
        ObjectNode seed = object().put("schemaVersion", "yuheng-mcp-mapping-seed-v1").put("publicationId", "300").put("ownerTenantId", "7")
                .put("gatewayGroupId", "10").put("sourceDraftRevision", "1").put("frozenAt", clock.now.minusSeconds(60).toString());
        seed.set("registeredSi", registered);
        ObjectNode seedEntry = seed.putArray("mappings").addObject().put("toolId", toolId); seedEntry.set("sourceOperation", source); seedEntry.set("tool", tool);
        ObjectNode seedPayload = object(); for (String name : List.of("schemaVersion", "registeredSi", "mappings")) seedPayload.set(name, seed.get(name));
        seed.put("contentSha256", canonicalHash(seedPayload)); seed.put("artifactSha256", canonicalHash(seed));
        ObjectNode spec = object().put("schemaVersion", "mcp-server-v2").put("sourceType", "COMPOSED_API");
        ObjectNode pointer = spec.putObject("apiMapping").put("gatewayGroupId", "10").put("publicationId", "300")
                .put("schemaVersion", "yuheng-mcp-mapping-seed-v1").put("contentSha256", seed.path("contentSha256").textValue())
                .put("artifactSha256", seed.path("artifactSha256").textValue());
        ObjectNode siTool = spec.putArray("tools").addObject().put("toolId", toolId).put("name", "lookup").put("description", "lookup")
                .put("riskLevel", "LOW").put("idempotent", true).put("enabled", true);
        siTool.set("inputSchema", requestSchema);
        siTool.putObject("outputSchema").put("type", "object");
        siTool.putArray("requiredPermissions").add("orders:read");
        String specJson = spec.toString(), siDigest = hash(specJson.getBytes(StandardCharsets.UTF_8));
        var identity = new DdcSiDependencyManifestVO.VersionIdentityVO("7", "201", "501", "1", DdcSiKindEnum.MCP, "test", siDigest);
        var manifest = DdcSiDependencyManifestVO.create(identity, null, List.of(), List.of());
        var descriptorVo = new DdcSiResourceDescriptor().setTenantId(7L).setResourceId(201L).setBizCode("commerce").setNamespaceCode("default")
                .setEnvCode("test").setOwnerAppCode("owner-app").setKind(DdcSiKindEnum.MCP).setResourceKey(server)
                .setResolvedVersionId(501L).setResolvedVersionNo(1L).setDigestSha256(siDigest).setSpecJson(specJson)
                .setDependencyManifest(manifest).setStatus(DdcSiLifecycleStatusEnum.PUBLISHED);
        si.values.put(server, descriptorVo);
        ObjectNode siRef = object().put("resourceId", "201").put("versionId", "501").put("versionNo", "1").put("digestSha256", siDigest)
                .put("bizCode", "commerce").put("namespaceCode", "default").put("envCode", "test").put("ownerAppCode", "owner-app").put("resourceKey", server);
        ObjectNode proof = object().put("schemaVersion", McpPublishedBindingLoader.PUBLICATION_SCHEMA).put("publicationId", "301").put("ownerTenantId", "7").put("gatewayGroupId", "10");
        proof.putObject("sourceRelease").put("releaseId", "301").put("artifactSha256", "b".repeat(64)).put("mappingContentSha256", "c".repeat(64));
        proof.set("publishedSi", siRef);
        ObjectNode mapping = proof.putArray("mappings").addObject().put("toolId", toolId).put("dependencyClosureDigest", manifest.closureDigest());
        mapping.set("sourceOperation", source); mapping.set("siServer", siRef); mapping.set("tool", tool); resignPublication(proof);
        ObjectNode operation = object().put("operationId", operationId).put("operationKey", operationKey).put("protocol", rpc ? "RPC" : "HTTP")
                .put("methodIdentity", rpc ? "fixture.Orders/Lookup" : "GET /orders/{id}").put("definitionDigest", definitionDigest).put("definitionJson", definitionJson);
        if (rpc) operation.putObject("rpcDescriptor").put("sha256", hash(descriptor)).put("base64DescriptorSet", Base64.getEncoder().encodeToString(descriptor))
                .put("serviceName", "fixture.Orders").put("group", "orders").put("version", "1.0.0").put("fullMethodName", "fixture.Orders/Lookup")
                .put("requestType", "fixture.Request").put("responseType", "fixture.Response");
        else operation.putNull("rpcDescriptor");
        ObjectNode transport = object().put("namespace", "apps-prod").put("service", "orders-api").put("port", "8080");
        if (rpc) transport.put("rpcBindingId", "orders-rpc").put("rpcBindingVersion", "1"); else transport.putNull("rpcBindingId").putNull("rpcBindingVersion");
        transport.putObject("credential").put("issuer", "https://identity.example.test/tenants/90001").put("registrationId", "business-orders")
                .put("appId", "mcp-access").put("identityTenantId", "90001").put("audience", "https://identity.example.test/resources/orders")
                .putArray("scopes").add("orders:read");
        ObjectNode binding = object().put("bindingId", server + "-execution").put("version", "1").put("ownerTenantId", "7").put("serverCode", server).put("toolId", toolId);
        binding.set("si", siRef.deepCopy()); binding.set("publicationEvidence", proof.deepCopy()); binding.set("sourceMappingEvidence", seed.deepCopy());
        binding.set("operation", operation.deepCopy()); binding.set("transport", transport.deepCopy());
        return new Fixture(binding, new McpPublishedBindingLoader.SelectionVO(7, server, toolId));
    }
    private static byte[] descriptor(boolean streaming) {
        var request = DescriptorProtos.DescriptorProto.newBuilder().setName("Request").addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName("name").setNumber(1).setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
        var response = DescriptorProtos.DescriptorProto.newBuilder().setName("Response").addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName("name").setNumber(1).setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
        var method = DescriptorProtos.MethodDescriptorProto.newBuilder().setName("Lookup").setInputType(".fixture.Request")
                .setOutputType(".fixture.Response").setServerStreaming(streaming);
        var file = DescriptorProtos.FileDescriptorProto.newBuilder().setName("orders.proto").setPackage("fixture").setSyntax("proto3")
                .addMessageType(request).addMessageType(response).addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("Orders").addMethod(method));
        return DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build().toByteArray();
    }
    private static void resignPublication(ObjectNode proof) {
        ObjectNode content = object(); for (String field : List.of("schemaVersion", "ownerTenantId", "gatewayGroupId", "publishedSi", "mappings")) content.set(field, proof.get(field));
        proof.put("contentSha256", canonicalHash(content)); proof.remove("artifactSha256"); proof.put("artifactSha256", canonicalHash(proof));
    }
    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static String canonicalHash(JsonNode n) { return hash(normalize(n).toString().getBytes(StandardCharsets.UTF_8)); }
    private static JsonNode normalize(JsonNode n) {
        if (n.isObject()) { ObjectNode result = object(); var keys = new ArrayList<String>(); n.fieldNames().forEachRemaining(keys::add); keys.stream().sorted().forEach(key -> result.set(key, normalize(n.get(key)))); return result; }
        if (n.isArray()) { ArrayNode result = JsonNodeFactory.instance.arrayNode(); n.forEach(value -> result.add(normalize(value))); return result; }
        if (n.isNumber()) return JsonNodeFactory.instance.numberNode(n.decimalValue().stripTrailingZeros()); return n.deepCopy();
    }
    private static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception impossible) { throw new AssertionError(impossible); } }
    private static void rejected(String code, Runnable action) {
        assertEquals(code, assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, action::run).errorCode());
    }
    private record Fixture(ObjectNode binding, McpPublishedBindingLoader.SelectionVO selection) { }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-19T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private static final class FakeSi implements DdcSiCatalogClient {
        private final Map<String,DdcSiResourceDescriptor> values = new HashMap<>(), exactValues = new HashMap<>();
        private final Set<String> missingVersions = new HashSet<>();
        private final Set<String> denied = new HashSet<>(), missing = new HashSet<>();
        private int reads; private String caller, owner; private Long version; private Runnable onRead = () -> { };
        @Override public Optional<DdcSiResourceDescriptor> resolveExecutionResource(long tenant, String biz, String namespace, String env,
                String owner, String caller, DdcSiKindEnum kind, String key, Long version) {
            reads++; this.caller = caller; this.owner = owner; this.version = version; onRead.run();
            if (denied.contains(key)) throw new IllegalStateException("synthetic authority rejection");
            return missing.contains(key) || missingVersions.contains(key + "@" + version) ? Optional.empty()
                    : Optional.ofNullable(exactValues.getOrDefault(key + "@" + version, values.get(key)));
        }
        @Override public List<DdcSiResourceDescriptor> listResources(DdcSiCatalogQuery query) { throw new AssertionError("no active catalog lookup"); }
        @Override public Optional<DdcSiResourceDescriptor> resolveResource(long tenant, String biz, String ns, String env, String app, DdcSiKindEnum kind, String key, Integer version) { throw new AssertionError("no active fallback"); }
        @Override public List<DdcSiSearchHit> semanticSearch(DdcSiSemanticSearchQuery query) { throw new AssertionError("no search"); }
        @Override public DdcSiResourceDescriptor registerManagedResource(DdcSiSelfRegistrationRequest request) { throw new AssertionError("no writes"); }
    }
}
