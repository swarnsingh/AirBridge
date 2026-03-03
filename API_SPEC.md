# AirBridge API Specification v2.1

> **Contract between Browser (index.html) and Server (Ktor)**  
> Last updated: 2026-03-02  
> Protocol: Deterministic Fail-Fast with POST-driven resume

---

## 1. Query Parameter Contract

### 1.1 Browser → Server (URL Parameters)

| Browser Uses | Server Constant | Value | Used In |
|--------------|-----------------|-------|---------|
| `?token=` | `QueryParams.TOKEN` | `"token"` | All endpoints |
| `&id=` | `QueryParams.UPLOAD_ID` | `"id"` | upload, status, pause, resume, cancel |
| `&filename=` | `QueryParams.FILENAME` | `"filename"` | upload, status |
| `?path=` | `QueryParams.PATH` | `"path"` | status, file ops |

**⚠️ CRITICAL:** Browser MUST use `id=` (not `uploadId=`) in URLs.

### 1.2 Server → Browser (JSON Response Fields)

| Server Sends | Constant | Value | Context |
|--------------|----------|-------|---------|
| `"uploadId"` | `ResponseFields.UPLOAD_ID` | `"uploadId"` | All upload responses |
| `"bytesReceived"` | `ResponseFields.BYTES_RECEIVED` | `"bytesReceived"` | Progress/status |
| `"success"` | `ResponseFields.SUCCESS` | `"success"` | Boolean result |

**⚠️ CRITICAL:** Server sends `uploadId` in JSON, but expects `id` in URLs.

---

## 2. State Machine Specification

### 2.1 Valid State Transitions

```
NONE ──┬──► QUEUED ──┬──► UPLOADING ──┬──► COMPLETED
       │              │                 │
       │              │                 ├──► PAUSING ──► PAUSED ──┬──► RESUMING ──► UPLOADING (resume cycle)
       │              │                 │                        │
       │              │                 ├──► CANCELLED            ├──► CANCELLED
       │              │                 │
       │              │                 └──► ERROR
       │              │
       │              └──► CANCELLED
       │
       └──► UPLOADING (direct start - initial upload)
       │
       └──► CANCELLED
```

### 2.2 State Definitions

| State | Value | Meaning | Terminal? |
|-------|-------|---------|-----------|
| `NONE` | `"none"` | Upload initialized, no data | No |
| `QUEUED` | `"queued"` | Waiting for browser POST | No |
| `UPLOADING` | `"uploading"` | Actively receiving bytes | No |
| `PAUSING` | `"pausing"` | Pause requested, finishing chunk | No |
| `PAUSED` | `"paused"` | Suspended, can resume | No |
| `RESUMING` | `"resuming"` | Resume handshake (5s deadline) | No |
| `COMPLETED` | `"completed"` | All bytes received | ✅ Yes |
| `CANCELLED` | `"cancelled"` | User cancelled, file deleted | ✅ Yes |
| `ERROR` | `"error"` | Permanent error | ✅ Yes |

### 2.3 State Transition Rules (Server-Side)

```kotlin
NONE → setOf(QUEUED, UPLOADING, CANCELLED)
QUEUED → setOf(RESUMING, UPLOADING, CANCELLED)
RESUMING → setOf(UPLOADING, PAUSED, CANCELLED)  // PAUSED on deadline timeout
UPLOADING → setOf(PAUSING, PAUSED, COMPLETED, CANCELLED, ERROR)
PAUSING → setOf(PAUSED, CANCELLED, ERROR)
PAUSED → setOf(RESUMING, CANCELLED)
COMPLETED, CANCELLED, ERROR → empty (terminal)
```

---

## 3. API Endpoints

### 3.1 Upload Status Check

**Endpoint:** `GET /api/upload/status`

**Query Parameters:**
```
?token={sessionToken}
&id={uploadId}
&filename={fileName}
&path={path}
```

**Response (200 OK):**
```json
{
  "exists": true,
  "bytesReceived": 65536,
  "status": "paused",
  "canResume": true,
  "isBusy": false
}
```

**Response Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `exists` | boolean | File exists on disk |
| `bytesReceived` | number | Disk size (source of truth) |
| `status` | string | Current state value |
| `canResume` | boolean | Can resume from current offset |
| `isBusy` | boolean | File locked by another upload |

**⚠️ CRITICAL:** Browser checks `status.status` (not `status.state`).

---

### 3.2 Upload Data (POST)

**Endpoint:** `POST /api/upload`

**Query Parameters:**
```
?token={sessionToken}
&filename={fileName}
&id={uploadId}
```

**Headers:**
```
Content-Range: bytes {offset}-{total-1}/{total}  (optional for resume)
Content-Length: {chunkSize}
```

**Response by Result Type:**

#### Success (200 OK)
```json
{
  "success": true,
  "uploadId": "uuid",
  "bytesReceived": 1048576,
  "state": "completed"
}
```

