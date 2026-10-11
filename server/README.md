# Cayana LLM API — Stateless Inference Server

Minimal, standalone inference gateway for Cayana Ask (Stage 8B).

## Architectural Boundaries

1. **Zero Memory Storage**: The server does NOT persist ContextPacks, memory records, questions, or answers. No database is created or connected.
2. **Operational-Only Logging**: General logs never contain plaintext questions, OCR, transcripts, answers, or credentials. Only operational telemetry is stored in bounded in-memory buffers (max 200 entries): `timestamp`, `req_id`, `inst_hash`, `duration_ms`, `status`, `error_code`, `prompt_tokens`, `completion_tokens`.
3. **Multi-Tier In-Process Abuse Prevention**:
   - Tier 1: Per-installation rate limit (`CAYANA_RATE_LIMIT_PER_MIN`).
   - Tier 2: Per-IP rate limit (`CAYANA_IP_RATE_LIMIT_PER_MIN`) prevents rotating anonymous UUIDs from evading limits on the same host.
   - Tier 3: Global throughput cap (`CAYANA_GLOBAL_RATE_LIMIT_PER_MIN`) bounds single-process upstream call volume.
   - Kill Switch: `CAYANA_KILL_SWITCH` immediately pauses all inference traffic with HTTP 503.
   - *Boundary Note*: These rate limits operate in-memory within a single server process. They do NOT provide a multi-instance distributed global cost guarantee.
4. **Hard Wire Budget**: Requests exceeding 32KB are immediately rejected with HTTP 413 `PAYLOAD_TOO_LARGE`. Content-Length is strictly validated (non-negative integer required, missing yields 411 `LENGTH_REQUIRED`). Responses exceeding 64KB are rejected with HTTP 500 `INTERNAL_ERROR`.
5. **Server-Side Budget Re-measurement**: Client-claimed `promptContextBytes` is never trusted; the server independently measures the prompt context bytes matching the exact XML tags, attributes, and escaped entity expansions sent upstream, and rejects packets >16KB with `CONTEXT_TOO_LARGE`.
6. **Strict Schema & Request ID Validation**: Request IDs with CRLF injection characters or invalid non-alphanumeric formats (>64 chars) are rejected with 400 `INVALID_REQUEST`. Memory IDs, snippets, and source types are strictly validated.
7. **Production Fail-Closed**: In production mode (`CAYANA_TEST_MODE=false`), missing upstream API keys/endpoints immediately fail-closed with HTTP 503 `UPSTREAM_NOT_CONFIGURED`. Furthermore, if server protection (`CAYANA_SERVER_API_KEY`) is not configured and `CAYANA_ALLOW_UNPROTECTED_PAID_INFERENCE=false`, requests fail-closed with HTTP 503 `SERVER_PROTECTION_NOT_CONFIGURED`. Simulated inference is strictly prohibited in production.
8. **Upstream Transport Security**: Strictly enforces HTTPS upstream endpoints, completely disables HTTP redirect following (preventing API key exfiltration to third parties), enforces 64KB upstream response limits, and XML-escapes all context blocks to prevent prompt injection escapes.

## Environment Variables (`server/config.py` as Single Source of Truth)

- `CAYANA_HOST`: Listening host (default: `0.0.0.0`)
- `CAYANA_PORT`: Listening port (default: `8080`)
- `CAYANA_KILL_SWITCH`: Set to `true` to pause all inference endpoints with 503 (default: `false`)
- `CAYANA_UPSTREAM_API_KEY`: Upstream LLM provider API key (kept strictly on server, never sent to client or APK)
- `CAYANA_UPSTREAM_ENDPOINT`: Upstream LLM chat completions endpoint (must be `https://`)
- `CAYANA_UPSTREAM_MODEL`: Upstream model identifier (default: `cayana-inference-v1`)
- `CAYANA_RATE_LIMIT_PER_MIN`: In-process sliding window request limit per installation ID (default: `30`)
- `CAYANA_IP_RATE_LIMIT_PER_MIN`: In-process sliding window request limit per client IP (default: `60`)
- `CAYANA_GLOBAL_RATE_LIMIT_PER_MIN`: In-process global request limit across all clients (default: `120`)
- `CAYANA_MAX_RATE_LIMITER_ENTRIES`: Maximum in-memory client tracking entries (default: `10000`)
- `CAYANA_ALLOW_UNPROTECTED_PAID_INFERENCE`: Explicit toggle required before allowing paid inference without server secret (default: `false`)
- `CAYANA_SERVER_API_KEY`: Application secret required via `X-Cayana-App-Secret` or `Authorization: Bearer <key>` header (never embedded into client APK)
- `CAYANA_TEST_MODE`: Set to `true` ONLY in explicit automated test environments to enable deterministic local simulation

