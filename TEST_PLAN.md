# AirBridge Upload Engine - Comprehensive Test Plan

## Protocol Version: v2.1-failfast

---

## Test Categories

### A. Basic Upload Operations (Use Cases 1-3)

#### A1. New File Upload
**Test ID**: UC-01  
**Precondition**: Server running, browser connected, no existing file  
**Steps**:
1. Browser: Select file (10MB)
2. Browser: Click upload
3. Server: Verify offset = 0, disk size = 0
4. Browser: Monitor progress to 100%
5. Server: Verify file exists, size = 10MB

**Expected Results**:
- Progress bar moves smoothly from 0% to 100%
- File appears in phone storage
- Status transitions: QUEUED → UPLOADING → COMPLETED
- Throughput > 1 MB/s on Wi-Fi

#### A2. Large File Upload (>100MB)
**Test ID**: UC-02  
**Precondition**: Server running, sufficient storage  
**Steps**:
1. Select file (500MB)
2. Start upload
3. Monitor for 30 seconds
4. Verify progress increments

**Expected Results**:
- Progress bar updates every ~1 second (8KB chunks)
- Memory usage stable (no large buffers)
- No ANR (Application Not Responding)

#### A3. Multiple Files Parallel
**Test ID**: UC-03  
**Precondition**: Server running  
**Steps**:
1. Select 5 files (each 20MB)
2. Add all to queue
3. Verify 3 active, 2 queued
4. Wait for completion

**Expected Results**:
- 3 uploads active simultaneously
- Queue shows "2 queued"
- All 5 complete successfully
- No file corruption

---

### B. Pause Operations (Use Cases 4-6)

#### B1. Pause Mid-Upload (Phone)
**Test ID**: UC-04  
**Precondition**: Upload in progress (50% complete)  
**Steps**:
1. Phone: Tap Pause button
2. Server: Verify state → PAUSING → PAUSED
3. Browser: Verify XHR aborts
4. Browser: Verify UI shows "Paused"
5. Check disk file size = 50%

**Expected Results**:
- Pause latency < 200ms
- Partial file preserved
- Progress stops immediately
- Status: PAUSING → PAUSED

#### B2. Pause Immediately After Resume
**Test ID**: UC-05  
**Precondition**: Upload paused at 30%  
**Steps**:
1. Resume upload
2. Immediately pause again (within 2 seconds)
3. Verify clean state transition

**Expected Results**:
- No duplicate data written
- State: PAUSED → RESUMING → UPLOADING → PAUSING → PAUSED
- No errors in log

#### B3. Pause All
**Test ID**: UC-06  
**Precondition**: 3 uploads active  
**Steps**:
1. Phone: Tap "Pause All"
2. Verify all 3 XHRs abort
3. Check all states = PAUSED

**Expected Results**:
- All uploads pause within 500ms
- Queue state: isPaused = true
- SSE broadcasts pause event

---

### C. Resume Operations (Use Cases 7-10)

#### C1. Normal Resume (Phone → Browser)
**Test ID**: UC-07  
**Precondition**: File paused at 60%  
**Steps**:
1. Phone: Tap Resume
2. Server: State = RESUMING
3. Browser: POSTs immediately (no polling wait)
4. Server: Validates offset = 60%
5. Upload continues from 60%

**Expected Results**:
- Resume latency < 300ms (phone tap → browser POST)
- No duplicate bytes
- Progress continues from 60%
- No 409 offset_mismatch errors

#### C2. Resume After App Restart
**Test ID**: UC-08  
**Precondition**: Upload paused, app killed  
**Steps**:
1. Kill AirBridge app
2. Restart app
3. Browser: Refresh page
4. Browser: POST same file
5. Server: Reads disk size, validates offset

**Expected Results**:
- Resume successful from disk size
- No data loss
- State machine initializes correctly

#### C3. Resume After Browser Refresh
**Test ID**: UC-09  
**Precondition**: Upload paused at 40%  
**Steps**:
1. Browser: Refresh page
2. Browser: Query status endpoint
3. Browser: POST with offset from server
4. Resume from 40%

