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
│  │  │  Jetpack     │    │   NanoHTTPD  │    │  Foreground  │       │   │
│  │  │  Compose UI  │◄──►│   Server     │◄──►│   Service    │       │   │
│  │  │              │    │   (Port 8080)│    │  + WakeLock  │       │   │
│  │  └──────────────┘    └──────┬───────┘    └──────────────┘       │   │
│  │         ▲                   │                                    │   │
│  │         │                   │ HTTP Requests                    │   │
│  │         │                   ▼                                    │   │
│  │  ┌──────┴──────┐    ┌──────────────┐                            │   │
│  │  │  Upload     │◄──►│   Browser    │                            │   │
│  │  │  Controller │    │   (Any WiFi) │                            │   │
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
│  │                      core:network                         │   │
│  │                                                         │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │   │
│  │  │LocalHttpServer│  │UploadController│  │FileController│  │   │
│  │  │              │  │              │  │              │  │   │
│  │  │ • NanoHTTPD  │  │ • State      │  │ • List files │  │   │
│  │  │ • Routing    │  │   machine    │  │ • Download   │  │   │
│  │  │ • Security   │  │ • Pause/Resume│  │              │  │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                      core:storage                       │   │
│  │                                                         │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │   │
│  │  │ FileRepository│  │MediaStoreData │  │SafDataSource │  │   │
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
│  │ • Downloads/ │  │ • User picks │  │ • Web UI served     │ │
│  │   AirBridge  │  │   any folder │  │   from assets       │ │
│  │ • Automatic  │  │ • Persistent │  │ • XMLHttpRequest    │ │
│  │   indexing   │  │   permissions│  │   uploads           │ │
│  └──────────────┘  └──────────────┘  └──────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

### Upload State Machine

The upload system implements a robust state machine that handles bidirectional control:

```
                    ┌─────────────┐
                    │    IDLE     │
                    │  (no entry) │
                    └──────┬──────┘
                           │ POST /api/upload
                           ▼
              ┌────────────────────────┐
     ┌───────►│       UPLOADING        │◄────────────────────────┐
     │        │  • Receiving bytes     │                         │
     │        │  • Progress updates    │                         │
     │        └──────────┬─────────────┘                         │
     │                   │                                         │
     │    ┌──────────────┼──────────────┐                        │
     │    │              │              │                        │
     │    ▼              ▼              ▼                        │
     │ ┌──────┐    ┌─────────┐   ┌──────────┐                  │
     │ │ PAUSED│    │CANCELLED│   │COMPLETED │                  │
     │ │       │    │         │   │          │                  │
     │ │Resume │    │Delete   │   │Show      │                  │
     │ │works  │    │partial  │   │"Finished"│                  │
     │ └───┬───┘    └────┬────┘   └────┬─────┘                  │
     │     │             │             │                         │
     │     │ resume()    │             │                         │
     │     ▼             │             │                         │
     │ ┌────────┐        │             │                         │
     └─┤RESUMING│        │             │                         │
       │        │        │             │                         │
       │Browser │        │             │                         │
       │restarts│        │             │                         │
       │upload  │        │             │                         │
       └────┬───┘        │             │                         │
            └─────────────┴─────────────┴─────────────────────────┘

State Transitions:
• UPLOADING → PAUSED     : User clicks pause (either side)
• PAUSED    → RESUMING   : User clicks resume (phone side)
• RESUMING  → UPLOADING  : Browser detects resuming, starts upload
• UPLOADING → CANCELLED  : User clicks cancel
• UPLOADING → COMPLETED  : All bytes received
• (any)     → INTERRUPTED: Network error (resumable)
```

### Bidirectional Sync Flow

