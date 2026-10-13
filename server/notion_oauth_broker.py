import base64
import hashlib
import hmac
import json
import secrets
import threading
import time
import urllib.parse
import urllib.request
import urllib.error
from typing import Tuple, Dict, Any, Optional
from .config import ServerConfig

class NotionOAuthConfigError(Exception):
    pass

class NotionOAuthBroker:
    """
    Minimal, privacy-preserving OAuth Broker for Notion.
    - Strictly handles authorization code exchange and token refresh.
    - Notion Client Secret exists ONLY in server environment variables.
    - Zero memory, notes, or user content stored or processed.
    - Strict state validation, PKCE-style code verifier validation, and atomic single-use tickets.
    - Fixed configured redirect URI; callers cannot replace redirect URI.
    - Thread-safe memory store guarded by threading.Lock.
    
    MULTI-WORKER / SERVERLESS DEPLOYMENT ARCHITECTURE NOTE:
    In single-instance deployments, state and relay tickets are stored in-memory
    guarded by threading.Lock with strict TTL cleanup.
    In multi-worker (e.g. Gunicorn/Uvicorn multi-process) or serverless (e.g. AWS Lambda/Cloud Run) deployments:
    1. Shared Cache Store: Use an external Redis/Memcached cluster. Operations such as
       validate_and_consume_ticket use atomic GETDEL (Redis 6.2+) or Lua scripts to ensure
       exact-once single-use consumption across workers.
    2. Stateless Signed Tokens: Alternatively, the 'state' and 'ticket' can be cryptographically
       signed HMAC/AES tokens containing (challenge, created_at, expiry, nonce) so any worker can
       verify authenticity statelessly, coupled with a distributed bloom filter or short-TTL nonce
       blacklist for replay prevention.
    In all cases, NO user accounts, database credentials, or Memory items are stored on the server.
    """

    STATE_TTL_SECONDS = 600       # 10 minutes
    TICKET_TTL_SECONDS = 60       # 1 minute single-use relay ticket
    CODE_TTL_SECONDS = 3600       # 1 hour replay window
    MAX_TRACKED_ENTRIES = 5000

    def __init__(self, config: ServerConfig = None):
        self.config = config or ServerConfig()
        self.lock = threading.Lock()
        self._active_states: Dict[str, dict] = {}
        self._relay_tickets: Dict[str, dict] = {}
        self._consumed_codes: Dict[str, float] = {}

    def _cleanup_expired_locked(self):
        """Must be called while holding self.lock."""
        now = time.time()
        # Clean expired states
        expired_states = [s for s, data in self._active_states.items() if now - data.get("created_at", 0) > self.STATE_TTL_SECONDS]
        for s in expired_states:
            self._active_states.pop(s, None)

        # Clean expired relay tickets
        expired_tickets = [t for t, data in self._relay_tickets.items() if now - data.get("created_at", 0) > self.TICKET_TTL_SECONDS]
        for t in expired_tickets:
            self._relay_tickets.pop(t, None)

        # Clean expired consumed codes
        expired_codes = [c for c, t in self._consumed_codes.items() if now - t > self.CODE_TTL_SECONDS]
        for c in expired_codes:
            self._consumed_codes.pop(c, None)

        # Enforce hard cap to prevent memory exhaustion
        if len(self._active_states) > self.MAX_TRACKED_ENTRIES:
            sorted_states = sorted(self._active_states.items(), key=lambda kv: kv[1].get("created_at", 0))
            for k, _ in sorted_states[:len(self._active_states) - self.MAX_TRACKED_ENTRIES]:
                self._active_states.pop(k, None)

        if len(self._relay_tickets) > self.MAX_TRACKED_ENTRIES:
            sorted_tickets = sorted(self._relay_tickets.items(), key=lambda kv: kv[1].get("created_at", 0))
            for k, _ in sorted_tickets[:len(self._relay_tickets) - self.MAX_TRACKED_ENTRIES]:
                self._relay_tickets.pop(k, None)

        if len(self._consumed_codes) > self.MAX_TRACKED_ENTRIES:
            sorted_codes = sorted(self._consumed_codes.items(), key=lambda kv: kv[1])
            for k, _ in sorted_codes[:len(self._consumed_codes) - self.MAX_TRACKED_ENTRIES]:
                self._consumed_codes.pop(k, None)

    def is_configured(self) -> bool:
        return bool(self.config.NOTION_CLIENT_ID and self.config.NOTION_CLIENT_SECRET)

    def create_authorization_url(self, code_challenge: str, redirect_uri: Optional[str] = None) -> Tuple[str, str]:
        if not self.config.NOTION_CLIENT_ID or not self.config.NOTION_CLIENT_SECRET:
            raise NotionOAuthConfigError("Notion client credentials are not configured on server")

        if not code_challenge or not isinstance(code_challenge, str) or len(code_challenge) < 16 or len(code_challenge) > 256:
            raise ValueError("code_challenge is required and must be between 16 and 256 characters")

        # Redirect URI must match configured redirect URI (prevent open redirection)
        configured_redirect = self.config.NOTION_REDIRECT_URI
        if redirect_uri and redirect_uri != configured_redirect:
            raise ValueError("redirect_uri cannot be replaced; must match configured redirect URI")

        with self.lock:
            self._cleanup_expired_locked()
            state = secrets.token_urlsafe(32)
            self._active_states[state] = {
                "created_at": time.time(),
                "code_challenge": code_challenge,
                "redirect_uri": configured_redirect
            }

        params = {
            "client_id": self.config.NOTION_CLIENT_ID,
            "response_type": "code",
            "owner": "user",
            "redirect_uri": configured_redirect,
            "state": state
        }
        url = f"{self.config.NOTION_AUTH_URL}?{urllib.parse.urlencode(params)}"
        return url, state

    def handle_notion_callback(self, code: Optional[str], state: Optional[str]) -> Tuple[bool, str, Optional[str]]:
        """
        Receives Notion callback code and state.
        Validates state atomically, pops it, and generates a single-use relay_ticket.
        Returns: (success, redirect_url_or_error_code, state)
        """
        if not state or not isinstance(state, str):
            return False, "INVALID_STATE", None

        with self.lock:
            self._cleanup_expired_locked()
            if state not in self._active_states:
                return False, "INVALID_STATE", state

            state_data = self._active_states.pop(state)  # ATOMIC SINGLE-USE CONSUMPTION OF STATE
            now = time.time()
            if now - state_data.get("created_at", 0) > self.STATE_TTL_SECONDS:
                return False, "STATE_EXPIRED", state

            if not code or not isinstance(code, str):
                return False, "MISSING_CODE", state

            ticket = secrets.token_urlsafe(32)
            self._relay_tickets[ticket] = {
                "created_at": now,
                "notion_code": code,
                "code_challenge": state_data["code_challenge"],
                "state": state,
                "redirect_uri": state_data["redirect_uri"]
            }

        # Raw code is NEVER placed in custom scheme; only the single-use ticket
        redirect_url = f"cayana://oauth/notion/callback?ticket={urllib.parse.quote(ticket)}&state={urllib.parse.quote(state)}"
        return True, redirect_url, state

    def exchange_ticket(self, ticket: str, state: str, code_verifier: str) -> Tuple[int, dict]:
        """
        Exchanges a single-use relay_ticket for Notion tokens, strictly verified against code_verifier.
        Atomic single-use under concurrency: ticket is consumed immediately upon first validation attempt.
        """
        if not ticket or not isinstance(ticket, str) or len(ticket) > 256:
            return 400, {"error": {"code": "INVALID_TICKET", "message": "Ticket is missing or invalid"}}

        if not state or not isinstance(state, str) or len(state) > 256:
            return 400, {"error": {"code": "INVALID_STATE", "message": "State is missing or invalid"}}

        if not code_verifier or not isinstance(code_verifier, str) or len(code_verifier) > 256:
            return 400, {"error": {"code": "INVALID_CODE_VERIFIER", "message": "Code verifier is missing or invalid"}}

        if not self.is_configured():
            return 503, {"error": {"code": "NOTION_OAUTH_NOT_CONFIGURED", "message": "Notion OAuth client credentials are not configured on server"}}

        with self.lock:
            self._cleanup_expired_locked()
            if ticket not in self._relay_tickets:
                return 400, {"error": {"code": "INVALID_TICKET", "message": "Invalid, expired, or already used ticket"}}

            ticket_data = self._relay_tickets.pop(ticket)  # ATOMIC SINGLE-USE
            now = time.time()
            if now - ticket_data.get("created_at", 0) > self.TICKET_TTL_SECONDS:
                return 400, {"error": {"code": "TICKET_EXPIRED", "message": "Relay ticket expired"}}

            if ticket_data.get("state") != state:
                return 400, {"error": {"code": "INVALID_STATE", "message": "State mismatch for ticket"}}

            # Verify PKCE code_verifier: SHA-256 base64url matching code_challenge
            expected_challenge = base64.urlsafe_b64encode(
                hashlib.sha256(code_verifier.encode("utf-8")).digest()
            ).decode("utf-8").rstrip("=")
            actual_challenge = ticket_data.get("code_challenge", "").rstrip("=")

            if not hmac.compare_digest(expected_challenge, actual_challenge):
                # Ticket was popped, so eavesdropper cannot brute-force
                return 401, {"error": {"code": "INVALID_CODE_VERIFIER", "message": "Code verifier does not match challenge"}}

            notion_code = ticket_data["notion_code"]
            if notion_code in self._consumed_codes:
                return 409, {"error": {"code": "CODE_REPLAY_DETECTED", "message": "Authorization code has already been exchanged"}}

            self._consumed_codes[notion_code] = now
            effective_redirect = ticket_data["redirect_uri"]

        # Exchange authorization code with official Notion token endpoint
        creds = f"{self.config.NOTION_CLIENT_ID}:{self.config.NOTION_CLIENT_SECRET}"
        basic_auth = base64.b64encode(creds.encode("utf-8")).decode("utf-8")

        payload = {
            "grant_type": "authorization_code",
            "code": notion_code,
            "redirect_uri": effective_redirect
        }
        payload_bytes = json.dumps(payload).encode("utf-8")

        headers = {
            "Authorization": f"Basic {basic_auth}",
            "Content-Type": "application/json",
            "Notion-Version": self.config.NOTION_API_VERSION
        }

        req = urllib.request.Request(
            self.config.NOTION_TOKEN_URL,
            data=payload_bytes,
            headers=headers,
            method="POST"
        )

        try:
            with urllib.request.urlopen(req, timeout=self.config.REQUEST_TIMEOUT_SECONDS) as resp:
                resp_bytes = resp.read()
                resp_json = json.loads(resp_bytes.decode("utf-8"))
                return resp.status, resp_json
        except urllib.error.HTTPError as e:
            try:
                err_raw = e.read().decode("utf-8")
                err_data = json.loads(err_raw)
                err_code = err_data.get("code") or err_data.get("error", "UPSTREAM_ERROR")
            except Exception:
                err_code = "UPSTREAM_ERROR"
            return e.code, {"error": {"code": err_code, "message": "Notion upstream token exchange failed"}}
        except urllib.error.URLError as e:
            return 502, {"error": {"code": "UPSTREAM_UNAVAILABLE", "message": f"Failed reaching Notion token endpoint: {e.reason}"}}
        except Exception as e:
            return 500, {"error": {"code": "INTERNAL_ERROR", "message": f"Unexpected broker error: {type(e).__name__}"}}

    def refresh_token(self, refresh_token: str) -> Tuple[int, dict]:
        if not refresh_token or not isinstance(refresh_token, str) or len(refresh_token) > 1024:
            return 400, {"error": {"code": "INVALID_REFRESH_TOKEN", "message": "Refresh token is missing or invalid"}}

        if not self.is_configured():
            return 503, {"error": {"code": "NOTION_OAUTH_NOT_CONFIGURED", "message": "Notion OAuth client credentials are not configured on server"}}

        creds = f"{self.config.NOTION_CLIENT_ID}:{self.config.NOTION_CLIENT_SECRET}"
        basic_auth = base64.b64encode(creds.encode("utf-8")).decode("utf-8")

        payload = {
            "grant_type": "refresh_token",
            "refresh_token": refresh_token
        }
        payload_bytes = json.dumps(payload).encode("utf-8")

        headers = {
            "Authorization": f"Basic {basic_auth}",
            "Content-Type": "application/json",
            "Notion-Version": self.config.NOTION_API_VERSION
        }

        req = urllib.request.Request(
            self.config.NOTION_TOKEN_URL,
            data=payload_bytes,
            headers=headers,
            method="POST"
        )

        try:
            with urllib.request.urlopen(req, timeout=self.config.REQUEST_TIMEOUT_SECONDS) as resp:
                resp_bytes = resp.read()
                resp_json = json.loads(resp_bytes.decode("utf-8"))
                return resp.status, resp_json
        except urllib.error.HTTPError as e:
            try:
                err_raw = e.read().decode("utf-8")
                err_data = json.loads(err_raw)
                err_code = err_data.get("code") or err_data.get("error", "UPSTREAM_ERROR")
            except Exception:
                err_code = "UPSTREAM_ERROR"
            return e.code, {"error": {"code": err_code, "message": "Notion upstream token refresh failed"}}
        except urllib.error.URLError as e:
            return 502, {"error": {"code": "UPSTREAM_UNAVAILABLE", "message": f"Failed reaching Notion token endpoint: {e.reason}"}}
        except Exception as e:
            return 500, {"error": {"code": "INTERNAL_ERROR", "message": f"Unexpected broker error: {type(e).__name__}"}}
