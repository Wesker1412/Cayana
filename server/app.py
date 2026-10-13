import http.server
import json
import time
import uuid
import re
import urllib.parse
from typing import Tuple, Dict, Any, List
from .config import ServerConfig
from .rate_limiter import BoundedSlidingWindowRateLimiter
from .logger import logger
from .notion_oauth_broker import NotionOAuthBroker, NotionOAuthConfigError
from .upstream_client import (
    UpstreamLlmClient,
    UpstreamNotConfiguredError,
    UpstreamPayloadTooLargeError,
    UpstreamSecurityError,
    UpstreamException,
    build_upstream_user_prompt,
    calculate_actual_context_bytes
)

SAFE_REQ_ID_REGEX = re.compile(r"^[a-zA-Z0-9_\-]{1,64}$")
SAFE_INSTALLATION_ID_REGEX = re.compile(r"^[a-zA-Z0-9_\-]{8,128}$")
SAFE_MEMORY_ID_REGEX = re.compile(r"^[a-zA-Z0-9_\-\.]{1,128}$")

VALID_SOURCE_TYPES = {
    "SCREENSHOT",
    "PHOTO",
    "RECORDING",
    "DOWNLOAD",
    "SHARED_URL",
    "SHARED_TEXT",
    "SHARED_IMAGE",
    "SHARED_DOCUMENT",
    "SHARED_FILE"
}