Both the Android app and browser can control uploads. Here's how they stay synchronized:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    BROWSER PAUSE → PHONE UPDATES                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Browser                              Server              Phone UI      │
│     │                                    │                   │          │
│     │  POST /api/upload/pause            │                   │          │
│     │───────────────────────────────────►│                   │          │
│     │                                    │ set PAUSED status │          │
│     │                                    │──────────────────►│          │
│     │                                    │                   │          │
│     │                                    │    StateFlow      │          │
│     │                                    │    emits update   │          │
│     │                                    │──────────────────►│          │
│     │                                    │                   ▼          │
│     │                                    │            ┌─────────────┐    │
│     │                                    │            │ Shows       │    │
│     │                                    │            │ "Paused" +  │    │
│     │                                    │            │ Resume btn  │    │
│     │                                    │            └─────────────┘    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                    PHONE PAUSE → BROWSER UPDATES                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Phone UI                             Server              Browser         │
│     │                                    │                   │           │
│     │  User taps Pause                   │                   │           │
│     │───────────────────────────────────►│                   │           │
│     │                                    │ set PAUSED status │           │
│     │                                    │                   │           │
│     │                                    │◄──────────────────│           │
│     │                                    │   GET /status     │           │
│     │                                    │   (every 2s)      │           │
│     │                                    │                   │           │
│     │                                    │ returns isPaused=true          │
│     │                                    │───────────────────►│          │
│     │                                    │                   ▼          │
│     │                                    │            ┌─────────────┐     │
│     │                                    │            │ remotePause()│    │
│     │                                    │            │ updates UI   │    │
│     │                                    │            └─────────────┘     │
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

#### 1. Why NanoHTTPD + CompletableFuture?

```
Problem:  NanoHTTPD runs on a thread pool and expects blocking responses
Solution: CompletableFuture.get() blocks HTTP thread while coroutine does work

┌──────────┐         ┌──────────────────┐         ┌──────────────┐
│  Browser │────────►│ NanoHTTPD Thread │────────►│  Coroutine   │
│  Request │         │                  │         │  (IO thread) │
└──────────┘         └──────────────────┘         └──────────────┘
                            │                            │
                            │  future.get()              │ actual work
                            │  (blocks thread)           │ (non-blocking)
                            │                            │
                            ▼                            ▼
                     ┌──────────────┐              ┌──────────────┐
                     │ Waits for    │              │ Uploads file │
                     │ coroutine    │              │ with progress│
                     └──────────────┘              └──────────────┘
```

#### 2. Why uploadLocks?

Prevents overlapping uploads when user clicks pause/resume rapidly:

```kotlin
// Without locks:
Thread 1: Check uploadJobs["id"] → null
Thread 2: Check uploadJobs["id"] → null  ← Both see null!
Thread 1: Store job
Thread 2: Store job  ← Overwritten! First upload orphaned.

// With synchronized(lock):
Thread 1: synchronized(lock) { check → null; store job }
Thread 2: synchronized(lock) { check → exists; return busy }
```

#### 3. Why Terminal State Protection?

Prevents "ghost progress" after user pauses:

```
Without protection:
1. User clicks Pause → status = PAUSED
2. Server cancels job → CancellationException
3. Late progress callback arrives → Updates bytes to 500KB
4. UI shows "Paused at 500KB" but actually PAUSED

With protection:
1. User clicks Pause → status = PAUSED (terminal)
2. Late progress callback → updateProgress() returns early
3. UI stays at "Paused at 300KB" (correct)
```

#### 4. Why MediaStore Query for Cancel?

SAF and MediaStore store files differently. For reliable deletion:

1. **Primary**: Direct MediaStore query by filename (works regardless of path)
2. **Fallback**: Repository-based lookup with path guessing

This ensures cancel always deletes the partial file, even if the file was created via a different storage method.

Tech Stack & Best Practices
---------------------------

| Component | Technology |
|-------------|------------|
| **Language** | 100% Kotlin with Coroutines and Flows |
| **UI** | Jetpack Compose with Immutable Collections (`kotlinx-collections-immutable`) |
| **Architecture** | Multi-module Clean Architecture + MVI |
| **DI** | Hilt (Dagger) |
| **HTTP Server** | NanoHTTPD |
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

- **Local Only**: All traffic is restricted to your local network via a `SecurityInterceptor`. The app rejects requests from outside the LAN (192.168.x.x, 10.x.x.x, 172.16-31.x.x ranges).
- **Session Auth**: Every connection requires a unique, randomly generated session token created during QR code pairing.
- **No Cloud**: Your files never touch a server outside your own device and computer. All transfers are peer-to-peer over your Wi-Fi.

Known Issues
------------

- **Bidirectional Pause/Resume Timing**: When pausing from one device (browser or phone) and attempting to resume from the other, there can be a 2-4 second delay before the sync loop detects the state change. During this window, clicking resume may not respond. Wait for the status indicator to update before clicking resume.

- **Large File Progress**: For files >100MB, the progress bar may appear to stall at 99% briefly while the final buffer is flushed. This is normal and the transfer will complete.

- **Network Interruptions**: If Wi-Fi disconnects during upload, the transfer will show "Error" rather than "Interrupted". You can still resume by selecting the same file again.
