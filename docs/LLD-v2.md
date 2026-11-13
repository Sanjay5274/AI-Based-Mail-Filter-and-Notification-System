# InboxIQ – Low-Level Design (LLD) v2.0
**Modular Monolith Implementation & Service Architecture**

---

## 1. Backend Package Structure (`package-by-feature`)

The Spring Boot backend adopts a strict package-by-feature modular monolith structure. Dependencies flow inward toward domain entities and cross-cutting abstractions.

```text
backend/src/main/java/com/inboxiq/
├── InboxIqApplication.java
│
├── config/
│   ├── SecurityConfig.java
│   ├── CorsConfig.java
│   ├── GmailConfig.java
│   ├── AiConfig.java
│   ├── AsyncConfig.java
│   └── EncryptionConfig.java
│
├── common/
│   ├── entity/BaseAuditableEntity.java
│   ├── dto/ApiResponse.java
│   ├── dto/PageResponse.java
│   ├── exception/InboxIqException.java
│   ├── exception/ResourceNotFoundException.java
│   ├── exception/UnauthorizedException.java
│   ├── exception/ValidationException.java
│   ├── exception/GlobalExceptionHandler.java
│   └── util/SanitizationUtil.java
│
├── security/
│   ├── JwtTokenProvider.java
│   ├── JwtAuthenticationFilter.java
│   ├── UserPrincipal.java
│   ├── CustomUserDetailsService.java
│   └── SecurityUtils.java
│
├── auth/
│   ├── controller/AuthController.java
│   ├── service/AuthService.java
│   ├── service/RefreshTokenService.java
│   ├── entity/AuthSession.java
│   ├── repository/AuthSessionRepository.java
│   ├── dto/RegisterRequest.java
│   ├── dto/LoginRequest.java
│   ├── dto/AuthResponse.java
│   └── dto/TokenRefreshResponse.java
│
├── user/
│   ├── controller/UserController.java
│   ├── service/UserService.java
│   ├── entity/User.java
│   ├── repository/UserRepository.java
│   ├── dto/UserProfileResponse.java
│   └── dto/UpdateProfileRequest.java
│
├── account/
│   ├── controller/AccountController.java
│   ├── service/AccountService.java
│   ├── service/GoogleOAuthService.java
│   ├── service/TokenEncryptionService.java
│   ├── entity/ConnectedAccount.java
│   ├── repository/ConnectedAccountRepository.java
│   ├── dto/AccountResponse.java
│   ├── dto/OAuthUrlResponse.java
│   └── dto/SyncStatusResponse.java
│
├── email/
│   ├── controller/EmailController.java
│   ├── service/EmailService.java
│   ├── service/GmailSyncService.java
│   ├── service/EmailSanitizationService.java
│   ├── entity/Email.java
│   ├── entity/EmailAttachment.java
│   ├── repository/EmailRepository.java
│   ├── repository/EmailAttachmentRepository.java
│   ├── dto/EmailSummaryResponse.java
│   ├── dto/EmailDetailResponse.java
│   └── dto/EmailFilterCriteria.java
│
├── processing/
│   ├── service/JobDispatcherService.java
│   ├── service/JobWorkerService.java
│   ├── service/JobRecoveryService.java
│   ├── queue/BoundedJobQueue.java
│   ├── entity/ProcessingJob.java
│   ├── entity/ProcessingAttempt.java
│   ├── repository/ProcessingJobRepository.java
│   ├── repository/ProcessingAttemptRepository.java
│   └── model/JobExecutionContext.java
│
├── ai/
│   ├── service/AiClassificationService.java
│   ├── service/EmailPreprocessingService.java
│   ├── service/PromptBuilderService.java
│   ├── model/ClassificationResult.java
│   ├── model/DetectedCategory.java
│   ├── model/ExtractedEntities.java
│   ├── validation/AiResponseValidator.java
│   └── prompt/PromptTemplates.java
│
├── classification/
│   ├── service/ClassificationService.java
│   ├── entity/EmailClassification.java
│   ├── entity/EmailClassificationCategory.java
│   ├── repository/EmailClassificationRepository.java
│   ├── repository/EmailClassificationCategoryRepository.java
│   └── dto/ClassificationDetailResponse.java
│
├── category/
│   ├── controller/CategoryController.java
│   ├── service/CategoryService.java
│   ├── entity/Category.java
│   ├── entity/UserCategoryPreference.java
│   ├── repository/CategoryRepository.java
│   ├── repository/UserCategoryPreferenceRepository.java
│   ├── dto/CategoryResponse.java
│   ├── dto/CreateCustomCategoryRequest.java
│   ├── dto/UpdateCustomCategoryRequest.java
│   └── dto/CategoryPreferenceRequest.java
│
├── matching/
│   ├── service/MatchingEngineService.java
│   ├── service/RelevanceScoringService.java
│   ├── entity/EmailCategoryMatch.java
│   ├── repository/EmailCategoryMatchRepository.java
│   └── dto/MatchResult.java
│
├── notification/
│   ├── controller/NotificationController.java
│   ├── service/NotificationService.java
│   ├── service/SmsProviderService.java
│   ├── entity/Notification.java
│   ├── entity/NotificationDelivery.java
│   ├── entity/NotificationPreference.java
│   ├── repository/NotificationRepository.java
│   ├── repository/NotificationDeliveryRepository.java
│   ├── repository/NotificationPreferenceRepository.java
│   ├── provider/SmsProvider.java
│   ├── provider/TwilioSmsProvider.java
│   ├── provider/IndianDltSmsProvider.java
│   ├── dto/NotificationResponse.java
│   └── dto/NotificationPreferenceRequest.java
│
├── analytics/
│   ├── controller/AnalyticsController.java
│   ├── service/AnalyticsService.java
│   └── dto/AnalyticsOverviewResponse.java
│
└── webhook/
    ├── controller/WebhookController.java
    └── service/WebhookVerificationService.java
```

