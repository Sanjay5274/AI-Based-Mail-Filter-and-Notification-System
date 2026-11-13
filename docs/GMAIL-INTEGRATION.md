# InboxIQ – Gmail Integration

## 1. Overview

InboxIQ integrates with Gmail via Google OAuth 2.0. The integration is delivered in two phases:

| Phase | Approach | When |
|---|---|---|
| **Phase 1 (MVP)** | OAuth connection + manual & scheduled polling sync | Implemented in Phase 3 |
| **Phase 2 (Advanced)** | Gmail Push Notifications via Google Cloud Pub/Sub | Implemented in Phase 9 |

> [!IMPORTANT]
> Phase 2 (Push Notifications) must **NOT** be implemented during Phase 1. Build the polling approach first, validate it works end-to-end, then layer push on top.

---

## 2. OAuth 2.0 Connection Flow

The Gmail connection flow is separate from the InboxIQ login flow. The user must already be authenticated to InboxIQ before connecting Gmail.

```
Step 1: User clicks "Connect Gmail" in Settings
         ↓
Step 2: Backend generates Google OAuth URL
         scope=https://www.googleapis.com/auth/gmail.readonly
         access_type=offline (to get refresh_token)
         prompt=consent (force consent screen to always get refresh_token)
         state={signed_jwt_with_userId}  ← CSRF protection
         ↓
Step 3: User redirected to Google consent screen
         ↓
Step 4: Google redirects to GOOGLE_REDIRECT_URI with ?code=...&state=...
         ↓
Step 5: Backend validates state, exchanges code for tokens
         POST https://oauth2.googleapis.com/token
         → access_token, refresh_token, expiry
         ↓
Step 6: refresh_token encrypted with AES-256-GCM
         Stored in connected_accounts table
         access_token: not stored long-term (fetched as needed)
         ↓
Step 7: Account marked status=ACTIVE
         Initial full Gmail sync triggered (async via processing job)
```

### Key Configuration
```yaml
google:
  client-id: ${GOOGLE_CLIENT_ID}
  client-secret: ${GOOGLE_CLIENT_SECRET}
  redirect-uri: ${GOOGLE_REDIRECT_URI}  # e.g. http://localhost:8080/api/v1/oauth/callback
```

> [!CAUTION]
> Always validate the `state` parameter in the callback to prevent CSRF attacks. The state should be a signed JWT containing the InboxIQ `userId` and a nonce.

---

## 3. Gmail Sync Strategy (Phase 1)

### Two Sync Modes

#### Full Sync (First Connection)
Triggered once when a Gmail account is first connected.

```
1. Call Gmail API: GET /gmail/v1/users/me/messages?maxResults=100&q=...
2. For each message ID: GET /gmail/v1/users/me/messages/{id}
3. Extract metadata and body (see §5)
4. Store in emails table (with dedup — see §4)
5. Create processing job for AI classification
6. Store historyId from latest message → connected_accounts.last_history_id
```

**Fetches the last 100 emails** to populate the initial inbox view. Additional history is not backfilled (MVP scope).

#### Incremental Sync (Scheduled)
Triggered by the scheduler (see §6) after the initial full sync.

```
1. Read connected_accounts.last_history_id
2. Call Gmail History API:
   GET /gmail/v1/users/me/history?startHistoryId={last_history_id}&historyTypes=messageAdded
3. Extract messagesAdded from history response
4. For each new message ID: GET /gmail/v1/users/me/messages/{id}
5. Store new emails (dedup applies)
6. Create processing jobs for new emails
7. Update connected_accounts.last_history_id to latest historyId from response
```

> [!NOTE]
> If `startHistoryId` is too old (Google purges history after ~7 days), Gmail returns a `404`. Handle this by falling back to a full sync and resetting `last_history_id`.

---

## 4. Deduplication Strategy

**Database constraint:**
```sql
UNIQUE (connected_account_id, gmail_message_id)
```

**Insert strategy:**
```sql
INSERT INTO emails (connected_account_id, gmail_message_id, ...)
VALUES (...)
ON CONFLICT (connected_account_id, gmail_message_id) DO NOTHING;
```

This guarantees that even if the scheduler runs twice in quick succession (race condition), or a full sync overlaps an incremental sync, **no email is processed twice**.

The `ON CONFLICT DO NOTHING` approach is chosen over `ON CONFLICT DO UPDATE` because:
- Once an email is classified, re-syncing the same metadata offers no value
- It prevents triggering duplicate processing jobs

---

## 5. Email Data Extraction

From each Gmail API message object, extract the following fields:

| InboxIQ Field | Gmail API Source | Notes |
|---|---|---|
| `gmail_message_id` | `message.id` | Primary dedup key |
| `gmail_thread_id` | `message.threadId` | Thread grouping |
| `subject` | `headers["Subject"]` | From `message.payload.headers` |
| `sender_email` | `headers["From"]` | Parse email from `"Name <email>"` |
| `sender_name` | `headers["From"]` | Display name component |
| `recipient_email` | `headers["To"]` | First recipient |
| `received_at` | `message.internalDate` | Unix milliseconds → timestamp |
| `body_text` | `payload` | Prefer `text/plain`; fall back to strip-HTML from `text/html` |
| `snippet` | `message.snippet` | First 200 chars preview |
| `gmail_labels` | `message.labelIds` | Array stored as JSONB |

