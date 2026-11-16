package com.inboxiq.email.repository;

import com.inboxiq.email.entity.EmailAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmailAttachmentRepository extends JpaRepository<EmailAttachment, UUID> {

    List<EmailAttachment> findAllByEmailId(UUID emailId);
}
