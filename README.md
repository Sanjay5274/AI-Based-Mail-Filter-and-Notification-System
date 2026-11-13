# InboxIQ – AI-Powered Context-Aware Email Intelligence & Notification Platform

---

## 1. Project Overview

**InboxIQ** is a multi-user SaaS platform that connects with users' Gmail accounts, analyzes incoming emails using Artificial Intelligence (Google Gemini via Spring AI), identifies relevant information across 11 canonical categories, evaluates emails against personal user preferences and private custom categories, calculates deterministic relevance scores (0–100), and sends intelligent SMS notifications.

> **Key Distinction:** InboxIQ does not replace Gmail. Gmail manages message communication. InboxIQ provides an autonomous intelligence layer that prioritizes, summarizes, and alerts users to critical opportunities—such as job interview invitations, brand sponsorships, and urgent personal matters—ensuring you never miss what matters most.

---

## 2. Core Features

- **Multi-Account Gmail Connection:** Connect one or more Gmail accounts securely via Google OAuth 2.0 with minimal read-only permissions (`gmail.readonly`).
- **Encrypted Credentials at Rest:** Refresh tokens encrypted with AES-256-GCM, unique nonces, authentication tags, and Additional Authenticated Data (AAD).
- **Two-Stage Intelligence Pipeline:**
  - **Stage A (Global AI Classification):** Multi-label classification across 11 canonical built-in categories with structured entity extraction and concise summaries.
  - **Stage B (User-Specific Matching Engine):** Evaluates emails against the owning user's enabled preferences and private custom categories with deterministic scoring.
- **Consolidated Single-Notification Events:** Multiple category matches trigger exactly **one consolidated alert**, eliminating duplicate SMS notifications.
- **Lease-Based Persistent Background Processing:** PostgreSQL-backed durable job queue utilizing `FOR UPDATE SKIP LOCKED` and time-bounded leases. Zero external message broker dependencies (no RabbitMQ/Kafka in MVP).
- **Crash Recovery & Retry Backoff:** Automatic reclamation of abandoned jobs upon container restart, paired with exponential backoff and randomized jitter.
- **Privacy-First Data Architecture:** No storage of raw AI prompts or full LLM response dumps. Configurable retention periods for email body content.
- **Modern SaaS Dashboard:** Built with Next.js 14, TypeScript, Tailwind CSS, and shadcn/ui.

---

## 3. Technology Stack

| Layer | Technology |
|---|---|
| **Backend Runtime** | Java 21 (LTS) |
| **Backend Framework** | Spring Boot 3.3+ (Modular Monolith) |
| **Security** | Spring Security + Stateless JWT (HttpOnly Cookies) + Persistent Session Rotation |
| **Database & Migrations** | PostgreSQL 15+ & Flyway (`V1` to `V15` sequential migrations) |
| **AI Integration** | Spring AI with Google Gemini (`gemini-1.5-flash` / `gemini-1.5-pro`) |
| **Email Integration** | Google Gmail API v1 (Google OAuth 2.0, Gmail History API) |
| **Background Processing**| PostgreSQL Queue (`FOR UPDATE SKIP LOCKED`) + `ThreadPoolTaskExecutor` |
| **Notification Engine** | SMS via Twilio / Indian DLT Provider (MSG91 / Fast2SMS) |
| **Frontend Framework** | Next.js 14+ (App Router) + TypeScript |
| **UI Components** | Tailwind CSS + shadcn/ui + Lucide Icons |
| **Containerization** | Docker & Docker Compose |

---

## 4. Documentation Index

The complete architectural specifications are documented in the `docs/` directory:

