-- Migration: 20261008000000_cloud_memory_records.sql
-- Cayana Cloud v1: Encrypted Memory Records & Tenant Isolation

-- 1. Create authoritative monotonically increasing sequence for change ordering
CREATE SEQUENCE IF NOT EXISTS cloud_memory_records_change_seq START WITH 1 INCREMENT BY 1;

-- 2. Create encrypted memory records table
CREATE TABLE IF NOT EXISTS cloud_memory_records (
    owner_id uuid NOT NULL,
    memory_id text NOT NULL,
    revision bigint NOT NULL,
    payload_version integer NOT NULL,
    nonce text NOT NULL,
    ciphertext text NOT NULL,
    is_tombstone boolean NOT NULL DEFAULT false,
    change_seq bigint NOT NULL DEFAULT nextval('cloud_memory_records_change_seq'),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_cloud_memory_records PRIMARY KEY (owner_id, memory_id)
);

-- 3. Indexes for fast range-based cursor pulling and sequencing
CREATE INDEX IF NOT EXISTS idx_cloud_memory_records_owner_change_seq
    ON cloud_memory_records (owner_id, change_seq ASC);

-- 4. Enable Row Level Security (RLS)
ALTER TABLE cloud_memory_records ENABLE ROW LEVEL SECURITY;

-- 5. RLS Policies (explicitly TO authenticated)
-- SELECT: Authenticated users can only read their own rows
CREATE POLICY select_own_cloud_memory_records ON cloud_memory_records
    FOR SELECT
    TO authenticated
    USING (auth.uid() IS NOT NULL AND owner_id = auth.uid());

-- INSERT: Authenticated users can only insert rows owned by themselves
CREATE POLICY insert_own_cloud_memory_records ON cloud_memory_records
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() IS NOT NULL AND owner_id = auth.uid());

-- UPDATE: Authenticated users can only update their own rows
CREATE POLICY update_own_cloud_memory_records ON cloud_memory_records
    FOR UPDATE
    TO authenticated
    USING (auth.uid() IS NOT NULL AND owner_id = auth.uid())
    WITH CHECK (auth.uid() IS NOT NULL AND owner_id = auth.uid());

-- 6. Explicit least-privilege GRANTS for authenticated role
GRANT USAGE ON SCHEMA public TO authenticated;

GRANT SELECT, INSERT, UPDATE
ON TABLE public.cloud_memory_records
TO authenticated;

GRANT USAGE, SELECT
ON SEQUENCE public.cloud_memory_records_change_seq
TO authenticated;

