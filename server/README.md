# Cayana LLM API — Stateless Inference Server

Minimal, standalone inference gateway for Cayana Ask (Stage 8B).

## Architectural Boundaries

1. **Zero Memory Storage**: The server does NOT persist ContextPacks, memory records, questions, or answers. No database is created or connected.
2. **Operational-Only Logging**: General logs never contain plaintext questions, OCR, transcripts, answers, or credentials. Only operational telemetry is stored in bounded in-memory buffers (max 200 entries): `timestamp`, `req_id`, `inst_hash`, `duration_ms`, `status`, `error_code`, `prompt_tokens`, `completion_tokens`.
3. **Multi-Tier Abuse Prevention**: 
   - Tier 1: Per-installation rate limit (`RATE_LIMIT_PER_MINUTE`).
   - Tier 2: Per-IP rate limit (`IP_RATE_LIMIT_PER_MINUTE`) prevents rotating anonymous UUIDs from evading limits.
   - Tier 3: Global throughput cap (`GLOBAL_RATE_LIMIT_PER_MINUTE`) protects the upstream paid API budget from distributed exhaustion.
   - Kill Switch: `CAYANA_KILL_SWITCH` immediately pauses all inference traffic.
4. **Hard Wire Budget**: Requests exceeding 32KB are immediately rejected with HTTP 413 `PAYLOAD_TOO_LARGE`. Responses exceeding 64KB are rejected with HTTP 500 `INTERNAL_ERROR`.
5. **Server-Side Budget Re-measurement**: Client-claimed `promptContextBytes` is never trusted; the server independently measures the prompt context bytes and rejects packets >16KB with `CONTEXT_TOO_LARGE`.
6. **Strict Schema & Request ID Validation**: Request IDs with CRLF injection characters or invalid non-alphanumeric formats (>64 chars) are rejected with 400 `INVALID_REQUEST`. Memory IDs, snippets, and source types are strictly validated.
7. **Production Fail-Closed**: In production mode (`TEST_MODE=false`), missing upstream API keys or endpoints immediately fail-closed with HTTP 503 `UPSTREAM_NOT_CONFIGURED`. Simulated inference is prohibited in production.
8. **Upstream Transport Security**: Strictly enforces HTTPS upstream endpoints, completely disables HTTP redirect following (preventing API key exfiltration to third parties), enforces 64KB upstream response limits, and XML-escapes all context blocks to prevent prompt injection escapes.

## Environment Variables

- `CAYANA_PORT`: Listening port (default: `8080`)
- `CAYANA_HOST`: Listening host (default: `0.0.0.0`)
- `CAYANA_KILL_SWITCH`: Set to `true` to pause all inference endpoints with 503 (default: `false`)
- `CAYANA_UPSTREAM_API_KEY`: Upstream LLM provider API key (strictly kept in environment, never committed or sent to client)
- `CAYANA_UPSTREAM_ENDPOINT`: Upstream LLM chat completions endpoint (must be `https://`)
- `CAYANA_UPSTREAM_MODEL`: Upstream model identifier (default: `cayana-inference-v1`)
- `CAYANA_RATE_LIMIT_PER_MIN`: Sliding window request limit per installation ID (default: `30`)
- `CAYANA_IP_RATE_LIMIT_PER_MIN`: Sliding window request limit per client IP (default: `60`)
- `CAYANA_GLOBAL_RATE_LIMIT_PER_MIN`: Global request limit across all clients (default: `120`)
- `CAYANA_MAX_RATE_LIMITER_ENTRIES`: Maximum in-memory client tracking entries (default: `10000`)
- `CAYANA_ALLOW_UNPROTECTED_PAID_INFERENCE`: Explicit toggle required before exposing paid inference without app secret (default: `false`)
- `CAYANA_SERVER_API_KEY`: Optional application secret required via `X-Cayana-App-Secret` header
- `CAYANA_TEST_MODE`: Set to `true` to allow deterministic local simulation for offline testing

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
  - `400 Bad Request`: `INVALID_REQUEST`, `TOP_K_EXCEEDED`, `CONTEXT_TOO_LARGE`
  - `401 Unauthorized`: `UNAUTHORIZED` (missing/invalid installation ID or app secret)
  - `413 Payload Too Large`: `PAYLOAD_TOO_LARGE` (body > 32KB)
  - `429 Too Many Requests`: `RATE_LIMITED`
  - `502 Bad Gateway`: `UPSTREAM_ERROR`
  - `503 Service Unavailable`: `UPSTREAM_NOT_CONFIGURED`, `SERVICE_UNAVAILABLE` (kill switch)
  - `500 Internal Server Error`: `INTERNAL_ERROR`

## Running Tests

```bash
python -m unittest discover -s server -p "test_*.py"
```
