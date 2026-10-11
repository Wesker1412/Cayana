import os

class ServerConfig:
    HOST = os.environ.get("CAYANA_HOST", "0.0.0.0")
    PORT = int(os.environ.get("CAYANA_PORT", "8080"))

    # Global safety kill switch
    KILL_SWITCH = os.environ.get("CAYANA_KILL_SWITCH", "false").lower() in ("true", "1", "yes")

    # Wire budget & payload limits
    MAX_REQUEST_BYTES = 32 * 1024  # 32 KB hard wire budget
    MAX_RESPONSE_BYTES = 64 * 1024  # 64 KB response budget
    MAX_QUESTION_CHARS = 2000
    MAX_TOP_K = 10
    MAX_CONTEXT_PROMPT_BYTES = 16 * 1024  # 16 KB context prompt budget
    MAX_MEMORY_ID_CHARS = 128
    MAX_SNIPPET_CHARS = 8000

    # Rate limiting & abuse prevention (multi-tier)
    RATE_LIMIT_PER_MINUTE = int(os.environ.get("CAYANA_RATE_LIMIT_PER_MIN", "30"))
    IP_RATE_LIMIT_PER_MINUTE = int(os.environ.get("CAYANA_IP_RATE_LIMIT_PER_MIN", "60"))
    GLOBAL_RATE_LIMIT_PER_MINUTE = int(os.environ.get("CAYANA_GLOBAL_RATE_LIMIT_PER_MIN", "120"))
    MAX_RATE_LIMITER_ENTRIES = int(os.environ.get("CAYANA_MAX_RATE_LIMITER_ENTRIES", "10000"))

    # Upstream LLM configuration
    UPSTREAM_API_KEY = os.environ.get("CAYANA_UPSTREAM_API_KEY", "")
    UPSTREAM_ENDPOINT = os.environ.get("CAYANA_UPSTREAM_ENDPOINT", "")
    UPSTREAM_MODEL = os.environ.get("CAYANA_UPSTREAM_MODEL", "cayana-inference-v1")
    TEST_MODE = os.environ.get("CAYANA_TEST_MODE", "false").lower() in ("true", "1", "yes")
    ALLOW_UNPROTECTED_PAID_INFERENCE = os.environ.get("CAYANA_ALLOW_UNPROTECTED_PAID_INFERENCE", "false").lower() in ("true", "1", "yes")
    SERVER_API_KEY = os.environ.get("CAYANA_SERVER_API_KEY", "")

    # Timeout
    REQUEST_TIMEOUT_SECONDS = 30

    # Operational logger memory bounds
    MAX_IN_MEMORY_LOGS = 200
