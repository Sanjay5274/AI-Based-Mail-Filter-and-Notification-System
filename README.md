# InboxIQ – AI-Powered Context-Aware Email Intelligence & Notification Platform

---

## 1. Project Overview

**InboxIQ** is a multi-user SaaS platform that connects with users' Gmail accounts, analyzes incoming emails using Artificial Intelligence (Google Gemini via Spring AI), identifies relevant information across 11 canonical categories, evaluates emails against personal user preferences and private custom categories, calculates deterministic relevance scores (0–100), and sends intelligent SMS notifications.

> **Key Distinction:** InboxIQ does not replace Gmail. Gmail manages message communication. InboxIQ provides an autonomous intelligence layer that prioritizes, summarizes, and alerts users to critical opportunities—such as job interview invitations, brand sponsorships, and urgent personal matters—ensuring you never miss what matters most.

---

## 2. Technology Stack

| Layer | Technology | Version | Description |
|---|---|---|---|
| **Backend Runtime** | Java | 21 (LTS) | Modern LTS Java runtime with virtual threads |
| **Backend Framework** | Spring Boot | 3.4.3 | Modular Monolith architecture |
| **Security** | Spring Security | 6.4+ | Baseline Actuator security (Stateless JWT in Phase 3) |
| **Database** | PostgreSQL | 15+ / 18 | Relational database with UUID and JSONB support |
| **Database Migrations** | Flyway | 10.20+ | Version-controlled schema migrations (`db/migration`) |
| **Monitoring** | Spring Boot Actuator | 3.4.3 | Health checks (`/actuator/health`) and DB indicator |
| **Frontend Framework** | Next.js | 14.2+ | React 18 with TypeScript and App Router |
| **UI & Styling** | Tailwind CSS + Lucide | 3.4+ | Utility-first CSS with Lucide icon library |
| **Package Manager** | pnpm | 9.14+ | Fast, disk space efficient package manager |
| **Infrastructure** | Docker Compose | 3.8 | Local containerized PostgreSQL |

---

## 3. Monorepo Repository Structure

```text
inboxiq/
├── backend/                               # Spring Boot 3.4+ (Java 21)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/inboxiq/
│   │   │   │   ├── InboxIqApplication.java
│   │   │   │   ├── config/               # Security & App Configuration
│   │   │   │   ├── common/               # Shared Utilities & Exceptions
│   │   │   │   ├── security/             # JWT & Authentication Filters
│   │   │   │   ├── auth/                 # User Auth & Session Domain
│   │   │   │   ├── user/                 # User Management Domain
│   │   │   │   ├── account/              # Connected Accounts (Gmail OAuth)
│   │   │   │   ├── email/                # Email Storage & Sync Domain
│   │   │   │   ├── category/             # Categories & Preferences Domain
│   │   │   │   ├── classification/       # Email Classification Domain
│   │   │   │   ├── matching/             # User Matching Engine Domain
│   │   │   │   ├── notification/         # Notification & SMS Domain
│   │   │   │   ├── processing/           # Background Job Queue Domain
│   │   │   │   └── ai/                   # Spring AI & Gemini Integration
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-dev.yml
│   │   │       └── db/migration/
│   │   │           └── V1__init_schema.sql
│   │   └── test/
│   │       └── java/com/inboxiq/
│   │           └── InboxIqApplicationTests.java
│   ├── pom.xml
│   └── mvnw.cmd
│
├── frontend/                              # Next.js 14+ (TypeScript, Tailwind)
│   ├── src/
│   │   ├── app/
│   │   │   ├── globals.css
│   │   │   ├── layout.tsx
│   │   │   └── page.tsx
│   │   ├── components/                   # Reusable UI Components
│   │   ├── lib/                          # Utility Functions (`utils.ts`)
│   │   ├── services/                     # API Client Services
│   │   ├── hooks/                        # Custom React Hooks
│   │   └── types/                        # TypeScript Interfaces
│   ├── package.json
│   ├── tsconfig.json
│   ├── tailwind.config.ts
│   └── postcss.config.mjs
│
├── infrastructure/                        # Database Infrastructure
│   ├── docker-compose.yml                # Docker PostgreSQL Service
│   └── postgres_data/                    # Local persistent storage
│
├── docs/                                 # Architectural Specifications
│   ├── HLD-v2.md
│   ├── LLD-v2.md
│   ├── DATABASE-v2.md
│   ├── SECURITY-v2.md
│   ├── API-v2.md
│   ├── GMAIL-INTEGRATION-v2.md
│   ├── AI-PIPELINE-v2.md
│   ├── BACKGROUND-PROCESSING-v2.md
│   ├── IMPLEMENTATION-ROADMAP-v2.md
│   └── diagrams/                         # Mermaid Architecture Diagrams
│
├── .env.example                          # Environment Variables Template
├── .gitignore
└── README.md
```

