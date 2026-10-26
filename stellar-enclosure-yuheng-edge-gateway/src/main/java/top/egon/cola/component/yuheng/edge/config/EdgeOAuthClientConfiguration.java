package top.egon.cola.component.yuheng.edge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityAuthorityRegistry;
import top.egon.cola.platform.tianquan.shoubing.contract.ServiceTokenContext;
import top.egon.cola.platform.tianquan.shoubing.contract.state.IdentityAuthorityScopes;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpClientCredentialsRequestEntityConverter;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceOAuth2Client;
import top.egon.cola.platform.tianquan.shoubing.starter.client.IdpServiceTokenRequest;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TrustedIssuerRegistry;
import top.egon.cola.platform.tianquan.shoubing.starter.state.HttpIdentityAuthorityClient;
import top.egon.cola.platform.tianquan.shoubing.starter.state.ReactiveIdentityAuthorityClient;

/** Retains native client credentials and registration mapping without default browser token/session stores. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OAuth2ClientProperties.class, EdgeIdentityAuthorityProperties.class})
public class EdgeOAuthClientConfiguration {
    @Bean(name = "edgeClientRegistrations")
    public Map<String, ClientRegistration> edgeClientRegistrations(OAuth2ClientProperties properties) {
        return Map.copyOf(new OAuth2ClientPropertiesMapper(properties).asClientRegistrations());
    }

    @Bean(name = "reactiveClientRegistrationRepository")
    public ReactiveClientRegistrationRepository reactiveClientRegistrationRepository(
            @Qualifier("edgeClientRegistrations") Map<String, ClientRegistration> registrations) {
        return new InMemoryReactiveClientRegistrationRepository(new ArrayList<>(registrations.values()));
    }

    @Bean(name = "edgeServiceClientRegistrationRepository")
    public ClientRegistrationRepository edgeServiceClientRegistrationRepository(
            @Qualifier("edgeClientRegistrations") Map<String, ClientRegistration> registrations) {
        return new InMemoryClientRegistrationRepository(new ArrayList<>(registrations.values()));
    }

    @Bean(name = "edgeOAuthAuthorityScheduler", destroyMethod = "dispose")
    public Scheduler edgeOAuthAuthorityScheduler() {
        return Schedulers.newBoundedElastic(8, 200, "edge-identity-authority", 60, true);
    }

    @Bean(destroyMethod = "close")
    public EdgeIdentityAuthorityRegistry edgeIdentityAuthorityRegistry(EdgeIdentityAuthorityProperties authorityProperties,
            @Qualifier("edgeOAuthProperties") EdgeOAuthProperties edgeProperties,
            @Qualifier("edgeServiceClientRegistrationRepository") ClientRegistrationRepository registrations,
            TrustedIssuerRegistry trustedIssuers, @Qualifier("edgeOAuthAuthorityScheduler") Scheduler scheduler,
            @Qualifier("edgeClock") Clock clock, @Qualifier("jacksonObjectMapper") ObjectMapper mapper) {
        var authorities = new LinkedHashMap<EdgeIdentityAuthorityRegistry.Binding, ReactiveIdentityAuthorityClient>();
        var services = new ArrayList<IdpServiceOAuth2Client>();
        var scopes = Set.of(IdentityAuthorityScopes.STATE_RESOLVE, IdentityAuthorityScopes.REFRESH_CONTEXT, IdentityAuthorityScopes.AUDIT_APPEND);
        var transport = HttpIdentityAuthorityClient.boundedTransport(authorityProperties.getTimeout());
        try {
            authorityProperties.getBindings().forEach((id, binding) -> {
                String issuer = binding.getIssuer().toASCIIString();
                var trust = trustedIssuers.findByIssuer(issuer)
                        .orElseThrow(() -> new IllegalStateException("EDGE authority issuer is not trusted"));
                ClientRegistration registration = registrations.findByRegistrationId(binding.getRegistrationId());
                if (registration == null || !issuer.equals(registration.getProviderDetails().getIssuerUri())
                        || !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(registration.getAuthorizationGrantType())
                        || !ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(registration.getClientAuthenticationMethod())
                        || !registration.getScopes().containsAll(scopes)) {
                    throw new IllegalStateException("EDGE authority requires a dedicated SERVICE registration");
                }
                var converter = new IdpClientCredentialsRequestEntityConverter();
                var responseClient = new RestClientClientCredentialsTokenResponseClient();
                responseClient.setRestClient(transport.mutate().configureMessageConverters(converters -> {
                    converters.addCustomConverter(new org.springframework.http.converter.FormHttpMessageConverter());
                    converters.addCustomConverter(new org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter());
                }).defaultStatusHandler(new org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler()).build());
                responseClient.setHeadersConverter(converter::convertHeaders);
                responseClient.setParametersConverter(converter::convertParameters);
                var provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
                provider.setClock(clock);
                provider.setClockSkew(Duration.ofSeconds(30));
                provider.setAccessTokenResponseClient(responseClient);
                var noStore = new NoStoreServiceAuthorizedClients();
                var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, noStore);
                manager.setAuthorizedClientProvider(provider);
                var serviceProperties = new IdpStarterProperties();
                serviceProperties.getServiceClient().setAppId(authorityProperties.getAppId());
                serviceProperties.getServiceClient().setRegistrationId(binding.getRegistrationId());
                serviceProperties.getServiceClient().setCredentialVersions(Map.of(binding.getRegistrationId(), binding.getCredentialVersion()));
                var service = IdpServiceOAuth2Client.createConfigured(manager, converter, clock, Duration.ofSeconds(30),
                        registrations, noStore, trustedIssuers, serviceProperties);
                services.add(service);
                var context = new IdpServiceTokenRequest(binding.getRegistrationId(), authorityProperties.getAppId(), binding.getResourceUri(),
                        trust.realmType() == TrustedIssuerRegistry.RealmType.TENANT ? ServiceTokenContext.TENANT : ServiceTokenContext.PLATFORM,
                        trust.tenantId(), scopes);
                var authority = new HttpIdentityAuthorityClient(edgeProperties.getUpstreams().getAuthorizationServer(), transport,
                        mapper, service, context);
                var key = new EdgeIdentityAuthorityRegistry.Binding(binding.getIssuer(), binding.getSourceResource());
                if (authorities.putIfAbsent(key, new ReactiveIdentityAuthorityClient(authority, scheduler)) != null) {
                    throw new IllegalStateException("EDGE duplicate issuer and source-resource SERVICE binding");
                }
            });
            return new EdgeIdentityAuthorityRegistry(authorities, services);
        } catch (RuntimeException failure) {
            services.forEach(IdpServiceOAuth2Client::close);
            throw failure;
        }
    }

    /** CC's bounded credential facade owns renewal; this adapter cannot retain browser or service contexts. */
    private static final class NoStoreServiceAuthorizedClients implements OAuth2AuthorizedClientService {
        @Override public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId, String principalName) { return null; }
        @Override public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal) { }
        @Override public void removeAuthorizedClient(String clientRegistrationId, String principalName) { }
    }
}
