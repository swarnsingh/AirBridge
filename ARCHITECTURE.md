# AirBridge Upload Engine v2 - Architecture Document

## Deterministic Resumable Upload Architecture

---

## 1. Overview

AirBridge is a peer-to-peer file transfer system that enables browser-to-phone uploads over local network using a deterministic resumable upload protocol.

The system supports:

* Parallel multi-file uploads
* Individual pause and resume
* Global pause and resume
* Deterministic offset-based resume
* Strict file-level concurrency control
* Direct-append storage model
* No data corruption guarantees
* Event-driven UI synchronization

This document describes the system architecture, protocol, state model, concurrency strategy, and production considerations.

---

## 2. Architectural Principles

AirBridge Upload Engine is built on five core principles:

1. **Deterministic State Transitions** - All state changes validated, no illegal transitions
2. **Fail-Fast Concurrency Control** - `tryLock()` + `tryAcquire()` return immediately, never block
3. **Disk-Offset as Source of Truth** - File size on disk is immutable resume point
4. **POST-Driven Resume (No Deadlocks)** - Browser initiates, server never auto-resumes
5. **Clean Separation of Concerns** - Control plane vs data plane separation

The system avoids implicit retries, hidden buffering, and race-prone polling logic.

---

## 3. System Architecture

### 3.1 High-Level Architecture

```
Browser (Web UI)
    │
    ▼
Ktor HTTP Server (Android)
    │
    ▼
UploadScheduler
    │
    ▼
UploadStateManager
    │
    ▼
StorageRepository
    │
    ▼
Android Storage (SAF / MediaStore)
```

### 3.2 Layer Responsibilities

#### Browser Layer

* UploadQueueManager (multi-file orchestration)
* Resume engine
* Exponential retry handling
* SSE listener
* UI progress rendering

#### HTTP Layer (UploadRoutes)

* Parse HTTP requests
* Translate request into domain model
* Map domain result → HTTP response
* No business logic

#### UploadScheduler

* File-level locking
* Global concurrency limit (semaphore)
* Offset validation
* Pause/resume enforcement
* Job cancellation

#### UploadStateManager

* Deterministic state machine
* Progress tracking
* Global pause flag
* State transition validation

#### StorageRepository

* Direct append writes
* Small chunk streaming (8KB)
* Cooperative cancellation
* Delete on cancel

---

## 4. Upload Protocol

### 4.1 Direct Append Model

Files are written directly to final filename.

There are no temporary or `.part` files.

Disk size is the single source of truth for resume validation.

### 4.2 Resume Handshake

1. Phone triggers resume
   → State transitions to `RESUMING`

2. Browser immediately POSTs with:

```
Content-Range: bytes <offset>-<end>/<total>
```

3. Server validates:

* File-level mutex available
* Concurrency slot available
* offset == diskSize

4. If valid:
   → state = UPLOADING

5. If busy:
   → HTTP 409 busy

6. If offset mismatch:
   → HTTP 409 offset_mismatch

Resume is POST-driven, not state-driven.

This eliminates deadlocks.

---

## 5. State Machine

### 5.1 Server Upload State

States:

```
NONE
QUEUED
RESUMING
UPLOADING
PAUSING
PAUSED
COMPLETED
CANCELLED
ERROR_RETRYABLE
ERROR_PERMANENT
```

Valid Transitions:

```
NONE → QUEUED
QUEUED → UPLOADING
UPLOADING → PAUSING
PAUSING → PAUSED
PAUSED → RESUMING
RESUMING → UPLOADING
UPLOADING → COMPLETED
UPLOADING → ERROR_RETRYABLE
UPLOADING → ERROR_PERMANENT
ANY → CANCELLED
```

Illegal transitions are rejected.

### 5.2 Browser Upload State

```
queued
waiting_for_slot
uploading
paused
resuming
completed
cancelled
error
```

Browser state is independent but synchronized via SSE + status endpoint.

---

## 6. Concurrency Model

### 6.1 File-Level Locking

Each file path has a dedicated mutex:

```
lockKey = path + "/" + filename
```

Guarantee:

* Only one writer per file
* No duplicate byte streams
* No corruption

### 6.2 Global Concurrency Limit

UploadScheduler uses:

```kotlin
Semaphore(MAX_CONCURRENT_UPLOADS)
```

Server fails fast on resource contention.

It never blocks.

Browser retries using exponential backoff.

### 6.3 Fail-Fast Implementation

```kotlin
// Try file lock
val fileMutex = fileLocks.getOrPut(lockKey) { Mutex() }
if (!fileMutex.tryLock()) {
    return UploadResult.Busy(uploadId, retryAfterMs = 200)
}

// Try semaphore
if (!uploadSemaphore.tryAcquire()) {
    fileMutex.unlock()
    return UploadResult.Busy(uploadId, retryAfterMs = 300)
}
```

---

## 7. Pause and Resume Design

### 7.1 Pause

Pause triggers:

* activeJob.cancel()
* CancellationException
* state → PAUSED

Streaming loop uses:

* 8KB buffer
* ensureActive()
* state checks per chunk

Pause latency target: < 200ms

### 7.2 Resume

Resume flow:

* state → RESUMING
* Browser POST immediately
* Offset validated against disk
* state → UPLOADING

Resume latency target: < 300ms

### 7.3 Resume Deadline

