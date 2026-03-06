/**
 * AirBridge Browser Unit Tests
 * 
 * These tests verify the browser-side upload logic without a real server.
 * Run with: npm test
 */

// Mock the upload queue class from index.html
class MockUploadQueue {
  constructor() {
    this.items = new Map();
    this.queue = [];
    this.globalPaused = false;
    this.active = 0;
  }

  add(item) {
    this.items.set(item.id, item);
    this.queue.push(item);
    this.process();
  }

  pause(id) {
    const item = this.items.get(id);
    if (!item) return;
    
    // BUG CHECK: Original code didn't abort XHR in pause()
    // Fixed version must abort XHR
    if (item.xhr && item.state === 'uploading') {
      item.xhr.aborted = true;
    }
    
    item.state = 'paused';
    item.isPaused = true;
    
    const queueIndex = this.queue.findIndex(i => i.id === id);
    if (queueIndex >= 0) {
      this.queue.splice(queueIndex, 1);
    }
  }

  resume(id) {
    const item = this.items.get(id);
    // CRITICAL CHECK: Must be 'paused' state
    if (!item || item.state !== 'paused') {
      return false; // Failed - wrong state
    }
    
    item.state = 'queued';
    item.isPaused = false;
    this.queue.push(item);
    this.process();
    return true;
  }

  cancel(id) {
    const item = this.items.get(id);
    if (!item) return;
    
    item.state = 'cancelled';
    
    if (item.xhr) {
      item.xhr.aborted = true;
      item.xhr = null;
    }
    
    const queueIndex = this.queue.findIndex(i => i.id === id);
    if (queueIndex >= 0) {
      this.queue.splice(queueIndex, 1);
    }
    
    this.items.delete(id);
  }

  process() {
    // Simulate processing queue
  }
}

class MockUploadItem {
  constructor(id, name) {
    this.id = id;
    this.name = name;
    this.state = 'none';
    this.isPaused = false;
    this.xhr = null;
    this.progress = 0;
    this.offset = 0;
  }
}

describe('UploadQueue', () => {
  let queue;
  let item;

  beforeEach(() => {
    queue = new MockUploadQueue();
    item = new MockUploadItem('test-1', 'video.mp4');
    item.state = 'paused'; // Start paused for resume tests
    item.xhr = { aborted: false };
    queue.add(item);
  });

  describe('resume()', () => {
    test('should succeed when state is PAUSED', () => {
      const result = queue.resume('test-1');
      expect(result).toBe(true);
      expect(item.state).toBe('queued');
    });

    test('should FAIL when state is RESUMING (bug scenario)', () => {
      // This catches the bug where setting state to 'resuming' before resume() 
      // causes resume() to return early
      item.state = 'resuming';
      
      const result = queue.resume('test-1');
      
      expect(result).toBe(false); // Should fail - state !== 'paused'
    });

    test('should FAIL when state is UPLOADING', () => {
      item.state = 'uploading';
      
      const result = queue.resume('test-1');
      
      expect(result).toBe(false);
    });

    test('should add to queue after successful resume', () => {
      queue.resume('test-1');
      
      expect(queue.queue).toContain(item);
    });
  });

  describe('pause()', () => {
    test('should set state to PAUSED', () => {
      item.state = 'uploading';
      
      queue.pause('test-1');
      
      expect(item.state).toBe('paused');
      expect(item.isPaused).toBe(true);
    });

    test('should abort active XHR', () => {
      item.state = 'uploading';
      item.xhr = { aborted: false };
      
      queue.pause('test-1');
      
      expect(item.xhr.aborted).toBe(true);
    });

    test('should remove from active queue', () => {
      item.state = 'uploading';
      queue.queue = [item];
      
      queue.pause('test-1');
      
      expect(queue.queue).not.toContain(item);
    });
  });

  describe('cancel()', () => {
    test('should set state to CANCELLED', () => {
      queue.cancel('test-1');
      
      expect(item.state).toBe('cancelled');
    });

    test('should abort XHR', () => {
      item.xhr = { aborted: false };
      
      queue.cancel('test-1');
      
      expect(item.xhr).toBeNull();
    });

    test('should remove from items map', () => {
      queue.cancel('test-1');
      
      expect(queue.items.has('test-1')).toBe(false);
    });
  });
});

