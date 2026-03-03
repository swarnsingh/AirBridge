#!/bin/bash
#
# AirBridge API Contract Validation Script
# Run this to check for browser/server parameter mismatches
#

set -e

# Get script directory and project root
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=========================================="
echo "AirBridge API Contract Validation"
echo "=========================================="
echo ""
echo "Project Root: $PROJECT_ROOT"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

ERRORS=0

# Check 1: Server QueryParams constants
echo "📋 Checking Server QueryParams..."
if grep -q 'UPLOAD_ID = "id"' "$PROJECT_ROOT/core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/QueryParams.kt"; then
    echo "${GREEN}✓${NC} UPLOAD_ID = 'id' (correct for browser)"
else
    echo "${RED}✗${NC} UPLOAD_ID mismatch - expected 'id'"
    ERRORS=$((ERRORS + 1))
fi

if grep -q 'TOKEN = "token"' "$PROJECT_ROOT/core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/QueryParams.kt"; then
    echo "${GREEN}✓${NC} TOKEN = 'token'"
else
    echo "${RED}✗${NC} TOKEN mismatch"
    ERRORS=$((ERRORS + 1))
fi

if grep -q 'FILENAME = "filename"' "$PROJECT_ROOT/core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/QueryParams.kt"; then
    echo "${GREEN}✓${NC} FILENAME = 'filename'"
else
    echo "${RED}✗${NC} FILENAME mismatch"
    ERRORS=$((ERRORS + 1))
fi

# Check 2: Browser uses 'id=' (not 'uploadId=')
echo ""
echo "🌐 Checking Browser URL Parameters..."
UPLOADID_COUNT=$(grep -oE '\?uploadId=' "$PROJECT_ROOT/web/src/main/assets/index.html" 2>/dev/null | wc -l || echo "0")
ID_COUNT=$(grep -oE '\?id=' "$PROJECT_ROOT/web/src/main/assets/index.html" 2>/dev/null | wc -l || echo "0")

if [ "$UPLOADID_COUNT" -eq 0 ]; then
    echo "${GREEN}✓${NC} Browser uses 'id=' (correct)"
else
    echo "${RED}✗${NC} Browser uses 'uploadId=' $UPLOADID_COUNT times - should be 'id='"
    ERRORS=$((ERRORS + 1))
fi

if [ "$ID_COUNT" -gt 0 ]; then
    echo "${GREEN}✓${NC} Browser has $ID_COUNT 'id=' parameter(s)"
else
    echo "${YELLOW}⚠${NC} No 'id=' parameters found in browser"
fi

# Check 3: Server ResponseFields
echo ""
echo "📤 Checking Server Response Fields..."
if grep -q 'UPLOAD_ID = "uploadId"' "$PROJECT_ROOT/core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/QueryParams.kt"; then
    echo "${GREEN}✓${NC} Response UPLOAD_ID = 'uploadId' (correct for JSON)"
else
    echo "${RED}✗${NC} Response UPLOAD_ID mismatch"
    ERRORS=$((ERRORS + 1))
fi

# Check 4: Status endpoint uses 'status' (not 'state')
echo ""
echo "🔍 Checking Status Endpoint Response..."
if grep -q 'put("status"' "$PROJECT_ROOT/core/network/src/main/kotlin/com/swaran/airbridge/core/network/ktor/routes/UploadRoutes.kt"; then
    echo "${GREEN}✓${NC} Status endpoint uses 'status' field"
else
    echo "${RED}✗${NC} Status endpoint missing 'status' field"
    ERRORS=$((ERRORS + 1))
fi

# Check 5: State machine transitions
echo ""
echo "⚙️  Checking State Machine..."
if grep -q 'NONE -> target in setOf(QUEUED, UPLOADING, CANCELLED)' "$PROJECT_ROOT/domain/src/main/kotlin/com/swaran/airbridge/domain/model/UploadModels.kt"; then
    echo "${GREEN}✓${NC} NONE → UPLOADING transition enabled"
else
    echo "${YELLOW}⚠${NC} Check NONE → UPLOADING transition"
fi

if grep -q 'RESUMING -> target in setOf(UPLOADING, PAUSED, CANCELLED)' "$PROJECT_ROOT/domain/src/main/kotlin/com/swaran/airbridge/domain/model/UploadModels.kt"; then
    echo "${GREEN}✓${NC} RESUMING transitions correct"
else
    echo "${YELLOW}⚠${NC} Check RESUMING transitions"
fi

# Check 6: Browser SSE handlers
echo ""
echo "📡 Checking Browser SSE Handlers..."
if grep -q "data.state === 'cancelled'" "$PROJECT_ROOT/web/src/main/assets/index.html"; then
    echo "${GREEN}✓${NC} Browser handles 'cancelled' state"
else
    echo "${YELLOW}⚠${NC} Browser missing 'cancelled' handler"
fi

if grep -q "data.state === 'resuming'" "$PROJECT_ROOT/web/src/main/assets/index.html"; then
    echo "${GREEN}✓${NC} Browser handles 'resuming' state"
else
    echo "${YELLOW}⚠${NC} Browser missing 'resuming' handler"
fi

if grep -q "data.state === 'completed'" "$PROJECT_ROOT/web/src/main/assets/index.html"; then
    echo "${GREEN}✓${NC} Browser handles 'completed' state"
else
    echo "${YELLOW}⚠${NC} Browser missing 'completed' handler"
fi

# Summary
echo ""
echo "=========================================="
if [ $ERRORS -eq 0 ]; then
    echo "${GREEN}✅ ALL CHECKS PASSED${NC}"
    echo "Browser and server are aligned!"
else
    echo "${RED}❌ FOUND $ERRORS ERROR(S)${NC}"
    echo "Review API_SPEC.md for correct values"
    exit 1
fi
echo "=========================================="
