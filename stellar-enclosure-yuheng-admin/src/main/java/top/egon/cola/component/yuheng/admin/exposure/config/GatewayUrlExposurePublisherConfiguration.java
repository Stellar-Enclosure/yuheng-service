package top.egon.cola.component.yuheng.admin.exposure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import top.egon.cola.component.common.id.autoconfigure.IdGeneratorAutoConfiguration;
import top.egon.cola.component.common.id.probe.WorkerProcessProbe;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.dispatch.OutboxDispatcher;
import top.egon.cola.component.outbox.dispatch.OutboxPoller;
import top.egon.cola.component.outbox.event.OutboxCommittedEvent;
import top.egon.cola.component.outbox.event.OutboxCommittedEventListener;
import top.egon.cola.component.rpc.tianshu.autoconfigure.DdcRpcProperties;
import top.egon.cola.component.yuheng.admin.config.GatewayExposurePublicationConfiguration;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.service.ExposureDesiredStateDeliveryHandler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.AccessScope;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.GatewayKey;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ServiceKey;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.GrantKey;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureReconciler;
import top.egon.cola.component.yuheng.admin.exposure.support.outbox.GatewayUrlExposureOutboxDeliveryHandler;
import top.egon.cola.component.yuheng.admin.exposure.support.outbox.GatewayUrlExposureOutboxDeliveryHandler.Writer;
import top.egon.cola.component.yuheng.admin.mcp.config.McpNativeSiReaderConfiguration;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.scope.config.GatewayScopeBindingConfiguration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;

