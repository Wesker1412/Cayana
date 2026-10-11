import http.server
import json
import time
import uuid
from typing import Tuple
from .config import ServerConfig
from .rate_limiter import SlidingWindowRateLimiter
from .logger import logger
from .upstream_client import UpstreamLlmClient

class CayanaApiHandler(http.server.BaseHTTPRequestHandler):
    config = ServerConfig()
    rate_limiter = SlidingWindowRateLimiter(
        max_requests_per_window=ServerConfig.RATE_LIMIT_PER_MINUTE,
        window_seconds=60.0
    )
    upstream_client = UpstreamLlmClient(config)

    def _send_json(self, status_code: int, data: dict, request_id: str = None, installation_id: str = None, error_code: str = None):
        body_bytes = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body_bytes)))
        if request_id:
            self.send_header("X-Cayana-Request-Id", request_id)
        self.end_headers()
        self.wfile.write(body_bytes)

    def do_GET(self):
        start_time = time.time()
        if self.path == "/health":
            self._send_json(200, {
                "status": "ok",
                "service": "cayana-llm-api",
                "version": "0.1.0"
            })
            duration_ms = int((time.time() - start_time) * 1000)
            logger.log_request("health", "", duration_ms, 200)
        else:
            self._send_json(404, {
                "error": {
                    "code": "NOT_FOUND",
                    "message": "Resource not found"
                }
            })

    def do_POST(self):
        start_time = time.time()
        req_id = self.headers.get("X-Cayana-Request-Id", "") or str(uuid.uuid4())
        inst_id = self.headers.get("X-Cayana-Installation-Id", "").strip()

        if self.path != "/v1/ask":
            self._send_json(404, {
                "error": {"code": "NOT_FOUND", "message": "Resource not found"}
            }, request_id=req_id, error_code="NOT_FOUND")
            return

        # 1. Content-Length and Wire Budget Check
        try:
            content_length = int(self.headers.get("Content-Length", 0))
        except (ValueError, TypeError):
            content_length = 0

        if content_length > self.config.MAX_REQUEST_BYTES:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(413, {
                "error": {
                    "code": "PAYLOAD_TOO_LARGE",
                    "message": f"Request body ({content_length} bytes) exceeds {self.config.MAX_REQUEST_BYTES} bytes limit."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="PAYLOAD_TOO_LARGE")
            logger.log_request(req_id, inst_id, duration_ms, 413, "PAYLOAD_TOO_LARGE")
            return

        # 2. Read Request Body
        raw_body = self.rfile.read(content_length)
        if len(raw_body) > self.config.MAX_REQUEST_BYTES:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(413, {
                "error": {
                    "code": "PAYLOAD_TOO_LARGE",
                    "message": f"Read payload exceeded {self.config.MAX_REQUEST_BYTES} bytes."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="PAYLOAD_TOO_LARGE")
            logger.log_request(req_id, inst_id, duration_ms, 413, "PAYLOAD_TOO_LARGE")
            return

        # 3. Parse JSON
        try:
            payload = json.loads(raw_body.decode("utf-8"))
        except Exception:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(400, {
                "error": {
                    "code": "INVALID_REQUEST",
                    "message": "Malformed JSON request body."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
            logger.log_request(req_id, inst_id, duration_ms, 400, "INVALID_REQUEST")
            return

        # Fallback to body-specified IDs if not in headers
        if not inst_id:
            inst_id = str(payload.get("installationId", "")).strip()
        if not self.headers.get("X-Cayana-Request-Id"):
            req_id = str(payload.get("requestId", "")).strip() or req_id

        # 4. Anonymous Authentication Check (fail-closed)
        if not inst_id:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(401, {
                "error": {
                    "code": "UNAUTHORIZED",
                    "message": "Missing required anonymous installation credential."
                }
            }, request_id=req_id, error_code="UNAUTHORIZED")
            logger.log_request(req_id, "", duration_ms, 401, "UNAUTHORIZED")
            return

        # 5. Rate Limiting Check
        if not self.rate_limiter.is_allowed(inst_id):
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(429, {
                "error": {
                    "code": "RATE_LIMITED",
                    "message": "Too many requests. Please wait before retrying."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="RATE_LIMITED")
            logger.log_request(req_id, inst_id, duration_ms, 429, "RATE_LIMITED")
            return

        # 6. Validate Question
        question = payload.get("question", "")
        if not isinstance(question, str) or not question.strip():
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(400, {
                "error": {
                    "code": "INVALID_REQUEST",
                    "message": "Question must be a non-empty string."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
            logger.log_request(req_id, inst_id, duration_ms, 400, "INVALID_REQUEST")
            return

        if len(question) > self.config.MAX_QUESTION_CHARS:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(400, {
                "error": {
                    "code": "INVALID_REQUEST",
                    "message": f"Question length ({len(question)}) exceeds {self.config.MAX_QUESTION_CHARS} characters."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
            logger.log_request(req_id, inst_id, duration_ms, 400, "INVALID_REQUEST")
            return

        # 7. Validate ContextPack
        context_pack = payload.get("contextPack", {})
        if not isinstance(context_pack, dict):
            context_pack = {}

        items = context_pack.get("items", [])
        if not isinstance(items, list):
            items = []

        if len(items) > self.config.MAX_TOP_K:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(400, {
                "error": {
                    "code": "TOP_K_EXCEEDED",
                    "message": f"ContextPack items ({len(items)}) exceeds maximum Top-K limit of {self.config.MAX_TOP_K}."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="TOP_K_EXCEEDED")
            logger.log_request(req_id, inst_id, duration_ms, 400, "TOP_K_EXCEEDED")
            return

        prompt_bytes = context_pack.get("promptContextBytes", 0)
        if isinstance(prompt_bytes, int) and prompt_bytes > self.config.MAX_CONTEXT_PROMPT_BYTES:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(400, {
                "error": {
                    "code": "CONTEXT_TOO_LARGE",
                    "message": f"ContextPack prompt bytes ({prompt_bytes}) exceeds {self.config.MAX_CONTEXT_PROMPT_BYTES} limit."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="CONTEXT_TOO_LARGE")
            logger.log_request(req_id, inst_id, duration_ms, 400, "CONTEXT_TOO_LARGE")
            return

        # 8. Execute Upstream Inference
        options = payload.get("options", {})
        try:
            inference_result = self.upstream_client.generate_answer(question, items, options)
        except Exception as e:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(502, {
                "error": {
                    "code": "UPSTREAM_ERROR",
                    "message": "Upstream inference failure."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="UPSTREAM_ERROR")
            logger.log_request(req_id, inst_id, duration_ms, 502, "UPSTREAM_ERROR")
            return

        # 9. Enforce Citation Boundary
        valid_memory_ids = {str(item.get("memoryId", "")) for item in items if item.get("memoryId")}
        raw_cited_ids = inference_result.get("citedMemoryIds", [])
        clean_cited_ids = []
        seen = set()
        for cid in raw_cited_ids:
            cid_str = str(cid)
            if cid_str in valid_memory_ids and cid_str not in seen:
                clean_cited_ids.append(cid_str)
                seen.add(cid_str)

        evidence_state = inference_result.get("evidenceState", "INSUFFICIENT")
        if evidence_state == "NO_EVIDENCE":
            clean_cited_ids = []

        duration_ms = int((time.time() - start_time) * 1000)
        prompt_tokens = inference_result.get("promptTokens", 0)
        completion_tokens = inference_result.get("completionTokens", 0)

        response_data = {
            "requestId": req_id,
            "answer": inference_result.get("answer", ""),
            "evidenceState": evidence_state,
            "citedMemoryIds": clean_cited_ids,
            "model": inference_result.get("model", self.config.UPSTREAM_MODEL),
            "usage": {
                "promptTokens": prompt_tokens,
                "completionTokens": completion_tokens
            }
        }

        # Validate response size
        resp_bytes = json.dumps(response_data, ensure_ascii=False).encode("utf-8")
        if len(resp_bytes) > self.config.MAX_RESPONSE_BYTES:
            self._send_json(500, {
                "error": {
                    "code": "INTERNAL_ERROR",
                    "message": "Response size exceeded maximum threshold."
                }
            }, request_id=req_id, installation_id=inst_id, error_code="INTERNAL_ERROR")
            logger.log_request(req_id, inst_id, duration_ms, 500, "INTERNAL_ERROR")
            return

        self._send_json(200, response_data, request_id=req_id, installation_id=inst_id)
        logger.log_request(
            req_id,
            inst_id,
            duration_ms,
            200,
            None,
            prompt_tokens,
            completion_tokens
        )

def run_server(host=ServerConfig.HOST, port=ServerConfig.PORT):
    server = http.server.ThreadingHTTPServer((host, port), CayanaApiHandler)
    print(f"Cayana LLM API Server listening on {host}:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

if __name__ == "__main__":
    run_server()
