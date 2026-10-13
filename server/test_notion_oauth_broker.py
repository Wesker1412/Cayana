import json
import time
import unittest
from unittest.mock import patch, MagicMock
import urllib.parse
import http.client
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

    def test_create_authorization_url(self):
        url, state = self.broker.create_authorization_url()
        self.assertTrue(len(state) >= 32)
        parsed = urllib.parse.urlparse(url)
        params = urllib.parse.parse_qs(parsed.query)

        self.assertEqual(params["client_id"][0], "test-notion-client-id-uuid")
        self.assertEqual(params["response_type"][0], "code")
        self.assertEqual(params["owner"][0], "user")
        self.assertEqual(params["state"][0], state)
        self.assertEqual(params["redirect_uri"][0], "https://cayana.app/v1/oauth/notion/callback")

    def test_unconfigured_credentials_returns_503(self):
        unconf_config = ServerConfig()
        unconf_config.NOTION_CLIENT_ID = ""
        unconf_config.NOTION_CLIENT_SECRET = ""
        broker = NotionOAuthBroker(unconf_config)

        with self.assertRaises(NotionOAuthConfigError):
            broker.create_authorization_url()

        status, body = broker.exchange_code("test-code", "test-state")
        self.assertEqual(status, 503)
        self.assertEqual(body["error"]["code"], "NOTION_OAUTH_NOT_CONFIGURED")

    def test_invalid_state_rejected(self):
        # State never created
        status, body = self.broker.exchange_code("some-code", "non-existent-state")
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "INVALID_STATE")

    def test_expired_state_rejected(self):
        url, state = self.broker.create_authorization_url()
        # Simulate state timeout (older than 600s)
        self.broker._active_states[state]["created_at"] = time.time() - 700

        status, body = self.broker.exchange_code("some-code", state)
        self.assertEqual(status, 400)
        self.assertEqual(body["error"]["code"], "INVALID_STATE")

    def test_state_cannot_be_reused_once_consumed(self):
        url, state = self.broker.create_authorization_url()

        # Mock Notion response
        mock_response = MagicMock()
        mock_response.status = 200
        mock_response.__enter__.return_value = mock_response
        mock_response.read.return_value = json.dumps({
            "access_token": "ntn_test_access_token_123",
            "refresh_token": "ntn_test_refresh_token_456",
            "bot_id": "bot-123",
            "workspace_id": "ws-123",
            "workspace_name": "Test Workspace",
            "token_type": "bearer"
        }).encode("utf-8")

        with patch("urllib.request.urlopen", return_value=mock_response):
            status, body = self.broker.exchange_code("code-1", state)
            self.assertEqual(status, 200)
            self.assertEqual(body["access_token"], "ntn_test_access_token_123")

        # Second exchange with the same state must be rejected
        status2, body2 = self.broker.exchange_code("code-2", state)
        self.assertEqual(status2, 400)
        self.assertEqual(body2["error"]["code"], "INVALID_STATE")

    def test_code_replay_rejected(self):
        _, state1 = self.broker.create_authorization_url()
        _, state2 = self.broker.create_authorization_url()

        mock_response = MagicMock()
        mock_response.status = 200
        mock_response.__enter__.return_value = mock_response
        mock_response.read.return_value = json.dumps({
            "access_token": "ntn_tok",
            "token_type": "bearer"
        }).encode("utf-8")

        with patch("urllib.request.urlopen", return_value=mock_response):
            status, _ = self.broker.exchange_code("reused-code-xyz", state1)
            self.assertEqual(status, 200)

        # Attempt to exchange the exact same code with a new valid state
        status2, body2 = self.broker.exchange_code("reused-code-xyz", state2)
        self.assertEqual(status2, 409)
        self.assertEqual(body2["error"]["code"], "CODE_REPLAY_DETECTED")

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
        # Verify broker data structures contain no memory or private note data
        self.assertEqual(len(self.broker.__dict__.keys()), 3)
        self.assertIn("config", self.broker.__dict__)
        self.assertIn("_active_states", self.broker.__dict__)
        self.assertIn("_consumed_codes", self.broker.__dict__)

if __name__ == "__main__":
    unittest.main()
