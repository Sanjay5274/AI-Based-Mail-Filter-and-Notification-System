# InboxIQ – REST API Specification (API-v2.md)
**Version 2.0 – Production REST Contracts & Ownership Validation Rules**

---

## 1. Global API Standards

- **Base URL:** `https://api.inboxiq.com/api/v1` (Local: `http://localhost:8080/api/v1`)
- **Content-Type:** `application/json; charset=utf-8`
- **Authentication:** Bearer JWT in `accessToken` HttpOnly cookie or `Authorization: Bearer <token>` header.
- **CSRF Protection:** State-changing requests (`POST`, `PUT`, `DELETE`, `PATCH`) require `X-CSRF-Token` header.
- **Ownership Verification:** Any request with a path variable (e.g., `{accountId}`, `{emailId}`, `{categoryId}`) verifies that the underlying resource belongs to `SecurityContext.getCurrentUser()`. If not owned, the server returns `404 Not Found` (to prevent ID enumeration) or `403 Forbidden`.
- **Standard Error Envelope:**
  ```json
  {
    "status": 400,
    "error": "VALIDATION_FAILED",
    "message": "Invalid request parameters",
    "timestamp": "2026-11-14T00:30:00Z",
    "path": "/api/v1/categories/custom",
    "details": {
      "name": "Category name must not be blank"
    }
  }
  ```

---

## 2. Authentication API (`/api/v1/auth`)

### 2.1 Register New User
- **Method / Path:** `POST /api/v1/auth/register`
- **Auth Required:** No
- **Request Body:**
  ```json
  {
    "email": "user@example.com",
    "password": "SecurePassword123!",
    "fullName": "John Developer",
    "phoneNumber": "+919876543210"
  }
  ```
- **Responses:**
  - `201 Created`: User successfully created.
  - `400 Bad Request`: Field validation failure.
  - `409 Conflict`: Email already registered.

### 2.2 User Login
- **Method / Path:** `POST /api/v1/auth/login`
- **Auth Required:** No
- **Request Body:**
  ```json
  {
    "email": "user@example.com",
    "password": "SecurePassword123!"
  }
  ```
- **Responses:**
  - `200 OK`: Sets `accessToken` (15m) and `refreshToken` (7d) HttpOnly cookies.
  - `401 Unauthorized`: Invalid email or password.

### 2.3 Rotate Refresh Token
- **Method / Path:** `POST /api/v1/auth/refresh`
- **Auth Required:** No (Requires `refreshToken` cookie)
- **Responses:**
  - `200 OK`: New access token and rotated refresh token issued.
  - `401 Unauthorized`: Token expired, invalid, or reuse detected (all family sessions revoked).

### 2.4 User Logout
- **Method / Path:** `POST /api/v1/auth/logout`
- **Auth Required:** Yes
- **Responses:**
  - `200 OK`: Revokes active refresh session and clears auth cookies.

### 2.5 Current User Profile
- **Method / Path:** `GET /api/v1/auth/me`
- **Auth Required:** Yes
- **Response:** `200 OK`
  ```json
  {
    "status": 200,
    "data": {
      "id": "e72bbf6b-675e-49b9-8e47-e17f0a716bc5",
      "email": "user@example.com",
      "fullName": "John Developer",
      "phoneNumber": "+919876543210",
      "phoneVerified": true,
      "emailVerified": true
    }
  }
  ```

---

## 3. Connected Accounts API (`/api/v1/accounts`)

### 3.1 List Connected Gmail Accounts
- **Method / Path:** `GET /api/v1/accounts`
- **Auth Required:** Yes
- **Response:** `200 OK` (Array of connected accounts owned by the authenticated user)

### 3.2 Initiate Google OAuth Connection
- **Method / Path:** `POST /api/v1/accounts/google/connect`
- **Auth Required:** Yes
- **Response:** `200 OK`
  ```json
  {
    "status": 200,
    "data": {
      "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?client_id=...&redirect_uri=...&response_type=code&scope=https://www.googleapis.com/auth/gmail.readonly&state=SECURE_RANDOM_STATE"
    }
  }
  ```

### 3.3 Google OAuth Callback
- **Method / Path:** `GET /api/v1/accounts/google/callback?code={code}&state={state}`
- **Auth Required:** Yes
- **Behavior:** Validates state, exchanges code for refresh token, encrypts via AES-256-GCM with AAD, saves account, enqueues initial sync job.
- **Response:** `302 Redirect` to frontend `/settings?accountConnected=true`.

### 3.4 Disconnect Account & Revoke Token
- **Method / Path:** `DELETE /api/v1/accounts/{accountId}`
- **Auth Required:** Yes (Ownership verified)
- **Response:** `200 OK` (Revokes Google authorization and deletes stored credentials).

