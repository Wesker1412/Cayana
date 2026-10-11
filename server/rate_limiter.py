import time
import threading
from collections import defaultdict, deque

class SlidingWindowRateLimiter:
    """
    Thread-safe in-memory sliding window rate limiter keyed by anonymous installation_id.
    Stores timestamps of recent requests within the window.
    """
    def __init__(self, max_requests_per_window: int = 30, window_seconds: float = 60.0):
        self.max_requests = max_requests_per_window
        self.window_seconds = window_seconds
        self.lock = threading.Lock()
        self.requests = defaultdict(deque)

    def is_allowed(self, key: str) -> bool:
        if not key:
            return False
            
        now = time.time()
        window_start = now - self.window_seconds
        
        with self.lock:
            q = self.requests[key]
            # Evict timestamps older than window_start
            while q and q[0] <= window_start:
                q.popleft()
                
            if len(q) < self.max_requests:
                q.append(now)
                return True
            else:
                return False

    def reset(self):
        with self.lock:
            self.requests.clear()