class CayanaApiHandler(http.server.BaseHTTPRequestHandler):
    config = ServerConfig()
    rate_limiter = BoundedSlidingWindowRateLimiter(
        max_requests_per_window=ServerConfig.RATE_LIMIT_PER_MINUTE,
        ip_max_requests=ServerConfig.IP_RATE_LIMIT_PER_MINUTE,
        global_max_requests=ServerConfig.GLOBAL_RATE_LIMIT_PER_MINUTE,
        window_seconds=60.0,
        max_entries=ServerConfig.MAX_RATE_LIMITER_ENTRIES
    )
    upstream_client = UpstreamLlmClient(config)
    notion_broker = NotionOAuthBroker(config)

    def _send_json(
        self,
        status_code: int,
        data: dict,
        request_id: str = None,
        installation_id: str = None,
        error_code: str = None
    ):
        body_bytes = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body_bytes)))
        if request_id and SAFE_REQ_ID_REGEX.match(request_id):
            self.send_header("X-Cayana-Request-Id", request_id)
        self.end_headers()
        self.wfile.write(body_bytes)

    def do_GET(self):
        start_time = time.time()
        self.notion_broker.config = self.config
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = parsed.query
        try:
            if path == "/health":
                self._send_json(200, {
                    "status": "ok",
                    "service": "cayana-llm-api",
                    "version": "0.1.0"
                })
                duration_ms = int((time.time() - start_time) * 1000)
                logger.log_request("health", "", duration_ms, 200)
            elif path == "/v1/oauth/notion/initiate":
                self._send_json(405, {
                    "error": {"code": "METHOD_NOT_ALLOWED", "message": "GET /v1/oauth/notion/initiate is not allowed. Use POST."}
                })
                return
            elif path == "/v1/oauth/notion/callback":
                client_ip = self.client_address[0] if hasattr(self, "client_address") and self.client_address else "127.0.0.1"
                allowed, rejection_reason = self.rate_limiter.check_allowed(
                    installation_id=f"oauth_cb_{client_ip}",
                    client_ip=client_ip
                )
                if not allowed:
                    self._send_json(429, {
                        "error": {"code": "RATE_LIMITED", "message": f"Too many requests ({rejection_reason}). Please try again later."}
                    }, error_code="RATE_LIMITED")
                    return

                query_params = urllib.parse.parse_qs(query)
                error = query_params.get("error", [None])[0]
                if error:
                    state = query_params.get("state", [""])[0]
                    # Sanitize error to prevent injection
                    safe_error = re.sub(r"[^a-zA-Z0-9_-]", "", error)[:64]
                    safe_state = re.sub(r"[^a-zA-Z0-9_-]", "", state)[:128]
                    redirect_target = f"cayana://oauth/notion/callback?error={urllib.parse.quote(safe_error)}&state={urllib.parse.quote(safe_state)}"
                    self.send_response(302)
                    self.send_header("Location", redirect_target)
                    self.end_headers()
                    return

                code = query_params.get("code", [None])[0]
                state = query_params.get("state", [None])[0]
                success, target_url, _ = self.notion_broker.handle_notion_callback(code, state)
                if not success:
                    self._send_json(400, {"error": {"code": target_url, "message": "Failed processing OAuth callback"}})
                    return

                self.send_response(302)
                self.send_header("Location", target_url)
                self.end_headers()
            else:
                self._send_json(404, {
                    "error": {
                        "code": "NOT_FOUND",
                        "message": "Resource not found"
                    }
                })
        except Exception:
            self._send_json(500, {
                "error": {"code": "INTERNAL_ERROR", "message": "An internal server error occurred."}
            })

    def do_POST(self):
        start_time = time.time()
        self.notion_broker.config = self.config
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        req_id = ""
        inst_id = ""
        client_ip = self.client_address[0] if hasattr(self, "client_address") and self.client_address else "127.0.0.1"

        try:
            # 0. Global Safety Kill Switch
            if self.config.KILL_SWITCH:
                self._send_json(503, {
                    "error": {
                        "code": "SERVICE_UNAVAILABLE",
                        "message": "Service is currently paused via safety kill switch."
                    }
                }, request_id="kill-switch", error_code="SERVICE_UNAVAILABLE")
                logger.log_request("kill-switch", "", 0, 503, "SERVICE_UNAVAILABLE")
                return

            # Notion OAuth routes with rate limiting and bounded inputs
            if path in ("/v1/oauth/notion/initiate", "/v1/oauth/notion/exchange", "/v1/oauth/notion/refresh"):
                allowed, rejection_reason = self.rate_limiter.check_allowed(
                    installation_id=f"oauth_{client_ip}",
                    client_ip=client_ip
                )
                if not allowed:
                    self._send_json(429, {
                        "error": {"code": "RATE_LIMITED", "message": f"Too many requests ({rejection_reason}). Please try again later."}
                    }, error_code="RATE_LIMITED")
                    return

            if path == "/v1/oauth/notion/initiate":
                content_length = int(self.headers.get("Content-Length", 0))
                if content_length > 4096:
                    self._send_json(413, {"error": {"code": "PAYLOAD_TOO_LARGE", "message": "Payload exceeds 4KB limit"}})
                    return
                body = {}
                if content_length > 0:
                    raw_body = self.rfile.read(content_length)
                    try:
                        body = json.loads(raw_body.decode("utf-8"))
                    except Exception:
                        self._send_json(400, {"error": {"code": "INVALID_JSON", "message": "Malformed JSON payload"}})
                        return
                code_challenge = body.get("code_challenge", "")
                if not code_challenge:
                    self._send_json(400, {"error": {"code": "MISSING_CODE_CHALLENGE", "message": "code_challenge is required"}})
                    return
                redirect_uri = body.get("redirect_uri")
                try:
                    auth_url, state = self.notion_broker.create_authorization_url(code_challenge, redirect_uri)
                    self._send_json(200, {
                        "authorize_url": auth_url,
                        "state": state
                    })
                except NotionOAuthConfigError as e:
                    self._send_json(503, {
                        "error": {"code": "NOTION_OAUTH_NOT_CONFIGURED", "message": str(e)}
                    })
                except ValueError as e:
                    self._send_json(400, {
                        "error": {"code": "INVALID_REQUEST", "message": str(e)}
                    })
                return

            if path == "/v1/oauth/notion/exchange":
                content_length = int(self.headers.get("Content-Length", 0))
                if content_length > 4096:
                    self._send_json(413, {"error": {"code": "PAYLOAD_TOO_LARGE", "message": "Payload exceeds 4KB limit"}})
                    return
                raw_body = self.rfile.read(content_length)
                try:
                    body = json.loads(raw_body.decode("utf-8"))
                except Exception:
                    self._send_json(400, {"error": {"code": "INVALID_JSON", "message": "Malformed JSON payload"}})
                    return

                ticket = body.get("ticket", "")
                state = body.get("state", "")
                code_verifier = body.get("code_verifier", "")
                status, res = self.notion_broker.exchange_ticket(ticket, state, code_verifier)
                self._send_json(status, res)
                return

            if path == "/v1/oauth/notion/refresh":
                content_length = int(self.headers.get("Content-Length", 0))
                if content_length > 4096:
                    self._send_json(413, {"error": {"code": "PAYLOAD_TOO_LARGE", "message": "Payload exceeds 4KB limit"}})
                    return
                raw_body = self.rfile.read(content_length)
                try:
                    body = json.loads(raw_body.decode("utf-8"))
                except Exception:
                    self._send_json(400, {"error": {"code": "INVALID_JSON", "message": "Malformed JSON payload"}})
                    return

                refresh_token = body.get("refresh_token", "")
                status, res = self.notion_broker.refresh_token(refresh_token)
                self._send_json(status, res)
                return

            # Path check for inference
            if path != "/v1/ask":
                self._send_json(404, {
                    "error": {"code": "NOT_FOUND", "message": "Resource not found"}
                }, request_id="not-found", error_code="NOT_FOUND")
                return

            # 1. Request ID Validation (Header or fallback)
            raw_hdr_req_id = self.headers.get("X-Cayana-Request-Id", "").strip()
            if raw_hdr_req_id:
                if not SAFE_REQ_ID_REGEX.match(raw_hdr_req_id):
                    self._send_json(400, {
                        "error": {
                            "code": "INVALID_REQUEST",
                            "message": "Request ID header contains invalid or unsafe characters. Must be alphanumeric (1-64 chars)."
                        }
                    }, error_code="INVALID_REQUEST")
                    logger.log_request("invalid-req-id", "", 0, 400, "INVALID_REQUEST")
                    return
                req_id = raw_hdr_req_id

            # 2. Content-Length Validation and Bounded Body Read
            raw_cl = self.headers.get("Content-Length")
            if raw_cl is None:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(411, {
                    "error": {
                        "code": "LENGTH_REQUIRED",
                        "message": "Content-Length header is required for POST /v1/ask."
                    }
                }, request_id=req_id, error_code="LENGTH_REQUIRED")
                logger.log_request(req_id, "", duration_ms, 411, "LENGTH_REQUIRED")
                return

            raw_cl_clean = raw_cl.strip()
            if not re.match(r"^\d+$", raw_cl_clean):
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(400, {
                    "error": {
                        "code": "INVALID_CONTENT_LENGTH",
                        "message": "Content-Length must be a valid non-negative integer."
                    }
                }, request_id=req_id, error_code="INVALID_CONTENT_LENGTH")
                logger.log_request(req_id, "", duration_ms, 400, "INVALID_CONTENT_LENGTH")
                return

            try:
                content_length = int(raw_cl_clean)
            except ValueError:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(400, {
                    "error": {
                        "code": "INVALID_CONTENT_LENGTH",
                        "message": "Content-Length must be a valid integer."
                    }
                }, request_id=req_id, error_code="INVALID_CONTENT_LENGTH")
                logger.log_request(req_id, "", duration_ms, 400, "INVALID_CONTENT_LENGTH")
                return

            if content_length < 0:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(400, {
                    "error": {
                        "code": "INVALID_CONTENT_LENGTH",
                        "message": "Content-Length cannot be negative."
                    }
                }, request_id=req_id, error_code="INVALID_CONTENT_LENGTH")
                logger.log_request(req_id, "", duration_ms, 400, "INVALID_CONTENT_LENGTH")
                return

            if content_length == 0:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(400, {
                    "error": {
                        "code": "EMPTY_PAYLOAD",
                        "message": "Request payload cannot be empty."
                    }
                }, request_id=req_id, error_code="EMPTY_PAYLOAD")
                logger.log_request(req_id, "", duration_ms, 400, "EMPTY_PAYLOAD")
                return

            if content_length > self.config.MAX_REQUEST_BYTES:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(413, {
                    "error": {
                        "code": "PAYLOAD_TOO_LARGE",
                        "message": f"Request body ({content_length} bytes) exceeds {self.config.MAX_REQUEST_BYTES} bytes limit."
                    }
                }, request_id=req_id, error_code="PAYLOAD_TOO_LARGE")
                logger.log_request(req_id, "", duration_ms, 413, "PAYLOAD_TOO_LARGE")
                return

            # Read Request Body with bounded buffer chunks
            chunks = []
            bytes_remaining = content_length
            while bytes_remaining > 0:
                chunk_size = min(bytes_remaining, 4096)
                chunk = self.rfile.read(chunk_size)
                if not chunk:
                    break
                chunks.append(chunk)
                bytes_remaining -= len(chunk)

            raw_body = b"".join(chunks)
            if len(raw_body) != content_length:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(400, {
                    "error": {
                        "code": "INCOMPLETE_BODY",
                        "message": f"Expected {content_length} bytes, but received {len(raw_body)} bytes."
                    }
                }, request_id=req_id, error_code="INCOMPLETE_BODY")
                logger.log_request(req_id, "", duration_ms, 400, "INCOMPLETE_BODY")
                return

            # 3. Parse JSON safely
            try:
                payload = json.loads(raw_body.decode("utf-8"))
            except Exception:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(400, {
                    "error": {
                        "code": "INVALID_REQUEST",
                        "message": "Malformed JSON request body."
                    }
                }, request_id=req_id, error_code="INVALID_REQUEST")
                logger.log_request(req_id, "", duration_ms, 400, "INVALID_REQUEST")
                return

            if not isinstance(payload, dict):
                self._send_json(400, {
                    "error": {"code": "INVALID_REQUEST", "message": "Request payload must be a JSON object."}
                }, request_id=req_id, error_code="INVALID_REQUEST")
                return

            # Extract & validate Request ID from body if not in header
            if not req_id:
                raw_body_req_id = str(payload.get("requestId", "")).strip()
                if raw_body_req_id:
                    if not SAFE_REQ_ID_REGEX.match(raw_body_req_id):
                        self._send_json(400, {
                            "error": {
                                "code": "INVALID_REQUEST",
                                "message": "Body requestId contains invalid or unsafe characters. Must be alphanumeric (1-64 chars)."
                            }
                        }, error_code="INVALID_REQUEST")
                        return
                    req_id = raw_body_req_id
                else:
                    req_id = str(uuid.uuid4())

            # 4. Anonymous Installation ID Extraction & Validation
            inst_id = self.headers.get("X-Cayana-Installation-Id", "").strip()
            if not inst_id:
                inst_id = str(payload.get("installationId", "")).strip()

            if not inst_id or not SAFE_INSTALLATION_ID_REGEX.match(inst_id):
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(401, {
                    "error": {
                        "code": "UNAUTHORIZED",
                        "message": "Missing or invalid anonymous installation credential."
                    }
                }, request_id=req_id, error_code="UNAUTHORIZED")
                logger.log_request(req_id, "", duration_ms, 401, "UNAUTHORIZED")
                return

            # 5. Guard Against Unprotected Paid Inference
            if not self.config.TEST_MODE and not self.config.ALLOW_UNPROTECTED_PAID_INFERENCE:
                # Production default fail-closed: reject paid inference unless server protection is active
                if not self.config.SERVER_API_KEY:
                    duration_ms = int((time.time() - start_time) * 1000)
                    self._send_json(503, {
                        "error": {
                            "code": "SERVER_PROTECTION_NOT_CONFIGURED",
                            "message": "Paid inference is disabled: server protection is not configured in production mode."
                        }
                    }, request_id=req_id, installation_id=inst_id, error_code="SERVER_PROTECTION_NOT_CONFIGURED")
                    logger.log_request(req_id, inst_id, duration_ms, 503, "SERVER_PROTECTION_NOT_CONFIGURED")
                    return

            if self.config.SERVER_API_KEY:
                auth_header = self.headers.get("Authorization", "").strip()
                client_secret = self.headers.get("X-Cayana-App-Secret", "").strip()
                bearer_token = auth_header[7:].strip() if auth_header.startswith("Bearer ") else ""
                is_authorized = (client_secret == self.config.SERVER_API_KEY) or (bearer_token == self.config.SERVER_API_KEY)
                if not is_authorized:
                    duration_ms = int((time.time() - start_time) * 1000)
                    self._send_json(401, {
                        "error": {
                            "code": "UNAUTHORIZED",
                            "message": "Access restricted: valid application secret or authorization token required."
                        }
                    }, request_id=req_id, installation_id=inst_id, error_code="UNAUTHORIZED")
                    logger.log_request(req_id, inst_id, duration_ms, 401, "UNAUTHORIZED")
                    return

            # 6. Multi-tier Rate Limiting & Abuse Defense
            allowed, rejection_reason = self.rate_limiter.check_allowed(
                installation_id=inst_id,
                client_ip=client_ip
            )
            if not allowed:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(429, {
                    "error": {
                        "code": "RATE_LIMITED",
                        "message": f"Rate limit exceeded ({rejection_reason}). Please wait before retrying."
                    }
                }, request_id=req_id, installation_id=inst_id, error_code="RATE_LIMITED")
                logger.log_request(req_id, inst_id, duration_ms, 429, "RATE_LIMITED")
                return

            # 7. Validate Question
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

            # 8. Validate ContextPack Structure and Schema
            context_pack = payload.get("contextPack", {})
            if not isinstance(context_pack, dict):
                self._send_json(400, {
                    "error": {"code": "INVALID_REQUEST", "message": "contextPack must be a JSON object."}
                }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                return

            items = context_pack.get("items", [])
            if not isinstance(items, list):
                self._send_json(400, {
                    "error": {"code": "INVALID_REQUEST", "message": "contextPack.items must be a list."}
                }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                return

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

            # Validate each item schema
            for idx, item in enumerate(items):
                if not isinstance(item, dict):
                    self._send_json(400, {
                        "error": {
                            "code": "INVALID_REQUEST",
                            "message": f"ContextPack item at index {idx} must be a JSON object."
                        }
                    }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                    return

                mem_id = item.get("memoryId")
                if not isinstance(mem_id, str) or not SAFE_MEMORY_ID_REGEX.match(mem_id):
                    self._send_json(400, {
                        "error": {
                            "code": "INVALID_REQUEST",
                            "message": f"Item at index {idx} contains invalid memoryId (must be alphanumeric/dash/dot up to {self.config.MAX_MEMORY_ID_CHARS} chars)."
                        }
                    }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                    return

                snippet = item.get("snippet")
                if not isinstance(snippet, str) or len(snippet) > self.config.MAX_SNIPPET_CHARS:
                    self._send_json(400, {
                        "error": {
                            "code": "INVALID_REQUEST",
                            "message": f"Item at index {idx} snippet must be a string up to {self.config.MAX_SNIPPET_CHARS} chars."
                        }
                    }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                    return

                source_type = item.get("sourceType")
                if not isinstance(source_type, str) or source_type not in VALID_SOURCE_TYPES:
                    self._send_json(400, {
                        "error": {
                            "code": "INVALID_REQUEST",
                            "message": f"Item at index {idx} contains invalid sourceType '{source_type}'."
                        }
                    }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                    return

                captured_at = item.get("capturedAt")
                if captured_at is not None and (not isinstance(captured_at, (int, float)) or captured_at < 0):
                    self._send_json(400, {
                        "error": {
                            "code": "INVALID_REQUEST",
                            "message": f"Item at index {idx} contains invalid capturedAt timestamp."
                        }
                    }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                    return

                captured_date_iso = item.get("capturedDateIso")
                if captured_date_iso is not None and (not isinstance(captured_date_iso, str) or len(captured_date_iso) > 64):
                    self._send_json(400, {
                        "error": {
                            "code": "INVALID_REQUEST",
                            "message": f"Item at index {idx} contains invalid capturedDateIso string."
                        }
                    }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                    return

            # 9. Server-Measured Context Budget Verification (Do not trust client-claimed bytes)
            actual_context_bytes = calculate_actual_context_bytes(question, items)

            if actual_context_bytes > self.config.MAX_CONTEXT_PROMPT_BYTES:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(400, {
                    "error": {
                        "code": "CONTEXT_TOO_LARGE",
                        "message": f"Formatted context prompt budget ({actual_context_bytes} bytes) exceeds {self.config.MAX_CONTEXT_PROMPT_BYTES} bytes limit."
                    }
                }, request_id=req_id, installation_id=inst_id, error_code="CONTEXT_TOO_LARGE")
                logger.log_request(req_id, inst_id, duration_ms, 400, "CONTEXT_TOO_LARGE")
                return

            client_prompt_bytes = context_pack.get("promptContextBytes", 0)
            if isinstance(client_prompt_bytes, int) and client_prompt_bytes > self.config.MAX_CONTEXT_PROMPT_BYTES:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(400, {
                    "error": {
                        "code": "CONTEXT_TOO_LARGE",
                        "message": f"ContextPack prompt bytes ({client_prompt_bytes}) exceeds {self.config.MAX_CONTEXT_PROMPT_BYTES} limit."
                    }
                }, request_id=req_id, installation_id=inst_id, error_code="CONTEXT_TOO_LARGE")
                logger.log_request(req_id, inst_id, duration_ms, 400, "CONTEXT_TOO_LARGE")
                return

            # 10. Validate Options (if present)
            options = payload.get("options", {})
            if options:
                if not isinstance(options, dict):
                    self._send_json(400, {
                        "error": {"code": "INVALID_REQUEST", "message": "options must be a JSON object."}
                    }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                    return
                if "temperature" in options:
                    temp = options["temperature"]
                    if not isinstance(temp, (int, float)) or not (0.0 <= temp <= 2.0):
                        self._send_json(400, {
                            "error": {"code": "INVALID_REQUEST", "message": "options.temperature must be between 0.0 and 2.0."}
                        }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                        return
                if "maxTokens" in options:
                    mt = options["maxTokens"]
                    if not isinstance(mt, int) or not (1 <= mt <= 2048):
                        self._send_json(400, {
                            "error": {"code": "INVALID_REQUEST", "message": "options.maxTokens must be an integer between 1 and 2048."}
                        }, request_id=req_id, installation_id=inst_id, error_code="INVALID_REQUEST")
                        return

            # 11. Execute Upstream Inference
            try:
                inference_result = self.upstream_client.generate_answer(question, items, options)
            except UpstreamNotConfiguredError:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(503, {
                    "error": {
                        "code": "UPSTREAM_NOT_CONFIGURED",
                        "message": "Upstream LLM provider is not configured in production mode."
                    }
                }, request_id=req_id, installation_id=inst_id, error_code="UPSTREAM_NOT_CONFIGURED")
                logger.log_request(req_id, inst_id, duration_ms, 503, "UPSTREAM_NOT_CONFIGURED")
                return
            except (UpstreamPayloadTooLargeError, UpstreamSecurityError, UpstreamException) as e:
                duration_ms = int((time.time() - start_time) * 1000)
                self._send_json(502, {
                    "error": {
                        "code": "UPSTREAM_ERROR",
                        "message": f"Upstream inference failure: {str(e)}"
                    }
                }, request_id=req_id, installation_id=inst_id, error_code="UPSTREAM_ERROR")
                logger.log_request(req_id, inst_id, duration_ms, 502, "UPSTREAM_ERROR")
                return

            # 12. Enforce Citation Boundary
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

            # 13. Validate response size
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
        except Exception:
            duration_ms = int((time.time() - start_time) * 1000)
            self._send_json(500, {
                "error": {
                    "code": "INTERNAL_ERROR",
                    "message": "An unexpected server error occurred."
                }
            }, request_id=req_id or "unknown", installation_id=inst_id or "", error_code="INTERNAL_ERROR")
            logger.log_request(req_id or "unknown", inst_id or "", duration_ms, 500, "INTERNAL_ERROR")

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