-- 7. RPC: Server-Authoritative Upsert Function (SECURITY INVOKER)
-- Enforces auth.uid(), checks revision monotonically, handles idempotency and decoded byte bounds.
CREATE OR REPLACE FUNCTION upsert_cloud_memory_record(
    p_memory_id text,
    p_revision bigint,
    p_payload_version integer,
    p_nonce text,
    p_ciphertext text,
    p_is_tombstone boolean
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_owner_id uuid;
    v_decoded_nonce bytea;
    v_decoded_ciphertext bytea;
    v_stored_revision bigint;
    v_stored_change_seq bigint;
    v_result_revision bigint;
    v_result_change_seq bigint;
    c_max_ciphertext_bytes CONSTANT integer := 524288; -- Exactly 512 KiB decoded bytes
BEGIN
    -- Check caller authentication
    v_owner_id := auth.uid();
    IF v_owner_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: auth.uid() is null';
    END IF;

    -- Validate bounds and inputs
    IF p_memory_id IS NULL OR length(trim(p_memory_id)) = 0 THEN
        RAISE EXCEPTION 'Invalid memory_id: cannot be empty';
    END IF;

    IF p_revision <= 0 THEN
        RAISE EXCEPTION 'Invalid revision: must be positive';
    END IF;

    IF p_payload_version <= 0 THEN
        RAISE EXCEPTION 'Invalid payload_version: must be positive';
    END IF;

    -- Validate Base64 and byte length for Nonce (must be valid Base64 and exactly 12 bytes)
    IF p_nonce IS NULL THEN
        RAISE EXCEPTION 'Invalid nonce: cannot be null';
    END IF;

    BEGIN
        v_decoded_nonce := decode(p_nonce, 'base64');
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Invalid nonce: not valid base64';
    END;

    IF octet_length(v_decoded_nonce) != 12 THEN
        RAISE EXCEPTION 'Invalid nonce: decoded length must be exactly 12 bytes';
    END IF;

    -- Validate Base64 and byte length for Ciphertext (must be valid Base64 and <= 512 KiB)
    IF p_ciphertext IS NULL THEN
        RAISE EXCEPTION 'Invalid ciphertext: cannot be null';
    END IF;

    BEGIN
        v_decoded_ciphertext := decode(p_ciphertext, 'base64');
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Invalid ciphertext: not valid base64';
    END;

    IF octet_length(v_decoded_ciphertext) = 0 THEN
        RAISE EXCEPTION 'Invalid ciphertext: cannot be empty';
    END IF;

    IF octet_length(v_decoded_ciphertext) > c_max_ciphertext_bytes THEN
        RAISE EXCEPTION 'Ciphertext exceeds maximum allowed length of % bytes', c_max_ciphertext_bytes;
    END IF;

    -- Concurrency-safe atomic UPSERT with PostgreSQL row-level conflict lock
    INSERT INTO public.cloud_memory_records (
        owner_id,
        memory_id,
        revision,
        payload_version,
        nonce,
        ciphertext,
        is_tombstone,
        change_seq,
        updated_at
    ) VALUES (
        v_owner_id,
        p_memory_id,
        p_revision,
        p_payload_version,
        p_nonce,
        p_ciphertext,
        p_is_tombstone,
        nextval('public.cloud_memory_records_change_seq'),
        now()
    )
    ON CONFLICT (owner_id, memory_id)
    DO UPDATE
    SET revision = EXCLUDED.revision,
        payload_version = EXCLUDED.payload_version,
        nonce = EXCLUDED.nonce,
        ciphertext = EXCLUDED.ciphertext,
        is_tombstone = EXCLUDED.is_tombstone,
        change_seq = nextval('public.cloud_memory_records_change_seq'),
        updated_at = now()
    WHERE public.cloud_memory_records.revision < EXCLUDED.revision
    RETURNING public.cloud_memory_records.revision, public.cloud_memory_records.change_seq
    INTO v_result_revision, v_result_change_seq;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'status', 'ACCEPTED',
            'revision', v_result_revision,
            'change_seq', v_result_change_seq
        );
    ELSE
        -- Conflict update condition was not met because stored revision >= p_revision
        SELECT revision, change_seq INTO v_stored_revision, v_stored_change_seq
        FROM public.cloud_memory_records
        WHERE owner_id = v_owner_id AND memory_id = p_memory_id;

        IF v_stored_revision = p_revision THEN
            RETURN jsonb_build_object(
                'status', 'IDEMPOTENT',
                'revision', v_stored_revision,
                'change_seq', v_stored_change_seq
            );
        ELSE
            RETURN jsonb_build_object(
                'status', 'STALE',
                'revision', v_stored_revision
            );
        END IF;
    END IF;
END;
$$;

-- 8. Explicit Function Privileges
REVOKE ALL
ON FUNCTION public.upsert_cloud_memory_record(
    text, bigint, integer, text, text, boolean
)
FROM PUBLIC;

REVOKE ALL
ON FUNCTION public.upsert_cloud_memory_record(
    text, bigint, integer, text, text, boolean
)
FROM anon;

GRANT EXECUTE
ON FUNCTION public.upsert_cloud_memory_record(
    text, bigint, integer, text, text, boolean
)
TO authenticated;