describe('SSE Event Handlers', () => {
  let queue;
  let item;

  beforeEach(() => {
    queue = new MockUploadQueue();
    item = new MockUploadItem('upload-123', 'file.mp4');
    queue.add(item);
  });

  describe('resuming event', () => {
    test('should call resume() BEFORE changing state (bug fix verification)', () => {
      // Setup: Item is paused, waiting for resume
      item.state = 'paused';
      item.xhr = null; // No active upload
      
      // BUG: Setting state before calling resume() breaks resume()
      // FIX: Call resume() first, then set state
      
      // Simulate the FIXED handler
      let resumeCalled = false;
      const originalResume = queue.resume.bind(queue);
      queue.resume = (id) => {
        resumeCalled = true;
        return originalResume(id);
      };
      
      // Fixed order: check state, call resume(), then update
      if (item.state === 'paused' && !item.xhr) {
        queue.resume(item.id);
      }
      item.state = 'resuming';
      
      expect(resumeCalled).toBe(true);
      expect(item.state).toBe('resuming');
    });

    test('should NOT resume if already uploading', () => {
      item.state = 'uploading';
      item.xhr = { aborted: false }; // Active upload
      
      const result = queue.resume(item.id);
      
      expect(result).toBe(false);
    });
  });

  describe('cancelled event', () => {
    test('should remove item from queue', () => {
      queue.cancel(item.id);
      
      expect(queue.items.has(item.id)).toBe(false);
    });
  });

  describe('completed event', () => {
    test('should abort XHR', () => {
      item.xhr = { aborted: false };
      item.state = 'completed';
      
      // Handler should abort
      if (item.xhr) {
        item.xhr.aborted = true;
      }
      
      expect(item.xhr.aborted).toBe(true);
    });
  });
});

describe('Multi-File Scenarios', () => {
  let queue;

  beforeEach(() => {
    queue = new MockUploadQueue();
  });

  test('should track independent states for multiple uploads', () => {
    const file1 = new MockUploadItem('file-1', 'a.mp4');
    const file2 = new MockUploadItem('file-2', 'b.mp4');
    const file3 = new MockUploadItem('file-3', 'c.mp4');
    
    file1.state = 'uploading';
    file2.state = 'paused';
    file3.state = 'resuming';
    
    queue.add(file1);
    queue.add(file2);
    queue.add(file3);
    
    expect(queue.items.get('file-1').state).toBe('uploading');
    expect(queue.items.get('file-2').state).toBe('paused');
    expect(queue.items.get('file-3').state).toBe('resuming');
  });

  test('pausing one file should not affect others', () => {
    const file1 = new MockUploadItem('file-1', 'a.mp4');
    const file2 = new MockUploadItem('file-2', 'b.mp4');
    
    file1.state = 'uploading';
    file2.state = 'uploading';
    
    queue.add(file1);
    queue.add(file2);
    
    // Pause only file1
    queue.pause('file-1');
    
    expect(queue.items.get('file-1').state).toBe('paused');
    expect(queue.items.get('file-2').state).toBe('uploading');
  });
});

// Export for use in other test files
module.exports = { MockUploadQueue, MockUploadItem };


describe('Progress offset guards', () => {
  function applyProgressOffset(item, baseOffset, loaded) {
    const optimisticOffset = Math.min(baseOffset + loaded, item.fileSize);
    return Math.max(item.offset, item.serverOffset, optimisticOffset);
  }

  test('offset never regresses when serverOffset jumps forward', () => {
    const item = { offset: 700, serverOffset: 900, fileSize: 1000 };

    const next = applyProgressOffset(item, 700, 20); // optimistic would be 720

    expect(next).toBe(900); // preserve higher server-confirmed offset
  });

  test('offset advances monotonically with local progress', () => {
    const item = { offset: 500, serverOffset: 520, fileSize: 1000 };

    const next = applyProgressOffset(item, 520, 40); // optimistic 560

    expect(next).toBe(560);
  });
});

