# AirBridge Status Audit Report

**Date:** 2026-03-02  
**Purpose:** Comprehensive trace of all state/status handling between Browser and Server

---

## 1. State vs Status Terminology

### Server States (UploadState enum)
- `NONE`, `QUEUED`, `UPLOADING`, `PAUSING`, `PAUSED`, `RESUMING`, `COMPLETED`, `CANCELLED`, `ERROR`
- Stored in: `UploadStateManager.states` (ConcurrentHashMap)
- Accessed via: `status.state` (UploadStatus object)

### JSON Response Fields
| Server Sends | Field Name | Example Value |
|--------------|------------|---------------|
| UploadStatus.state | `"state"` in SSE | `"paused"` |
| UploadStatus.state | `"status"` in /api/upload/status | `"paused"` |

### Critical Finding
**SSE Events** use `"state": "paused"`  
**Status Endpoint** uses `"status": "paused"`  
This is INTENTIONAL - check API_SPEC.md section 3.1 vs 3.6

---

## 2. Browser Handlers Analysis

### 2.1 SSE Handler (index.html ~702-741)

```javascript
// Browser checks: data.state (CORRECT - matches SSE format)
if (data.state === 'cancelled') { ... }
if (data.state === 'pausing') { ... }
if (data.state === 'paused') { ... }
if (data.state === 'resuming') { ... }  // FIXED: now calls resume() before changing state
if (data.state === 'completed') { ... }
```

**SSE Format from server:**
```
data: {"type":"upload","uploadId":"...","state":"resuming",...}
                                        ^^^^^
```

**Status:** ✅ Browser correctly checks `data.state`

---

### 2.2 Status Endpoint Response Handler (index.html ~499-520)

```javascript
// Browser checks: status.status (CORRECT - matches status endpoint)
if (status.status === 'completed') { ... }
if (status.status === 'cancelled') { ... }
if (status.status === 'busy' || status.isBusy) { ... }
```

**Status Endpoint Format (FIXED):**
```json
{
  "exists": true,
  "bytesReceived": 65536,
  "status": "paused",    // <-- Was "state", now "status"
  "canResume": true,
  "isBusy": false
}
```

**Status:** ✅ FIXED - Server now sends `"status"` not `"state"`

---

### 2.3 Upload POST Response Handler (index.html ~623-662)

```javascript
// Browser checks: resp.state (CORRECT - upload POST uses "state")
if (resp.state === 'completed') { ... }
if (resp.state === 'paused') { ... }
if (resp.state === 'cancelled') { ... }
```

**Upload POST Response Format:**
```json
{
  "success": false,
  "uploadId": "...",
  "bytesReceived": 524288,
  "state": "paused"    // <-- POST uses "state"
}
```

**Status:** ✅ Server correctly sends `"state"` in POST responses

---

## 3. State Transition Flows (Multi-File Scenario)

### 3.1 Upload File 1 + File 2 → Pause File 1 → Resume File 1

```
Time    File 1 State          File 2 State           Action
----    -------------          ------------           ------
T0      NONE                   NONE                   Browser selects files
T1      UPLOADING              UPLOADING              Browser POSTs both
T2      UPLOADING              UPLOADING              Phone UI shows progress
T3      PAUSING → PAUSED       UPLOADING              User clicks pause on File 1
T4      PAUSED                 UPLOADING              SSE: File 1 state=paused
T5      PAUSED                 UPLOADING              User clicks resume on File 1
T6      RESUMING               UPLOADING              Server sets RESUMING + deadline
T7      UPLOADING              UPLOADING              Browser POSTs (within 30s)
```

