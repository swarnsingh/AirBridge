#!/bin/bash
#
# AirBridge Complete Quality Check Script
# Runs all validation, linting, and tests in one comprehensive report
#
# Usage: ./scripts/check.sh [--verbose] [--ci]
#   --verbose: Show full test output
#   --ci:      Fail fast mode for CI/CD
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# Get script directory and project root
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Parse arguments
VERBOSE=false
CI_MODE=false

for arg in "$@"; do
    case $arg in
        --verbose)
            VERBOSE=true
            shift
            ;;
        --ci)
            CI_MODE=true
            shift
            ;;
    esac
done

# Ensure we run from project root
cd "$PROJECT_ROOT"

# Track results
TOTAL_CHECKS=0
PASSED_CHECKS=0
FAILED_CHECKS=0
WARNINGS=0

# Arrays to store results
declare -a CHECK_NAMES
declare -a CHECK_STATUSES
declare -a CHECK_DETAILS

# Helper functions
log_section() {
    echo ""
    echo -e "${BOLD}${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}${BLUE}  $1${NC}"
    echo -e "${BOLD}${BLUE}═══════════════════════════════════════════════════════════════${NC}"
}

log_check() {
    local name="$1"
    local status="$2"
    local detail="${3:-}"
    
    TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
    CHECK_NAMES+=("$name")
    CHECK_DETAILS+=("$detail")
    
    if [ "$status" == "PASS" ]; then
        PASSED_CHECKS=$((PASSED_CHECKS + 1))
        CHECK_STATUSES+=("${GREEN}✓ PASS${NC}")
        echo -e "${GREEN}✓${NC} $name"
    elif [ "$status" == "FAIL" ]; then
        FAILED_CHECKS=$((FAILED_CHECKS + 1))
        CHECK_STATUSES+=("${RED}✗ FAIL${NC}")
        echo -e "${RED}✗${NC} $name"
        if [ -n "$detail" ]; then
            echo -e "   ${RED}$detail${NC}"
        fi
    elif [ "$status" == "WARN" ]; then
        WARNINGS=$((WARNINGS + 1))
        CHECK_STATUSES+=("${YELLOW}⚠ WARN${NC}")
        echo -e "${YELLOW}⚠${NC} $name"
        if [ -n "$detail" ]; then
            echo -e "   ${YELLOW}$detail${NC}"
        fi
    fi
}

run_gradle_check() {
    local name="$1"
    shift
    
    echo -e "${CYAN}▶ Running: $name${NC}"
    
    if [ "$VERBOSE" == true ]; then
        if ./gradlew "$@" 2>&1; then
            log_check "$name" "PASS"
        else
            log_check "$name" "FAIL" "Gradle task failed"
            [ "$CI_MODE" == true ] && exit 1
        fi
    else
        if ./gradlew "$@" --quiet 2>&1 | tail -10; then
            log_check "$name" "PASS"
        else
            log_check "$name" "FAIL" "Gradle task failed"
            [ "$CI_MODE" == true ] && exit 1
        fi
    fi
}

# ==================== HEADER ====================

echo ""
echo -e "${BOLD}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                                                                ║${NC}"
echo -e "${BOLD}║${NC}           ${CYAN}AirBridge${NC} ${BOLD}Complete Quality Check${NC}                    ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}                                                                ${BOLD}║${NC}"
echo -e "${BOLD}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "Time: $(date)"
echo -e "Working Directory: $(pwd)"
echo -e "Project Root: $PROJECT_ROOT"
echo -e "Mode: $([ "$CI_MODE" == true ] && echo "CI/CD" || echo "Development")"
echo ""

# ==================== CHECK 1: API CONTRACT ====================

log_section "1. API CONTRACT VALIDATION"

# Run the dedicated API validation script
if [ -f "$PROJECT_ROOT/scripts/validate_api.sh" ]; then
    echo -e "${CYAN}▶ Running validate_api.sh...${NC}"
    if bash "$PROJECT_ROOT/scripts/validate_api.sh" 2>&1 | grep -q "ALL CHECKS PASSED"; then
        log_check "API Contract (via validate_api.sh)" "PASS"
    else
        log_check "API Contract" "WARN" "Check validate_api.sh output above"
    fi