**Expected Results**:
- Status endpoint returns correct disk size
- Browser adjusts offset
- Upload resumes correctly

#### C4. Resume When File Complete
**Test ID**: UC-10  
**Precondition**: File already 100% on disk  
**Steps**:
1. Browser: POST file
2. Server: Sees disk size = total bytes
3. Server: Returns status = completed

**Expected Results**:
- Immediate completion (no re-upload)
- Status: COMPLETED
- Success toast shown

---

### D. Cancel Operations (Use Cases 11-12)

#### D1. Cancel Mid-Upload
**Test ID**: UC-11  
**Precondition**: Upload at 50%  
**Steps**:
1. Phone: Tap Cancel
2. Server: Cancels job, deletes partial file
3. Browser: XHR aborts
4. Check file deleted from storage

**Expected Results**:
- File deleted from phone
- Status: CANCELLED
- Browser UI removes item

#### D2. Cancel While Paused
**Test ID**: UC-12  
**Precondition**: Upload paused at 70%  
**Steps**:
1. Phone: Tap Cancel
2. Server: Deletes partial file
3. Browser: Item removed

**Expected Results**:
- Partial file deleted
- State: CANCELLED

---

### E. Edge Cases - External Modification (Use Cases 13-16)

#### E1. File Deleted Externally Mid-Upload
**Test ID**: UC-13  
**Precondition**: Upload in progress  
**Steps**:
1. Upload at 30%
2. User deletes file via file manager
3. Resume upload attempt
4. Server: findFileByName returns null
5. Server: diskSize = 0

**Expected Results**:
- Offset validation: 30% != 0
- 409 offset_mismatch
- Browser restarts from 0%

#### E2. File Edited Externally
**Test ID**: UC-14  
**Precondition**: Upload paused at 50%  
**Steps**:
1. User modifies partial file externally
2. Resume upload
3. Server: diskSize != expected offset
4. Server: Returns offset_mismatch

**Expected Results**:
- 409 offset_mismatch
- Browser fetches new disk size
- May corrupt or restart (depending on modification)

#### E3. Storage Full
**Test ID**: UC-15  
**Precondition**: Phone storage almost full  
**Steps**:
1. Start large upload
2. Wait for storage full
3. Write operation fails

**Expected Results**:
- HTTP 507 (Insufficient Storage)
- State: ERROR
- Partial file remains (can resume after freeing space)

#### E4. Permission Revoked
**Test ID**: UC-16  
**Precondition**: Upload in progress  
**Steps**:
1. User revokes storage permission
2. Next write attempt fails

**Expected Results**:
- PermissionError exception
- State: ERROR (permanent)
- User must re-grant permission

---

### F. Network Scenarios (Use Cases 17-18)

#### F1. Network Disconnect Mid-Upload
**Test ID**: UC-17  
**Precondition**: Upload at 40%  
**Steps**:
1. Disable Wi-Fi on browser device
2. Wait 10 seconds
3. Re-enable Wi-Fi
4. Browser: Reconnect and resume

**Expected Results**:
- XHR times out
- State on server: ERROR_RETRYABLE or PAUSED
- Resume successful from 40%
- No data corruption

#### F2. Server Busy (Concurrency)
**Test ID**: UC-18  
**Precondition**: 3 uploads active  
**Steps**:
1. Browser: POST 4th upload
2. Server: tryAcquire fails
3. Server: Returns 409 busy
4. Browser: Exponential backoff (200ms → 400ms → 800ms)
5. Eventually succeeds

**Expected Results**:
- 409 busy response < 50ms
- Browser retries automatically
- No blocking on server
- Upload eventually succeeds

---

### G. Multi-File Behavior (Use Cases 19-21)

#### G1. Two Uploads Same Filename
**Test ID**: UC-19  
**Precondition**: Upload A.txt in progress  
**Steps**:
1. Browser: Start upload A.txt
2. Browser: Try to upload A.txt again (different file)

**Expected Results**:
- Second upload: tryLock fails
- Second upload: 409 busy
- File not corrupted
- Sequential processing

