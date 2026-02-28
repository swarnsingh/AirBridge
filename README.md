AirBridge
=========

Overview
--------

AirBridge is a robust, high‑performance Android LAN file‑sharing app that transforms your device into a local HTTP server. It enables seamless, lightning-fast file transfers between your phone and any browser on the same Wi‑Fi network. Built with modern Android standards, it features a foreground service, parallel resumable upload support, and a responsive web UI—all without requiring any cloud backend or internet connection.

Key Features
-----------

- **High-Performance Transfers**
  - **True Parallel Uploads**: Send multiple files simultaneously from your browser.
  - **Manual Pause & Resume**: Individually control every parallel transfer. Pause an upload on your phone and resume it later from your browser (or vice versa).
  - **Robust Cancellation**: Stop ongoing transfers instantly with proper cleanup. "Cancel" deletes partial data, while "Pause" preserves it for resumption.
  - **Background Reliability**: Uses a **Foreground Service** + **WakeLock** to ensure transfers continue even when the app is in the background or the screen is off.
- **Modern Android Integration**
  - **Zero-Config Startup**: Automatically uses a default "AirBridge" folder in `Downloads` for immediate use.
  - **Custom Storage**: Seamlessly switch between the default folder and any specific directory via the Storage Access Framework (SAF).
  - **Latest Tech Stack**: Targets **Android 16 (API 36)** and requires **Android 12 (API 31)** as minimum.
- **Seamless Connectivity**
  - **Bidirectional Control**: Manage uploads from both the Android App Dashboard and the Web Interface.
  - **Instant Pairing**: Connect via QR Code scanning or "Copy URL".
  - **Automatic Resumption**: Browser-side persistence tracks interrupted uploads, allowing them to resume exactly where they left off after a reconnect.

Architecture
------------

### System Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           ANDROID DEVICE                                  │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      AIRBRIDGE APP                                 │   │
│  │                                                                   │   │
│  │  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │   │
│  │  │  Jetpack     │    │   Ktor CIO   │    │  Foreground  │       │   │
│  │  │  Compose UI  │◄──►│   Server     │◄──►│   Service    │       │   │
│  │  │              │    │   (Port 8081)│    │  + WakeLock  │       │   │
│  │  └──────────────┘    └──────┬───────┘    └──────────────┘       │   │
│  │         ▲                   │                                    │   │
│  │         │                   │ HTTP Requests                    │   │
│  │         │                   ▼                                    │   │
│  │  ┌──────┴──────┐    ┌──────────────┐                            │   │
│  │  │  Upload     │◄──►│   Browser    │                            │   │
│  │  │  Scheduler  │    │   (Any WiFi) │                            │   │
│  │  └─────────────┘    └──────────────┘                            │   │
│  │         │                                                        │   │
│  │         │                                                        │   │
│  │  ┌──────┴──────┐    ┌──────────────┐    ┌──────────────┐       │   │
│  │  │  FileRepo   │◄──►│  MediaStore  │    │     SAF      │       │   │
│  │  │             │    │  (Default)   │    │  (Optional)  │       │   │
│  │  └─────────────┘    └──────────────┘    └──────────────┘       │   │
│  │                                                                   │   │
│  └───────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Module Structure

AirBridge follows **Clean Architecture** principles with a highly modularized structure:

