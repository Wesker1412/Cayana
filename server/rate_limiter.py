import time
import threading
from collections import deque
from typing import Tuple, Dict

class BoundedSlidingWindowRateLimiter:
    """
    Thread-safe in-memory sliding window rate limiter with strict memory bounds
    and multi-tier abuse defense:
    1. Global request throttle (protects against total budget exhaustion across all callers).
    2. IP-based throttle (prevents rotating UUID from the same IP to evade limits).
    3. Installation-based throttle (bounds single-client burst usage).

    Resource bounds:
    - Automatically purges expired timestamps and stale keys.
    - Limits total key count to max_entries to prevent memory-exhaustion attacks.
    """
    def __init__(
        self,
        max_requests_per_window: int = 30,
        ip_max_requests: int = 60,
        global_max_requests: int = 120,
        window_seconds: float = 60.0,
        max_entries: int = 10000
    ):
        self.max_requests_per_window = max_requests_per_window
        self.ip_max_requests = ip_max_requests
        self.global_max_requests = global_max_requests
        self.window_seconds = window_seconds
        self.max_entries = max_entries

        self.lock = threading.Lock()
        self.inst_requests: Dict[str, deque] = {}
        self.ip_requests: Dict[str, deque] = {}
        self.global_requests: deque = deque()
        self._last_cleanup = time.time()

    def _cleanup_map(self, target_map: Dict[str, deque], now: float, window_start: float):
        keys_to_delete = []
        for key, q in target_map.items():
            while q and q[0] <= window_start:
                q.popleft()
            if not q:
                keys_to_delete.append(key)
        for key in keys_to_delete:
            del target_map[key]

    def _enforce_entry_bounds(self, target_map: Dict[str, deque], now: float, window_start: float):
        if len(target_map) >= self.max_entries:
            # First, clean up expired entries
            self._cleanup_map(target_map, now, window_start)
            # If still over capacity, evict oldest entries
            while len(target_map) >= self.max_entries:
                oldest_key = next(iter(target_map))
                del target_map[oldest_key]

    def check_allowed(self, installation_id: str, client_ip: str = "") -> Tuple[bool, str]:
        """
        Atomically checks and records request across global, IP, and installation tiers.
        Returns (is_allowed, rejection_reason).
        """
        now = time.time()
        window_start = now - self.window_seconds

        with self.lock:
            # Periodic cleanup every 30 seconds
            if now - self._last_cleanup > 30.0:
                self._cleanup_map(self.inst_requests, now, window_start)
                self._cleanup_map(self.ip_requests, now, window_start)
                self._last_cleanup = now

            # 1. Check Global Limit
            while self.global_requests and self.global_requests[0] <= window_start:
                self.global_requests.popleft()
            if len(self.global_requests) >= self.global_max_requests:
                return False, "GLOBAL_RATE_LIMITED"

            # 2. Check Client IP Limit (if IP is provided)
            if client_ip:
                ip_q = self.ip_requests.get(client_ip)
                if ip_q:
                    while ip_q and ip_q[0] <= window_start:
                        ip_q.popleft()
                    if len(ip_q) >= self.ip_max_requests:
                        return False, "IP_RATE_LIMITED"

            # 3. Check Installation ID Limit
            if installation_id:
                inst_q = self.inst_requests.get(installation_id)
                if inst_q:
                    while inst_q and inst_q[0] <= window_start:
                        inst_q.popleft()
                    if len(inst_q) >= self.max_requests_per_window:
                        return False, "INSTALLATION_RATE_LIMITED"

            # All tiers passed -> record the request
            self.global_requests.append(now)

            if client_ip:
                self._enforce_entry_bounds(self.ip_requests, now, window_start)
                if client_ip not in self.ip_requests:
                    self.ip_requests[client_ip] = deque()
                self.ip_requests[client_ip].append(now)

            if installation_id:
                self._enforce_entry_bounds(self.inst_requests, now, window_start)
                if installation_id not in self.inst_requests:
                    self.inst_requests[installation_id] = deque()
                self.inst_requests[installation_id].append(now)

            return True, ""

    def is_allowed(self, key: str) -> bool:
        """Backwards-compatible check for single-key usage."""
        allowed, _ = self.check_allowed(installation_id=key, client_ip="")
        return allowed

    def get_entry_count(self) -> int:
        with self.lock:
            return len(self.inst_requests) + len(self.ip_requests)

    def reset(self):
        with self.lock:
            self.inst_requests.clear()
            self.ip_requests.clear()
            self.global_requests.clear()
