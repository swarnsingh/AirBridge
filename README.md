AirBridge
=========

Overview
--------

AirBridge is an Android LAN file‑sharing app that turns your phone into a local HTTP server so you can send and receive files between your device and any browser on the same Wi‑Fi network. It uses a foreground service plus an embedded HTTP server and a rich web UI to provide drag‑and‑drop uploads, downloads, and basic previews without any cloud backend.

Key Features
-----------

- Android app
  - Permissions screen to request media/storage access
  - Dashboard to start/stop the local HTTP server
  - QR code and “Copy URL” to connect browsers easily
  - File browser screen for on‑device files
  - Per‑file upload progress list (Xender‑style) driven from the server
- Web UI
  - Modern single‑page interface (`web/src/main/assets/index.html`)
  - File list with download links
  - Drag‑and‑drop or picker‑based uploads
  - Parallel uploads with per‑file progress bars
- Local HTTP server
  - Runs on `http://<phone-ip>:8080`
  - Token‑based session auth
  - Controllers for listing, uploading, downloading, and zipping files

Architecture
------------

The project follows a Clean Architecture, multi‑module setup:

- `domain` (pure Kotlin)
  - Entities in `domain.model` such as `FileItem`, `FolderItem`, `SessionInfo`, `ServerStatus`
  - Repository interfaces: `ServerRepository`, `StorageRepository`, `StorageAccessManager`, `SessionRepository`
  - Use cases under `domain.usecase` (e.g. `StartServerUseCase`, `StopServerUseCase`, `GenerateQrCodeUseCase`, `BrowseFilesUseCase`, `UploadFileUseCase`)
  - Shared `UseCase` base class in `core.common` (ResultState + dispatcher injection)
- `core:common` (pure Kotlin)
  - `ResultState` sealed class for Loading/Success/Error streams
  - `Dispatcher` qualifier + `AirDispatchers` enum
- `core:data` (Android library)
  - Hilt modules wiring domain repositories to implementations
  - DI for dispatchers, storage, network, and session token manager
- `core:storage`
  - `FileRepository` implementing `StorageRepository` and `StorageAccessManager`
  - `SafDataSource` using SAF + `DocumentFile` for folder selection and file IO
  - `MediaStoreDataSource` to query media files as a fallback
- `core:network`
  - `LocalHttpServer` wrapper around NanoHTTPD
  - Controllers:
    - `StaticAssetController` (serves web UI)
    - `FileController` (list/download)
    - `UploadController` (upload + progress)
    - `ZipController` (ZIP download)
    - `HealthController` (health checks)
    - `PairingController` (browser ↔ app pairing)
  - `SessionTokenManager`, `SecurityInterceptor`, `IpAddressProvider`, `QrCodeGenerator`
- `core:service`
  - `ForegroundServerService` Android service that hosts `LocalHttpServer`
  - `ServiceController` / `ServerRepositoryImpl` bridging domain and service
- `core:mvi`
  - Lightweight MVI contracts: `MviIntent`, `MviState`, `MviEffect`, `MviViewModel`, `Reducer`
- Feature modules
  - `feature:permissions`
    - Permissions MVI ViewModel & Compose UI to request media/storage
  - `feature:dashboard`
    - Dashboard ViewModel (MVI) orchestrating server state and QR code
    - Dashboard screen with server controls, QR, storage folder selection, and upload progress (`UploadProgressCard`)
  - `feature:filebrowser`
    - File browser UI for navigating local files
- `app`
  - `AirBridgeApplication` with Hilt setup
  - `MainActivity` with Compose + Navigation between permissions, dashboard, and file browser
  - Manifest with:
    - Main launcher activity
    - Foreground service declaration (`ForegroundServerService`)
    - `airbridge://pair` deep link for QR‑based browser pairing
- `web`
  - `build.gradle.kts` Android library to package `assets/index.html`
  - Standalone HTML/JS/CSS for the browser UI

