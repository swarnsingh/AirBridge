# AirBridge

## Deterministic Resumable File Transfer

AirBridge enables peer-to-peer file transfers between Android devices and web browsers over local Wi-Fi using a deterministic resumable upload protocol.

---

## Key Features

| Feature | Description |
|---------|-------------|
| **Parallel Uploads** | Transfer multiple files simultaneously with independent pause/resume control |
| **Deterministic Resume** | POST-driven resume protocol eliminates deadlocks and race conditions |
| **Fail-Fast Concurrency** | Server returns Busy immediately instead of blocking — browser handles retry |
| **Bidirectional Control** | Pause and resume from either phone or browser |
| **Direct Append Model** | Files written directly to final location — no temporary files |
| **Offset Validation** | Strict disk-size validation prevents duplicate bytes and corruption |
| **Event-Driven UI** | Real-time progress via Server-Sent Events (SSE) |
| **Clean Architecture** | Separation of Control Plane (HTTP API) and Data Plane (streaming) |

---

## Architecture Overview

### High-Level System

```
┌─────────────────────────────────────────────────────────────────┐
│                        BROWSER (Web UI)                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │ UploadQueue  │  │   Resume     │  │     SSE Client       │   │
│  │   Manager    │  │   Engine     │  │                      │   │
│  └──────────────┘  └──────────────┘  └──────────────────────┘   │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTP (POST / SSE)
                               ▼
┌───────────────────────────────────────────────────────────────────┐
│                    ANDROID (Ktor HTTP Server)                     │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────┐ │
│  │ UploadRoutes │    │   Upload     │    │  UploadStateManager  │ │
│  │   (HTTP)     │──► │  Scheduler   │──► │   (State Machine)    │ │
│  └──────────────┘    └──────────────┘    └──────────────────────┘ │
│         │                 │                  │                    │
│         │                 │                  │                    │
│         └─────────────────┴──────────────────┘                    │
│                           │                                       │
│                           ▼                                       │
│                  ┌──────────────────┐                             │
│                  │ StorageRepository│                             │
│                  │ (Direct Append)  │                             │
│                  └────────┬─────────┘                             │
└───────────────────────────┼───────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                 ANDROID STORAGE (SAF / MediaStore)              │
└─────────────────────────────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Responsibility |
|-------|----------------|
| **Browser** | Upload orchestration, exponential backoff, SSE consumption |
| **UploadRoutes** | HTTP translation only — no business logic |
| **UploadScheduler** | Fail-fast locking, offset validation, cooperative cancellation |
| **UploadStateManager** | Deterministic state machine with transition validation |
| **StorageRepository** | Direct append I/O with small chunks (8KB) |

---

## Deterministic Upload Protocol v2.1

### Core Principles

1. **Disk Offset is Source of Truth** — Browser must POST from exact disk size
2. **POST-Driven Resume** — Browser initiates, server never auto-resumes
3. **Fail-Fast Concurrency** — `tryLock()` + `tryAcquire()` return immediately
4. **Never Block on Resources** — Server returns 409 Busy, browser retries

### Resume Handshake

```
Phone UI          Server              Browser
   │                │                   │
   │ resumeUpload() │                   │
   │───────────────►│                   │
   │                │ state=RESUMING    │
   │                │ (5s deadline)     │
   │                │                   │
   │                │◄──────────────────│ POST /upload
   │                │   Content-Range:  │   bytes offset-end/total
   │                │                   │
   │                │ tryLock()         │
   │                │ tryAcquire()      │
   │                │ offset==diskSize  │
   │                │                   │
   │                │ state=UPLOADING   │
   │                │──────────────────►│ 200 OK
```

### State Machine

```
                    ┌─────────┐
         ┌─────────►│  NONE   │────────┐
         │          └────┬────┘        │
         │               │             │
         │               ▼             │
    ┌────┴────┐    ┌──────────┐    ┌───┴────────┐
    │CANCELLED│◄───│ UPLOADING│───►│  PAUSING   │
    └─────────┘    └────┬─────┘    └─────┬──────┘
         ▲              │                │
         │              ▼                ▼
         │         ┌──────────┐    ┌─────────┐
         └─────────│ COMPLETED│    │  PAUSED │
                   └──────────┘    └────┬────┘
                                          │
                                          ▼
                                    ┌──────────┐
                                    │ RESUMING │
                                    └──────────┘