---

## 4. Prerequisites

- **Java Development Kit (JDK):** Version 21 (LTS)
- **Node.js:** Version 18+ or 20+ (Verified on v22.13.1)
- **Package Manager:** `pnpm` (v9.14+) or `npm` (v10.9+)
- **Database:** Docker & Docker Compose OR Local PostgreSQL 15+ / 18

---

## 5. Local Setup & Execution Guide

### 5.1 Environment Configuration
Copy the template configuration file:
```bash
cp .env.example .env
```
*(On Windows PowerShell: `Copy-Item .env.example .env`)*

---

### 5.2 Starting PostgreSQL Database

#### Option A: Using Docker Compose (Recommended if Docker is installed)
```bash
docker-compose -f infrastructure/docker-compose.yml up -d
```
Verify the container is healthy:
```bash
docker-compose -f infrastructure/docker-compose.yml ps
```

#### Option B: Using Local PostgreSQL (Windows)
If running PostgreSQL natively on Windows without Docker:
```powershell
& "C:\Program Files\PostgreSQL\18\bin\postgres.exe" -D "c:\Users\sanja\Desktop\InboxIQ\infrastructure\postgres_data" -p 5433
```
Create the database:
```powershell
& "C:\Program Files\PostgreSQL\18\bin\createdb.exe" -h 127.0.0.1 -p 5433 -U inboxiq_user inboxiq
```

---

### 5.3 Running the Backend (Spring Boot 3.4+)

Navigate to the `backend/` directory:
```bash
cd backend
```
Compile and verify tests:
```bash
.\mvnw.cmd test
```
Start the Spring Boot server:
```bash
.\mvnw.cmd spring-boot:run
```
The backend starts on port **8081** (to avoid conflicts with standard local services like Jenkins).

#### Verify Backend & Database Health
Open your browser or run:
```bash
curl http://localhost:8081/actuator/health
```
Expected response:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

---

### 5.4 Running the Frontend (Next.js 14+)

Navigate to the `frontend/` directory:
```bash
cd frontend
```
Install dependencies (if not already installed):
```bash
pnpm install
```
Build the production bundle:
```bash
pnpm run build
```
Start the development server:
```bash
pnpm run dev
```
Open [http://localhost:3000](http://localhost:3000) in your browser.

---

## 6. Phase 1 Verification Results

| Validation Step | Test Command | Result | Details |
|---|---|---|---|
| **1. Java 21 Toolchain** | `java -version` | **PASS** | Java 21.0.12 LTS verified |
| **2. Maven Backend Build** | `mvnw.cmd compile` | **PASS** | Java 21 compiled with 0 errors |
| **3. Database Cluster** | `initdb.exe` + `postgres.exe` | **PASS** | PostgreSQL 18 cluster initialized |
| **4. Database Connection** | `psql -h 127.0.0.1 -p 5433` | **PASS** | `inboxiq` database active & accessible |
| **5. Flyway Migrations** | `mvnw.cmd test` | **PASS** | `V1__init_schema.sql` applied successfully |
| **6. Spring Boot Context** | `mvnw.cmd test` | **PASS** | JPA EntityManager & context loaded cleanly |
| **7. Actuator Health Probe** | `GET /actuator/health` | **PASS** | `{"status": "UP", "components": {"db": "UP"}}` |
| **8. Next.js Frontend Build** | `pnpm run build` | **PASS** | Static generation & TypeScript build clean |

---

## 7. Current Status & Next Steps

> ✅ **Phase 1 (Project Foundation Setup) is COMPLETE.**
> 
> Awaiting review and approval before proceeding to **Phase 2: Database Architecture & Domain Migrations** (authoring all Flyway migrations V1–V15 and schema validation).