**Key Transitions:**
- `PAUSED → RESUMING`: Server accepts, starts deadline watcher
- `RESUMING → UPLOADING`: Only if browser POSTs within 30s
- `RESUMING → PAUSED`: If deadline expires (browser didn't POST)

---

### 3.2 Upload → Cancel While Paused

```
Time    State                  Action
----    -----                  ------
T0      UPLOADING              Active upload
T1      PAUSING → PAUSED       User clicks pause
T2      PAUSED                 SSE notifies browser
T3      PAUSED                 User clicks cancel
T4      CANCELLED              Server: cancel job + delete file + set state
T5      (removed)              SSE: state=cancelled, browser removes from UI
```

**Critical Code Paths:**

**Server - UploadScheduler.cancel():**
```kotlin
suspend fun cancel(uploadId: String, request: UploadRequest) {
    // 1. Cancel active job
    activeJobs[uploadId]?.cancel()?.join()
    
    // 2. Clean up partial file
    cleanupPartial(request)  // <-- TRACING THIS
    
    // 3. Mark as cancelled
    stateManager.transition(uploadId, UploadState.CANCELLED)
}
```

**Server - cleanupPartial():**
```kotlin
private suspend fun cleanupPartial(request: UploadRequest) {
    val cleanPath = request.path.trimEnd('/')  // Removes trailing slash
    val fileName = request.fileName
    // Find and delete...
}
```

**Status:** ✅ Path handling fixed to trim trailing slashes

---

## 4. Cancel Not Working - Debug Analysis

### 4.1 Root Causes Identified

| Issue | Location | Fix Applied |
|-------|----------|-------------|
| Path shows `//file.mp4` | cleanupPartial log | Path trim added |
| Missing detailed logs | cleanupPartial | Added find/delete result logging |
| Status lookup not logged | uploadCancelRoute | Added status lookup logging |

### 4.2 Cancel Flow Verification

**Browser Side:**
```javascript
async function cancelUpload(id) {
    await fetch(`${serverUrl}/api/upload/cancel?token=${token}&id=${id}`, ...);
    uploadQueue.cancel(id);  // Aborts XHR, removes from UI
}
```
- Sends: `?id=` ✅ (matches QueryParams.UPLOAD_ID = "id")

**Server Side - Route:**
```kotlin
val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID]  // "id"
val status = scheduler.activeUploads.value[uploadId]  // Lookup upload
val path = status?.metadata?.path  // Get actual path from metadata
val fileName = status?.metadata?.displayName  // Get actual filename
queueManager.cancel(uploadId, request)
```
- Expects: `?id=` ✅ (QueryParams.UPLOAD_ID = "id")
- Extracts metadata ✅ (uses stored metadata, not query params)

**Server Side - Scheduler:**
```kotlin
suspend fun cancel(uploadId, request) {
    job?.cancel()?.join()  // Stop the upload coroutine
    cleanupPartial(request)  // Delete file
    stateManager.transition(uploadId, CANCELLED)  // Set state
}
```

---

## 5. Resume Not Working - Debug Analysis

### 5.1 Previous Bug (FIXED)

**Problem:** Browser set `item.state = 'resuming'` BEFORE calling `uploadQueue.resume()`

**uploadQueue.resume() guard:**
```javascript
resume(id) {
    if (!item || item.state !== 'paused') return;  // Returns early!
    // ...
}
```

**Fix Applied:**
```javascript
// BEFORE (broken):
item.state = 'resuming';  // State changed first
uploadQueue.resume(data.uploadId);  // Returns immediately - state != 'paused'

// AFTER (fixed):
if (item.state === 'paused' && !item.xhr) {  // Check while still paused
    uploadQueue.resume(data.uploadId);  // Actually resumes
}
item.state = 'resuming';  // Then update for UI
```

---

## 6. Testing Checklist

### 6.1 Status Field Verification Commands

```bash
# Verify server sends correct fields
./validate_api.sh

# Manual checks:
# 1. SSE events use "state" field
grep 'append("\\"state\\"' core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/routes/UploadRoutes.kt

# 2. Status endpoint uses "status" field (FIXED)
grep 'put("status"' core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/routes/UploadRoutes.kt

# 3. Browser checks correct fields
grep "data.state" web/src/main/assets/index.html
grep "status.status" web/src/main/assets/index.html
grep "resp.state" web/src/main/assets/index.html
```

### 6.2 Expected Behaviors

| Scenario | Server State | Browser Sees (SSE) | Browser Sees (Status) |
|----------|-------------|-------------------|---------------------|
| Initial upload | `UPLOADING` | `state: "uploading"` | `status: "uploading"` |
| Pause clicked | `PAUSED` | `state: "paused"` | `status: "paused"` |
| Resume clicked | `RESUMING` | `state: "resuming"` | `status: "resuming"` |
| Deadline expired | `PAUSED` | `state: "paused"` | `status: "paused"` |
| Cancel clicked | `CANCELLED` | `state: "cancelled"` | `status: "cancelled"` |
| Upload complete | `COMPLETED` | `state: "completed"` | `status: "completed"` |

---

## 7. Remaining Potential Issues

### 7.1 Browser-Only Pause (Not Server Pause)

When user clicks pause in browser:
1. Browser calls `POST /api/upload/pause?id=...`
2. Server sets `PAUSING → PAUSED` and cancels job
3. SSE sends `state: "paused"` to browser
4. Browser SSE handler should abort XHR

**Current browser behavior:**
```javascript
// In SSE handler
} else if (data.state === 'paused') {
    if (!item.isPaused) {
        uploadQueue.pause(data.uploadId);  // Sets isPaused = true
    }
}
```

**Issue:** Does `uploadQueue.pause()` abort the XHR?
```javascript
pause(id) {
    const item = this.items.get(id);
    item.state = 'paused';  // Just sets state, doesn't abort XHR!
    item.isPaused = true;
    if (item.xhr) {
        item.xhr.abort();  // This aborts
        item.xhr = null;
    }
}
```

✅ **XHR is aborted in uploadQueue.pause()**

### 7.2 File Deletion Path Issue

When path is "/" and fileName is "Promo-2.mp4":
- Log shows: `//Promo-2.mp4` (double slash)
- But actual lookup: `findFileByName("/", "Promo-2.mp4")` - should work

**Fix Applied:**
```kotlin
val cleanPath = request.path.trimEnd('/')  // "/" → ""
```

Now lookup is: `findFileByName("", "Promo-2.mp4")` - may need verification

---

## 8. Recommended Testing Steps

1. **Single file upload:** Verify progress, completion
2. **Pause during upload:** Verify progress stops, file remains
3. **Resume paused upload:** Verify progress resumes from same point
4. **Cancel paused upload:** Verify file is deleted from Downloads/AirBridge
5. **Multi-file upload:** Verify 2-3 files upload simultaneously
6. **Pause one file:** Verify other continues
7. **Resume paused file:** Verify it resumes while others complete

---

## 9. File Deletion Verification

To verify cancel actually deletes files:

```bash
# Before cancel - check file exists
adb shell ls -la /sdcard/Download/AirBridge/

# Click cancel in app

# After cancel - check file deleted
adb shell ls -la /sdcard/Download/AirBridge/
```

Or check server logs for:
```
D/UploadScheduler: [uploadId] File deleted successfully
```

---

**END OF AUDIT**

**Action Items:**
1. ✅ Fix resume handler order (DONE)
2. ✅ Fix status endpoint field name (DONE)
3. ✅ Add detailed cancel logging (DONE)
4. ✅ Fix path trimming in cleanupPartial (DONE)
5. ⏳ Test cancel file deletion with new logging
