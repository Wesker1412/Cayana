import os

class ServerConfig:
    HOST = os.environ.get("CAYANA_HOST", "0.0.0.0")
    PORT = int(os.environ.get("CAYANA_PORT", "8080"))
    
    # Wire budget & payload limits
    MAX_REQUEST_BYTES = 32 * 1024  # 32 KB hard wire budget
    MAX_RESPONSE_BYTES = 64 * 1024  # 64 KB response budget
    MAX_QUESTION_CHARS = 2000
    MAX_TOP_K = 10
    MAX_CONTEXT_PROMPT_BYTES = 16 * 1024  # 16 KB context prompt budget
    
    # Rate limiting & quota
    RATE_LIMIT_PER_MINUTE = int(os.environ.get("CAYANA_RATE_LIMIT_PER_MIN", "30"))
    
    # Upstream LLM configuration
    UPSTREAM_API_KEY = os.environ.get("CAYANA_UPSTREAM_API_KEY", "")
    UPSTREAM_ENDPOINT = os.environ.get("CAYANA_UPSTREAM_ENDPOINT", "")
    UPSTREAM_MODEL = os.environ.get("CAYANA_UPSTREAM_MODEL", "cayana-inference-v1")
    TEST_MODE = os.environ.get("CAYANA_TEST_MODE", "false").lower() in ("true", "1", "yes")
    
    # Timeout
    REQUEST_TIMEOUT_SECONDS = 30
