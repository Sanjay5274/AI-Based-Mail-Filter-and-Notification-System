package com.inboxiq.processing.repository;

import com.inboxiq.processing.entity.ProcessingAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProcessingAttemptRepository extends JpaRepository<ProcessingAttempt, UUID> {

    List<ProcessingAttempt> findAllByJobIdOrderByAttemptNumberAsc(UUID jobId);

    long countByJobId(UUID jobId);
}
