# AirBridge Test Structure & Guidelines

## Test Organization

```
├── core/
│   ├── network/src/test/kotlin/
│   │   └── com/swaran/airbridge/core/network/
│   │       ├── ktor/routes/
│   │       │   ├── ApiContractTest.kt          ✅ Verifies API constants
│   │       │   └── UploadRoutesWorkflowTest.kt ✅ HTTP workflow tests
│   │       └── upload/
│   │           └── UploadSchedulerTest.kt       (TODO)
│   ├── data/src/test/kotlin/
│   │   └── com/swaran/airbridge/core/data/
│   │       └── db/
│   │           └── entity/
│   │               └── UploadQueueEntityTest.kt ✅ Entity tests
│   ├── storage/src/test/kotlin/
│   │   └── com/swaran/airbridge/core/storage/
│   │       └── repository/
│   │           └── FileRepositoryIntegrationTest.kt ✅ Robolectric tests
│   └── common/src/test/kotlin/
│       └── (utility tests)
├── domain/src/test/kotlin/
│   └── com/swaran/airbridge/domain/
│       ├── model/
│       │   ├── UploadModelsTest.kt             ✅ State machine
│       │   ├── UploadStateMachineTest.kt         ✅ Transitions
│       │   ├── UploadStateMachineIntegrationTest.kt ✅ Workflows
│       │   ├── UploadResultTest.kt               ✅ Result sealed class
│       │   ├── UploadStatusTest.kt                 ✅ Status calculations
│       │   └── TransferStatusTest.kt              ✅ Transfer states
│       └── usecase/
│           ├── UploadStateManagerTest.kt          ✅ State management
│           └── UploadStateManagerIntegrationTest.kt ✅ Integration
├── feature/
│   └── dashboard/src/test/kotlin/
│       └── viewmodel/
│           └── DashboardViewModelTest.kt         (TODO)
└── web/src/test/js/
    └── uploadQueue.test.js                         ✅ Browser JS tests
```

## Test Types

### 1. Unit Tests
Test individual classes/methods in isolation.

**Location:** `src/test/kotlin/`

**Examples:**
- `UploadStateMachineTest` - State transition validation
- `UploadResultTest` - Result object creation
- `UploadQueueEntityTest` - Entity field validation

### 2. Integration Tests
Test complete workflows and component interactions.

**Location:** `src/test/kotlin/.../*IntegrationTest.kt`

**Examples:**
- `UploadStateManagerIntegrationTest` - Full upload workflows
- `UploadRoutesWorkflowTest` - HTTP endpoint workflows
- `FileRepositoryIntegrationTest` - File operations with Robolectric

### 3. Contract Tests
Verify API contracts between browser and server.

**Location:** `core/network/src/test/kotlin/.../ApiContractTest.kt`

**Checks:**
- Query parameter names
- JSON field names
- State value strings

### 4. Browser Tests
Test browser-side JavaScript logic.

**Location:** `web/src/test/js/`

**Examples:**
- `uploadQueue.test.js` - Queue operations
- `sseHandler.test.js` - SSE event handling
- `resumeLogic.test.js` - Resume workflow

## Adding New Tests

### Step 1: Choose Test Type

Ask: What am I testing?

- **Single class logic** → Unit Test
- **Multiple components working together** → Integration Test
- **Browser/Server agreement** → Contract Test
- **JavaScript behavior** → Browser Test

### Step 2: Create Test File

**Naming:**
```kotlin
// For class FooBar
FooBarTest.kt          // Unit tests
FooBarIntegrationTest.kt // Integration tests
```

**Package:**
```kotlin
// Match source package structure
package com.swaran.airbridge.domain.model
```

### Step 3: Write Test

**Template:**
```kotlin
class MyComponentTest {

    @Test
    fun `should do X when Y`() {
        // Given
        val input = ...
        
        // When
        val result = component.doSomething(input)
        
        // Then
        assertEquals(expected, result)
    }

    @Test
    fun `edge case - empty input`() {
        // Test edge case
    }

    @Test
    fun `error case - invalid input`() {
        // Test error handling
    }
}
```

### Step 4: Run Tests

```bash
# Single test
./gradlew :domain:test --tests "MyComponentTest"

# All tests
./gradlew test

# With coverage
./gradlew test jacocoTestReport
```

## Test Patterns

### State Machine Tests
```kotlin
@Test
fun `valid transition should succeed`() {
    assertTrue(UploadState.NONE.canTransitionTo(UploadState.UPLOADING))
}

@Test
fun `invalid transition should fail`() {
    assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.UPLOADING))
}
```

### Workflow Tests
```kotlin
@Test
fun `upload pause resume workflow`() {
    // 1. Initialize
    stateManager.initialize(metadata)
    
    // 2. Start upload
    stateManager.transition(uploadId, UploadState.UPLOADING)
    
    // 3. Pause
    stateManager.transition(uploadId, UploadState.PAUSING)
    stateManager.transition(uploadId, UploadState.PAUSED)
    
    // 4. Resume
    stateManager.transition(uploadId, UploadState.RESUMING)
    
    // Verify state
    assertEquals(UploadState.RESUMING, stateManager.getStatus(uploadId)?.state)
}
```

### Robolectric Tests
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidComponentTest {
    
    @Test
    fun `component works with Android context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = MyComponent(context)
        
        assertNotNull(component)
    }
}
```

## Test Checklist

Before submitting new tests:

- [ ] Test compiles
- [ ] Test passes
- [ ] Test covers happy path
- [ ] Test covers edge cases
- [ ] Test covers error cases
- [ ] Test name is descriptive
- [ ] Test uses given/when/then structure
- [ ] No hardcoded values (use constants)

## Missing Tests (TODO)

### Priority: High
- [ ] `UploadSchedulerTest` - Test pause/resume/cancel logic
- [ ] `UploadQueueManagerTest` - Test queue operations
- [ ] `FileRepositoryTest` - Test without Robolectric (mocked)

### Priority: Medium
- [ ] `DashboardViewModelTest` - MVI pattern tests
- [ ] `FileBrowserViewModelTest` - File browser logic
- [ ] `PairingRoutesTest` - Pairing endpoint tests

### Priority: Low
- [ ] `ThermalMonitorTest` - Thermal throttling tests
- [ ] `NetworkMonitorTest` - Network state tests
- [ ] `StorageRepositoryTest` - Storage operations

## Running Tests

### Quick Commands

```bash
# All tests
./gradlew test

# Specific module
./gradlew :domain:test
./gradlew :core:network:test
./gradlew :core:storage:test

# Specific test class
./gradlew :domain:test --tests "UploadStateMachineTest"

# Integration tests only
./gradlew :domain:test --tests "*IntegrationTest*"

# With scripts
./scripts/check.sh
./scripts/run_all_tests.sh
./scripts/validate_api.sh
```

### Continuous Integration

```yaml
# .github/workflows/test.yml
name: Tests
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      - run: ./gradlew test
```

## Test Coverage

Current coverage by module:

| Module | Coverage | Notes |
|--------|----------|-------|
| domain | ~85% | Good state machine coverage |
| network | ~40% | Need more route tests |
| data | ~30% | Need DAO tests |
| storage | ~50% | Robolectric limited |
| common | ~60% | Utility tests needed |

Target: 80% overall

---

**See also:**
- [TEST_SUITE_README.md](../TEST_SUITE_README.md) - Running tests
- [scripts/README.md](../scripts/README.md) - Script documentation
