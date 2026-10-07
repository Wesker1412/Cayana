# Cayana Cloud Encrypted Memory Sync — Architecture (Stage 7)

## 1. Overview

Stage 7 implemented an end-to-end encrypted (E2EE), zero-knowledge, multi-tenant memory synchronization mechanism using Supabase as a backend. The system ensures that:
- The cloud backend sees only encrypted blobs (`ciphertext`, `iv`, `tag`, `change_seq`, `is_deleted`).
- The cloud backend cannot decrypt or search any memory content, OCR text, transcripts, or metadata.
- Authentication is anonymous, avoiding personal identifiable information (PII), email, or social logins.
- Tenants are strictly isolated via Postgres Row Level Security (RLS).

---

## 2. Authentication & Tenant Isolation

1. **Anonymous Authentication**:
   - Uses Supabase GoTrue Anonymous Auth (`supabase.auth.signInAnonymously()`).
   - Session tokens are stored locally via `CloudSyncStateDao`.
   - The user's `auth.uid()` acts as the `tenant_id`.
   - No email, phone, Google account, or passwords are used for Cloud Sync.

2. **Database Schema & Least-Privilege RLS**:
   - Table `cloud_memory_records`:
     - `tenant_id` (UUID, references `auth.users(id)` or matches `auth.uid()`)
     - `memory_id` (TEXT)
     - `version` (INT)
     - `ciphertext` (TEXT, Base64)
     - `iv` (TEXT, Base64)
     - `tag` (TEXT, Base64)
     - `is_deleted` (BOOLEAN)
     - `change_seq` (BIGSERIAL)
     - `updated_at` (TIMESTAMPTZ)
   - RLS Policy:
     - `FOR ALL TO authenticated USING (tenant_id = auth.uid()) WITH CHECK (tenant_id = auth.uid())`
   - Explicit Grants:
     - `GRANT USAGE ON SCHEMA public TO authenticated;`
     - `GRANT SELECT, INSERT, UPDATE ON TABLE public.cloud_memory_records TO authenticated;` (no hard `DELETE` grant)
     - `GRANT USAGE, SELECT ON SEQUENCE public.cloud_memory_records_change_seq TO authenticated;`
   - RPC:
     - `upsert_cloud_memory_record` with `SECURITY DEFINER` revoked from `PUBLIC` and `anon`, granted only to `authenticated`.

---

## 3. Cryptography & Envelope

1. **Key Derivation**:
   - Master Key source: Recovery Key or Android Keystore master seed.
   - HKDF-SHA256 derivation with application info: `"cayana-cloud-sync-v1"`.
   - Derives a 256-bit AES symmetric key.

2. **Authenticated Encryption**:
   - Algorithm: AES-256-GCM.
   - 12-byte random IV per record.
   - 128-bit authentication tag.
   - Additional Authenticated Data (AAD): `${tenantId}:${memoryId}:${updatedAt}` prevents ciphertext swapping across records or tenants.

3. **Plaintext Payload**:
   - Standardized JSON structure (`CloudMemoryPayload`) containing canonical memory fields: `title`, `content`, `sourceType`, `tags`, `timestamp`, `metadata`.
   - Raw binary media (screenshots, audio recordings, photo binaries) are **never** synced to Cloud Sync; they are kept strictly local or in Google Drive backup.

---

## 4. Local Database Architecture

In Stage 7, three dedicated Room tables were added:
1. `cloud_sync_state`: Stores local sync cursor (`last_change_sequence`), anonymous auth token, tenant ID, and last sync timestamp.
2. `cloud_memory_sync_metadata`: Tracks local version, cloud version, and sync status for each memory.
3. `cloud_sync_outbox`: Transactional outbox pattern. Any local mutation (`saveMemory`, `deleteMemory`) atomically inserts an outbox event.

### Sync Lifecycle
1. **Push Phase**:
   - Queries `cloud_sync_outbox` for pending events.
   - Encrypts payload with AES-256-GCM.
   - Calls Supabase `upsert_cloud_memory_record`.
   - On success, removes outbox record and updates `cloud_memory_sync_metadata`.
2. **Pull Phase**:
   - Queries Supabase for `cloud_memory_records` where `change_seq > last_change_sequence` ordered by `change_seq ASC`.
   - For each remote record:
     - If `is_deleted == true`: deletes local canonical memory.
     - If active: decrypts payload and updates canonical `memories` table with `origin = MutationOrigin.REMOTE` to avoid echoing back to the outbox.
   - Updates `cloud_sync_state.last_change_sequence`.