## Production Prerequisites & Abuse Defense Boundaries

Before deploying and exposing this inference service to public production traffic:
1. **Trusted Front Gateway / Reverse Proxy**: Must be deployed behind a front gateway (e.g. Cloudflare, AWS API Gateway) handling TLS termination, WAF, DDoS mitigation, and distributed IP rate limiting.
2. **Hard Billing Caps**: Hard cost ceilings and spend alerts must be configured directly within the upstream LLM provider console (e.g., OpenAI / Anthropic / Google Cloud).
3. **Persistent Quotas**: If scaling across multiple server instances, centralized quotas (e.g., Redis token bucket) must back the rate limiting.
4. **Client Device Attestation**: For public anonymous mobile clients without `CAYANA_SERVER_API_KEY`, cryptographic hardware attestation (Google Play Integrity API / Apple App Attest) must be deployed via an auth gateway before issuing short-lived ephemeral inference tokens. Permanent shared `CAYANA_SERVER_API_KEY` must NEVER be embedded into Android APK builds.

## API Contract

### `GET /health`
- **Response**: `200 OK`
  ```json
  {
    "status": "ok",
    "service": "cayana-llm-api",
    "version": "0.1.0"
  }
  ```

### `POST /v1/ask`
- **Headers**:
  - `Content-Type: application/json`
  - `X-Cayana-Installation-Id: <uuid>` (or in request body)
  - `X-Cayana-Request-Id: <uuid>` (alphanumeric, max 64 chars)
  - `X-Cayana-App-Secret: <secret>` (optional, required if `CAYANA_SERVER_API_KEY` set)
- **Request Body** (max 32KB):
  ```json
  {
    "installationId": "anon-install-12345",
    "requestId": "req-001",
    "question": "Where is my passport?",
    "contextPack": {
      "query": "Where is my passport?",
      "promptContextBytes": 200,
      "items": [
        {
          "memoryId": "mem-1",
          "snippet": "Left passport in desk drawer.",
          "capturedAt": 1728633600000,
          "capturedDateIso": "2024-10-11T08:00:00Z",
          "sourceType": "SHARED_TEXT"
        }
      ]
    },
    "options": {
      "temperature": 0.1,
      "maxTokens": 1024
    }
  }
  ```
- **Response Body** (200 OK, max 64KB):
  ```json
  {
    "requestId": "req-001",
    "answer": "According to your memory: Left passport in desk drawer.",
    "evidenceState": "SUPPORTED",
    "citedMemoryIds": ["mem-1"],
    "model": "cayana-inference-v1",
    "usage": {
      "promptTokens": 150,
      "completionTokens": 40
    }
  }
  ```

- **Error Codes**:
  - `400 Bad Request`: `INVALID_REQUEST`, `TOP_K_EXCEEDED`, `CONTEXT_TOO_LARGE`, `INVALID_CONTENT_LENGTH`, `EMPTY_PAYLOAD`, `INCOMPLETE_BODY`
  - `401 Unauthorized`: `UNAUTHORIZED` (missing/invalid installation ID or server authorization)
  - `411 Length Required`: `LENGTH_REQUIRED` (missing Content-Length header)
  - `413 Payload Too Large`: `PAYLOAD_TOO_LARGE` (body > 32KB)
  - `429 Too Many Requests`: `RATE_LIMITED`
  - `502 Bad Gateway`: `UPSTREAM_ERROR`
  - `503 Service Unavailable`: `UPSTREAM_NOT_CONFIGURED`, `SERVER_PROTECTION_NOT_CONFIGURED`, `SERVICE_UNAVAILABLE` (kill switch)
  - `500 Internal Server Error`: `INTERNAL_ERROR`

## Running Tests

```bash
python -m unittest discover -s server -p "test_*.py"
```
