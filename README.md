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

AirBridge follows **Clean Architecture** principles within a highly modularized structure, ensuring a clear separation of concerns and a testable codebase.

- **`domain`** (Pure Kotlin): Core business logic, entities (`FileItem`, `SessionInfo`), and Use Cases.
- **`core:mvi`**: A lightweight, reactive MVI (Model-View-Intent) framework for predictable state management.
- **`core:network`**: Embedded HTTP server (NanoHTTPD) with custom controllers for streaming, range-based resumable uploads, and downloads.
- **`core:storage`**: Intelligent storage abstraction that handles chunked, cancellable I/O across both `MediaStore` and SAF.
- **`core:service`**: Manages the server lifecycle as a high-priority foreground process with CPU protection.
- **`feature:*`**: Decoupled UI modules (Dashboard, File Browser, Permissions) using **Jetpack Compose** and **Hilt**.
- **`web`**: A modern, single-page web application served directly from the device assets.

Tech Stack & Best Practices
---------------------------

- **Language**: 100% Kotlin with Coroutines and Flows for asynchronous excellence.
- **UI**: Jetpack Compose with **Immutable Collections** (`kotlinx-collections-immutable`) to eliminate unnecessary recompositions.
- **Architecture**: Multi-module Clean Architecture + MVI.
- **Dependency Injection**: Hilt (Dagger) for robust and scalable DI.
- **Platform**: Targets API 36, leveraging the latest Android security and performance APIs.
- **Principles**: Strictly adheres to **SOLID** principles and modern Android development patterns.

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

- **Local Only**: All traffic is restricted to your local network via a `SecurityInterceptor`.
- **Session Auth**: Every connection requires a unique, randomly generated session token.
- **No Cloud**: Your files never touch a server outside your own device and computer.

Known Issues
------------

- **Resume not working correctly**: File resume functionality is currently unreliable. When resuming a paused upload, the transfer may restart from the beginning instead of continuing from the paused position. This is being actively investigated.
