# Cayana LLM API — Stateless Inference Server

Minimal, standalone inference gateway for Cayana Ask (Stage 8B).

## Architectural Boundaries

1. **Zero Memory Storage**: The server does NOT persist ContextPacks, memory records, questions, or answers. No database is created or connected.
2. **Operational-Only Logging**: General logs never contain plaintext questions, OCR, transcripts, answers, or credentials. Only operational telemetry is stored: `timestamp`, `req_id`, `inst_hash`, `duration_ms`, `status`, `error_code`, `prompt_tokens`, `completion_tokens`.
3. **Anonymous Quota Enforcement**: Authenticates clients using opaque anonymous installation IDs. No accounts, email, or telephone required.
4. **Hard Wire Budget**: Requests exceeding 32KB are immediately rejected with HTTP 413 `PAYLOAD_TOO_LARGE`. Responses exceeding 64KB are rejected with HTTP 500 `INTERNAL_ERROR`.
5. **Anti-Injection & Citation Verification**: Prompt injection within `<untrusted_memory>` blocks is treated as inert data. Citations are strictly filtered to memory IDs present in the incoming ContextPack.

## Environment Variables

- `CAYANA_PORT`: Listening port (default: `8080`)
- `CAYANA_HOST`: Listening host (default: `0.0.0.0`)
- `CAYANA_UPSTREAM_API_KEY`: Upstream LLM provider API key (strictly kept in environment, never committed or sent to client)
- `CAYANA_UPSTREAM_ENDPOINT`: Upstream LLM chat completions endpoint (e.g. OpenAI / Gemini / Anthropic compatible)
- `CAYANA_UPSTREAM_MODEL`: Upstream model identifier (default: `cayana-inference-v1`)
- `CAYANA_RATE_LIMIT_PER_MIN`: Sliding window request limit per installation ID (default: `30`)
- `CAYANA_TEST_MODE`: Set to `true` to use deterministic local simulation for offline testing

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
  - `X-Cayana-Request-Id: <uuid>` (optional, generated if omitted)
- **Request Body** (max 32KB):
  ```json
  {
    "installationId": "550e8400-e29b-41d4-a716-446655440000",
    "requestId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "question": "Where did I park?",
    "contextPack": {
      "query": "Where did I park?",
      "promptContextBytes": 250,
      "items": [
        {
          "memoryId": "mem-1",
          "snippet": "Parked on B2 floor space 42",
          "capturedAt": 1728633600000,
          "capturedDateIso": "2024-10-11T08:00:00Z",
          "sourceType": "SCREENSHOT"
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
    "requestId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "answer": "According to your memory recorded on 2024-10-11T08:00:00Z: Parked on B2 floor space 42",
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
  - `401 Unauthorized`: `UNAUTHORIZED` (missing anonymous installation ID)
  - `413 Payload Too Large`: `PAYLOAD_TOO_LARGE` (body > 32KB)
  - `429 Too Many Requests`: `RATE_LIMITED`
  - `502 Bad Gateway`: `UPSTREAM_ERROR`
  - `500 Internal Server Error`: `INTERNAL_ERROR`

## Running Tests

```bash
python -m unittest discover -s server -p "test_*.py"
```
