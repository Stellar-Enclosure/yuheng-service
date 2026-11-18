package top.egon.cola.component.yuheng.admin.exposure.repository.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationUtils;
import top.egon.cola.component.yuheng.admin.exposure.converter.GatewayUrlExposurePersistenceConverter;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.po.GatewayUrlExposurePO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.repository.mp.GatewayUrlExposurePersistenceRepository;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Owner-facing persistence adapter. Global occupancy never leaves this internal reservation boundary. */
@Slf4j
@Validated
@Repository("mpGatewayUrlExposureRepository")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Transactional(transactionManager = "gatewayTransactionManager", propagation = Propagation.MANDATORY)
public class MpGatewayUrlExposureRepository implements GatewayUrlExposureRepository {

    @Qualifier("gatewayUrlExposurePersistenceRepository")
    private final GatewayUrlExposurePersistenceRepository persistence;
    @Qualifier("gatewayUrlExposurePersistenceConverter")
    private final GatewayUrlExposurePersistenceConverter converter;
    @Qualifier("egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties")
    private final EgonColaMybatisPlusProperties properties;
    @Qualifier("gatewayPersistenceContextComponent")
    private final GatewayPersistenceContextComponent context;
    @Qualifier("gatewayUrlExposurePublicationDomainResolver")
    private final ObjectProvider<PublicationDomainResolver> domains;

    @Override
    public GatewayUrlExposureBO reserve(long trustedOwner, GatewayUrlExposureBO registration) {
        return reserveAll(trustedOwner, List.of(Objects.requireNonNull(registration, "registration"))).getFirst();
    }

