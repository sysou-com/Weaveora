package studio.weaveora.infra.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 观测最小集（§24）：LLM 导演/任务/NSFW 基础计数与耗时。Prometheus 经 /actuator/prometheus 暴露。 */
@Component
public class Metrics {

    private final MeterRegistry registry;
    private final Counter directorTotal;
    private final Counter directorErrors;
    private final Counter jobsQueued;
    private final Counter jobsSucceeded;
    private final Counter jobsFailed;
    private final Counter jobsCancelled;
    private final Counter nsfwHits;
    private final Timer directorTimer;

    public Metrics(MeterRegistry registry) {
        this.registry = registry;
        this.directorTotal = registry.counter("weaveora_director_total");
        this.directorErrors = registry.counter("weaveora_director_errors_total");
        this.jobsQueued = registry.counter("weaveora_job_queued_total");
        this.jobsSucceeded = registry.counter("weaveora_job_succeeded_total");
        this.jobsFailed = registry.counter("weaveora_job_failed_total");
        this.jobsCancelled = registry.counter("weaveora_job_cancelled_total");
        this.nsfwHits = registry.counter("weaveora_nsfw_hits_total");
        this.directorTimer = Timer.builder("weaveora_director_latency_seconds")
                .publishPercentiles(0.5, 0.9, 0.99).register(registry);
    }

    public void director(long nanos, boolean ok) {
        directorTotal.increment();
        directorTimer.record(nanos, TimeUnit.NANOSECONDS);
        if (!ok) directorErrors.increment();
    }

    public void jobQueued() {
        jobsQueued.increment();
    }

    public void jobSucceeded() {
        jobsSucceeded.increment();
    }

    public void jobFailed() {
        jobsFailed.increment();
    }

    public void jobCancelled() {
        jobsCancelled.increment();
    }

    public void nsfwHit() {
        nsfwHits.increment();
    }
}