#### Paused (200 OK)
```json
{
  "success": false,
  "uploadId": "uuid",
  "bytesReceived": 524288,
  "state": "paused"
}
```

#### Cancelled (200 OK)
```json
{
  "success": false,
  "uploadId": "uuid",
  "state": "cancelled"
}
```

#### Busy - Retry (409 Conflict)
```json
{
  "success": false,
  "uploadId": "uuid",
  "status": "busy",
  "retryAfterMs": 200,
  "message": "Server busy - retry with backoff"
}
```

#### Offset Mismatch (409 Conflict)
```json
{
  "success": false,
  "uploadId": "uuid",
  "bytesReceived": 1048576,
  "state": "error",
  "error": "offset_mismatch",
  "expectedOffset": 1048576
}
```

#### File Deleted (410 Gone)
```json
{
  "success": false,
  "uploadId": "uuid",
  "bytesReceived": 0,
  "state": "error",
  "error": "file_deleted",
  "message": "File was deleted externally"
}
```

**⚠️ CRITICAL:** Browser handles 409 specially - checks for `resp.error` containing "offset".

---

### 3.3 Pause Upload

**Endpoint:** `POST /api/upload/pause`

**Query Parameters:**
```
?token={sessionToken}
&id={uploadId}
```

**Server Behavior:**
1. Transition state: `UPLOADING → PAUSING → PAUSED`
2. Cancel active coroutine job
3. Browser's XHR will detect disconnect and abort

**Response (200 OK):**
```json
{
  "success": true,
  "state": "paused"
}
```

---

### 3.4 Resume Upload

**Endpoint:** `POST /api/upload/resume`

**Query Parameters:**
```
?token={sessionToken}
&id={uploadId}
```

**Server Behavior:**
1. Validate current state is `PAUSED`
2. Transition: `PAUSED → RESUMING`
3. Start 5-second deadline watcher
4. SSE broadcasts `RESUMING` state to browser

**Response (200 OK):**
```json
{
  "success": true,
  "message": "Resume requested - browser will POST from disk offset"
}
```

**⚠️ CRITICAL:** Server does NOT auto-resume. Browser MUST POST within 5s or server reverts to `PAUSED`.

---

### 3.5 Cancel Upload

**Endpoint:** `POST /api/upload/cancel`

**Query Parameters:**
```
?token={sessionToken}
&id={uploadId}
```

**Server Behavior:**
1. Cancel active job (if running)
2. Delete partial file from disk
3. Transition to `CANCELLED`
4. Remove from state tracking

**Response (200 OK):**
```json
{
  "success": true,
  "state": "cancelled"
}
```

---

### 3.6 Server-Sent Events (SSE)

**Endpoint:** `GET /api/upload/events`

**Query Parameters:**
```
?token={sessionToken}
&id={uploadId}  (optional - filter to single upload)
```

**Event Format:**
```
data: {"type":"upload","uploadId":"uuid","fileName":"file.mp4","state":"uploading","bytesReceived":1048576,"totalBytes":5242880,"progress":20}

data: {"type":"queue","isPaused":false,"active":1,"queued":0,"paused":0}

```

**Upload Event Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `type` | string | Always `"upload"` |
| `uploadId` | string | Upload identifier |
| `fileName` | string | Original file name |
| `state` | string | Current state value |
| `bytesReceived` | number | Bytes on disk |
| `totalBytes` | number | Expected total size |
| `progress` | number | Percentage (0-100) |

**⚠️ CRITICAL:** Browser MUST handle terminal states:
- `state === 'cancelled'` → Abort XHR, remove from UI
- `state === 'completed'` → Show success, refresh file list
- `state === 'resuming'` → Trigger immediate POST if not uploading

---

## 4. Browser Responsibilities

### 4.1 Upload Flow

```javascript
async function uploadFile(item) {
  // 1. Check status (get authoritative offset)
  const status = await fetchStatus(item.id);
  if (status.status === 'completed') return;
  if (status.status === 'cancelled') return;
  
  // 2. Calculate offset (disk is source of truth)
  const offset = status.bytesReceived;
  
  // 3. Slice file and POST
  const chunk = item.file.slice(offset);
  const result = await sendChunk(item, offset, chunk);
  
  // 4. Handle result
  if (result.success) {
    // Completed or continue
  } else if (result.paused) {
    // Wait for resume
  } else if (result.busy) {
    // Backoff and retry
  } else if (result.offsetMismatch) {
    // Refresh offset and retry
  }
}
```

### 4.2 SSE Event Handlers (REQUIRED)