```
┌────────────────────────────────────────────────────────────────┐
│                        PRESENTATION                            │
│                      (Android UI Layer)                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │  feature:   │  │  feature:   │  │       feature:          │ │
│  │  dashboard  │  │ filebrowser │  │     permissions         │ │
│  │             │  │             │  │                         │ │
│  │ • Uploads   │  │ • Browse    │  │ • Storage access        │ │
│  │ • Server    │  │ • Download  │  │ • Pairing               │ │
│  │ • QR Code   │  │ • Navigate  │  │                         │ │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘ │
│          │              │                      │               │
│          └──────────────┴──────────────────────┘               │
│                         │                                      │
│                         ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    core:mvi                             │   │
│  │              (MVI Framework)                            │   │
│  │  • StateFlow-based state management                     │   │
│  │  • Intent/State/Effect pattern                          │   │
│  └─────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│                           DOMAIN                               │
│                   (Pure Kotlin, No Android)                    │
│                                                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │  Entities   │  │  Use Cases  │  │   Repository Interfaces │ │
│  │             │  │             │  │                         │ │
│  │ • FileItem  │  │ • Start     │  │ • StorageRepository     │ │
│  │ • FolderItem│  │   Server    │  │ • StorageAccessManager  │ │
│  │ • Server    │  │ • Generate  │  │                         │ │
│  │   Status    │  │   QR Code   │  │                         │ │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│                          DATA LAYER                            │
│                                                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                      core:network                       │   │
│  │                                                         │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │   │
│  │  │KtorLocalServer│  │UploadScheduler│  │ UploadRoutes │  │   │
│  │  │              │  │              │  │              │  │   │
│  │  │ • Ktor CIO   │  │ • State      │  │ • HTTP API   │  │   │
│  │  │ • Routing    │  │   machine    │  │ • Auth       │  │   │
│  │  │ • Security   │  │ • Pause/Resume│  │              │  │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                      core:storage                     │   │
│  │                                                         │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │   │
│  │  │ FileRepository│  │MediaStoreData│  │SafDataSource │  │   │
│  │  │              │  │   Source     │  │              │  │   │
│  │  │ • Dual mode  │  │ • Query API  │  │ • DocumentFile│  │   │
│  │  │ • Resume     │  │ • Insert     │  │ • Tree nav   │  │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                      core:service                       │   │
│  │                                                         │   │
│  │  ┌─────────────────────────────────────────────────┐    │   │
│  │  │      ForegroundServerService                    │    │   │
│  │  │  • Prevents app kill during transfers           │    │   │
│  │  │  • WakeLock for CPU during upload               │    │   │
│  │  │  • Notification channel for user awareness       │    │   │
│  └─────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│                         EXTERNAL                               │
│                                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │  MediaStore  │  │   SAF        │  │     Browser        │ │
│  │  (Android)   │  │ (Android)    │  │   (Any Device)      │ │
│  │              │  │              │  │                     │ │
│  └──────────────┘  └──────────────┘  └──────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

### Upload State Machine

```
                    ┌─────────┐
         ┌─────────►│  QUEUED │────────┐
         │          └────┬────┘        │
         │               │             │
         │               ▼             │
    ┌────┴────┐    ┌──────────┐    ┌───┴────┐
    │ CANCELLED│◄───│ UPLOADING│───►│ PAUSED │
    └─────────┘    └────┬─────┘    └────────┘
         ▲              │
         │              ▼
         │         ┌──────────┐
         └─────────│ COMPLETED│
                   └──────────┘
```

### Pause/Resume Architecture

The system supports bidirectional pause/resume control from both the phone and browser.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    BROWSER PAUSE → PHONE CONTROLS                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Browser           Server              Phone UI                          │
│     │                │                     │                              │
│     │ POST /cancel   │                     │                              │
│     │───────────────►│                     │                              │
│     │                │ set CANCELLED       │                              │
│     │                │                    │                              │
│     │                │────────────────────►│ update UI                    │
│     │                │                    │                              │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                    PHONE PAUSE → BROWSER RETRIES                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Phone UI          Server              Browser                           │
│     │                │                     │                              │
│     │ pauseUpload()  │                     │                              │
│     │───────────────►│                     │                              │
│     │                │ set PAUSED          │                              │
│     │                │                    │                              │
│     │                │◄───────────────────│ GET /status                  │
│     │                │                    │ (every 2s)                   │
│     │                │ returns status=    │                              │
│     │                │  "paused"           │                              │
│     │                │────────────────────►│                              │
│     │                │                    │                              │
│     │                │                    ▼                              │
│     │                │             ┌─────────────┐                        │
│     │                │             │ showPauseUI│                        │
│     │                │             └─────────────┘                        │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                    PHONE RESUME → BROWSER STARTS                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Phone UI          Server              Browser                           │
│     │                │                     │                              │
│     │ resumeUpload() │                     │                              │
│     │───────────────►│                     │                              │
│     │                │ set RESUMING       │                              │
│     │                │                    │                              │
│     │                │◄───────────────────│ GET /status                  │
│     │                │                    │ (every 2s)                   │
│     │                │ returns status=    │                              │
│     │                │  "resuming"         │                              │
│     │                │────────────────────►│                              │
│     │                │                    │                              │
│     │                │                    ▼                              │
│     │                │             ┌─────────────┐                        │
│     │                │             │ start()     │                        │
│     │                │             │ re-uploads  │                        │
│     │                │             │ from offset │                        │
│     │                │             └─────────────┘                        │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Key Design Decisions

#### 1. Why Ktor + Coroutines?

```
Problem:  HTTP servers need to handle concurrent connections efficiently
Solution: Ktor with coroutines provides non-blocking I/O and structured concurrency

