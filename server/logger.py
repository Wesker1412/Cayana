import json
import hashlib
import time
import sys
import threading

class OperationalLogger:
    """
    Privacy-safe operational logger.
    Logs ONLY operational telemetry:
    - timestamp
    - request_id
    - installation_id_hash (one-way SHA-256 prefix for coarse grouping, no raw UUID)
    - duration_ms
    - http_status
    - error_code
    - prompt_tokens
    - completion_tokens
    
    STRICT PRIVACY GUARANTEE:
    Prohibits logging questions, context items, OCR, transcripts, answers, or credentials.
    """
    def __init__(self, stream=sys.stdout):
        self.stream = stream
        self.lock = threading.Lock()
        self.in_memory_logs = []  # Kept for programmatic assertions in test suites

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
        # Truncate/hash installation ID for coarse analytics without identity linkage
        inst_hash = ""
        if installation_id:
            inst_hash = hashlib.sha256(installation_id.encode("utf-8")).hexdigest()[:12]

        record = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "req_id": request_id,
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