What Works Today
----------------

- App launches into a permissions flow and requests the correct media/storage permissions for the current Android version.
- User can:
  - Select a SAF folder where uploads will be saved.
  - Start/stop the local HTTP server from the dashboard.
  - See the server IP and port.
  - Show a QR code and copy the full URL (including token) for browser access.
  - Browse files on the device via the file browser feature.
- Browser can:
  - Open `http://<phone-ip>:8080/` and load the SPA web UI.
  - See directory contents and download files from the phone.
  - Upload multiple files in parallel with progress bars.
- On the Android side:
  - Selected SAF folder is persisted across app restarts.
  - Uploaded files are written into the chosen SAF folder.
  - A per‑file upload progress card shows status (uploading/completed/error).
  - Foreground service uses a proper notification and special‑use foreground declaration for Android 14+.

Current Issues / Limitations
----------------------------

- Upload behavior
  - Live progress in the app is limited by NanoHTTPD buffering behavior; the browser shows true network progress, while the app currently tracks the server’s disk write into SAF, so the in‑app progress may feel delayed.
  - Large uploads (e.g. big videos) are sensitive to timeouts and device conditions; there are still edge cases where files can be truncated or the UI may briefly blank if the upload or write fails.
- Permissions
  - Flow is tuned for Play Store‑friendly media access (no MANAGE_EXTERNAL_STORAGE), so full arbitrary path access is intentionally limited to the selected SAF folder.
- Web pairing
  - Deep‑link based pairing (browser QR → app) is wired but not heavily hardened for all edge cases and browser/device combinations.
- Observability
  - Logging is focused on debugging; there is no structured telemetry or crash reporting service integrated yet.
- Testing
  - No unit tests, integration tests, or UI tests have been added; coverage is effectively 0%.

Recommended Improvements
------------------------

- Upload robustness
  - Implement a streaming upload pipeline that doesn’t rely on NanoHTTPD buffering a full temp file before passing control to Kotlin.
  - Add resumable or chunked uploads for very large files.
  - Improve error propagation so the app UI can clearly distinguish between network, timeout, and SAF write failures.
- UX / UI
  - Smooth out permission flows across OEMs and Android versions, with clearer in‑app explanations.
  - Refine the dashboard layout for very long upload lists (virtualized list, grouping, filters).
  - Improve web UI for mobile browsers and add dark‑mode toggling on the browser side.
- Architecture / Code Quality
  - Add proper unit tests for:
    - Use cases (domain)
    - Repositories (storage, network, service)
    - ViewModels (permissions, dashboard, file browser)
  - Add UI tests for the main flows: permissions → dashboard → start server → upload/download.
  - Harden `core:network` controllers with better input validation and more explicit error models.
- Tooling / Production Readiness
  - Add CI (GitHub Actions) for lint, tests, and release builds.
  - Integrate crash reporting (e.g. Firebase Crashlytics or Sentry).
  - Integrate dependency scanning (e.g. OWASP, Dependabot) and license checks.

Build & Run
-----------

Prerequisites:

- Android Studio (Hedgehog or newer) with AGP 9.x and JDK 17
- Android device or emulator on the same LAN as the browser client

Steps:

1. Sync Gradle:
   - Open the project in Android Studio and let it sync, or run:
     - `./gradlew :app:assembleDebug`
2. Install and run:
   - From Android Studio: Run the `app` configuration.
   - Or via CLI:
     - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Use the app:
   - Grant media/storage permissions.
   - Select a storage folder via “Select Storage Folder”.
   - Tap “Start Server” on the dashboard.
   - On your computer, open the shown URL (or scan the QR / use “Copy URL”) in a browser.
   - Use the web UI to browse, upload, and download files.

Security Notes
--------------

- The server only accepts LAN traffic (enforced by `SecurityInterceptor`).
- Access is protected by a random session token included in the URL.
- No data leaves your local network; there is no cloud backend.