┌──────────┐         ┌──────────────────┐         ┌──────────────┐
│  Browser │────────►│   Ktor Server    │────────►│  Coroutine   │
│  Request │         │                  │         │  (IO thread) │
└──────────┘         └──────────────────┘         └──────────────┘
                            │                            │
                            │  suspend function          │ actual work
                            │  (non-blocking)            │ (non-blocking)
                            │                            │
                            ▼                            ▼
                     ┌──────────────┐              ┌──────────────┐
                     │ Handles other│              │ Uploads file │
                     │ requests while│              │ with progress│
                     │ waiting for I/O│              │              │
                     └──────────────┘              └──────────────┘
```

#### 2. Why File-Level Locking?

Prevents concurrent writes to the same file from different uploads:

```kotlin
// Without file-level locks:
Upload A: Check file.lock → not locked
Upload B: Check file.lock → not locked  ← Both see unlocked!
Upload A: Acquire lock, start writing
Upload B: Acquire lock, start writing  ← Data corruption!

// With file-level locking:
val lockKey = "$path/$fileName"
fileLocks[lockKey].withLock {
    // Only one upload can write to this file at a time
}
```

#### 3. Why State Machine with Atomic Transitions?

Prevents invalid state transitions and race conditions:

```kotlin
// Without state machine:
Thread 1: Upload is UPLOADING, transitioning to PAUSED
Thread 2: Reads state as UPLOADING, also tries to pause
Thread 1: Updates state to PAUSED
Thread 2: Updates state to PAUSED (redundant, but safe)

// With atomic state machine:
Thread 1: compareAndSet(UPLOADING → PAUSED) → success
Thread 2: compareAndSet(UPLOADING → PAUSED) → fails, state is now PAUSED
Thread 2: Returns false, caller knows pause already in progress
```

#### 4. Why Offset Validation?

Prevents duplicate bytes and data corruption on resume:

```
Browser claims: "I've sent 1000 bytes already"
Server checks: "File on disk has 800 bytes"
Result: Mismatch! Browser's offset is wrong.
Action: Return 409 CONFLICT with actual disk size.
Browser: Restart upload from byte 800.
```

Tech Stack & Best Practices
---------------------------

| Component | Technology |
|-------------|------------|
| **Language** | 100% Kotlin with Coroutines and Flows |
| **UI** | Jetpack Compose with Immutable Collections (`kotlinx-collections-immutable`) |
| **Architecture** | Multi-module Clean Architecture + MVI |
| **DI** | Hilt (Dagger) |
| **HTTP Server** | Ktor (CIO engine) |
| **Storage** | MediaStore (default) + SAF (optional) |
| **Minimum SDK** | 31 (Android 12) |
| **Target SDK** | 36 (Android 16 Preview) |

What Works Today
----------------

- **Instant Start**: Launch, grant permissions, and start sharing immediately using the default "AirBridge" folder.
- **Parallel Processing**: Browser can push multiple large files at once; the phone UI tracks each one individually in real-time.
- **Resumable Transfers**: Start an upload, pause it from either side, or even close the browser—re-opening and selecting the same file will resume exactly from the last byte.
- **Full Control**: Independent Pause/Play and Cancel buttons for every file in the transfer list.
- **Secure by Design**: Token-based authentication and LAN-only access ensure your data stays private and local.

Build & Run
-----------

- **IDE**: Android Studio (Ladybug or newer)
- **JDK**: 17+
- **Min SDK**: 31 (Android 12)
- **Target SDK**: 36 (Android 16 Preview)

1. **Sync**: `./gradlew :app:assembleDebug`
2. **Run**: Deploy to any physical device on your local Wi-Fi.
3. **Connect**: Tap "Start Server," scan the QR code on your computer, and start sharing.

Security Notes
--------------

- **Local Only**: All traffic is restricted to your local network. The app rejects requests from outside the LAN (192.168.x.x, 10.x.x.x, 172.16-31.x.x ranges).
- **Session Auth**: Every connection requires a unique, randomly generated session token created during QR code pairing.
- **No Cloud**: Your files never touch a server outside your own device and computer. All transfers are peer-to-peer over your Wi-Fi.

Known Issues
------------

- **Bidirectional Pause/Resume Timing**: When pausing from one device (browser or phone) and attempting to resume from the other, there can be a 2-4 second delay before the sync loop detects the state change. During this window, clicking resume may not respond. Wait for the status indicator to update before clicking resume.

- **Large File Progress**: For files >100MB, the progress bar may appear to stall at 99% briefly while the final buffer is flushed. This is normal and the transfer will complete.

- **Network Interruptions**: If Wi-Fi disconnects during upload, the transfer will show "Error" rather than "Interrupted". You can still resume by selecting the same file again.