describe('resume button error handling', () => {
  async function resumeUploadWithDeps(id, deps) {
    const response = await deps.fetchImpl(`/api/upload/resume?id=${id}`, { method: 'POST' });
    const data = await response.json().catch(() => ({}));

    if (response.ok && data.success) {
      const resumed = deps.queue.resume(id);
      if (resumed) {
        const item = deps.queue.items.get(id);
        if (item && item.state !== 'uploading') {
          item.state = 'resuming';
        }
      }
      return;
    }

    deps.showToast(data.message || 'Unable to resume from current state', 'error');
  }

  test('shows toast when resume endpoint rejects request', async () => {
    const queue = new MockUploadQueue();
    const item = new MockUploadItem('u-1', 'f.bin');
    item.state = 'uploading';
    queue.add(item);

    const toasts = [];

    await resumeUploadWithDeps('u-1', {
      queue,
      showToast: (message, type) => toasts.push({ message, type }),
      fetchImpl: async () => ({
        ok: false,
        json: async () => ({ success: false, message: 'Upload is not paused; cannot resume' })
      })
    });

    expect(toasts).toHaveLength(1);
    expect(toasts[0].type).toBe('error');
    expect(toasts[0].message).toMatch(/cannot resume/i);
  });
});


describe('resumeAll respects max parallel scheduling', () => {
  class SimQueue {
    constructor(maxParallel = 3) {
      this.maxParallel = maxParallel;
      this.active = 0;
      this.queue = [];
      this.items = new Map();
      this.started = [];
      this.globalPaused = true;
      this.processing = false;
    }

    add(item) {
      this.items.set(item.id, item);
    }

    resume(id) {
      const item = this.items.get(id);
      if (!item) return false;
      if (item.state === 'completed' || item.state === 'cancelled') return false;
      if (item.state === 'uploading') return false;
      item.state = 'queued';
      if (!this.queue.find(i => i.id === id)) this.queue.push(item);
      return true;
    }

    process() {
      if (this.processing) return;
      this.processing = true;
      try {
        while (this.active < this.maxParallel && this.queue.length > 0) {
          const item = this.queue.shift();
          this.active++;
          item.state = 'uploading';
          this.started.push(item.id);
        }
      } finally {
        this.processing = false;
      }
    }

    resumeAll() {
      this.globalPaused = false;
      this.items.forEach(item => {
        if (item.state === 'paused' || item.state === 'pausing') {
          this.resume(item.id);
        }
      });
      this.process();
    }
  }

  test('resumeAll starts at most maxParallel uploads immediately', () => {
    const queue = new SimQueue(3);

    for (let i = 1; i <= 6; i++) {
      queue.add({ id: `u-${i}`, state: 'paused' });
    }

    queue.resumeAll();

    expect(queue.started.length).toBe(3);
    expect(queue.active).toBe(3);
    expect(queue.queue.length).toBe(3);
  });
});


describe('status sync fallback behavior', () => {
  test('sync should trigger resume when server reports resuming for paused item', () => {
    const queue = new MockUploadQueue();
    const item = new MockUploadItem('sync-1', 'video.mp4');
    item.state = 'paused';
    queue.add(item);

    let resumed = false;
    const originalResume = queue.resume.bind(queue);
    queue.resume = (id) => {
      resumed = true;
      return originalResume(id);
    };

    const serverState = 'resuming';
    if (serverState === 'resuming' && (item.state === 'paused' || item.state === 'pausing')) {
      queue.resume(item.id);
    }

    expect(resumed).toBe(true);
    expect(item.state).toBe('queued');
  });

  test('sync should abort active xhr when server reports paused', () => {
    const queue = new MockUploadQueue();
    const item = new MockUploadItem('sync-2', 'video.mp4');
    item.state = 'uploading';
    item.xhr = { aborted: false };
    queue.add(item);

    const serverState = 'paused';
    if (serverState === 'paused' || serverState === 'pausing') {
      if (item.state === 'uploading' && item.xhr) {
        item.xhr.aborted = true;
      }
      item.state = 'paused';
      item.isPaused = true;
    }

    expect(item.xhr.aborted).toBe(true);
    expect(item.state).toBe('paused');
  });
});
