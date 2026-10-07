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

-- 5. RLS Policies
-- SELECT: Users can only read their own rows
CREATE POLICY select_own_cloud_memory_records ON cloud_memory_records
    FOR SELECT
    USING (auth.uid() IS NOT NULL AND owner_id = auth.uid());

-- INSERT: Users can only insert rows owned by themselves
CREATE POLICY insert_own_cloud_memory_records ON cloud_memory_records
    FOR INSERT
    WITH CHECK (auth.uid() IS NOT NULL AND owner_id = auth.uid());

-- UPDATE: Users can only update their own rows
CREATE POLICY update_own_cloud_memory_records ON cloud_memory_records
    FOR UPDATE
    USING (auth.uid() IS NOT NULL AND owner_id = auth.uid())
    WITH CHECK (auth.uid() IS NOT NULL AND owner_id = auth.uid());

-- DELETE: Users can only delete their own rows (normal sync uses tombstones, but policy enforced)
CREATE POLICY delete_own_cloud_memory_records ON cloud_memory_records
    FOR DELETE
    USING (auth.uid() IS NOT NULL AND owner_id = auth.uid());

-- 6. RPC: Server-Authoritative Upsert Function (SECURITY INVOKER)
-- Enforces auth.uid(), checks revision monotonically, handles idempotency and bounds.
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
    v_stored_revision bigint;
    v_new_change_seq bigint;
    c_max_ciphertext_length CONSTANT integer := 524288; -- 512 KB bound
BEGIN
    -- Check caller authentication
    v_owner_id := auth.uid();
    IF v_owner_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: auth.uid() is null';
    END IF;

    -- Validate bounds and inputs
    IF p_memory_id IS NULL OR length(p_memory_id) = 0 THEN
        RAISE EXCEPTION 'Invalid memory_id: cannot be empty';
    END IF;

    IF p_revision <= 0 THEN
        RAISE EXCEPTION 'Invalid revision: must be positive';
    END IF;

    IF p_payload_version <= 0 THEN
        RAISE EXCEPTION 'Invalid payload_version: must be positive';
    END IF;

    -- Nonce: 12 bytes encoded in Base64 is 16 chars
    IF p_nonce IS NULL OR length(p_nonce) < 12 THEN
        RAISE EXCEPTION 'Invalid nonce length';
    END IF;

    IF p_ciphertext IS NULL OR length(p_ciphertext) = 0 THEN
        RAISE EXCEPTION 'Invalid ciphertext: cannot be empty';
    END IF;

    IF length(p_ciphertext) > c_max_ciphertext_length THEN
        RAISE EXCEPTION 'Ciphertext exceeds maximum allowed length of % bytes', c_max_ciphertext_length;
    END IF;

    -- Look up existing record
    SELECT revision INTO v_stored_revision
    FROM cloud_memory_records
    WHERE owner_id = v_owner_id AND memory_id = p_memory_id;

    IF NOT FOUND THEN
        -- Initial insert
        v_new_change_seq := nextval('cloud_memory_records_change_seq');
        INSERT INTO cloud_memory_records (
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
            v_new_change_seq,
            now()
        );

        RETURN jsonb_build_object(
            'status', 'ACCEPTED',
            'revision', p_revision,
            'change_seq', v_new_change_seq
        );
    ELSIF p_revision > v_stored_revision THEN
        -- Newer revision: accept update and advance change_seq
        v_new_change_seq := nextval('cloud_memory_records_change_seq');
        UPDATE cloud_memory_records
        SET revision = p_revision,
            payload_version = p_payload_version,
            nonce = p_nonce,
            ciphertext = p_ciphertext,
            is_tombstone = p_is_tombstone,
            change_seq = v_new_change_seq,
            updated_at = now()
        WHERE owner_id = v_owner_id AND memory_id = p_memory_id;

        RETURN jsonb_build_object(
            'status', 'ACCEPTED',
            'revision', p_revision,
            'change_seq', v_new_change_seq
        );
    ELSIF p_revision = v_stored_revision THEN
        -- Idempotent retry: keep existing record, return IDEMPOTENT
        SELECT change_seq INTO v_new_change_seq
        FROM cloud_memory_records
        WHERE owner_id = v_owner_id AND memory_id = p_memory_id;

        RETURN jsonb_build_object(
            'status', 'IDEMPOTENT',
            'revision', p_revision,
            'change_seq', v_new_change_seq
        );
    ELSE
        -- Stale revision: ignore
        RETURN jsonb_build_object(
            'status', 'STALE',
            'revision', v_stored_revision
        );
    END IF;
END;
$$;