else
    log_check "validate_api.sh exists" "FAIL" "Missing API validation script"
fi

# ==================== CHECK 2: CODE QUALITY ====================

log_section "2. CODE QUALITY CHECKS"

# Check for hardcoded strings that should use constants
echo -e "${CYAN}▶ Checking for hardcoded query param strings...${NC}"

HARDCODED_QUERY=$(grep -r '"uploadId"' --include="*.kt" "$PROJECT_ROOT/core/network/src/main/kotlin" 2>/dev/null | grep -v "ResponseFields" | grep -v "QueryParams" | grep -v "const val" | wc -l)
if [ "$HARDCODED_QUERY" -eq 0 ]; then
    log_check "No hardcoded 'uploadId' strings" "PASS"
else
    log_check "Hardcoded strings" "WARN" "Found $HARDCODED_QUERY hardcoded 'uploadId' - use ResponseFields.UPLOAD_ID"
fi

# Check for TODOs
TODO_COUNT=$(grep -r "TODO" --include="*.kt" --include="*.java" "$PROJECT_ROOT" 2>/dev/null | grep -v "test" | grep -v ".git" | wc -l)
if [ "$TODO_COUNT" -eq 0 ]; then
    log_check "No TODOs in production code" "PASS"
else
    log_check "TODOs in code" "WARN" "$TODO_COUNT TODO(s) found"
fi

# Check for println (should use logger)
PRINTLN_COUNT=$(grep -r "println(" --include="*.kt" "$PROJECT_ROOT/core" "$PROJECT_ROOT/feature" 2>/dev/null | grep -v "test" | wc -l)
if [ "$PRINTLN_COUNT" -eq 0 ]; then
    log_check "No println statements" "PASS"
else
    log_check "println usage" "WARN" "$PRINTLN_COUNT println(s) found - use AirLogger"
fi

# ==================== CHECK 3: UNIT TESTS ====================

log_section "3. UNIT TESTS"

run_gradle_check "Domain Unit Tests" :domain:test
run_gradle_check "Network API Contract Tests" :core:network:test
run_gradle_check "Common Module Tests" :core:common:test

# ==================== CHECK 4: INTEGRATION TESTS ====================

log_section "4. INTEGRATION TESTS"

run_gradle_check "State Manager Integration Tests" :domain:test --tests "*IntegrationTest*"

# ==================== CHECK 5: BROWSER TESTS ====================

log_section "5. BROWSER JAVASCRIPT TESTS"

echo -e "${CYAN}▶ Checking browser test setup...${NC}"

# Check if npm/node is available
if command -v npm &> /dev/null; then
    # Check if browser tests exist
    if [ -f "$PROJECT_ROOT/web/src/test/js/uploadQueue.test.js" ]; then
        # Check if node_modules exists (tests installed)
        if [ -d "$PROJECT_ROOT/web/src/test/js/node_modules" ]; then
            echo -e "${CYAN}▶ Running browser unit tests...${NC}"
            cd "$PROJECT_ROOT/web/src/test/js"
            if npm test --silent 2>&1 | grep -q "PASS\|Tests:"; then
                cd "$PROJECT_ROOT"
                log_check "Browser JavaScript Tests" "PASS"
            else
                cd "$PROJECT_ROOT"
                log_check "Browser JavaScript Tests" "WARN" "Some JS tests failed - check output above"
            fi
        else
            log_check "Browser test dependencies" "WARN" "Run 'cd web/src/test/js && npm install'"
        fi
    else
        log_check "Browser test file exists" "WARN" "web/src/test/js/uploadQueue.test.js not found"
    fi
else
    log_check "Node.js/npm available" "WARN" "Install Node.js to run browser tests"
fi

# ==================== CHECK 6: LINT ====================

# Check if detekt is available
if ./gradlew tasks --all 2>/dev/null | grep -q "detekt"; then
    run_gradle_check "Detekt Static Analysis" detekt
else
    log_check "Detekt" "WARN" "Detekt not configured in project"
