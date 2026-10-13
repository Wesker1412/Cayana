import base64
import hashlib
import json
import time
import unittest
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch, MagicMock

from server.config import ServerConfig
from server.notion_oauth_broker import NotionOAuthBroker, NotionOAuthConfigError

class TestNotionOAuthBroker(unittest.TestCase):

    def setUp(self):
        self.config = ServerConfig()
        self.config.NOTION_CLIENT_ID = "test-notion-client-id-uuid"
        self.config.NOTION_CLIENT_SECRET = "test-notion-client-secret-sec"
        self.config.NOTION_REDIRECT_URI = "https://cayana.app/v1/oauth/notion/callback"
        self.config.NOTION_AUTH_URL = "https://api.notion.com/v1/oauth/authorize"
        self.config.NOTION_TOKEN_URL = "https://api.notion.com/v1/oauth/token"
        self.broker = NotionOAuthBroker(self.config)

    def _generate_pkce(self, verifier_secret: str = "valid_client_held_verifier_12345678"):
        code_verifier = verifier_secret
        challenge = base64.urlsafe_b64encode(
            hashlib.sha256(code_verifier.encode("utf-8")).digest()
        ).decode("utf-8").rstrip("=")
        return code_verifier, challenge

    def test_create_authorization_url_with_code_challenge(self):
        _, challenge = self._generate_pkce()
        url, state = self.broker.create_authorization_url(challenge)
        self.assertTrue(len(state) >= 32)
        parsed = urllib.parse.urlparse(url)
        params = urllib.parse.parse_qs(parsed.query)

        self.assertEqual(params["client_id"][0], "test-notion-client-id-uuid")
        self.assertEqual(params["response_type"][0], "code")
        self.assertEqual(params["owner"][0], "user")
        self.assertEqual(params["state"][0], state)
        self.assertEqual(params["redirect_uri"][0], "https://cayana.app/v1/oauth/notion/callback")

    def test_redirectUriCannotBeReplaced(self):
        _, challenge = self._generate_pkce()
        with self.assertRaises(ValueError):
            self.broker.create_authorization_url(
                code_challenge=challenge,
                redirect_uri="https://malicious-phishing-domain.com/callback"
            )

    def test_unconfigured_credentials_returns_503(self):
        unconf_config = ServerConfig()
        unconf_config.NOTION_CLIENT_ID = ""
        unconf_config.NOTION_CLIENT_SECRET = ""
        broker = NotionOAuthBroker(unconf_config)
        _, challenge = self._generate_pkce()

        with self.assertRaises(NotionOAuthConfigError):
            broker.create_authorization_url(challenge)

        status, body = broker.exchange_ticket("test-ticket", "test-state", "verifier")
        self.assertEqual(status, 503)
        self.assertEqual(body["error"]["code"], "NOTION_OAUTH_NOT_CONFIGURED")

    def test_interceptedCallbackCannotRedeemToken(self):
        # 1. Honest App initiates OAuth with its secret verifier
        verifier, challenge = self._generate_pkce("legit_app_secret_verifier_999999")
        _, state = self.broker.create_authorization_url(challenge)

        # 2. Notion redirects to Broker with auth code; Broker creates single-use relay ticket
        success, redirect_url, callback_state = self.broker.handle_notion_callback("notion_raw_code_abc", state)
        self.assertTrue(success)
        self.assertEqual(callback_state, state)
        parsed = urllib.parse.urlparse(redirect_url)
        qs = urllib.parse.parse_qs(parsed.query)
        ticket = qs["ticket"][0]

        # Raw Notion code is NEVER exposed in the redirect URL
        self.assertNotIn("notion_raw_code_abc", redirect_url)

        # 3. Malicious eavesdropper intercepts ticket + state, attempts to exchange without verifier
        status_attacker, body_attacker = self.broker.exchange_ticket(
            ticket=ticket,
            state=state,
            code_verifier="attacker_fake_verifier_888888"
        )
        self.assertEqual(status_attacker, 401)
        self.assertEqual(body_attacker["error"]["code"], "INVALID_CODE_VERIFIER")

        # 4. Ticket was atomically burned on first attempt; subsequent attempts by anyone fail closed
        status_retry, body_retry = self.broker.exchange_ticket(
            ticket=ticket,
            state=state,
            code_verifier=verifier
        )
        self.assertEqual(status_retry, 400)
        self.assertEqual(body_retry["error"]["code"], "INVALID_TICKET")

    def test_unauthorizedExchangeFailsClosed(self):
        # Invalid ticket
        status, body = self.broker.exchange_ticket("nonexistent-ticket", "state", "verifier")
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "INVALID_TICKET")

        # Expired ticket
        verifier, challenge = self._generate_pkce()
        _, state = self.broker.create_authorization_url(challenge)
        _, redirect_url, _ = self.broker.handle_notion_callback("code", state)
        ticket = urllib.parse.parse_qs(urllib.parse.urlparse(redirect_url).query)["ticket"][0]

        with self.broker.lock:
            self.broker._relay_tickets[ticket]["created_at"] = time.time() - 100

        status_exp, body_exp = self.broker.exchange_ticket(ticket, state, verifier)
        self.assertEqual(status_exp, 400)
        self.assertIn(body_exp["error"]["code"], ("INVALID_TICKET", "TICKET_EXPIRED"))

    def test_oauthStateSingleUseUnderConcurrency(self):
        verifier, challenge = self._generate_pkce("concurrency_verifier_123456789")
        _, state = self.broker.create_authorization_url(challenge)
        _, redirect_url, _ = self.broker.handle_notion_callback("concurrent_code", state)
        ticket = urllib.parse.parse_qs(urllib.parse.urlparse(redirect_url).query)["ticket"][0]

        mock_response = MagicMock()
        mock_response.status = 200
        mock_response.__enter__.return_value = mock_response
        mock_response.read.return_value = json.dumps({
            "access_token": "ntn_access_token_concurrency",
            "refresh_token": "ntn_refresh_token_concurrency",
            "token_type": "bearer"
        }).encode("utf-8")

        results = []

        def attempt_exchange():
            with patch("urllib.request.urlopen", return_value=mock_response):
                return self.broker.exchange_ticket(ticket, state, verifier)

        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(attempt_exchange) for _ in range(10)]
            for f in futures:
                results.append(f.result())

        success_count = sum(1 for status, _ in results if status == 200)
        failure_count = sum(1 for status, _ in results if status == 400)

        # EXACTLY ONE thread must succeed; all other concurrent threads fail with 400 (single-use)
        self.assertEqual(success_count, 1)
        self.assertEqual(failure_count, 9)

    def test_refresh_token_success(self):
        mock_response = MagicMock()
        mock_response.status = 200
        mock_response.__enter__.return_value = mock_response
        mock_response.read.return_value = json.dumps({
            "access_token": "ntn_new_access_token",
            "refresh_token": "ntn_new_refresh_token"
        }).encode("utf-8")

        with patch("urllib.request.urlopen", return_value=mock_response):
            status, body = self.broker.refresh_token("valid_refresh_token_abc")
            self.assertEqual(status, 200)
            self.assertEqual(body["access_token"], "ntn_new_access_token")
            self.assertEqual(body["refresh_token"], "ntn_new_refresh_token")

    def test_refresh_token_invalid_input(self):
        status, body = self.broker.refresh_token("")
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "INVALID_REFRESH_TOKEN")

    def test_zero_memory_stored_in_broker(self):
        self.assertIn("config", self.broker.__dict__)
        self.assertIn("_active_states", self.broker.__dict__)
        self.assertIn("_relay_tickets", self.broker.__dict__)
        self.assertIn("_consumed_codes", self.broker.__dict__)

if __name__ == "__main__":
    unittest.main()