#### G2. Two Uploads Different Files
**Test ID**: UC-20  
**Precondition**: No active uploads  
**Steps**:
1. Start upload A.txt
2. Start upload B.txt

**Expected Results**:
- Both upload simultaneously
- Different file locks
- Both complete successfully

#### G3. Pause One File, Others Continue
**Test ID**: UC-21  
**Precondition**: A.txt and B.txt uploading  
**Steps**:
1. Pause A.txt
2. Verify B.txt continues

**Expected Results**:
- A.txt: state = PAUSED, progress frozen
- B.txt: continues uploading
- Independent control works

---

### H. App Lifecycle (Use Cases 22-23)

#### H1. App Killed During Upload
**Test ID**: UC-22  
**Precondition**: Upload at 50%  
**Steps**:
1. Swipe away app (kill)
2. File remains partially written
3. Restart app
4. Browser: POST same file

**Expected Results**:
- Disk size = 50%
- Resume from 50%
- Foreground service should prevent this (if enabled)

#### H2. Phone Reboot
**Test ID**: UC-23  
**Precondition**: Upload paused at 75%  
**Steps**:
1. Reboot phone
2. Restart AirBridge
3. Browser: Resume upload

**Expected Results**:
- Resume from disk size (75%)
- State re-initialized
- Upload completes

---

### I. Security & Validation (Use Cases 24-26)

#### I1. Malformed Content-Range
**Test ID**: UC-24  
**Steps**:
1. Browser: Send invalid Content-Range header
2. Server: Parse fails

**Expected Results**:
- Offset = 0 (safe default)
- Upload starts from beginning
- No crash

#### I2. Negative Offset
**Test ID**: UC-25  
**Steps**:
1. Browser: Send Content-Range with negative offset

**Expected Results**:
- Server rejects as invalid
- 400 Bad Request
- No file corruption

#### I3. Offset > DiskSize
**Test ID**: UC-26  
**Steps**:
1. Browser: Claim offset = 1000
2. Server: diskSize = 500

**Expected Results**:
- 409 offset_mismatch
- Server returns actualDiskSize = 500
- Browser adjusts and retries

---

## Regression Test Execution Checklist

### Pre-Flight Checks
- [ ] Server starts without errors
- [ ] QR code generates correctly
- [ ] Browser connects successfully
- [ ] Token authentication works

### Core Functionality
- [ ] UC-01: New file upload works
- [ ] UC-04: Pause from phone works
- [ ] UC-07: Resume from phone works (< 300ms)
- [ ] UC-11: Cancel deletes file
- [ ] UC-18: 409 busy returned correctly

### Edge Cases
- [ ] UC-13: External delete handled gracefully
- [ ] UC-15: Storage full error (507)
- [ ] UC-24: Malformed headers rejected

### Stress Tests
- [ ] Upload 10 files simultaneously
- [ ] Pause/Resume 20 times in succession
- [ ] Upload 1GB file to completion

### UI Verification
- [ ] Progress bar updates correctly
- [ ] Status text accurate (not stuck on "uploading")
- [ ] Button states correct (Pause/Resume/Cancel)
- [ ] SSE events received in real-time

---

## Known Issues Log

| Issue | Severity | Workaround | Fix Target |
|-------|----------|------------|------------|
| Progress may stall at 99% briefly | Low | Normal behavior | v2.2 |
| 2-4s delay on cross-device pause/resume | Medium | Wait for sync | v2.2 (WebSocket) |

---

## Performance Benchmarks

| Metric | Target | Measurement Method |
|--------|--------|---------------------|
| Pause latency | < 200ms | Log timestamp diff |
| Resume latency | < 300ms | Phone tap → browser POST |
| Busy response | < 50ms | Server receives POST → 409 sent |
| Throughput | > 5 MB/s | 100MB file / time |
| Memory usage | < 50MB | Android profiler |

---

## Sign-Off

**Test Engineer**: _________________  
**Date**: _________________  
**Version Tested**: v2.1-failfast  
**Result**: PASS / FAIL (with notes)
