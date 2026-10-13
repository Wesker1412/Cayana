import base64
import json
import secrets
import time
import urllib.parse
import urllib.request
import urllib.error
from typing import Tuple, Dict, Any, Optional
from .config import ServerConfig
from .logger import logger

class NotionOAuthConfigError(Exception):
    pass

class NotionOAuthBroker:
    """
    Minimal, privacy-preserving OAuth Broker for Notion.
    - Strictly handles authorization code exchange and token refresh.
    - Notion Client Secret exists ONLY in server environment variables.
    - Zero memory, notes, or user content stored or processed.
    - Strict state validation, code replay prevention, and atomic consumption.
    - Never acts as a content proxy for Notion API calls.
    """

    STATE_TTL_SECONDS = 600       # 10 minutes
    CODE_TTL_SECONDS = 3600       # 1 hour replay window
    MAX_TRACKED_ENTRIES = 5000

    def __init__(self, config: ServerConfig = None):
        self.config = config or ServerConfig()
        self._active_states: Dict[str, dict] = {}
        self._consumed_codes: Dict[str, float] = {}

    def _cleanup_expired(self):
        now = time.time()
        # Clean expired states
        expired_states = [s for s, data in self._active_states.items() if now - data.get("created_at", 0) > self.STATE_TTL_SECONDS]
        for s in expired_states:
            self._active_states.pop(s, None)

        # Clean expired consumed codes
        expired_codes = [c for c, t in self._consumed_codes.items() if now - t > self.CODE_TTL_SECONDS]
        for c in expired_codes:
            self._consumed_codes.pop(c, None)

        # Enforce hard cap to prevent memory exhaustion
        if len(self._active_states) > self.MAX_TRACKED_ENTRIES:
            sorted_states = sorted(self._active_states.items(), key=lambda kv: kv[1].get("created_at", 0))
            for k, _ in sorted_states[:len(self._active_states) - self.MAX_TRACKED_ENTRIES]:
                self._active_states.pop(k, None)

        if len(self._consumed_codes) > self.MAX_TRACKED_ENTRIES:
            sorted_codes = sorted(self._consumed_codes.items(), key=lambda kv: kv[1])
            for k, _ in sorted_codes[:len(self._consumed_codes) - self.MAX_TRACKED_ENTRIES]:
                self._consumed_codes.pop(k, None)

    def is_configured(self) -> bool:
        return bool(self.config.NOTION_CLIENT_ID and self.config.NOTION_CLIENT_SECRET)

    def create_authorization_url(self, redirect_uri: Optional[str] = None) -> Tuple[str, str]:
        if not self.config.NOTION_CLIENT_ID:
            raise NotionOAuthConfigError("Notion client ID is not configured on server")

        self._cleanup_expired()
        state = secrets.token_urlsafe(32)
        effective_redirect = redirect_uri or self.config.NOTION_REDIRECT_URI

        self._active_states[state] = {
            "created_at": time.time(),
            "consumed": False,
            "redirect_uri": effective_redirect
        }

        params = {
            "client_id": self.config.NOTION_CLIENT_ID,
            "response_type": "code",
            "owner": "user",
            "redirect_uri": effective_redirect,
            "state": state
        }
        url = f"{self.config.NOTION_AUTH_URL}?{urllib.parse.urlencode(params)}"
        return url, state

    def validate_and_consume_state(self, state: str) -> bool:
        self._cleanup_expired()
        if not state or state not in self._active_states:
            return False
        data = self._active_states[state]
        now = time.time()
        if now - data.get("created_at", 0) > self.STATE_TTL_SECONDS:
            self._active_states.pop(state, None)
            return False
        if data.get("consumed", False):
            return False
        data["consumed"] = True
        return True

    def exchange_code(self, code: str, state: str, redirect_uri: Optional[str] = None) -> Tuple[int, dict]:
        self._cleanup_expired()

        if not code or not isinstance(code, str) or len(code) > 256:
            return 400, {"error": {"code": "INVALID_CODE", "message": "Authorization code is missing or invalid"}}

        if not state or not isinstance(state, str) or len(state) > 256:
            return 400, {"error": {"code": "INVALID_STATE", "message": "State is missing or invalid"}}

        if not self.is_configured():
            return 503, {"error": {"code": "NOTION_OAUTH_NOT_CONFIGURED", "message": "Notion OAuth client credentials are not configured on server"}}

        # State validation and atomic consumption
        if not self.validate_and_consume_state(state):
            return 400, {"error": {"code": "INVALID_STATE", "message": "Invalid, expired, or already used OAuth state"}}

        # Replay prevention for code
        if code in self._consumed_codes:
            return 409, {"error": {"code": "CODE_REPLAY_DETECTED", "message": "Authorization code has already been exchanged"}}

        self._consumed_codes[code] = time.time()

        effective_redirect = redirect_uri or self.config.NOTION_REDIRECT_URI

        # HTTP Basic Auth header: base64(client_id:client_secret)
        creds = f"{self.config.NOTION_CLIENT_ID}:{self.config.NOTION_CLIENT_SECRET}"
        basic_auth = base64.b64encode(creds.encode("utf-8")).decode("utf-8")

        payload = {
            "grant_type": "authorization_code",
            "code": code,
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
            err_body = e.read().decode("utf-8")
            try:
                err_json = json.loads(err_body)
            except Exception:
                err_json = {"error": {"code": "UPSTREAM_ERROR", "message": err_body}}
            return e.code, err_json
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
            err_body = e.read().decode("utf-8")
            try:
                err_json = json.loads(err_body)
            except Exception:
                err_json = {"error": {"code": "UPSTREAM_ERROR", "message": err_body}}
            return e.code, err_json
        except urllib.error.URLError as e:
            return 502, {"error": {"code": "UPSTREAM_UNAVAILABLE", "message": f"Failed reaching Notion token endpoint: {e.reason}"}}
        except Exception as e:
            return 500, {"error": {"code": "INTERNAL_ERROR", "message": f"Unexpected broker error: {type(e).__name__}"}}