/** One process has one deployment role; only the non-web publisher constructs Kubernetes write capability. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayUrlExposurePublisherConfiguration.PublisherProperties.class)
public class GatewayUrlExposurePublisherConfiguration {
    public static final String PREFIX = "yuheng.admin.exposure";
    private static final String PROPERTIES = PREFIX + "-top.egon.cola.component.yuheng.admin.exposure.config.GatewayUrlExposurePublisherConfiguration$PublisherProperties";

    @Bean("gatewayUrlExposureEventReader")
    ObjectReader gatewayUrlExposureEventReader(@Qualifier("jacksonObjectMapper") ObjectMapper json) {
        return GatewayUrlExposureOutboxDeliveryHandler.eventReader(json);
    }

    @Bean("gatewayUrlExposureRole")
    RoleEnum gatewayUrlExposureRole(@Qualifier(PROPERTIES) PublisherProperties properties,
            @Qualifier("environment") Environment environment,
            @Qualifier("outboxStateMachineOutboxProperties") TransactionalOutboxProperties outbox,
            ApplicationContext application) {
        String declared = environment.getProperty(PREFIX + ".role", "ADMIN");
        if (!declared.equals(properties.role().name()) || outbox.getCleanup().isEnabled()) throw invalid("ROLE_INVALID");
        boolean publisherProfile = environment.acceptsProfiles(Profiles.of("publisher"));
        if (properties.role() == RoleEnum.ADMIN) {
            if (publisherProfile || outbox.getPolling().isEnabled()) throw invalid("ROLE_INVALID");
        } else {
            if (!publisherProfile || environment.acceptsProfiles(Profiles.of("local"))
                    || !"none".equals(environment.getProperty("spring.main.web-application-type"))
                    || application instanceof WebApplicationContext || !outbox.getPolling().isEnabled()
                    || !Boolean.FALSE.equals(environment.getProperty("egon.cola.component.rpc.enabled", Boolean.class))
                    || !Boolean.FALSE.equals(environment.getProperty("egon.cola.component.tianshu.enabled", Boolean.class))
                    || environment.getProperty(GatewayScopeBindingConfiguration.PREFIX + ".enabled", Boolean.class, false)
                    || environment.getProperty(McpNativeSiReaderConfiguration.PREFIX + ".enabled", Boolean.class, false)
                    || environment.getProperty(DdcRpcProperties.PREFIX + ".enabled", Boolean.class, false)
                    || environment.getProperty("yuheng.admin.openapi.enabled", Boolean.class, false)
                    || environment.getProperty("yuheng.admin.observability.kafka.enabled", Boolean.class, false)
                    || environment.getProperty("egon.cola.component.yuheng.openapi.enabled", Boolean.class, false)
                    || environment.getProperty("egon.cola.component.yuheng.reporting.enabled", Boolean.class, false)
                    || outbox.getNodeId() == null || !outbox.getNodeId().matches("[A-Za-z0-9._:-]{1,128}")) {
                throw invalid("ROLE_INVALID");
            }
            if (properties.apiServer() == null || properties.caFile() == null || properties.tokenFile() == null
                    || !properties.caFile().isAbsolute() || !properties.tokenFile().isAbsolute()) throw invalid("AUTHORITY_REQUIRED");
            validateTokenFile(properties.tokenFile());
        }
        return properties.role();
    }

    @Bean("gatewayUrlExposureWriterGuard")
    SmartInitializingSingleton gatewayUrlExposureWriterGuard(@Qualifier("gatewayUrlExposureRole") RoleEnum role,
            ObjectProvider<Writer> writers, ObjectProvider<OutboxMpStorageProperties> storage) {
        return () -> {
            long count = writers.stream().count();
            if (count != (role == RoleEnum.PUBLISHER ? 1 : 0)) throw invalid("WRITER_AMBIGUOUS");
            if (role == RoleEnum.PUBLISHER) {
                var selected = storage.getIfUnique();
                if (selected == null || selected.isMigrationMode()) throw invalid("STORAGE_INVALID");
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = PREFIX, name = "role", havingValue = "ADMIN", matchIfMissing = true)
    static class AdminRoleConfiguration {
        /** Polling is separately disabled; this also removes the producer's immediate post-commit claim path. */
        @Bean("outboxCommittedEventListener")
        OutboxCommittedEventListener outboxCommittedEventListener(
                @Qualifier("gatewayUrlExposureRole") RoleEnum role,
                @Qualifier("outboxDispatcher") OutboxDispatcher dispatcher) {
            return new OutboxCommittedEventListener(dispatcher) {
                @Override public void onApplicationEvent(PayloadApplicationEvent<OutboxCommittedEvent> event) {
                    // The committed row is durable. The independently authorized publisher polls it.
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = PREFIX, name = "role", havingValue = "PUBLISHER")
    static class PublisherRoleConfiguration {
        @Bean("stellarWorkerProbe")
        @ConditionalOnProperty(name = WorkerProcessProbe.ENABLED_PROPERTY, havingValue = "true")
        WorkerProcessProbe workerProbe(@Qualifier("applicationAvailability") ApplicationAvailability availability,
                @Qualifier("top.egon.cola.component.common.id.autoconfigure.IdGeneratorAutoConfiguration")
                IdGeneratorAutoConfiguration idAuthority, @Qualifier("outboxPoller") OutboxPoller poller) {
            return new WorkerProcessProbe(availability, idAuthority::assertRedisLeaseAvailable, poller::isRunning);
        }

        @Bean(value = "gatewayKubernetesClient", destroyMethod = "close")
        GatewayKubernetesClient gatewayKubernetesClient(@Qualifier("gatewayUrlExposureRole") RoleEnum role,
                @Qualifier(PROPERTIES) PublisherProperties properties,
                @Qualifier("gatewayExposurePublicationConfiguration") GatewayExposurePublicationConfiguration inventory,
                @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
                @Qualifier("jacksonObjectMapper") ObjectMapper json) {
            var bindings = inventory.publicationBindings();
            if (bindings.isEmpty()) throw invalid("AUTHORITY_REQUIRED");
            var first = bindings.getFirst();
            var gateways = new HashSet<GatewayKey>(); var services = new HashSet<ServiceKey>(); var grants = new HashSet<GrantKey>();
            for (var binding : bindings) {
                if (!Objects.equals(context.installationTenantId(), binding.ownerTenantId())
                        || !first.publicationDomain().clusterId().equals(binding.publicationDomain().clusterId())
                        || !first.routeNamespace().equals(binding.routeNamespace()) || !binding.publicationDomain().complete()) {
                    throw invalid("AUTHORITY_AMBIGUOUS");
                }
                gateways.add(new GatewayKey(binding.gateway().namespace(), binding.gateway().name(), binding.gateway().listenerName()));
                for (var backend : binding.backends()) {
                    services.add(new ServiceKey(backend.namespace(), backend.name()));
                    if (backend.grant() != null) grants.add(new GrantKey(backend.grant().namespace(), backend.grant().name()));
                }
            }
            var scope = new AccessScope(first.publicationDomain().clusterId(), first.routeNamespace(), gateways, services, grants);
            return new GatewayKubernetesClient(properties.apiServer(), properties.caFile(), properties.tokenFile(), json,
                    properties.requestTimeout(), properties.maxResponseBytes(), scope);
        }

        @Bean("gatewayUrlExposureObservationService")
        GatewayUrlExposureObservationService gatewayUrlExposureObservationService(@Qualifier("gatewayProjectionClock") Clock clock,
                @Qualifier("jacksonObjectMapper") ObjectMapper json,
                @Qualifier("gatewayHttpRouteCompiler") GatewayHttpRouteCompiler compiler) {
            return new GatewayUrlExposureObservationService(clock, json, compiler);
        }

        @Bean("gatewayUrlExposureReconciler")
        GatewayUrlExposureReconciler gatewayUrlExposureReconciler(
                @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
                @Qualifier("mpGatewayUrlExposureRepository") GatewayUrlExposureRepository exposures,
                @Qualifier("mpGatewayReleaseRepository") GatewayReleaseRepository releases,
                @Qualifier("gatewayExposureTransactionTemplate") TransactionTemplate transactions,
                @Qualifier("gatewayKubernetesClient") GatewayKubernetesClient client,
                @Qualifier("gatewayUrlExposureObservationService") GatewayUrlExposureObservationService observations,
                @Qualifier("gatewayHttpRouteBindingResolver") ObjectProvider<GatewayHttpRouteCompiler.BindingResolver> bindings,
                @Qualifier("gatewayUrlExposureSnapshotProvider") ObjectProvider<GatewayUrlExposureObservationService.SnapshotProvider> snapshots,
                @Qualifier("gatewayUrlExposureProofProvider") ObjectProvider<GatewayUrlExposureObservationService.ProofProvider> proofs,
                @Qualifier("gatewayProjectionClock") Clock clock,
                @Qualifier("jacksonObjectMapper") ObjectMapper json) {
            return new GatewayUrlExposureReconciler(context, exposures, releases, transactions, client, observations,
                    bindings, snapshots, proofs, clock, json);
        }

        @Bean("gatewayUrlExposureWriter")
        ExposureDesiredStateDeliveryHandler gatewayUrlExposureWriter(
                @Qualifier("gatewayPersistenceContextComponent") GatewayPersistenceContextComponent context,
                @Qualifier("gatewayUrlExposureReconciler") GatewayUrlExposureReconciler reconciler) {
            return new ExposureDesiredStateDeliveryHandler(context, reconciler);
        }
    }

    public enum RoleEnum { ADMIN, PUBLISHER }

    /** No target or credential fallback exists. Paths reference deployment-projected material, never its content. */
    @ConfigurationProperties(prefix = PREFIX, ignoreUnknownFields = false)
    public record PublisherProperties(RoleEnum role, URI apiServer, Path caFile, Path tokenFile,
                                      Duration requestTimeout, Integer maxResponseBytes) {
        public PublisherProperties {
            role = role == null ? RoleEnum.ADMIN : role;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
            maxResponseBytes = maxResponseBytes == null ? 1_048_576 : maxResponseBytes;
            if (requestTimeout.compareTo(Duration.ofMillis(1)) < 0 || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || maxResponseBytes < 1 || maxResponseBytes > 4 * 1024 * 1024) throw invalid("TRANSPORT_BOUNDS_INVALID");
        }
        @Override public String toString() { return "Publisher transport configuration"; }
    }

    private static void validateTokenFile(Path path) {
        byte[] bytes = null;
        try (var input = java.nio.file.Files.newInputStream(path)) {
            if (!java.nio.file.Files.isRegularFile(path)) throw invalid("TOKEN_UNAVAILABLE");
            bytes = input.readNBytes(16_385);
            if (bytes.length == 0 || bytes.length > 16_384 || bytes[0] == '=') throw invalid("TOKEN_UNAVAILABLE");
            boolean padding = false;
            for (byte value : bytes) {
                int character = value & 255;
                if (character == '=') padding = true;
                else if (padding || !(character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z'
                        || character >= '0' && character <= '9' || "._~+/-".indexOf(character) >= 0)) throw invalid("TOKEN_UNAVAILABLE");
            }
        } catch (java.io.IOException unavailable) {
            throw invalid("TOKEN_UNAVAILABLE");
        } finally {
            if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("YUHENG_EXPOSURE_PUBLISHER_" + detail);
    }
}