---

## 2. Core Service Contracts & Signatures

### 2.1 `TokenEncryptionService` (AES-256-GCM + AAD)
Handles encryption and decryption of Google OAuth refresh tokens.
```java
public interface TokenEncryptionService {
    /**
     * Encrypts plaintext refresh token using AES-256-GCM with a random 96-bit IV.
     * Binds AAD (accountId + ":" + userId + ":" + keyVersion) to prevent token transplantation.
     */
    EncryptedTokenResult encrypt(String plainTextToken, UUID accountId, UUID userId);

    /**
     * Decrypts ciphertext verifying the 128-bit authentication tag and matching AAD.
     */
    String decrypt(String ciphertext, String nonceIv, String authTag, String keyVersion, UUID accountId, UUID userId);
}
```

### 2.2 `JobDispatcherService` & `JobWorkerService`
Manages the lease-based background job lifecycle outside database transactions.
```java
public interface JobDispatcherService {
    /**
     * Scheduled poller that claims eligible jobs using FOR UPDATE SKIP LOCKED
     * and updates their status to PROCESSING with a 5-minute lease.
     */
    void dispatchEligibleJobs();
}

public interface JobWorkerService {
    /**
     * Executes the task outside the dispatcher's DB transaction.
     * Manages retry backoff with jitter and records attempts in processing_attempts.
     */
    void executeJob(ProcessingJob job);

    /**
     * Heartbeat extension for long-running jobs (e.g. initial account sync).
     */
    void renewLease(UUID jobId, Duration extension);
}
```

### 2.3 `AiClassificationService` (Spring AI)
Coordinates preprocessing, prompt formulation, Gemini API invocation, and output validation.
```java
public interface AiClassificationService {
    /**
     * Preprocesses email, formats structured prompt with untrusted data delimiters,
     * calls Gemini via Spring AI ChatClient, and validates the returned JSON.
     */
    ClassificationResult classifyEmail(Email email);
}
```

### 2.4 `MatchingEngineService` & `RelevanceScoringService`
Evaluates the classified email against the owning user's enabled preferences and custom categories.
```java
public interface MatchingEngineService {
    /**
     * Stage B: Matches an email for its owning user.
     * Evaluates system category preferences and user custom categories.
     * Persists email_category_matches and triggers NotificationService if threshold met.
     */
    List<EmailCategoryMatch> matchEmailForOwner(UUID emailId, UUID userId);
}

public interface RelevanceScoringService {
    /**
     * Deterministic scoring algorithm (0-100).
     * Combines AI confidence, keyword matches (+15 per hit), exclude keywords (-100 disqualification),
     * and category-specific JSONB criteria.
     */
    int calculateScore(DetectedCategory detectedCategory, UserCategoryPreference preference, Email email);
}
```

