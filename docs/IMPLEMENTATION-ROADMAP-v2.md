# InboxIQ – Implementation Roadmap (IMPLEMENTATION-ROADMAP-v2.md)
**10-Phase Engineering Implementation Plan**

---

## Roadmap Overview

InboxIQ will be implemented in strictly phased, verifiable milestones. Each phase builds upon the verified deliverables of the preceding phase. **No phase will commence until the preceding phase passes all functional and security acceptance tests.**

```text
Phase 1: Project Foundation & Monorepo
   └── Phase 2: Database Architecture & Migrations
          └── Phase 3: Authentication & Gmail Integration
                 └── Phase 4: Background Processing Infrastructure
                        └── Phase 5: AI Intelligence & Classification
                               └── Phase 6: Matching Engine & Categories
                                      └── Phase 7: Notification Engine & SMS
                                             └── Phase 8: Next.js SaaS Dashboard
                                                    └── Phase 9: Push Sync (Post-MVP)
                                                           └── Phase 10: Production Readiness
```

---

## Phase 1: Project Foundation & Environment Setup
- **Goal:** Establish a working local monorepo with containerized PostgreSQL and verified startup for both backend and frontend.
- **Key Tasks:**
  1. Initialize monorepo directory layout (`backend/`, `frontend/`, `docs/`).
  2. Bootstrap Spring Boot 3.3+ backend with Java 21, Maven, Spring Web, Spring Security, Spring Data JPA, Flyway, and Spring AI.
  3. Bootstrap Next.js 14+ frontend with TypeScript, Tailwind CSS, and shadcn/ui.
  4. Author `docker-compose.yml` for local PostgreSQL 15+ container with persistent volume.
  5. Configure `.env.example` defining all required configuration variables.
  6. Verify backend health check endpoint (`GET /actuator/health`).
- **Deliverables:** Clean monorepo foundation, running database, verified frontend/backend startup.

---

## Phase 2: Database Architecture & Flyway Migrations
- **Goal:** Deploy the complete 15-table relational schema and seed the canonical 11 system categories.
- **Key Tasks:**
  1. Author sequential Flyway migrations (`V1__create_users_table.sql` through `V15__create_processing_jobs_and_attempts_tables.sql`).
  2. Implement partial unique indexes (e.g., active classification versioning, custom category slug uniqueness).
  3. Seed the canonical 11 system categories via `V7__seed_system_categories.sql`.
  4. Configure Hibernate `spring.jpa.hibernate.ddl-auto=validate` to strictly forbid auto-schema mutations.
  5. Verify relational integrity, foreign key cascades, and check constraints against PostgreSQL.
- **Deliverables:** Fully migrated PostgreSQL schema with complete constraints and indexes verified.

---

## Phase 3: Authentication & Gmail OAuth Integration
- **Goal:** Implement secure user registration, session management, and Gmail account connection with token encryption.
- **Key Tasks:**
  1. Implement user registration and login with BCrypt (cost factor 12).
  2. Implement stateless JWT access tokens (15m) delivered in HttpOnly cookies.
  3. Implement persistent refresh tokens in `auth_sessions` with family-based rotation and reuse compromise detection.
  4. Implement CSRF double-submit protection and secure CORS configuration.
  5. Implement Google OAuth 2.0 flow with minimal scope (`gmail.readonly`), PKCE, and state binding.
  6. Implement `TokenEncryptionService` using AES-256-GCM with AAD (`accountId:userId:keyVersion`).
  7. Implement initial Gmail synchronization and message deduplication (`UNIQUE (connected_account_id, gmail_message_id)`).
  8. Implement account disconnection and external token revocation with Google (`https://oauth2.googleapis.com/revoke`).
- **Deliverables:** Verified auth lifecycle, secure Gmail account connection, encrypted token storage.

---

## Phase 4: Email Processing Infrastructure
- **Goal:** Implement persistent, lease-based background job execution with crash recovery.
- **Key Tasks:**
  1. Implement `JobDispatcherService` polling every 5s using `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 10`.
  2. Implement atomic job claiming with 5-minute leases (`locked_by`, `locked_until`).
  3. Configure bounded in-memory buffer (`ArrayBlockingQueue` with capacity 50) and `ThreadPoolTaskExecutor`.
  4. Execute tasks outside database transactions to prevent connection pool exhaustion.
  5. Implement exponential retry backoff with randomized jitter and audit logging in `processing_attempts`.
  6. Implement `JobRecoveryService` scheduled every 2m to reclaim expired leases (`locked_until < NOW()`).
  7. Test unexpected worker termination and verify automatic job reclamation.
- **Deliverables:** Resilient background job queue capable of surviving container crashes and restarts.