```

### Error Handling

| HTTP Code | Condition | Browser Action |
|-----------|-----------|----------------|
| 200 | Success | Upload complete |
| 409 Busy | File locked or at capacity | Retry with exponential backoff |
| 409 Offset Mismatch | Browser offset ≠ disk size | Refresh offset from server, retry |
| 410 | Cancelled | Stop upload |
| 507 | Storage full | Show error, offer retry |

---

## Concurrency Model

### File-Level Locking

```kotlin
val lockKey = "${request.path}/${request.fileName}"
val fileMutex = fileLocks.getOrPut(lockKey) { Mutex() }

if (!fileMutex.tryLock()) {
    return UploadResult.Busy(uploadId) // Fail fast
}
```

**Guarantee**: Only one writer per file. No concurrent writes = no corruption.

### Global Concurrency Limit

```kotlin
if (!uploadSemaphore.tryAcquire()) {
    return UploadResult.Busy(uploadId, retryAfterMs = 300)
}
```

**Behavior**: Server returns Busy immediately. Browser implements exponential backoff (200ms → 400ms → 800ms... max 3s).

### Browser Queue

```javascript
class UploadQueueManager {
    maxParallel = 3        // Match server limit
    active = 0
    queue = []
    
    async performUpload(item) {
        let backoff = 200
        while (attempts < 20) {
            const status = await fetchStatus()
            if (status.isBusy) {
                await sleep(backoff)
                backoff = Math.min(backoff * 2, 3000)
                continue
            }
            // ... upload chunk
        }
    }
}
```

---

## Pause and Resume Design

### Pause (Target: < 200ms)

```kotlin
// Phone triggers pause
scheduler.pause(uploadId)

// Server side
stateManager.transition(uploadId, UploadState.PAUSING)
activeJobs[uploadId]?.cancel()  // Cancel coroutine

// In upload loop
catch (e: CancellationException) {
    stateManager.transition(uploadId, UploadState.PAUSED)
    return UploadResult.Paused(uploadId, bytesReceived)
}
```

**Key**: Small 8KB buffer + `ensureActive()` checks after every chunk = instant cancellation.

### Resume (Target: < 300ms)

```kotlin
// Phone triggers resume
scheduler.resume(uploadId)

// Server sets deadline
resumeDeadlines[uploadId] = System.currentTimeMillis() + 5000
stateManager.transition(uploadId, UploadState.RESUMING)

// Browser immediately POSTs
// Server validates and transitions to UPLOADING
```

---

## Multi-File Upload Queue

### Browser Queue Architecture

```
UploadQueueManager (maxParallel = 3)
    │
    ├── Item A (uploading)
    ├── Item B (uploading)
    ├── Item C (uploading)
    │
    └── Queue: [Item D, Item E, ...]

User Actions:
    - Pause Item B: aborts XHR, moves to paused list
    - Resume Item B: re-enters queue, starts when slot available
    - Pause All: aborts all XHRs, sets global pause flag
    - Resume All: clears flag, requeues all paused items
```

### Queue State SSE Events

```json
{
  "type": "queue",
  "isPaused": false,
  "active": 3,
  "queued": 2,
  "paused": 1
}
```

---

## Direct Append Storage Model

### Strategy

Files are written directly to final filename — no `.part` temporary files.

**Pros:**
- Simpler state management
- Immediate visibility in file managers
- Resume naturally works from existing file

**Cons:**
- Cancel must delete incomplete file
- External modification during upload = error

### Offset Validation (Critical)

```kotlin
val diskSize = storageRepository
    .findFileByName(request.path, request.fileName)
    .getOrNull()?.size ?: 0L