| Document | Description |
|---|---|
| [docs/HLD-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/HLD-v2.md) | High-Level Design: System context, architecture diagram, component overview, technology justifications, and ADRs. |
| [docs/LLD-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/LLD-v2.md) | Low-Level Design: `package-by-feature` structure, Java domain models, service signatures, REST API contracts, and JSON schemas. |
| [docs/DATABASE-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/DATABASE-v2.md) | Database Architecture: 15 core tables, ER diagram, partial unique indexes, check constraints, Flyway migration order, and JSONB schemas. |
| [docs/SECURITY-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/SECURITY-v2.md) | Security Specification: Dual-auth separation, AES-256-GCM token encryption, prompt injection defense, CSRF/CORS, and retention. |
| [docs/API-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/API-v2.md) | REST API Contracts: Complete endpoint catalog, request/response DTOs, query parameters, error responses, and ownership rules. |
| [docs/GMAIL-INTEGRATION-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/GMAIL-INTEGRATION-v2.md) | Gmail Integration: OAuth 2.0 flow, incremental sync via History API, token revocation, deduplication, and post-MVP Pub/Sub design. |
| [docs/AI-PIPELINE-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/AI-PIPELINE-v2.md) | AI Intelligence Pipeline: Spring AI abstraction, preprocessing, prompt templates, output validation, and versioning. |
| [docs/BACKGROUND-PROCESSING-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/BACKGROUND-PROCESSING-v2.md) | Background Jobs: Persistent queue, lease management, worker pools, retry backoff, crash recovery, and Cloud Run execution. |
| [docs/IMPLEMENTATION-ROADMAP-v2.md](file:///c:/Users/sanja/Desktop/InboxIQ/docs/IMPLEMENTATION-ROADMAP-v2.md) | Implementation Roadmap: Detailed 10-phase engineering plan from foundation to production readiness. |

### Architectural Diagrams (`docs/diagrams/`)
- [system-architecture.mmd](file:///c:/Users/sanja/Desktop/InboxIQ/docs/diagrams/system-architecture.mmd)
- [database-erd.mmd](file:///c:/Users/sanja/Desktop/InboxIQ/docs/diagrams/database-erd.mmd)
- [email-processing-flow.mmd](file:///c:/Users/sanja/Desktop/InboxIQ/docs/diagrams/email-processing-flow.mmd)
- [authentication-flow.mmd](file:///c:/Users/sanja/Desktop/InboxIQ/docs/diagrams/authentication-flow.mmd)
- [gmail-oauth-flow.mmd](file:///c:/Users/sanja/Desktop/InboxIQ/docs/diagrams/gmail-oauth-flow.mmd)
- [notification-flow.mmd](file:///c:/Users/sanja/Desktop/InboxIQ/docs/diagrams/notification-flow.mmd)
- [background-job-lifecycle.mmd](file:///c:/Users/sanja/Desktop/InboxIQ/docs/diagrams/background-job-lifecycle.mmd)

---

## 5. Implementation Roadmap Summary

```text
Phase 1: Project Foundation (Monorepo, Docker Compose, Spring Boot & Next.js Bootstrap)
Phase 2: Database Architecture (All 15 Flyway Migrations V1-V15, Schema Validation)
Phase 3: Authentication & Gmail Integration (JWT, Refresh Rotation, Google OAuth, AES-256-GCM)
Phase 4: Email Processing Infrastructure (PostgreSQL Lease Queue, Workers, Recovery Daemon)
Phase 5: AI Intelligence & Classification (Spring AI + Gemini, Schema Validation, Versioning)
Phase 6: Categories & Matching Engine (11 System Categories, Custom Categories, Scoring Engine)
Phase 7: Notification System (Deduplicated Single-Event Alerts, Twilio/DLT SMS Dispatch)
Phase 8: Frontend SaaS Dashboard (Next.js 14, Smart Inbox, Settings, Analytics)
Phase 9: Advanced Gmail Push Synchronization (Google Cloud Pub/Sub, History API, Watch Renewal)
Phase 10: Production Readiness (E2E Testing, Security Audit, Query Tuning, Cloud Run Deployment)
```

---

## 6. Current Project State

> ⚠️ **Status: Architecture & Design Approved Baseline**
> 
> The architecture documentation has been fully reconciled and updated with all 20 architectural corrections. No production application code or database migrations have been generated yet, awaiting formal human approval of this baseline before commencing Phase 1.