### 2.5 `SmsProvider` Abstraction
```java
public interface SmsProvider {
    /**
     * Sends an SMS message to an E.164 phone number with an idempotency key.
     */
    SmsDispatchResult sendSms(String toPhoneNumber, String message, String idempotencyKey);

    /**
     * Verifies the authenticity of incoming provider webhooks.
     */
    boolean verifyWebhookSignature(HttpServletRequest request, String webhookPayload);
}
```

---

## 3. Detailed REST API Specifications

### 3.1 Authentication Group (`/api/v1/auth`)

#### 1. Register User
- **Endpoint:** `POST /api/v1/auth/register`
- **Auth Required:** No
- **Request Body:**
  ```json
  {
    "email": "user@example.com",
    "password": "SecurePassword123!",
    "fullName": "Jane Doe",
    "phoneNumber": "+919876543210"
  }
  ```
- **Validation:** Valid email format, password min 8 chars (1 uppercase, 1 number, 1 special), E.164 phone.
- **Response:** `201 Created`
  ```json
  {
    "status": 201,
    "message": "User registered successfully",
    "data": {
      "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
      "email": "user@example.com",
      "fullName": "Jane Doe"
    }
  }
  ```

#### 2. User Login
- **Endpoint:** `POST /api/v1/auth/login`
- **Auth Required:** No
- **Request Body:**
  ```json
  {
    "email": "user@example.com",
    "password": "SecurePassword123!"
  }
  ```
- **Response:** `200 OK` (Sets `accessToken` [15m] & `refreshToken` [7d] HttpOnly cookies)
  ```json
  {
    "status": 200,
    "message": "Authentication successful",
    "data": {
      "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
      "email": "user@example.com",
      "fullName": "Jane Doe"
    }
  }
  ```

#### 3. Rotate Refresh Token
- **Endpoint:** `POST /api/v1/auth/refresh`
- **Auth Required:** No (Requires `refreshToken` HttpOnly cookie)
- **Response:** `200 OK` (Rotates cookie with new session and returns new access token)

#### 4. Logout
- **Endpoint:** `POST /api/v1/auth/logout`
- **Auth Required:** Yes
- **Response:** `200 OK` (Revokes session in `auth_sessions` and clears cookies)

---

### 3.2 Connected Accounts Group (`/api/v1/accounts`)

#### 1. List Connected Accounts
- **Endpoint:** `GET /api/v1/accounts`
- **Auth Required:** Yes
- **Response:** `200 OK`
  ```json
  {
    "status": 200,
    "data": [
      {
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "emailAddress": "jane.dev@gmail.com",
        "displayName": "Jane Doe",
        "syncStatus": "IDLE",
        "lastSyncAt": "2026-11-14T00:15:00Z",
        "isActive": true
      }
    ]
  }
  ```

#### 2. Initiate Google OAuth Connection
- **Endpoint:** `POST /api/v1/accounts/google/connect`
- **Auth Required:** Yes
- **Response:** `200 OK`
  ```json
  {
    "status": 200,
    "data": {
      "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?client_id=...&scope=https://www.googleapis.com/auth/gmail.readonly&state=SECURE_STATE_TOKEN"
    }
  }
  ```

#### 3. Trigger Manual Account Synchronization
- **Endpoint:** `POST /api/v1/accounts/{accountId}/sync`
- **Auth Required:** Yes (Ownership verified: `account.user_id == principal.id`)
- **Response:** `202 Accepted`
  ```json
  {
    "status": 202,
    "message": "Synchronization job enqueued successfully"
  }
  ```

#### 4. Disconnect Account & Revoke Google Authorization
- **Endpoint:** `DELETE /api/v1/accounts/{accountId}`
- **Auth Required:** Yes (Ownership verified)
- **Response:** `200 OK`
  ```json
  {
    "status": 200,
    "message": "Account disconnected and Google OAuth access revoked"
  }
  ```

---

### 3.3 Smart Inbox Group (`/api/v1/emails`)