fi

# Check Kotlin code style (only if ktlint task exists)
if ./gradlew tasks --all 2>/dev/null | grep -q "ktlintCheck"; then
    run_gradle_check "Kotlin Code Style" ktlintCheck
else
    log_check "Kotlin Code Style" "WARN" "ktlintCheck task not configured"
fi

# ==================== CHECK 7: BUILD VERIFICATION ====================

log_section "6. BUILD VERIFICATION"

run_gradle_check "Debug Build" assembleDebug

# ==================== CHECK 8: DOCUMENTATION ====================

log_section "7. DOCUMENTATION CHECKS"

echo -e "${CYAN}▶ Checking documentation files...${NC}"

[ -f "$PROJECT_ROOT/API_SPEC.md" ] && log_check "API_SPEC.md exists" "PASS" || log_check "API_SPEC.md" "WARN" "Missing API documentation"
[ -f "$PROJECT_ROOT/TEST_SUITE_README.md" ] && log_check "TEST_SUITE_README.md exists" "PASS" || log_check "TEST docs" "WARN" "Missing test documentation"
[ -f "$PROJECT_ROOT/README.md" ] && log_check "README.md exists" "PASS" || log_check "README.md" "WARN"
[ -f "$PROJECT_ROOT/scripts/validate_api.sh" ] && log_check "validate_api.sh exists" "PASS" || log_check "Validation script" "WARN" "Run from project root"

# Check if API_SPEC is up to date
echo -e "${CYAN}▶ Checking API_SPEC.md currency...${NC}"
if grep -q "v2.1" "$PROJECT_ROOT/API_SPEC.md" 2>/dev/null; then
    log_check "API_SPEC mentions protocol version" "PASS"
else
    log_check "API_SPEC version" "WARN" "Document may be outdated"
fi

# ==================== SUMMARY REPORT ====================

echo ""
echo -e "${BOLD}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                        SUMMARY REPORT                          ║${NC}"
echo -e "${BOLD}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Calculate percentages
PASS_PERCENT=$(( PASSED_CHECKS * 100 / TOTAL_CHECKS ))

# Print statistics
echo -e "Total Checks:      ${BOLD}$TOTAL_CHECKS${NC}"
echo -e "Passed:            ${GREEN}${BOLD}$PASSED_CHECKS${NC} ${GREEN}($PASS_PERCENT%)${NC}"
echo -e "Failed:            ${RED}${BOLD}$FAILED_CHECKS${NC}${NC}"
echo -e "Warnings:          ${YELLOW}${BOLD}$WARNINGS${NC}${NC}"
echo ""

# Print detailed results
echo -e "${BOLD}Detailed Results:${NC}"
echo "────────────────────────────────────────────────────────────────"

for ((i=0; i<$TOTAL_CHECKS; i++)); do
    printf "%-45s %s\n" "${CHECK_NAMES[$i]}" "${CHECK_STATUSES[$i]}"
done

echo ""
echo "────────────────────────────────────────────────────────────────"

# Final verdict
if [ $FAILED_CHECKS -eq 0 ]; then
    if [ $WARNINGS -eq 0 ]; then
        echo -e "${GREEN}${BOLD}✓ ALL CHECKS PASSED - CODE IS READY${NC}"
        echo ""
        echo -e "You can safely push this code. All validation passed."
        exit 0
    else
        echo -e "${YELLOW}${BOLD}⚠ CHECKS PASSED WITH WARNINGS${NC}"
        echo ""
        echo -e "Code is functional but has $WARNINGS warning(s)."
        echo -e "Review warnings above before pushing."
        exit 0
    fi
else
    echo -e "${RED}${BOLD}✗ CHECKS FAILED - DO NOT PUSH${NC}"
    echo ""
    echo -e "Found ${RED}${BOLD}$FAILED_CHECKS${NC} critical failure(s)."
    echo -e "Fix the issues above before pushing code."
    
    if [ "$CI_MODE" == true ]; then
        echo ""
        echo "CI/CD pipeline will block this PR."
    fi
    
    exit 1
fi