Server sets 5-second deadline when entering RESUMING state.

If no browser POST arrives:

```kotlin
if (deadlineExpired && state == RESUMING) {
    state = PAUSED
}
```

This prevents stuck handshakes.

---

## 8. Multi-File Upload Queue

### 8.1 Browser Queue

```
UploadQueueManager (maxParallel = 3)
    │
    ├── Item A (uploading)
    ├── Item B (uploading)
    ├── Item C (uploading)
    │
    └── Queue: [Item D, Item E, ...]
```

Responsibilities:

* Manage upload slots
* Handle priority
* Requeue paused files
* Pause All / Resume All
* Retry busy uploads

### 8.2 Exponential Backoff

```javascript
let backoff = 200;
while (attempts < 20) {
    const result = await attemptUpload();
    if (result.busy) {
        await sleep(backoff);
        backoff = Math.min(backoff * 2, 3000);
        continue;
    }
    // ...
}
```

---

## 9. Error Handling Strategy

| Scenario                | HTTP Code | Behavior                 |
| ----------------------- | --------- | ------------------------ |
| Offset mismatch         | 409       | Return diskSize, browser adjusts |
| Concurrency limit       | 409 Busy  | Retry with exponential backoff |
| Storage full            | 507       | Error, offer retry |
| Permission revoked      | 500       | ERROR_PERMANENT |
| Network drop            | -         | ERROR_RETRYABLE |
| File deleted externally | 409       | ERROR_PERMANENT |
| Cancel                  | 410       | Delete file, stop upload |

Disk size is authoritative.

---

## 10. Observability

### 10.1 Structured Logging

Log events:

* resume_requested
* busy_rejected
* offset_mismatch
* pause_requested
* upload_completed
* upload_failed

All logs include uploadId.

### 10.2 Metrics

Track:

* Resume latency
* Pause latency
* Busy rejection rate
* Offset mismatch rate
* Throughput (bytes/sec)
* Concurrent upload count

Expose:

```
GET /api/metrics
```

### 10.3 Browser Debug Mode

```javascript
window.DEBUG_UPLOAD = true;
```

Logs:

* Resume attempts
* Busy retries
* Offset changes
* Pause triggers

---

## 11. Event-Driven Model

Upload system emits events:

```
UPLOAD_STARTED
UPLOAD_PROGRESS
UPLOAD_PAUSED
UPLOAD_RESUMED
UPLOAD_COMPLETED
UPLOAD_FAILED
```

SSE broadcasts to browser.

Browser reacts without polling dependency.

---

## 12. Control Plane vs Data Plane

### Separation

| Control Plane | Data Plane |
|---------------|------------|
| Pause / Resume | POST /upload streaming |
| Cancel | Raw byte transfer |
| Status queries | Offset validation |
| SSE events | File I/O |

### Why This Matters

Future upgrades can swap out data plane without touching control logic:

* **Chunked multipart**: Replace single POST with multi-chunk protocol
* **Encryption layer**: Wrap OutputStream with CipherOutputStream
* **WebSocket control**: Replace SSE with WebSocket signaling
* **Compression**: Add Deflater between source and sink

---

## 13. Deployment Architecture

### 13.1 LAN Mode (Current)

* Phone runs Ktor server
* QR code encodes local IP
* Browser connects directly

### 13.2 Internet Mode (Future)

Possible upgrades:

* Reverse WebSocket tunnel
* WebRTC DataChannel
* Relay server for NAT traversal

Architecture supports extension without changing core upload engine.

---

## 14. Production Guarantees

The system guarantees:

* **No duplicate bytes** — Strict offset validation
* **No concurrent writes** — File-level mutex
* **Deterministic resume** — POST-driven with deadline
* **No deadlocks** — Fail-fast locking
* **Instant pause** — 8KB buffer + ensureActive()
* **Safe parallel uploads** — Semaphore limiting
* **Crash-safe** — Disk size is source of truth

---

## 15. Future Enhancements

Architecture supports:

### 15.1 Chunked Multipart Upload

Add:

```
chunkNumber
chunkHash
finalChecksum
```

### 15.2 Encryption Layer

Add:

```kotlin
EncryptionRepository
    encrypt before write
```

### 15.3 Integrity Verification

Add:

```
SHA256 validation after completion
```

### 15.4 Background Sync Mode

Queue persists across reboot.

### 15.5 Priority Scheduling

Queue.sort by:

* File size
* User priority
* Resume priority

---

## 16. File Organization

```
core/network/src/main/kotlin/...
└── upload/
    ├── UploadScheduler.kt      # Fail-fast locking, state machine
    ├── UploadQueueManager.kt   # HTTP orchestration, queue state
    └── ...

domain/src/main/kotlin/...
└── model/
    ├── UploadModels.kt         # UploadState, UploadResult
    └── ...

web/src/main/assets/
└── index.html                  # Browser UploadQueueManager (JS)
```

---

## 17. Conclusion

AirBridge Upload Engine v2 is a deterministic resumable upload system designed for correctness, reliability, and extensibility.

By separating:

* Control plane from data plane
* State management from storage
* Concurrency from protocol
* Browser orchestration from server enforcement

the system achieves production-grade robustness while remaining lightweight and mobile-friendly.

---

**Document Version**: 1.0  
**Protocol Version**: v2.1-failfast  
**Last Updated**: 2026-03-02