### 3.5 Trigger Manual Sync
- **Method / Path:** `POST /api/v1/accounts/{accountId}/sync`
- **Auth Required:** Yes (Ownership verified)
- **Response:** `202 Accepted` (Enqueues `SYNC_ACCOUNT` background job).

---

## 4. Smart Inbox & Emails API (`/api/v1/emails`)

### 4.1 Search & List Emails
- **Method / Path:** `GET /api/v1/emails`
- **Auth Required:** Yes
- **Query Parameters:**
  - `page`: Page index (default: `0`)
  - `size`: Page size (default: `20`, max: `100`)
  - `categoryId`: Filter by matched category UUID
  - `minScore`: Filter by minimum relevance score (0–100)
  - `priority`: Filter by priority (`HIGH`, `MEDIUM`, `LOW`)
  - `search`: Full-text search term in subject/sender
- **Response:** `200 OK` (Spring Data Page envelope with email metadata, AI summaries, and match badges).

### 4.2 Get Email Details
- **Method / Path:** `GET /api/v1/emails/{emailId}`
- **Auth Required:** Yes (Ownership verified)
- **Response:** `200 OK` (Full email detail including sanitized content, attachments, and matching reasons).

---

## 5. Categories & Preferences API (`/api/v1/categories`)

### 5.1 List All Accessible Categories
- **Method / Path:** `GET /api/v1/categories`
- **Auth Required:** Yes
- **Response:** `200 OK` (Returns 11 system categories + private custom categories of caller).

### 5.2 Create Custom Category
- **Method / Path:** `POST /api/v1/categories/custom`
- **Auth Required:** Yes
- **Request Body:**
  ```json
  {
    "name": "Startup Accelerator Offers",
    "description": "Emails regarding startup incubators, Y Combinator, Techstars, grants, and demo days.",
    "icon": "Rocket",
    "color": "amber"
  }
  ```
- **Response:** `201 Created`

### 5.3 Update Custom Category
- **Method / Path:** `PUT /api/v1/categories/custom/{categoryId}`
- **Auth Required:** Yes (Must be owner of custom category)
- **Response:** `200 OK`

### 5.4 Delete Custom Category
- **Method / Path:** `DELETE /api/v1/categories/custom/{categoryId}`
- **Auth Required:** Yes (Must be owner)
- **Behavior:** Soft-deletes or deactivates custom category, preserving historical notification audit logs.
- **Response:** `200 OK`

### 5.5 Update Category Preference Rules
- **Method / Path:** `PUT /api/v1/categories/{categoryId}/preferences`
- **Auth Required:** Yes
- **Request Body:**
  ```json
  {
    "isEnabled": true,
    "minRelevanceScore": 70,
    "priority": "HIGH",
    "notifyViaSms": true,
    "keywordsInclude": ["interview", "offer", "shortlisted"],
    "keywordsExclude": ["rejected", "status update"],
    "preferencesData": {
      "jobRoles": ["Java Developer", "Backend Engineer"],
      "locations": ["Bangalore", "Remote"]
    }
  }
  ```
- **Response:** `200 OK`

---

## 6. Notifications API (`/api/v1/notifications`)

### 6.1 List Notifications
- **Method / Path:** `GET /api/v1/notifications?page=0&size=20`
- **Auth Required:** Yes
- **Response:** `200 OK` (Notification events, matched categories, delivery status).

### 6.2 Mark Notification as Read
- **Method / Path:** `PATCH /api/v1/notifications/{notificationId}/read`
- **Auth Required:** Yes (Ownership verified)
- **Response:** `200 OK`

### 6.3 Update Notification Channel Preferences
- **Method / Path:** `PUT /api/v1/notifications/preferences`
- **Auth Required:** Yes
- **Request Body:**
  ```json
  {
    "smsEnabled": true,
    "smsNumber": "+919876543210",
    "globalMinRelevanceScore": 75,
    "quietHoursEnabled": true,
    "quietHoursStart": "22:00:00",
    "quietHoursEnd": "08:00:00"
  }
  ```
- **Response:** `200 OK`

---

## 7. Webhooks & Internal Endpoints

### 7.1 SMS Provider Status Webhook
- **Method / Path:** `POST /api/v1/webhooks/sms/status-callback`
- **Auth Required:** No (Provider signature verified via `X-Twilio-Signature`)
- **Behavior:** Updates `notification_deliveries` status (`DELIVERED`, `FAILED`).
- **Response:** `200 OK`

### 7.2 Internal Cloud Scheduler Heartbeat
- **Method / Path:** `POST /api/v1/internal/jobs/heartbeat`
- **Auth Required:** Yes (`X-Internal-Secret` header validation)
- **Behavior:** Wakes background job queue in scale-to-zero environments to drain pending jobs.
- **Response:** `200 OK`