```javascript
// MUST implement these handlers:

if (data.state === 'cancelled') {
  item.xhr.abort();
  uploadQueue.items.delete(data.uploadId);
  updateUI();
}

if (data.state === 'paused') {
  if (!item.isPaused) {
    uploadQueue.pause(data.uploadId);
  }
}

if (data.state === 'resuming') {
  if (!item.xhr) {
    uploadQueue.resume(data.uploadId); // Triggers POST
  }
}

if (data.state === 'completed') {
  item.xhr.abort();
  loadFiles(); // Refresh file list
  showToast('Upload complete');
}
```

### 4.3 Pause/Resume Button Handlers

```javascript
// Pause button
async function pauseUpload(id) {
  await fetch(`/api/upload/pause?token=${token}&id=${id}`, {method: 'POST'});
  // Server will set PAUSING → PAUSED
  // SSE will notify browser to abort XHR
}

// Resume button  
async function resumeUpload(id) {
  await fetch(`/api/upload/resume?token=${token}&id=${id}`, {method: 'POST'});
  // Server sets RESUMING with 5s deadline
  // Browser SSE handler will trigger POST automatically
}

// Cancel button
async function cancelUpload(id) {
  await fetch(`/api/upload/cancel?token=${token}&id=${id}`, {method: 'POST'});
  uploadQueue.cancel(id); // Abort XHR locally
}
```

---

## 5. Server Guarantees

### 5.1 Deterministic Behaviors

| Scenario | Server Behavior | Browser Expectation |
|----------|-----------------|---------------------|
| **First POST** | `NONE → UPLOADING` | Start from offset 0 |
| **Resume POST** | `RESUMING → UPLOADING` | Start from `bytesReceived` |
| **Pause Request** | `UPLOADING → PAUSING → PAUSED` | Abort XHR on SSE |
| **Resume Request** | `PAUSED → RESUMING` (5s deadline) | POST before deadline |
| **Deadline Expired** | `RESUMING → PAUSED` | Show paused UI |
| **Cancel Request** | Cancel job + delete file + `CANCELLED` | Remove from UI |
| **Server Busy** | Return 409 + `retryAfterMs` | Backoff and retry |
| **Offset Mismatch** | Return 409 + `expectedOffset` | Use server's offset |

### 5.2 Disk as Source of Truth

Server validates every POST:
```kotlin
if (request.offset != diskSize) {
    return OffsetMismatch(expectedOffset = diskSize)
}
```

Browser MUST use `status.bytesReceived` from `/api/upload/status` as the authoritative offset.

### 5.3 Fail-Fast Concurrency

- Server never blocks waiting for resources
- `tryLock()` and `tryAcquire()` return immediately
- Browser responsible for retry with exponential backoff

---

## 6. Testing Checksum

Use this to verify browser/server alignment:

```bash
# Server params
grep "const val" core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/QueryParams.kt

# Browser params  
grep -oE '\?\w+=|\&\w+=' web/src/main/assets/index.html | sort | uniq -c

# Should show: token, id, filename (matching QueryParams values)
```

### Verification Commands

```bash
# Check all browser API calls use 'id=' (not 'uploadId=')
rg '\?uploadId=' web/src/main/assets/index.html
# Should return: NO MATCHES

# Check server expects 'id=' (QueryParams.UPLOAD_ID)
rg 'UPLOAD_ID.*=' core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/QueryParams.kt
# Should show: const val UPLOAD_ID = "id"

# Check status endpoint sends 'status' (not 'state')
rg 'put\("status"' core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/routes/UploadRoutes.kt
# Should show: put("status", status.state)
```

---

## 7. Common Bugs & Fixes

### Bug: Browser sends `uploadId` instead of `id`
**Fix:** Browser must use `?id=${item.id}` in all upload endpoints.

### Bug: Server sends `state` instead of `status` in status endpoint
**Fix:** Server must use `put("status", status.state)` not `put("state", ...)`.

### Bug: Browser checks `response.state` for status endpoint
**Fix:** Browser must check `status.status` (status endpoint uses "status" field).

### Bug: Pause doesn't stop browser progress bar
**Cause:** HTTP connection stays open after server pause.
**Fix:** Browser SSE handler must call `xhr.abort()` on `state === 'paused'`.

### Bug: Resume doesn't trigger browser POST
**Cause:** Browser not watching SSE for `RESUMING` state.
**Fix:** Browser must implement SSE handler for `resuming` that triggers POST.

### Bug: Cancel doesn't delete file
**Cause:** Server uses `path`/`filename` from request instead of metadata.
**Fix:** Server looks up file info from `scheduler.activeUploads[uploadId].metadata`.

---

## 8. Version History

| Version | Date | Changes |
|---------|------|---------|
| v2.1 | 2026-03-02 | Fixed `status` field name in status endpoint, added cancel file deletion, deterministic resume deadline |
| v2.0 | 2026-02-28 | Initial fail-fast protocol with POST-driven resume |

---

**Maintainers:** Keep this document in sync with code changes. Mismatches between this spec and implementation are bugs.
