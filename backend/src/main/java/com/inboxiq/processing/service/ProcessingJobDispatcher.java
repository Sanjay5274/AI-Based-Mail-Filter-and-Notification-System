package com.inboxiq.processing.service;

import com.inboxiq.processing.entity.ProcessingJob;
import com.inboxiq.processing.repository.ProcessingJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled worker dispatcher that polls for claimable processing jobs using
 * PostgreSQL FOR UPDATE SKIP LOCKED and dispatches them across a bounded thread pool.
 */
@Component
public class ProcessingJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobDispatcher.class);

    private final ProcessingJobService processingJobService;
    private final ProcessingJobRepository processingJobRepository;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final ApplicationContext applicationContext;

    private final String workerInstanceId;
    private final Duration leaseDuration;
    private final int batchSize;
    private final boolean dispatcherEnabled;

    public ProcessingJobDispatcher(
            ProcessingJobService processingJobService,
            ProcessingJobRepository processingJobRepository,
            @Qualifier("processingJobExecutor") ThreadPoolTaskExecutor taskExecutor,
            ApplicationContext applicationContext,
            @Value("${inboxiq.jobs.lease-duration-minutes:5}") int leaseDurationMinutes,
            @Value("${inboxiq.jobs.batch-size:5}") int batchSize,
            @Value("${inboxiq.jobs.dispatcher-enabled:true}") boolean dispatcherEnabled
    ) {
        this.processingJobService = processingJobService;
        this.processingJobRepository = processingJobRepository;
        this.taskExecutor = taskExecutor;
        this.applicationContext = applicationContext;
        this.workerInstanceId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.leaseDuration = Duration.ofMinutes(leaseDurationMinutes);
        this.batchSize = batchSize;
        this.dispatcherEnabled = dispatcherEnabled;

        log.info("Initialized ProcessingJobDispatcher [instance: {}, lease: {}m, enabled: {}]",
                workerInstanceId, leaseDurationMinutes, dispatcherEnabled);
    }

    public String getWorkerInstanceId() {
        return workerInstanceId;
    }

    /**
     * Periodic poll loop for claiming pending jobs and recovering expired leases.
     */
    @Scheduled(fixedDelayString = "${inboxiq.jobs.poll-interval-ms:2000}")
    public void pollAndDispatch() {
        if (!dispatcherEnabled) {
            return;
        }

        try {
            // 1. Recover expired leases
            int recovered = processingJobService.recoverExpiredJobs(batchSize);
            if (recovered > 0) {
                log.info("Recovered {} expired job leases", recovered);
            }

            // 2. Check if thread pool has available capacity
            int active = taskExecutor.getActiveCount();
            int max = taskExecutor.getMaxPoolSize();
            int availableCapacity = max - active;
            if (availableCapacity <= 0) {
                return;
            }

            int toClaim = Math.min(availableCapacity, batchSize);

            // 3. Claim jobs atomically using FOR UPDATE SKIP LOCKED
            List<ProcessingJob> claimed = processingJobService.claimJobs(workerInstanceId, leaseDuration, toClaim);

            for (ProcessingJob job : claimed) {
                taskExecutor.execute(() -> executeClaimedJob(job.getId()));
            }

        } catch (Exception e) {
            log.error("Error in ProcessingJobDispatcher poll loop: {}", e.getMessage(), e);
        }
    }

    /**
     * Executes a claimed job by delegating to the appropriate handler service.
     */
    public void executeClaimedJob(UUID jobId) {
        ProcessingJobHandler handler = applicationContext.getBean(ProcessingJobHandler.class);
        handler.handleJob(jobId, workerInstanceId);
    }
}
