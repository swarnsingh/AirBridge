#!/bin/bash
#
# AirBridge Full Test Suite Runner
# Runs all test layers: Unit, Integration, API Contract, and Validation
#

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

FAILED=0
TOTAL=0

echo "=========================================="
echo "AirBridge Complete Test Suite"
echo "=========================================="
echo ""

# Get script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 1. Unit Tests
echo -e "${BLUE}▶ Running Unit Tests${NC}"
echo "----------------------------------------"
cd "$PROJECT_ROOT"
if ./gradlew :domain:test --quiet 2>&1 | tail -5; then
    echo -e "${GREEN}✓ Unit Tests Passed${NC}"
    TOTAL=$((TOTAL + 1))
else
    echo -e "${RED}✗ Unit Tests Failed${NC}"
    FAILED=$((FAILED + 1))
    TOTAL=$((TOTAL + 1))
fi
echo ""

# 2. Integration Tests
echo -e "${BLUE}▶ Running Integration Tests${NC}"
echo "----------------------------------------"
if cd "$PROJECT_ROOT" && ./gradlew :domain:test --tests "*IntegrationTest*" --quiet 2>&1 | tail -5; then
    echo -e "${GREEN}✓ Integration Tests Passed${NC}"
    TOTAL=$((TOTAL + 1))
else
    echo -e "${RED}✗ Integration Tests Failed${NC}"
    FAILED=$((FAILED + 1))
    TOTAL=$((TOTAL + 1))
fi
echo ""

# 3. API Contract Tests
echo -e "${BLUE}▶ Running API Contract Tests${NC}"
echo "----------------------------------------"
if cd "$PROJECT_ROOT" && ./gradlew :core:network:test --tests "*ApiContractTest*" --quiet 2>&1 | tail -5; then
    echo -e "${GREEN}✓ API Contract Tests Passed${NC}"
    TOTAL=$((TOTAL + 1))
else
    echo -e "${RED}✗ API Contract Tests Failed${NC}"
    FAILED=$((FAILED + 1))
    TOTAL=$((TOTAL + 1))
fi
echo ""

# 4. API Validation Script
echo -e "${BLUE}▶ Running API Validation${NC}"
echo "----------------------------------------"
if "$SCRIPT_DIR/validate_api.sh" 2>&1 | grep -q "ALL CHECKS PASSED"; then
    echo -e "${GREEN}✓ API Validation Passed${NC}"
    TOTAL=$((TOTAL + 1))
else
    echo -e "${RED}✗ API Validation Failed${NC}"
    FAILED=$((FAILED + 1))
    TOTAL=$((TOTAL + 1))
fi
echo ""

# Summary
echo "=========================================="
echo "Test Suite Summary"
echo "=========================================="
echo "Total Test Groups: $TOTAL"
echo -e "Passed: ${GREEN}$((TOTAL - FAILED))${NC}"
echo -e "Failed: ${RED}$FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}🎉 ALL TESTS PASSED!${NC}"
    echo "The codebase is ready for deployment or upgrades."
    exit 0
else
    echo -e "${RED}❌ SOME TESTS FAILED${NC}"
    echo "Review the failures above before deploying."
    exit 1
fi