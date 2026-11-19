package top.egon.cola.component.yuheng.llm.proxy.repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.exception.LlmInvocationException;
import top.egon.cola.component.yuheng.llm.proxy.repository.impl.MpLlmConfigurationRepository;

/**
 * 中文说明：仅后台从当前权威配置行加载完整RR快照，原子替换内存发布；请求不持有数据库连接。
 * 丢失提示由周期对账补齐，失败只允许使用尚未过期的已验证快照，不提供离线无限续期或历史回退。
 * English summary: Background coherent captures of current authoritative rows atomically replace a bounded immutable
 * publication. Hints only wake reconciliation; failures never extend its age or create an offline fallback.
 */
@Slf4j
@Validated
public class LlmConfigurationSnapshotCoordinator implements LlmConfigurationRepository, AutoCloseable {
    private final MpLlmConfigurationRepository authority;
    private final Clock clock;
    private final Duration reconcileInterval;
    private final Duration maximumAge;
    private final LongSupplier nanoClock;
    private final AtomicReference<Publication> current = new AtomicReference<>();
    private final AtomicLong invalidations = new AtomicLong();
    private final AtomicBoolean hintQueued = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;

    public LlmConfigurationSnapshotCoordinator(MpLlmConfigurationRepository authority, Clock clock,
            Duration reconcileInterval, Duration maximumAge) {
        this(authority, clock, reconcileInterval, maximumAge, System::nanoTime);
    }

    public LlmConfigurationSnapshotCoordinator(MpLlmConfigurationRepository authority, Clock clock,
            Duration reconcileInterval, Duration maximumAge, LongSupplier nanoClock) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (reconcileInterval == null || maximumAge == null || reconcileInterval.isNegative()
                || reconcileInterval.isZero() || reconcileInterval.toMillis() < 100
                || maximumAge.compareTo(reconcileInterval) <= 0 || maximumAge.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("LLM_PUBLICATION_REFRESH_CONFIGURATION_INVALID");
        }
        this.reconcileInterval = reconcileInterval;
        this.maximumAge = maximumAge;
    }

    /** 中文说明：首次验证完成前不接受请求。 English summary: Startup requires one successful authority capture. */
    public synchronized void start() {
        if (closed.get()) {
            throw unavailable();
        }
        if (executor != null) {
            return;
        }
        if (!reconcile()) {
            throw unavailable();
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "model-policy-reconciliation");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::reconcile, reconcileInterval.toMillis(),
                reconcileInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 中文说明：完整捕获完成后一次替换，提示与读取交错时不发布早于提示的结果。 English summary: Installs only a complete capture that was not invalidated while loading. */
    public synchronized boolean reconcile() {
        if (closed.get()) {
            return false;
        }
        long sequence = invalidations.get();
        // Age starts before SQL, so loading time never silently extends the authority freshness budget.
        Instant capturedAt = clock.instant();
        long capturedNanos = nanoClock.getAsLong();
        try {
            List<LlmModelSnapshotBO> snapshots = authority.loadPublication();
            if (snapshots == null || snapshots.size() > 1000) {
                throw unavailable();
            }
            Map<String, LlmModelSnapshotBO> byAlias = new LinkedHashMap<>();
            Long tenant = null;
            List<LlmModelSnapshotBO> frozen = new ArrayList<>(snapshots.size());
            for (LlmModelSnapshotBO snapshot : snapshots) {
                if (snapshot == null || snapshot.getInstallationTenantId() == null
                        || snapshot.getInstallationTenantId() <= 0 || snapshot.getPublicationDigest() == null
                        || !snapshot.getPublicationDigest().matches("[0-9a-f]{64}")) {
                    throw unavailable();
                }
                if (tenant != null && !tenant.equals(snapshot.getInstallationTenantId())) {
                    throw unavailable();
                }
                tenant = snapshot.getInstallationTenantId();
                LlmModelSnapshotBO immutable = snapshot.immutableCopy(snapshot.getInstallationTenantId(),
                        snapshot.getPublicationDigest());
                if (snapshot.getModelKey() == null || byAlias.put(snapshot.getModelKey(), immutable) != null) {
                    throw unavailable();
                }
                frozen.add(immutable);
            }
            List<LlmModelSnapshotBO> bundle = LlmModelSnapshotConverter.freezePublicationBundle(frozen);
            byAlias.clear();
            bundle.forEach(policy -> byAlias.put(policy.getModelKey(), policy));
            Publication publication = new Publication(Map.copyOf(byAlias), bundle,
                    capturedAt, capturedNanos, sequence);
            if (sequence != invalidations.get() || closed.get() || !fresh(publication)) {
                return false;
            }
            current.set(publication);
            // Close the race where a hint invalidated immediately before this store.
            if (sequence != invalidations.get()) {
                current.compareAndSet(publication, null);
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            log.warn("LLM_PUBLICATION_RECONCILIATION_UNAVAILABLE errorType={}", failure.getClass().getSimpleName());
            return false;
        }
    }

    /** 中文说明：提示无权提供策略或延长有效期，只失效并合并唤醒。 English summary: A hint carries no policy authority and never refreshes freshness. */
    public void hint() {
        invalidations.incrementAndGet();
        current.set(null);
        ScheduledExecutorService worker = executor;
        if (worker == null || closed.get() || !hintQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(() -> {
                hintQueued.set(false);
                reconcile();
            });
        } catch (RuntimeException unavailable) {
            hintQueued.set(false);
            log.warn("LLM_PUBLICATION_WAKE_UNAVAILABLE");
        }
    }

    @Override
    public Optional<LlmModelSnapshotBO> findSnapshot(String modelKey) {
        return Optional.ofNullable(publication().byAlias().get(modelKey));
    }

    @Override
    public List<LlmModelSnapshotBO> findCatalog() {
        return publication().ordered();
    }

    private Publication publication() {
        Publication publication = current.get();
        if (!readable(publication)) {
            throw new LlmInvocationException(503, "model_unavailable", null,
                    "The model configuration is unavailable", true);
        }
        return publication;
    }

    /** 中文说明：同一内存有效期门禁，不发起数据库或网络探测。 English summary: The same publication freshness gate, with no I/O. */
    public boolean isReady() {
        return readable(current.get());
    }

    private boolean readable(Publication publication) {
        return !closed.get() && publication != null && publication.sequence() == invalidations.get() && fresh(publication);
    }

    private boolean fresh(Publication publication) {
        Instant now = clock.instant();
        long elapsed = nanoClock.getAsLong() - publication.capturedNanos();
        return !now.isBefore(publication.capturedAt())
                && now.isBefore(publication.capturedAt().plus(maximumAge))
                && elapsed >= 0 && elapsed < maximumAge.toNanos();
    }

    @Override
    public synchronized void close() {
        closed.set(true);
        current.set(null);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("YUHENG_LLM_CONFIGURATION_UNAVAILABLE");
    }

    private record Publication(Map<String, LlmModelSnapshotBO> byAlias, List<LlmModelSnapshotBO> ordered,
            Instant capturedAt, long capturedNanos, long sequence) {}
}
