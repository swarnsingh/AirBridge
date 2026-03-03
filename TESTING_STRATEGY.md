# AirBridge Testing Strategy - Post-Mortem Analysis

**Date:** 2026-03-02  
**Purpose:** Analyze why unit tests failed to catch critical bugs, and define proper testing strategy

---

## Executive Summary

**The unit tests were inadequate because:**
1. They tested components in isolation, not workflows
2. They didn't verify browser/server contracts
3. They mocked dependencies, hiding integration failures
4. They lacked end-to-end scenario coverage
5. No contract tests for API field names

**Critical bugs that manual testing found:**
| Bug | Component | Test Gap |
|-----|-----------|----------|
| NONE→UPLOADING rejected | State machine | No direct upload start test |
| Resume deadline never ran | Scheduler | No deadline watcher test |
| Status field mismatch (state vs status) | API | No JSON field contract test |
| Browser resume handler broken | Browser JS | No browser code tests at all |
| Cancel path double-slash | File path | No file deletion path test |

---

## 1. Test Coverage Analysis

### 1.1 Existing Tests (What We Had)

**`UploadStateMachineTest.kt`** - 225 lines
- Tests individual state transitions (NONE→QUEUED, QUEUED→UPLOADING, etc.)
- Tests terminal states
- Tests idempotent transitions

**What it missed:**
- ❌ Direct upload start (NONE→UPLOADING was missing initially)
- ❌ Full workflow scenarios (upload → pause → resume → complete)
- ❌ Rapid state toggling
- ❌ Multi-file scenarios
- ❌ Deadline expiration
- ❌ Cancel from non-uploading states

**`UploadStateManagerTest.kt`** - 443 lines
- Tests state manager operations
- Tests progress tracking
- Tests flow emissions
- Tests recovery scenarios

**What it missed:**
- ❌ Integration with UploadScheduler
- ❌ Actual coroutine cancellation behavior
- ❌ Concurrent multi-upload scenarios
- ❌ State persistence across "app restart"

### 1.2 What We Needed (Integration Tests)

**Full Workflow Tests:**
```kotlin
@Test
fun `full workflow - upload pause resume complete`() {
    // Upload → Pause → Resume → Complete
    // Verifies: state transitions, progress preservation, terminal state
}
```

**API Contract Tests:**
```kotlin
@Test
fun `status endpoint - must return status field not state`() {
    // Would have caught: server sending "state" instead of "status"
}
```

**Browser/Server Contract Tests:**
```kotlin
@Test
fun `browser receives RESUMING - should POST within deadline`() {
    // Simulates: SSE event → Browser POST → Server UPLOADING
}
```

**Multi-File Scenario Tests:**
```kotlin
@Test
fun `multi-file - pause one while others continue`() {
    // Verifies: independent state per upload
}
```

---

## 2. Why Each Bug Slipped Through

### Bug 1: NONE→UPLOADING Rejected

**The Problem:**
```kotlin
// UploadModels.kt (original)
NONE -> target in setOf(QUEUED, CANCELLED)  // UPLOADING was missing!
```

**Why Tests Didn't Catch It:**
- Existing test: `none can transition to queued or cancelled` - Only tested QUEUED
- Missing test: `direct upload start - NONE to UPLOADING` - Would have caught it

**The Fix:**
```kotlin
NONE -> target in setOf(QUEUED, UPLOADING, CANCELLED)  // Added UPLOADING
```

**Test Added:**
```kotlin
@Test
fun `transition allows direct path - none to uploading`() {
    // Verifies NONE → UPLOADING is valid
}
```

---

### Bug 2: Resume Deadline Never Ran

**The Problem:**
```kotlin
// UploadScheduler.kt (original)
val job = activeJobs[uploadId]
if (job != null) {  // ❌ Always false when resume() called!
    applicationScope.launch { checkResumeDeadline(uploadId) }
}
```

**Why Tests Didn't Catch It:**
- No test for `resume()` function at all
- No test for deadline watcher behavior
- Unit tests mocked coroutine scope

**The Fix:**
```kotlin
// Always start deadline watcher
applicationScope.launch { checkResumeDeadline(uploadId) }
```

**Test Needed (but hard to unit test):**
```kotlin
@Test
fun `resume starts deadline watcher`() {
    // Requires: TestCoroutineDispatcher, time control
    // Verifies: watcher runs even without active job
}
```

---

### Bug 3: Status Field Mismatch ("state" vs "status")