---

## Phase 5: AI Intelligence & Classification Pipeline
- **Goal:** Integrate Spring AI with Google Gemini for multi-label classification and structured entity extraction.
- **Key Tasks:**
  1. Configure Spring AI Gemini client with environment-based API keys and timeout bounds.
  2. Implement `EmailPreprocessingService` (MIME parsing, quoted reply stripping, 3,500 character token budgeting).
  3. Implement `PromptBuilderService` with hardened untrusted data delimiters (`[EMAIL_CONTENT_START]`).
  4. Implement `AiResponseValidator` enforcing strict JSON schema, category taxonomy, and confidence bounds.
  5. Persist validated classifications in `email_classifications` (versioned) and `email_classification_categories`.
  6. Enforce zero raw prompt / full raw response storage policy to safeguard user privacy.
- **Deliverables:** Verified multi-label email classification pipeline producing validated structured JSON.

---

## Phase 6: Categories & User-Specific Matching Engine
- **Goal:** Build Stage B matching engine evaluating classified emails against user preferences and custom categories.
- **Key Tasks:**
  1. Implement category management APIs (list system categories, CRUD for user custom categories).
  2. Implement user category preferences API (`min_relevance_score`, `priority`, keywords, JSONB criteria).
  3. Implement `MatchingEngineService` evaluating emails exclusively for the owning user.
  4. Implement `RelevanceScoringService` calculating deterministic scores (0–100) combining AI confidence and keywords.
  5. Implement custom category matching against email summaries and extracted entities.
  6. Persist match outcomes in `email_category_matches`.
- **Deliverables:** Deterministic relevance matching engine honoring multi-tenant isolation and user preferences.

---

## Phase 7: Notification System & SMS Integration
- **Goal:** Deliver consolidated, deduplicated SMS notifications for high-priority matching emails.
- **Key Tasks:**
  1. Implement `NotificationService` consolidating multiple category matches into **one single notification event** per email.
  2. Enforce deduplication via `idempotency_key = "NOTIF:" || userId || ":" || emailId`.
  3. Create channel delivery records in `notification_deliveries`.
  4. Implement `SmsProvider` interface with `TwilioSmsProvider` and `IndianDltSmsProvider` implementations.
  5. Implement provider webhook controller with signature verification (`X-Twilio-Signature`) to track delivery.
  6. Implement quiet hours enforcement and user notification preference management.
- **Deliverables:** Reliable, deduplicated SMS notification pipeline with end-to-end delivery tracking.

---

## Phase 8: Next.js SaaS Frontend Dashboard
- **Goal:** Deliver a modern, accessible, responsive SaaS user interface connected to backend REST APIs.
- **Key Tasks:**
  1. Build authentication pages (login, registration) with form validation and cookie handling.
  2. Build Dashboard overview showing stats, category distribution, and recent activity.
  3. Build Smart Inbox with email list, category badges, relevance scores, and sanitized HTML rendering.
  4. Build Categories screen supporting preference configuration and custom category creation/editing.
  5. Build Notifications history screen with delivery status tracking.
  6. Build Settings screen managing connected Gmail accounts and SMS configurations.
- **Deliverables:** Production-grade Next.js web application connected to live backend APIs.

---

## Phase 9: Advanced Gmail Push Synchronization (Post-MVP)
- **Goal:** Real-time mailbox synchronization via Google Cloud Pub/Sub.
- **Key Tasks:**
  1. Configure Google Cloud Pub/Sub topic and subscription.
  2. Implement webhook receiver (`POST /api/v1/webhooks/gmail/push`) with Google service account JWT validation.
  3. Trigger incremental sync via History API upon receipt of mailbox change events.
  4. Implement daily scheduled watch renewal job (`POST /gmail/v1/users/me/watch`).
  5. Retain 15-minute scheduled polling as a resilient fallback.
- **Deliverables:** Real-time push synchronization with automated watch renewal.

---

## Phase 10: Production Readiness & Deployment
- **Goal:** Security hardening, query optimization, containerization, and Cloud Run deployment.
- **Key Tasks:**
  1. Run end-to-end integration test suite (JUnit 5, Mockito, Testcontainers).
  2. Perform security audit (SQL injection, XSS, CSRF, prompt injection, token encryption).
  3. Optimize database query performance with `EXPLAIN ANALYZE` on job claiming and email listing queries.
  4. Package backend and frontend into optimized Docker production images.
  5. Configure Google Cloud Run deployment with `--min-instances=1` and `--no-cpu-throttling`.
  6. Finalize operations and runbook documentation.
- **Deliverables:** Production-certified SaaS deployment ready for user traffic.