    @Override
    public List<GatewayUrlExposureBO> reserveAll(long trustedOwner, List<GatewayUrlExposureBO> registrations) {
        requireOwner(trustedOwner);
        Objects.requireNonNull(registrations, "registrations");
        if (registrations.isEmpty()) { return List.of(); }
        List<GatewayUrlExposurePO> rows = new ArrayList<>();
        List<PublicationDomainVO> scopes = new ArrayList<>();
        Map<String, PublicationDomainVO> versions = new LinkedHashMap<>();
        for (GatewayUrlExposureBO registration : registrations) {
            GatewayUrlExposurePO row = initialRow(trustedOwner, Objects.requireNonNull(registration, "registration"));
            PublicationDomainVO domain = domain(row);
            String key = domain.clusterId().length() + ":" + domain.clusterId() + domain.publicationDomainId();
            PublicationDomainVO previous = versions.putIfAbsent(key, domain);
            if (previous != null && !previous.equals(domain)) { throw inventoryIncomplete(); }
            rows.add(row);
            scopes.add(domain);
        }
        return context.withinUrlExposureControl(trustedOwner, () -> {
            persistence.requireTransaction();
            scopes.stream().map(MpGatewayUrlExposureRepository::lockKey).distinct().sorted()
                    .forEach(persistence::lockPublicationDomain);
            List<GatewayUrlExposureBO> reserved = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                GatewayUrlExposurePO row = rows.get(index);
                GatewayUrlExposurePO existing = persistence.findOwnedRoute(trustedOwner,
                        row.getGatewayGroupId(), row.getRouteId());
                if (existing != null) {
                    if (!sameRegistration(existing, row)) { throw bindingConflict(); }
                    reserved.add(converter.toBusiness(existing));
                    continue; // Current CLOSED/WITHDRAWN facts are never replaced by a replayed OPEN.
                }
                if (hasOverlap(row, scopes.get(index))) throw bindingConflict();
                try {
                    if (!persistence.save(row)) { throw new IllegalStateException("URL_EXPOSURE_INSERT_CONFLICT"); }
                } catch (DuplicateKeyException conflict) {
                    throw bindingConflict();
                }
                reserved.add(converter.toBusiness(persistence.findOwned(trustedOwner, row.getId())));
            }
            return List.copyOf(reserved);
        });
    }

    @Override
    @Transactional(transactionManager = "gatewayTransactionManager", propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<GatewayUrlExposureBO> findOwned(long trustedOwner, long exposureId) {
        requireOwner(trustedOwner);
        positive(exposureId);
        return context.withinUrlExposureControl(trustedOwner, () -> Optional.ofNullable(persistence.findOwned(trustedOwner, exposureId))
                .map(converter::toBusiness));
    }

    @Override
    @Transactional(transactionManager = "gatewayTransactionManager", propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<GatewayUrlExposureBO> findOwnedRoute(long trustedOwner, long gatewayGroupId, String routeId) {
        requireOwner(trustedOwner);
        positive(gatewayGroupId);
        if (routeId == null || routeId.isBlank() || routeId.length() > 128) {
            throw new IllegalArgumentException("URL_EXPOSURE_ROUTE_ID_INVALID");
        }
        return context.withinUrlExposureControl(trustedOwner, () ->
                Optional.ofNullable(persistence.findOwnedRoute(trustedOwner, gatewayGroupId, routeId))
                        .map(converter::toBusiness));
    }

    @Override
    @Transactional(transactionManager = "gatewayTransactionManager", propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean hasPublishedOverlap(long trustedOwner, GatewayUrlExposureBO proposed) {
        requireOwner(trustedOwner);
        if (!Objects.equals(proposed.getOwnerTenantId(), trustedOwner)) {
            throw new IllegalStateException("OWNER_CONTEXT_MISMATCH");
        }
        GatewayUrlExposurePO row = converter.toPersistence(proposed);
        // A preview may exclude only the authenticated tenant's current row for this stable route.
        row.setId(findOwnedRoute(trustedOwner, proposed.getGatewayGroupId(), proposed.getRouteId())
                .map(GatewayUrlExposureBO::getId).orElse(null));
        return context.withinUrlExposureControl(trustedOwner, () -> hasOverlap(row, domain(row)));
    }

    @Override
    public boolean compareAndSetDesired(long trustedOwner, long exposureId, long expectedVersion,
            long expectedGeneration, GatewayUrlExposureStateEnum oldState,
            GatewayUrlExposureStateEnum nextState, String reason, Instant at) {
        requireOwner(trustedOwner);
        preconditions(exposureId, expectedVersion, expectedGeneration);
        Objects.requireNonNull(oldState, "oldState");
        Objects.requireNonNull(nextState, "nextState");
        return context.withinUrlExposureControl(trustedOwner, () -> {
            persistence.requireTransaction();
            GatewayUrlExposurePO row = persistence.findOwned(trustedOwner, exposureId);
            if (!matches(row, expectedVersion, expectedGeneration, oldState)) { return false; }
            if (oldState == nextState) { return true; }
            if (oldState.isTerminal()) { throw new IllegalStateException("WITHDRAWN"); }
            if (nextState == GatewayUrlExposureStateEnum.OPEN) {
                PublicationDomainVO domain = domain(row);
                persistence.lockPublicationDomain(lockKey(domain));
                if (hasOverlap(row, domain)) throw bindingConflict();
            }
            row.setDesiredState(nextState).setReconcileStatus("PENDING").setChangeReason(reason);
            if (nextState == GatewayUrlExposureStateEnum.WITHDRAWN) { row.setWithdrawnAt(Objects.requireNonNull(at, "at")); }
            stamp(row, at);
            return persistence.changeDesired(row, oldState.wireValue(), expectedGeneration);
        });
    }

    @Override
    public boolean compareAndSetContent(long trustedOwner, long exposureId, long expectedVersion,
            long expectedGeneration, GatewayUrlExposureStateEnum oldState, long releaseId,
            String contentSha256, String compilerProfileId, String reason, Instant at) {
        requireOwner(trustedOwner);
        preconditions(exposureId, expectedVersion, expectedGeneration);
        positive(releaseId);
        Objects.requireNonNull(oldState, "oldState");
        return context.withinUrlExposureControl(trustedOwner, () -> {
            persistence.requireTransaction();
            GatewayUrlExposurePO row = persistence.findOwned(trustedOwner, exposureId);
            if (!matches(row, expectedVersion, expectedGeneration, oldState)) { return false; }
            if (oldState.isTerminal()) { throw new IllegalStateException("WITHDRAWN"); }
            if (Objects.equals(row.getContentSha256(), contentSha256)
                    && Objects.equals(row.getCompilerProfileId(), compilerProfileId)) { return true; }
            PublicationDomainVO domain = domain(row);
            persistence.lockPublicationDomain(lockKey(domain));
            if (hasOverlap(row, domain)) throw bindingConflict();
            row.setReleaseId(releaseId).setContentSha256(contentSha256).setCompilerProfileId(compilerProfileId)
                    .setReconcileStatus("PENDING").setChangeReason(reason);
            stamp(row, at);
            return persistence.changeContent(row, oldState.wireValue(), expectedGeneration);
        });
    }

    @Override
    public boolean bindRouteUid(long trustedOwner, long exposureId, long expectedVersion, String verifiedUid, Instant at) {
        requireOwner(trustedOwner);
        positive(exposureId);
        version(expectedVersion);
        requireUid(verifiedUid);
        return context.withinUrlExposureControl(trustedOwner, () -> {
            persistence.requireTransaction();
            GatewayUrlExposurePO row = persistence.findOwned(trustedOwner, exposureId);
            if (row == null || row.getVersion() != expectedVersion || row.getRouteUid() != null) { return false; }
            row.setRouteUid(verifiedUid);
            stamp(row, at);
            return persistence.bindUid(row);
        });
    }

    @Override
    public boolean recordObservation(long trustedOwner, long exposureId, long expectedVersion, long evaluatedGeneration,
            String verifiedUid, String observedState, long observedGeneration, String reconcileStatus,
            JsonNode observation, String lastErrorCode, Instant at) {
        requireOwner(trustedOwner);
        preconditions(exposureId, expectedVersion, evaluatedGeneration);
        requireUid(verifiedUid);
        return context.withinUrlExposureControl(trustedOwner, () -> {
            persistence.requireTransaction();
            GatewayUrlExposurePO row = persistence.findOwned(trustedOwner, exposureId);
            if (row == null || row.getVersion() != expectedVersion
                    || row.getDesiredGeneration() != evaluatedGeneration
                    || !verifiedUid.equals(row.getRouteUid())
                    || observedGeneration < row.getObservedGeneration()) { return false; }
            Objects.requireNonNull(observation, "observation");
            JsonNode route = observation.get("kubernetesRoute");
            if (route != null && route.isObject()
                    && (!route.path("uid").isTextual() || !verifiedUid.equals(route.path("uid").textValue()))) {
                throw new IllegalArgumentException("URL_EXPOSURE_OBSERVATION_UID_MISMATCH");
            }
            row.setObservedState(observedState).setObservedGeneration(observedGeneration)
                    .setReconcileStatus(reconcileStatus).setObservation(observation.deepCopy())
                    .setLastErrorCode(lastErrorCode);
            stamp(row, at);
            return persistence.observe(row, evaluatedGeneration, verifiedUid);
        });
    }

    private GatewayUrlExposurePO initialRow(long owner, GatewayUrlExposureBO input) {
        if (!Objects.equals(input.getOwnerTenantId(), owner)) { throw new IllegalStateException("OWNER_CONTEXT_MISMATCH"); }
        GatewayUrlExposurePO row = converter.toPersistence(input);
        if (row.getId() == null) { throw new IllegalArgumentException("Exposure identity must be allocated before reservation"); }
        positive(row.getId());
        if (row.getDeletedAt() != null || row.getRouteUid() != null
                || row.getVersion() != null && row.getVersion() != 0
                || row.getDesiredGeneration() != null && row.getDesiredGeneration() != 1
                || row.getObservedGeneration() != null && row.getObservedGeneration() != 0
                || row.getObservedState() != null && !"UNKNOWN".equals(row.getObservedState())
                || row.getReconcileStatus() != null && !"PENDING".equals(row.getReconcileStatus())) {
            throw new IllegalArgumentException("URL_EXPOSURE_INITIAL_STATE_INVALID");
        }
        String routeName = "yuheng-exposure-" + row.getId();
        if (row.getRouteName() != null && !routeName.equals(row.getRouteName())) {
            throw new IllegalArgumentException("URL_EXPOSURE_ROUTE_IDENTITY_INVALID");
        }
        if (!canonicalPath(row.getPathValue()).equals(row.getPathValue())
                || row.getHostname() == null || !row.getHostname().equals(row.getHostname().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("URL_EXPOSURE_MATCH_NOT_CANONICAL");
        }
        row.setRouteName(routeName).setDesiredGeneration(1L).setObservedGeneration(0L)
                .setObservedState("UNKNOWN").setReconcileStatus("PENDING");
        var observation = JsonNodeFactory.instance.objectNode();
        observation.put("schemaVersion", 1).putNull("kubernetesRoute");
        observation.putArray("conditions");
        row.setObservation(observation);
        row.setTenantId(0L);
        row.setVersion(0L);
        row.setCreateUserId(null);
        row.setCreateTime(null);
        row.setUpdateUserId(null);
        row.setUpdateTime(null);
        EgonColaModelValidationUtils.validateBusiness(row, EgonColaModelValidationGroups.Operation.INSERT);
        return row;
    }

    private PublicationDomainVO domain(GatewayUrlExposurePO row) {
        PublicationDomainResolver resolver = domains.getIfAvailable();
        if (resolver == null) { throw inventoryIncomplete(); }
        BindingVO binding = new BindingVO(row.getGatewayNamespace(), row.getGatewayName(), row.getListenerName());
        PublicationDomainVO domain = resolver.resolve(row.getClusterId(), binding);
        if (domain == null || !domain.complete() || !domain.clusterId().equals(row.getClusterId())
                || !domain.bindings().contains(binding)) { throw inventoryIncomplete(); }
        return domain;
    }

    private boolean hasOverlap(GatewayUrlExposurePO proposed, PublicationDomainVO domain) {
        for (BindingVO binding : domain.bindings()) {
            long cursor = 0;
            while (true) {
                List<GatewayUrlExposurePO> page = persistence.scanBinding(domain.clusterId(), binding, cursor);
                for (GatewayUrlExposurePO existing : page) {
                    if (existing.getId() <= cursor) { throw new IllegalStateException("URL_EXPOSURE_SCAN_NOT_PROGRESSING"); }
                    cursor = existing.getId();
                    if (!Objects.equals(existing.getId(), proposed.getId()) && overlaps(existing, proposed)) {
                        return true;
                    }
                }
                if (page.size() < persistence.pageSize()) { break; }
            }
        }
        return false;
    }

    private boolean sameRegistration(GatewayUrlExposurePO stored, GatewayUrlExposurePO proposed) {
        return Objects.equals(stored.getOwnerTenantId(), proposed.getOwnerTenantId())
                && Objects.equals(stored.getGatewayGroupId(), proposed.getGatewayGroupId())
                && Objects.equals(stored.getRouteId(), proposed.getRouteId())
                && Objects.equals(stored.getClusterId(), proposed.getClusterId())
                && Objects.equals(stored.getGatewayNamespace(), proposed.getGatewayNamespace())
                && Objects.equals(stored.getGatewayName(), proposed.getGatewayName())
                && Objects.equals(stored.getGatewayUid(), proposed.getGatewayUid())
                && Objects.equals(stored.getListenerName(), proposed.getListenerName())
                && Objects.equals(stored.getHostname(), proposed.getHostname())
                && Objects.equals(stored.getHttpMethod(), proposed.getHttpMethod())
                && Objects.equals(stored.getPathMatchType(), proposed.getPathMatchType())
                && Objects.equals(stored.getPathValue(), proposed.getPathValue())
                && Objects.equals(stored.getRouteKind(), proposed.getRouteKind())
                && Objects.equals(stored.getRouteNamespace(), proposed.getRouteNamespace())
                && Objects.equals(stored.getReleaseId(), proposed.getReleaseId())
                && Objects.equals(stored.getContentSha256(), proposed.getContentSha256())
                && Objects.equals(stored.getCompilerProfileId(), proposed.getCompilerProfileId());
    }

    private boolean overlaps(GatewayUrlExposurePO left, GatewayUrlExposurePO right) {
        return hostOverlap(left.getHostname(), right.getHostname())
                && (left.getHttpMethod().equals("*") || right.getHttpMethod().equals("*")
                || left.getHttpMethod().equals(right.getHttpMethod()))
                && ("EXACT".equals(left.getPathMatchType()) && "EXACT".equals(right.getPathMatchType())
                    ? left.getPathValue().equals(right.getPathValue())
                    : "PATH_PREFIX".equals(left.getPathMatchType()) && segmentPrefix(left.getPathValue(), right.getPathValue())
                    || "PATH_PREFIX".equals(right.getPathMatchType()) && segmentPrefix(right.getPathValue(), left.getPathValue()));
    }

    private boolean hostOverlap(String left, String right) {
        if (left.equals(right)) { return true; }
        if (left.startsWith("*.")) {
            String suffix = left.substring(1);
            if (right.endsWith(suffix)) { return true; }
        }
        return right.startsWith("*.") && left.endsWith(right.substring(1));
    }

    private boolean segmentPrefix(String prefix, String path) {
        while (prefix.length() > 1 && prefix.endsWith("/")) { prefix = prefix.substring(0, prefix.length() - 1); }
        return prefix.equals("/") || path.equals(prefix) || path.startsWith(prefix + '/');
    }

    private String canonicalPath(String path) {
        if (path == null || path.length() > 1024 || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("URL_EXPOSURE_PATH_INVALID");
        }
        URI uri = URI.create(path);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawAuthority() != null
                || !path.equals(uri.getRawPath())) { throw new IllegalArgumentException("URL_EXPOSURE_PATH_INVALID"); }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < path.length(); index++) {
            char value = path.charAt(index);
            if (value != '%') { normalized.append(value); continue; }
            int code = Integer.parseInt(path.substring(index + 1, index + 3), 16);
            char decoded = (char) code;
            if (decoded >= 'a' && decoded <= 'z' || decoded >= 'A' && decoded <= 'Z'
                    || decoded >= '0' && decoded <= '9' || "-._~".indexOf(decoded) >= 0) {
                normalized.append(decoded);
            } else {
                normalized.append('%').append(path.substring(index + 1, index + 3).toUpperCase(Locale.ROOT));
            }
            index += 2;
        }
        for (String segment : normalized.toString().split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) { throw new IllegalArgumentException("URL_EXPOSURE_PATH_AMBIGUOUS"); }
        }
        return normalized.toString();
    }

    private boolean matches(GatewayUrlExposurePO row, long version, long generation, GatewayUrlExposureStateEnum state) {
        return row != null && row.getVersion() == version && row.getDesiredGeneration() == generation
                && row.getDesiredState() == state;
    }

    private void stamp(GatewayUrlExposurePO row, Instant at) {
        String actor = MDC.get(properties.getAudit().getUserIdMdcKey());
        if (actor == null || actor.isBlank() || actor.length() > 128) { throw new IllegalStateException("USER_CONTEXT_MISSING"); }
        row.setUpdateUserId(actor);
        row.setUpdateTime(Objects.requireNonNull(at, "at"));
    }

    private void requireOwner(long owner) {
        positive(owner);
        if (EgonColaTenantIdProvider.currentTenantId() != owner) { throw new IllegalStateException("TENANT_CONTEXT_MISMATCH"); }
    }

    private static void positive(long value) {
        if (value <= 0) { throw new IllegalArgumentException("URL_EXPOSURE_POSITIVE_ID_REQUIRED"); }
    }

    private static void requireUid(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new IllegalArgumentException("URL_EXPOSURE_ROUTE_UID_INVALID");
        }
    }

    private static void version(long value) {
        if (value < 0 || value == Long.MAX_VALUE) { throw new IllegalArgumentException("URL_EXPOSURE_VERSION_INVALID"); }
    }

    private static void preconditions(long id, long version, long generation) {
        positive(id);
        version(version);
        if (generation < 1 || generation == Long.MAX_VALUE) { throw new IllegalArgumentException("URL_EXPOSURE_GENERATION_INVALID"); }
    }

    private static long lockKey(PublicationDomainVO domain) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String value : List.of(domain.clusterId(), domain.publicationDomainId())) {
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())).getLong();
        } catch (IOException | NoSuchAlgorithmException impossibleForJdk) {
            throw new IllegalStateException("URL_EXPOSURE_LOCK_KEY_UNAVAILABLE", impossibleForJdk);
        }
    }

    private static IllegalStateException inventoryIncomplete() {
        return new IllegalStateException("PUBLICATION_DOMAIN_INCOMPLETE");
    }
    private static IllegalStateException bindingConflict() {
        return new IllegalStateException("BINDING_CONFLICT");
    }
}
