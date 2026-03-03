# AirBridge Pre-Push Checklist

Before pushing code, run: `./scripts/check.sh`

## Quick Reference

```bash
# Complete quality check (recommended)
./scripts/check.sh

# With verbose output
./scripts/check.sh --verbose

# CI/CD mode (fail fast)
./scripts/check.sh --ci
```

## What Gets Checked

| Category | Checks |
|----------|--------|
| **API Contract** | Query params, field names, browser/server alignment |
| **State Machine** | Valid transitions, terminal states |
| **Code Quality** | Hardcoded strings, TODOs, println |
| **Tests** | Unit tests, integration tests |
| **Lint** | Detekt (if configured), ktlint |
| **Build** | Debug compilation |
| **Docs** | API_SPEC currency |

## Exit Codes

- `0` ✅ Safe to push
- `1` ❌ Fix failures first

## Common Warnings

| Warning | Meaning | Action |
|---------|---------|--------|
| Hardcoded strings | Using raw strings instead of constants | Use `QueryParams.UPLOAD_ID` etc. |
| Detekt not configured | Static analysis tool missing | Optional - can be added later |
| Validation script | Script location changed | Update path if needed |

## CI/CD Usage

```yaml
# .github/workflows/quality.yml
name: Quality Check
on: [push, pull_request]
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      - run: ./scripts/check.sh --ci
```

## Troubleshooting

**"Permission denied"**
```bash
chmod +x scripts/check.sh
```

**Gradle daemon issues**
```bash
./gradlew --stop
./scripts/check.sh
```

**Slow tests**
```bash
# Run only critical checks
./scripts/validate_api.sh
./gradlew :domain:test
```

## Related Documentation

- [TEST_SUITE_README.md](TEST_SUITE_README.md) - Test documentation
- [API_SPEC.md](API_SPEC.md) - API contract
- [scripts/README.md](scripts/README.md) - Script documentation