if (request.offset != diskSize) {
    return UploadResult.Failure.OffsetMismatch(
        uploadId = uploadId,
        bytesReceived = diskSize,
        expectedOffset = request.offset,
        actualDiskSize = diskSize
    )
}
```

**Without this**: Duplicate bytes, file corruption.

### Cancellable Streaming

```kotlin
private suspend fun InputStream.copyToCancellable(
    out: OutputStream,
    bufferSize: Int = 8 * 1024,  // Small for quick cancellation
    onProgress: (Long) -> Unit
) {
    val buffer = ByteArray(bufferSize)
    while (true) {
        ensureActive()  // Check cancellation before read
        
        val bytesRead = read(buffer)
        if (bytesRead < 0) break
        
        out.write(buffer, 0, bytesRead)
        onProgress(bytesRead)
        
        ensureActive()  // Check cancellation after write
    }
}
```

---

## Control Plane vs Data Plane

### Separation

| Control Plane | Data Plane |
|---------------|------------|
| Pause / Resume | POST /upload streaming |
| Cancel | Raw byte transfer |
| Status queries | Offset validation |
| SSE events | File I/O |

### Why This Matters

Future upgrades can swap out data plane without touching control logic:

- **Chunked multipart**: Replace single POST with multi-chunk protocol
- **Encryption layer**: Wrap OutputStream with CipherOutputStream
- **WebSocket control**: Replace SSE with WebSocket signaling
- **Compression**: Add Deflater between source and sink

---

## Observability

### Metrics Endpoint

```bash
GET /api/metrics?token=<session>
```

Response:
```json
{
  "timestamp": 1703123456789,
  "uploads": {
    "total": 5,
    "active": 2,
    "paused": 1,
    "queued": 1,
    "completed": 1,
    "error": 0
  },
  "throughput": {
    "avgBytesPerSecond": 5242880,
    "avgMBps": 5.0
  },
  "protocol": "v2.1-failfast"
}
```

### Browser Debug Mode

Enable in browser console:
```javascript
window.DEBUG_UPLOAD = true
```

Logs to console:
```
[QUEUE] Added upload-123 (file.zip), queue length: 1
[UPLOAD] upload-123 - Fetching status (attempt 1)
[UPLOAD] upload-123 - Server busy, backing off 200ms
[SSE] Received: {type: "upload", state: "paused"}
```

---

## Production Guarantees

| Guarantee | Mechanism |
|-----------|-----------|
| No duplicate bytes | Strict offset validation |
| No concurrent writes | File-level mutex |
| Deterministic resume | POST-driven protocol with 5s deadline |
| No deadlocks | Fail-fast locking, browser retry |
| Instant pause | 8KB buffer + ensureActive() checks |
| Safe parallel uploads | Semaphore limiting + per-file locking |
| Crash-safe | Disk size is source of truth |

---

## Future Extension Points

| Feature | Extension Point |
|---------|-----------------|
| Chunked multipart | Replace `performUpload` with chunk upload loop |
| Encryption | Add `EncryptionRepository` wrapping StorageRepository |
| Integrity verification | SHA-256 checksum after completion |
| Background sync | Persist queue to SQLite, resume on boot |
| Priority scheduling | Queue.sort() by priority score |
| Cloud relay mode | Replace Ktor with WebSocket tunnel |

---

## Module Structure

```
app/                          # Application entry point
├── feature/dashboard/        # Server control, upload list UI
├── feature/filebrowser/      # Local file management
└── feature/permissions/      # Storage permission handling

core/
├── network/                  # HTTP server, upload engine
│   ├── ktor/routes/          # HTTP endpoint definitions
│   └── upload/               # UploadScheduler, UploadQueueManager
├── storage/                  # FileRepository, SAF/MediaStore
├── service/                  # Foreground service + WakeLock
└── common/                   # Logging, MVI framework

domain/                       # Pure Kotlin business logic
├── model/                    # UploadState, UploadResult, entities
├── repository/               # Repository interfaces
└── usecase/                  # UploadStateManager

web/                          # Browser UI
└── src/main/assets/
    └── index.html            # UploadQueueManager (JS), SSE client
```

---

## Build & Run

**Requirements:**
- Android Studio Ladybug+
- JDK 17+
- Min SDK: 31 (Android 12)
- Target SDK: 36 (Android 16)

**Steps:**
1. Clone repository
2. Open in Android Studio
3. Sync: `./gradlew :app:assembleDebug`
4. Deploy to physical device (emulator lacks Wi-Fi LAN)
5. Tap "Start Server"
6. Scan QR code from browser on same Wi-Fi network

---

## Security

| Layer | Protection |
|-------|------------|
| Network | LAN-only (RFC1918 IPs only) |
| Session | Random UUID token, QR-based pairing |
| Transport | HTTP over local Wi-Fi (TLS not needed for LAN) |
| Storage | Android sandbox + SAF permissions |

---

## License

[Your License Here]

---

## Architecture Document Version

- Protocol: v2.1-failfast
- Document: 1.0
- Last Updated: 2026-03-02
