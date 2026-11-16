package com.inboxiq.processing.repository;

import com.inboxiq.processing.entity.ProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {

    Optional<ProcessingJob> findByIdempotencyKey(String idempotencyKey);

    Optional<ProcessingJob> findTopByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, UUID targetId);

    Optional<ProcessingJob> findFirstByTargetTypeAndTargetIdAndStatusIn(String targetType, UUID targetId, Collection<String> statuses);

    boolean existsByTargetTypeAndTargetIdAndStatusIn(String targetType, UUID targetId, Collection<String> statuses);

    @Query(value = """
        SELECT * FROM processing_jobs
        WHERE status IN ('PENDING', 'RETRY_WAIT')
          AND next_retry_at <= :now
        ORDER BY priority ASC, created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<ProcessingJob> findClaimableJobs(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM processing_jobs
        WHERE status = 'PROCESSING'
          AND locked_until < :now
        ORDER BY priority ASC, created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<ProcessingJob> findExpiredJobs(@Param("now") Instant now, @Param("limit") int limit);
}
