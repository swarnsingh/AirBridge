# AirBridge Scripts

Quality assurance and development scripts for the AirBridge project.

## Main Script

### `check.sh` - Complete Quality Check

**One script to validate everything before pushing code.**

```bash
./scripts/check.sh
```

**Features:**
- ✅ API Contract Validation (query params, field names)
- ✅ State Machine Validation (transitions, terminal states)
- ✅ Code Quality Checks (hardcoded strings, TODOs)
- ✅ Unit Tests (domain, network, common)
- ✅ Integration Tests (workflows, scenarios)
- ✅ Browser JavaScript Tests (queue logic, SSE handlers)
- ✅ Lint & Static Analysis (detekt, ktlint if available)
- ✅ Build Verification (assembleDebug)
- ✅ Documentation Checks (API_SPEC currency)

**Options:**
```bash
./scripts/check.sh --verbose    # Show full test output
./scripts/check.sh --ci         # Fail fast mode for CI/CD
```

**Exit Codes:**
- `0` - All checks passed, code is ready to push
- `1` - Critical failures found, do not push

## Supporting Scripts

### `validate_api.sh` - API Contract Validation

Validates browser/server API contract alignment.

```bash
./scripts/validate_api.sh
```

Checks:
- Query parameter constants match browser usage
- Response field names are correct
- State machine transitions are valid
- SSE handlers are implemented in browser

### `run_all_tests.sh` - Test Suite Runner

Runs all test layers.

```bash
./scripts/run_all_tests.sh
```

Runs:
1. Unit tests
2. Integration tests
3. API contract tests
4. API validation script

## CI/CD Integration

### GitHub Actions

```yaml
name: Quality Check
on: [push, pull_request]

jobs:
  quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Run Quality Check
        run: ./scripts/check.sh --ci
```

### Pre-Commit Hook

```bash
#!/bin/bash
# .git/hooks/pre-commit

if ! ./scripts/check.sh --ci; then
    echo "Quality check failed. Fix issues before committing."
    exit 1
fi
```

### Gradle Integration

```kotlin
// build.gradle.kts
tasks.register<Exec>("qualityCheck") {
    group = "verification"
    description = "Run complete quality check"
    commandLine("./scripts/check.sh")
}
```

## Browser JavaScript Tests

The `check.sh` script includes a check for browser-side JavaScript tests. These tests verify:
- UploadQueue operations (add, pause, resume, cancel)
- SSE event handlers
- Multi-file state management
- Bug scenarios (resume handler order, etc.)

### Setup Browser Tests

```bash
cd web/src/test/js
npm install
npm test
```

### Browser Tests in CI

Add to your GitHub Actions:
```yaml
- name: Set up Node.js
  uses: actions/setup-node@v3
  with:
    node-version: '18'
    
- name: Install JS dependencies
  run: cd web/src/test/js && npm ci
  
- name: Run browser tests
  run: cd web/src/test/js && npm test
```

## Quick Reference

| Script | Purpose | Run Time | Critical? |
|--------|---------|----------|-----------|
| `check.sh` | Complete validation | 2-3 min | ✅ Yes |
| `validate_api.sh` | API contract only | 2 sec | ✅ Yes |
| `run_all_tests.sh` | Tests only | 1-2 min | ✅ Yes |

## When to Run

### Before Every Commit
```bash
./scripts/check.sh
```

### Before Every Push
```bash
./scripts/check.sh --verbose
```

### In CI/CD Pipeline
```bash
./scripts/check.sh --ci
```

### After Dependency Updates
```bash
./scripts/check.sh --verbose
```

## Adding New Checks

To add a new check to `check.sh`:

1. Add a new section in the appropriate category
2. Use `log_check "Check Name" "PASS"` or `log_check "Check Name" "FAIL" "details"`
3. Test the check passes and fails correctly

Example:
```bash
echo -e "${CYAN}▶ Checking new requirement...${NC}"
if some_condition; then
    log_check "New requirement met" "PASS"
else
    log_check "New requirement" "FAIL" "Details of failure"
fi
```

## Troubleshooting

### Script fails with "Permission denied"
```bash
chmod +x scripts/check.sh
```

### Gradle not found
Make sure you're in the project root directory when running scripts.

### Tests fail but code works
- Check if tests are up to date with recent changes
- Run with `--verbose` to see full error messages
- Check if API_SPEC.md needs updating

### False positives in code quality checks
Some checks may be overly strict. Review the warning and suppress if necessary:
```kotlin
@Suppress("SomeRule")
fun intentionallyDoingThis() { }
```

## Maintenance

- Update checks when adding new features
- Remove obsolete checks when removing features
- Keep exit codes consistent (0 = success, 1 = failure)
- Document all new checks in this README

---

**For detailed testing documentation, see [TEST_SUITE_README.md](../TEST_SUITE_README.md)**

**For API contract documentation, see [API_SPEC.md](../API_SPEC.md)**
