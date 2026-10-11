import json
import hashlib
import time
import sys
import threading
import re
from collections import deque
from .config import ServerConfig

SAFE_REQ_ID_PATTERN = re.compile(r"^[a-zA-Z0-9_\-]{1,64}$")

class OperationalLogger:
    """
    Privacy-safe operational logger with bounded memory retention.
    Logs ONLY operational telemetry:
    - timestamp
    - req_id (strictly validated/sanitized, control characters stripped)
    - inst_hash (one-way SHA-256 prefix for coarse analytics, no raw UUID)
    - duration_ms
    - status
    - error_code
    - prompt_tokens
    - completion_tokens

    STRICT PRIVACY GUARANTEE:
    Prohibits logging questions, context items, OCR, transcripts, answers, or credentials.
    """
    def __init__(self, stream=sys.stdout, max_in_memory_logs: int = ServerConfig.MAX_IN_MEMORY_LOGS):
        self.stream = stream
        self.lock = threading.Lock()
        # Strictly bounded in-memory buffer to prevent unbounded heap growth in long-running processes
        self.in_memory_logs = deque(maxlen=max_in_memory_logs)

    @staticmethod
    def sanitize_request_id(request_id: str) -> str:
        if not request_id or not isinstance(request_id, str):
            return "unknown"
        # Strip all control characters (CRLF injection prevention)
        cleaned = re.sub(r"[\r\n\t\x00-\x1f\x7f]", "", request_id).strip()
        if SAFE_REQ_ID_PATTERN.match(cleaned):
            return cleaned
        return cleaned[:64] if cleaned else "unknown"

    def log_request(
        self,
        request_id: str,
        installation_id: str,
        duration_ms: int,
        http_status: int,
        error_code: str = None,
        prompt_tokens: int = 0,
        completion_tokens: int = 0
    ):
        safe_req_id = self.sanitize_request_id(request_id)

        # Truncate/hash installation ID for coarse analytics without identity linkage
        inst_hash = ""
        if installation_id:
            inst_hash = hashlib.sha256(installation_id.encode("utf-8")).hexdigest()[:12]

        record = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "req_id": safe_req_id,
            "inst_hash": inst_hash,
            "duration_ms": duration_ms,
            "status": http_status,
            "error_code": error_code,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens
        }

        serialized = json.dumps(record)
        with self.lock:
            self.in_memory_logs.append(record)
            if self.stream:
                self.stream.write(serialized + "\n")
                self.stream.flush()

    def get_logs(self):
        with self.lock:
            return list(self.in_memory_logs)

    def clear(self):
        with self.lock:
            self.in_memory_logs.clear()

logger = OperationalLogger()
