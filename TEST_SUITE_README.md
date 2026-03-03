# AirBridge Test Suite Documentation

## Quick Start

Run the complete test suite:
```bash
./run_all_tests.sh
```

Run individual test layers:
```bash
# Unit tests only
./gradlew test

# Integration tests
./gradlew test --tests "*IntegrationTest*"

# API contract validation
./validate_api.sh

# JavaScript tests (browser code)
cd web/src/test/js && npm test
```

---

## Test Suite Architecture

### 1. Unit Tests (`domain/src/test/`)
**Purpose:** Test individual components in isolation

**Coverage:**
- `UploadStateMachineTest` - State transition validation
- `UploadStateManagerTest` - State management logic
- `UploadResultTest` - Result object handling
- `TransferStatusTest` - Status calculations

**Run:** `./gradlew :domain:test`

---

### 2. Integration Tests (`domain/src/test/.../UploadStateManagerIntegrationTest.kt`)
**Purpose:** Test complete workflows and scenarios

**Coverage:**
- ✅ Full workflows (upload → pause → resume → complete)
- ✅ Multi-file scenarios
- ✅ State machine edge cases
- ✅ Bug scenarios that manual testing found

**Scenarios Tested:**
- Direct upload start (NONE → UPLOADING)
- Pause/resume cycle
- Cancel from paused
- Resume deadline expiration
- Rapid state toggling
- Multi-file independent states

**Run:** `./gradlew :domain:test --tests "*IntegrationTest*"`

---

### 3. API Contract Tests (`core/network/src/test/.../ApiContractTest.kt`)
**Purpose:** Verify browser/server API contract

**Coverage:**
- Query parameter names (`id=` vs `uploadId=`)
- JSON response field names (`status` vs `state`)
- State value strings (`"paused"`, `"resuming"`, etc.)

**Critical Checks:**
```kotlin
@Test
fun `query params - UPLOAD_ID must be id not uploadId`() {
    assertEquals("id", QueryParams.UPLOAD_ID)
    // Prevents: Browser sends ?uploadId= but server expects ?id=
}

@Test
fun `all state values match expected strings`() {
    assertEquals("paused", UploadState.PAUSED.value)
    // Prevents: Server sends "paused" but browser checks for "PAUSED"
}
```

**Run:** `./gradlew :core:network:test --tests "*ApiContractTest*"`

---

### 4. Browser JavaScript Tests (`web/src/test/js/`)
**Purpose:** Test browser-side upload logic

**Setup:**
```bash
cd web/src/test/js
npm install
npm test
```

**Coverage:**
- UploadQueue operations (add, pause, resume, cancel)
- SSE event handlers
- Multi-file state management
- Bug scenarios:
  - Resume handler order bug
  - Pause without XHR abort
  - Cancel cleanup

**Example Test:**
```javascript
test('should FAIL when state is RESUMING (bug scenario)', () => {
  item.state = 'resuming';
  const result = queue.resume('test-1');
  expect(result).toBe(false); // Catches the bug!
});
```

---

### 5. File System Tests (`core/storage/src/test/.../`)
**Purpose:** Test actual file operations

**Coverage:**
- Path construction (trailing slash handling)
- File upload with append (resume)
- File deletion (cancel)
- File not found scenarios

**Note:** Uses Robolectric for Android framework mocking

**Run:** `./gradlew :corestorage:test`

---

### 6. API Validation Script (`validate_api.sh`)
**Purpose:** Automated contract validation

**Checks:**
- Server QueryParams constants
- Browser URL parameter usage
- Response field names
- State machine transitions
- SSE handler implementations

**Run:** `./validate_api.sh`

---

## Test Scenarios Matrix

| Scenario | Unit | Integration | Contract | JS | File System |
|----------|------|-------------|----------|-----|-------------|
| State transitions | ✅ | ✅ | ✅ | ✅ | ❌ |
| Pause/resume | ❌ | ✅ | ❌ | ✅ | ❌ |
| Cancel/delete file | ❌ | ✅ | ❌ | ✅ | ✅ |
| Multi-file upload | ❌ | ✅ | ❌ | ✅ | ❌ |
| Resume deadline | ❌ | ✅ | ❌ | ❌ | ❌ |
| API field names | ❌ | ❌ | ✅ | ❌ | ❌ |
| Path construction | ❌ | ❌ | ❌ | ❌ | ✅ |
| Browser event handling | ❌ | ❌ | ❌ | ✅ | ❌ |

---

## CI/CD Integration

### Pre-Commit Hook
```bash
#!/bin/bash
# .git/hooks/pre-commit

./validate_api.sh || exit 1
./gradlew test --quiet || exit 1
```

### GitHub Actions
```yaml
name: Test Suite
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Run Full Test Suite
        run: ./run_all_tests.sh
      
      - name: Upload Coverage
        uses: codecov/codecov-action@v2
```

---

## Adding New Tests

### For New Features
1. Add unit tests for isolated logic
2. Add integration tests for workflows
3. Update API contract tests if fields change
4. Update API_SPEC.md documentation

### For Bug Fixes
1. Create reproduction test that FAILS before fix
2. Apply fix
3. Verify test PASSES
4. Keep test in suite to prevent regression

**Example:**
```kotlin
// Bug: Resume deadline watcher never started
@Test
fun `resume starts deadline watcher`() {
    // Setup
    stateManager.initialize(metadata)
    stateManager.transition(uploadId, UploadState.PAUSED)
    
    // Resume
    stateManager.transition(uploadId, UploadState.RESUMING)
    
    // Verify watcher is scheduled
    // (implementation depends on test framework)
}
```

---

## Coverage Goals

| Layer | Target | Current |
|-------|--------|---------|
| Unit Tests | 80% | ~75% |
| Integration Tests | 100% workflows | ~60% |
| API Contract | 100% endpoints | 100% |
| Browser JS | 70% | ~40% (new) |
| File System | 60% | ~30% |

---

## Regression Prevention

### What Tests Catch

**Dependency Upgrades:**
- Kotlin version updates → State machine still works
- Ktor version updates → API routes still respond correctly
- Android SDK updates → File operations still work

**Code Changes:**
- Refactoring UploadScheduler → Integration tests verify behavior
- Changing query params → Contract tests fail
- Modifying state machine → State tests fail

**Browser Changes:**
- Updating index.html → JS tests verify queue logic
- Changing event handlers → SSE handler tests fail

---

## Debugging Failed Tests

### State Machine Issues
```bash
# Run with verbose logging
./gradlew :domain:test --info | grep -E "transition|state"
```

### API Contract Issues
```bash
# Check field names
./validate_api.sh -v
```

### File System Issues
```bash
# Run specific test with logging
./gradlew :core:storage:test --tests "*FileRepository*" --info
```

---

## Test Maintenance

### Monthly Tasks
1. Review test coverage reports
2. Update tests for new features
3. Add tests for recently found bugs
4. Remove obsolete tests

### Before Release
1. Run full test suite
2. Verify all E2E scenarios manually
3. Update API_SPEC.md if changes
4. Run on physical devices (not just emulator)

---

## Resources

- **API_SPEC.md** - Complete API contract documentation
- **TESTING_STRATEGY.md** - Why tests failed initially
- **STATUS_AUDIT.md** - State transition analysis
- **FIXES_SUMMARY.md** - All bugs with line numbers

---

**END OF DOCUMENTATION**
