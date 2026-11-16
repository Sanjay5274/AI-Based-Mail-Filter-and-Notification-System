package com.inboxiq.processing.service;

import java.util.UUID;

public interface ProcessingJobHandler {
    void handleJob(UUID jobId, String workerId);
}