#### 1. List Emails (Paginated & Filterable)
- **Endpoint:** `GET /api/v1/emails?page=0&size=20&categoryId=...&minScore=70`
- **Auth Required:** Yes (Strictly filtered by `user_id = principal.id`)
- **Response:** `200 OK`
  ```json
  {
    "status": 200,
    "data": {
      "content": [
        {
          "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
          "senderName": "Google Careers",
          "senderEmail": "recruiting@google.com",
          "subject": "Interview Invitation: Senior Software Engineer",
          "snippet": "We were impressed with your background and would like to schedule...",
          "receivedAt": "2026-11-13T18:30:00Z",
          "aiSummary": "Google recruiter scheduling technical interview for Senior SWE role.",
          "priority": "HIGH",
          "maxRelevanceScore": 95,
          "matchedCategories": ["Career & Jobs"]
        }
      ],
      "pageNumber": 0,
      "pageSize": 20,
      "totalElements": 1,
      "totalPages": 1
    }
  }
  ```

#### 2. Get Email Detail
- **Endpoint:** `GET /api/v1/emails/{emailId}`
- **Auth Required:** Yes (Ownership verified)
- **Response:** `200 OK` (Includes sanitized HTML/text, attachments metadata, and versioned classification)

---

### 3.4 Categories & Preferences Group (`/api/v1/categories`)

#### 1. List All Accessible Categories (System + User Custom)
- **Endpoint:** `GET /api/v1/categories`
- **Auth Required:** Yes
- **Response:** `200 OK` (Returns the 11 built-in categories + any custom categories owned by `principal.id`)

#### 2. Create User Custom Category
- **Endpoint:** `POST /api/v1/categories/custom`
- **Auth Required:** Yes
- **Request Body:**
  ```json
  {
    "name": "Angel Investment Pitches",
    "description": "Emails from startup founders seeking pre-seed or seed funding, pitch decks, and investor meetings.",
    "icon": "TrendingUp",
    "color": "emerald"
  }
  ```
- **Response:** `201 Created`

#### 3. Update User Category Preferences
- **Endpoint:** `PUT /api/v1/categories/{categoryId}/preferences`
- **Auth Required:** Yes
- **Request Body:**
  ```json
  {
    "isEnabled": true,
    "minRelevanceScore": 75,
    "priority": "HIGH",
    "notifyViaSms": true,
    "keywordsInclude": ["interview", "offer", "shortlisted"],
    "keywordsExclude": ["rejection", "unsuccessful"],
    "preferencesData": {
      "jobRoles": ["Senior Java Engineer", "Backend Architect"],
      "locations": ["Bangalore", "Remote"],
      "skills": ["Java 21", "Spring Boot", "PostgreSQL"]
    }
  }
  ```
- **Response:** `200 OK`

---

### 3.5 Notifications Group (`/api/v1/notifications`)

#### 1. List Notifications
- **Endpoint:** `GET /api/v1/notifications?page=0&size=20`
- **Auth Required:** Yes (Filtered by `user_id = principal.id`)
- **Response:** `200 OK` (Returns notification events, matched categories, delivery status)

#### 2. Update Notification Preferences
- **Endpoint:** `PUT /api/v1/notifications/preferences`
- **Auth Required:** Yes
- **Request Body:**
  ```json
  {
    "smsEnabled": true,
    "smsNumber": "+919876543210",
    "globalMinRelevanceScore": 70,
    "quietHoursEnabled": true,
    "quietHoursStart": "22:00:00",
    "quietHoursEnd": "08:00:00"
  }
  ```
- **Response:** `200 OK`

---

## 4. AI Structured Output JSON Schema

When invoking Gemini via Spring AI, the model must return strictly structured JSON matching this schema:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "EmailClassificationResult",
  "type": "object",
  "properties": {
    "categories": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "name": {
            "type": "string",
            "enum": [
              "Career & Jobs",
              "Creator & Brand Promotions",
              "Sports",
              "Education",
              "Finance & Banking",
              "Shopping & Offers",
              "Events & Invitations",
              "News & Updates",
              "Technology & Product Updates",
              "Travel & Bookings",
              "Personal & Important Communications"
            ]
          },
          "confidence": {
            "type": "number",
            "minimum": 0.0,
            "maximum": 1.0
          }
        },
        "required": ["name", "confidence"]
      }
    },
    "summary": {
      "type": "string",
      "maxLength": 200
    },
    "priority": {
      "type": "string",
      "enum": ["HIGH", "MEDIUM", "LOW"]
    },
    "isSpam": {
      "type": "boolean"
    },
    "extractedData": {
      "type": "object",
      "additionalProperties": {
        "type": "string"
      }
    }
  },
  "required": ["categories", "summary", "priority", "isSpam", "extractedData"]
}
```