**The Problem:**
```kotlin
// UploadRoutes.kt (original)
put("state", status.state)  // ❌ Browser checks status.status!
```

**Why Tests Didn't Catch It:**
- No API contract tests
- No JSON schema validation
- No browser integration tests
- Unit tests only checked internal state, not HTTP response

**The Fix:**
```kotlin
put("status", status.state)  // ✅ Matches browser expectation
```

**Test That Would Have Caught It:**
```kotlin
@Test
fun `status endpoint - must return status field not state`() {
    val response = client.get("/api/upload/status?...")
    val json = Json.parseToJsonElement(response.bodyAsText())
    
    assertTrue(json.jsonObject.containsKey("status"))  // Must have
    assertFalse(json.jsonObject.containsKey("state"))  // Must NOT have
}
```

---

### Bug 4: Browser Resume Handler Broken

**The Problem:**
```javascript
// index.html (original)
} else if (data.state === 'resuming') {
    item.state = 'resuming';  // ❌ Changed state first!
    uploadQueue.resume(data.uploadId);  // Returns immediately - state != 'paused'
}
```

**Why Tests Didn't Catch It:**
- **NO BROWSER CODE TESTS AT ALL**
- JavaScript is not tested in Android project
- No end-to-end tests with real browser

**The Fix:**
```javascript
} else if (data.state === 'resuming') {
    if (item.state === 'paused' && !item.xhr) {  // Check first
        uploadQueue.resume(data.uploadId);  // Then resume
    }
    item.state = 'resuming';  // Then update UI
}
```

**Testing Gap:**
- Android unit tests can't test JavaScript
- Need: WebDriver/Selenium tests OR JS unit tests (Jest/Mocha)

---

### Bug 5: Cancel Path Double-Slash

**The Problem:**
```kotlin
// Log showed: "Deleting partial file: //Promo-2.mp4"
// Path was "/" + "/" + filename = "//filename"
```

**Why Tests Didn't Catch It:**
- No file path validation tests
- Mocked storage repository in tests
- No integration with real file system

**The Fix:**
```kotlin
val cleanPath = request.path.trimEnd('/')
```

**Test Needed:**
```kotlin
@Test
fun `cleanupPartial handles root path correctly`() {
    // Given: path = "/", filename = "test.txt"
    // When: cleanupPartial called
    // Then: should find file at root, not at "//"
}
```

---

## 3. Proper Testing Strategy Going Forward

### 3.1 Test Pyramid for AirBridge

```
           /\
          /  \
         / E2E \      <- Selenium/WebDriver tests (browser + server)
        /--------\
       /          \
      / Integration \   <- API contract tests, workflow tests
     /--------------\
    /                \
   /    Unit Tests     \  <- State machine, business logic
  /----------------------\
```

**Current State:**
- ✅ Unit tests exist (bottom layer)
- ❌ Missing integration tests (middle layer)
- ❌ Missing E2E tests (top layer)

### 3.2 Required Test Layers

#### Layer 1: Unit Tests (We Have These)
- State machine transitions
- Individual use cases
- Repository operations with mocks

**Coverage Goal:** 80%+ code coverage

#### Layer 2: Integration Tests (NEEDED)

**API Contract Tests:**
```kotlin
class UploadApiContractTest {
    @Test
    fun `all endpoints use correct query parameter names`()
    
    @Test
    fun `status endpoint returns status field not state`()
    
    @Test
    fun `SSE events use correct field names`()
    
    @Test
    fun `upload POST returns correct response structure`()
}
```

**Workflow Tests:**
```kotlin
class UploadWorkflowTest {
    @Test
    fun `upload pause resume complete workflow`()
    
    @Test
    fun `upload cancel workflow`()
    
    @Test
    fun `resume deadline expiration workflow`()
    
    @Test
    fun `multi-file mixed state workflow`()
}
```

**Coverage Goal:** All user scenarios

#### Layer 3: E2E Tests (NEEDED)

**Browser Automation Tests:**
```javascript
// Using Playwright or Selenium
describe('AirBridge E2E', () => {
    test('upload file and verify appears in file list', async () => {
        // 1. Open browser, scan QR, pair
        // 2. Select file, upload
        // 3. Verify progress
        // 4. Verify completion
        // 5. Verify file in list
    });
    
    test('pause resume file upload', async () => {
        // 1. Start upload
        // 2. Pause at 30%
        // 3. Verify paused
        // 4. Resume
        // 5. Verify continues from 30%
    });
});
```

**Coverage Goal:** Critical user journeys