### Body Extraction Priority
```
1. Look for text/plain MIME part → use directly
2. If not found: look for text/html MIME part → strip HTML tags → use as body_text
3. If multipart: recurse into parts array to find above
4. If truly empty: store empty string, classify from subject + snippet only
```

### Attachment Handling
Store **metadata only** — never download attachment content:
```json
{
  "attachments": [
    {
      "filename": "resume.pdf",
      "mimeType": "application/pdf",
      "size": 204800,
      "attachmentId": "ANGjdJ..."
    }
  ]
}
```
Stored as JSONB in `emails.attachments_metadata`. InboxIQ does not store or process attachment content.

---

## 6. Scheduled Sync

```java
@Scheduled(fixedDelayString = "${gmail.sync.interval-ms:900000}") // 15 minutes default
public void runIncrementalSync() {
    List<ConnectedAccount> accounts = connectedAccountRepository
        .findAllByStatus(AccountStatus.ACTIVE);
    
    for (ConnectedAccount account : accounts) {
        syncExecutor.submitSyncJob(account.getId());
    }
}
```

### Per-Account Sync Process
```
1. Load account with DB lock (prevents parallel sync of same account):
   SELECT ... FOR UPDATE SKIP LOCKED
   (skip if another node/thread is already syncing this account)

2. Set sync_status = SYNCING, last_sync_at = NOW()

3. Decrypt refresh_token → fetch new access_token from Google

4. Run incremental sync (see §3)

5. On success: sync_status = IDLE, last_history_id updated

6. On failure: sync_status = FAILED, last_error = exception message
   → Will be retried next scheduled cycle
```

### Configuration
```yaml
gmail:
  sync:
    interval-ms: ${GMAIL_SYNC_INTERVAL_MS:900000}  # 15 minutes
    max-results-per-sync: ${GMAIL_MAX_RESULTS:100}
```

---

## 7. Phase 2 – Gmail Push Notifications (Pub/Sub)

> [!WARNING]
> **Do NOT implement in Phase 1.** This section is for reference when implementing Phase 9.

### Why Push?
Polling every 15 minutes means up to 15-minute delay between email arrival and notification. Push reduces this to seconds.

### Setup Steps

#### 1. Create Pub/Sub Infrastructure
```bash
# Create topic
gcloud pubsub topics create inboxiq-gmail-push

# Grant Gmail permission to publish
gcloud pubsub topics add-iam-policy-binding inboxiq-gmail-push \
  --member="serviceAccount:gmail-api-push@system.gserviceaccount.com" \
  --role="roles/pubsub.publisher"

# Create push subscription pointing to our endpoint
gcloud pubsub subscriptions create inboxiq-gmail-sub \
  --topic=inboxiq-gmail-push \
  --push-endpoint=https://api.inboxiq.com/api/v1/webhooks/gmail/push \
  --ack-deadline=30
```

#### 2. Register Gmail Watch per Account
```
POST https://gmail.googleapis.com/gmail/v1/users/me/watch
{
  "topicName": "projects/{project-id}/topics/inboxiq-gmail-push",
  "labelIds": ["INBOX"]
}
```
Response includes `historyId` and `expiration` (Unix ms). Watch expires after **~7 days**.

#### 3. Push Endpoint
```
POST /api/v1/webhooks/gmail/push
Authorization: Google-signed JWT (verify signature with Google's public keys)
```

Payload:
```json
{
  "message": {
    "data": "<base64-encoded JSON>",
    "messageId": "...",
    "publishTime": "..."
  },
  "subscription": "projects/.../subscriptions/inboxiq-gmail-sub"
}
```

Decoded `data`:
```json
{
  "emailAddress": "user@gmail.com",
  "historyId": "12345"
}
```

Handler:
1. Verify Google JWT signature
2. Look up `ConnectedAccount` by `emailAddress`
3. Run incremental sync from `last_history_id` using received `historyId`

#### 4. Watch Renewal
Gmail watch expires after 7 days. A scheduled job renews it daily:
```java
@Scheduled(cron = "0 0 2 * * *")  // 2 AM daily
public void renewGmailWatches() {
    // For each active account with watch expiring within 24 hours
    // Call watch API again (renewing resets expiration)
}
```

#### 5. Fallback
Scheduled polling (Phase 1 implementation) **remains active** as a fallback. If a push notification is missed, the next 15-minute poll catches any gaps.

---

## 8. Error Handling

| Error Condition | Response | Recovery |
|---|---|---|
| **OAuth token expired** (`401` from Gmail) | Catch 401, use `refresh_token` to get new `access_token` | Transparent retry |
| **Refresh token invalid/revoked** | Catch token exchange error | Mark account `status=INACTIVE`, send user notification to reconnect |
| **Gmail API rate limit** (`429`) | Exponential backoff: 1s → 2s → 4s → 8s | Retry up to 5 times before marking sync as FAILED |
| **History ID too old** (`404` on History API) | Fall back to full sync | Reset `last_history_id` after full sync |
| **Network timeout** | Log error | Retry next scheduled cycle; `sync_status=FAILED` |
| **Gmail service unavailable** (`503`) | Log and skip | Retry next cycle |
| **Invalid message format** | Log message ID, skip | Continue with next message |

### Sync Status Values
```
IDLE       → Account connected, no sync in progress
SYNCING    → Sync currently running
FAILED     → Last sync failed (will retry next cycle)
INACTIVE   → Refresh token invalid; user must reconnect
```