### 3.3 Contract Testing (CRITICAL)

Create a contract file that both browser and server tests verify against:

**`api-contract.json`:**
```json
{
  "endpoints": {
    "/api/upload/status": {
      "method": "GET",
      "queryParams": ["token", "id", "filename"],
      "responseFields": ["exists", "bytesReceived", "status", "canResume", "isBusy"]
    },
    "/api/upload": {
      "method": "POST", 
      "queryParams": ["token", "filename", "id"],
      "responseFields": ["success", "uploadId", "bytesReceived", "state"]
    }
  }
}
```

**Contract Test:**
```kotlin
@Test
fun `server conforms to api-contract.json`() {
    val contract = loadContract()
    for (endpoint in contract.endpoints) {
        val response = callEndpoint(endpoint)
        assertHasAllFields(response, endpoint.responseFields)
    }
}
```

---

## 4. Edge Cases That Need Testing

### 4.1 File Operations
- [ ] Upload file, rename during upload
- [ ] Upload file, delete during upload
- [ ] Upload file with same name as existing
- [ ] Upload file with special characters in name
- [ ] Upload 0-byte file
- [ ] Upload 10GB+ file
- [ ] Upload to full storage

### 4.2 Network Conditions
- [ ] WiFi drops during upload
- [ ] WiFi restored, resume upload
- [ ] Very slow network (throttle to 56kbps)
- [ ] Upload over mobile data (not WiFi)

### 4.3 App Lifecycle
- [ ] App killed during upload, restart, resume
- [ ] Phone locked during upload
- [ ] Phone reboot during upload
- [ ] Storage permission revoked during upload

### 4.4 Browser Scenarios
- [ ] Browser refresh during upload
- [ ] Browser closed during upload, reopen
- [ ] Multiple browser tabs uploading
- [ ] Browser offline, then online

### 4.5 Multi-File Scenarios
- [ ] 10 files uploading simultaneously
- [ ] Pause 3 of 5 uploads
- [ ] Cancel 2 of 5 uploads
- [ ] Resume all paused at once

---

## 5. Test Automation Checklist

### 5.1 Pre-Commit Hooks
- [ ] Unit tests pass
- [ ] Lint checks pass
- [ ] Contract validation passes

### 5.2 CI/CD Pipeline
- [ ] Run unit tests on every PR
- [ ] Run integration tests before merge
- [ ] Run E2E tests nightly
- [ ] Generate coverage report

### 5.3 Release Gates
- [ ] All E2E tests pass
- [ ] Manual QA sign-off on critical journeys
- [ ] Performance benchmarks met

---

## 6. Files Created for Better Testing

| File | Purpose |
|------|---------|
| `UploadStateManagerIntegrationTest.kt` | Full workflow tests |
| `UploadRoutesIntegrationTest.kt` | API contract tests |
| `validate_api.sh` | Automated contract validation |
| `API_SPEC.md` | Human-readable API contract |
| `STATUS_AUDIT.md` | State transition analysis |
| `FIXES_SUMMARY.md` | All bugs with line numbers |

---

## 7. Recommendations

### Immediate Actions
1. ✅ **DONE** - Created integration tests
2. ✅ **DONE** - Created contract validation script
3. ✅ **DONE** - Fixed all discovered bugs
4. **TODO** - Add contract tests to CI
5. **TODO** - Add JS unit tests for browser code
6. **TODO** - Add E2E tests with browser automation

### Process Changes
1. **Definition of Done:** Must include integration tests for new features
2. **Code Review:** Check for API contract changes
3. **Release Testing:** Run E2E tests before release
4. **Monitoring:** Log API mismatches in production

### Tooling Needed
1. **Ktor Test Framework:** For HTTP API testing
2. **Playwright/Selenium:** For browser automation
3. **Jest:** For JavaScript unit tests
4. **Contract Testing Tool:** Like Pact or custom validator

---

## 8. Conclusion

**The unit tests were not wrong - they were incomplete.**

They tested that individual gears turn, but not that the machine works.

**Going forward:**
- Unit tests for logic ✅
- Integration tests for workflows ⭐ NEW
- Contract tests for API compatibility ⭐ NEW  
- E2E tests for critical journeys ⭐ NEW

**Run before every release:**
```bash
./gradlew test                    # Unit tests
./validate_api.sh                 # Contract validation
./gradlew integrationTest         # Workflow tests  
./gradlew e2eTest                 # Browser automation
```

---

**END OF ANALYSIS**
