# Cayana Cloud Encrypted Memory Sync — Architecture (Stage 7 Final Baseline)

> **Ground Truth Reference**: This document describes the exact architecture of Cayana Cloud Sync as finalized in commit `c918752a76a8c23dc812719369b6af9904bcf251` (`c918752`), archived in `original-implementation/`.

---

## 1. Overview & Trust Boundaries

Stage 7 implements an end-to-end encrypted (E2EE), zero-knowledge memory replication mechanism using Supabase as a cloud storage engine.
- **Client Trust Boundary**: Only the local device possesses the root recovery key, derives encryption keys, and handles plaintext memories.
- **Zero-Knowledge Cloud Boundary**: The server only sees encrypted ciphertext blobs, 12-byte nonces, revision counters, monotonic sequence numbers, and owner identifiers (`owner_id`).
- **No PII**: Authentication relies entirely on anonymous Supabase identities; email, phone numbers, passwords, and profile metadata are strictly prohibited.
- **Payload Scope**: Synchronizes only parsed/structured canonical Memory entities (`CloudMemoryPayloadV1`). Raw binaries (screenshots, camera photos, audio recordings) and external provider state (Android Calendar, Google Drive backups) are never uploaded to the cloud sync backend.

---

## 2. Authentication & Tenant Ownership

1. **Anonymous Authentication**:
   - Backed by Supabase GoTrue Anonymous Auth (`supabaseClient.auth.signInAnonymously()`).
   - The user's authenticated UUID (`auth.uid()`) defines tenant ownership (`owner_id`).

2. **Token Lifecycle & Key Isolation**:
   - **Access Token**: Stored strictly in memory (`@Volatile private var inMemoryAccessToken: String? = null`). Never written to disk, Room database, or logs.
   - **Refresh Token**: Encrypted with AES-256-GCM using an Android Keystore key (`cayana_cloud_refresh_token_wrapper`) and stored in private SharedPreferences (`cayana_cloud_secure_prefs`).
   - **Local Database Isolation**: The local Room table `cloud_sync_state` **never** stores authentication tokens (access or refresh). It tracks only operational sync state.
   - **Session Restoration**: On app launch or sync execution, `SupabaseCloudAuthManager` attempts to restore/refresh the session using the encrypted refresh token. If refresh fails, it enters `CloudAuthStatus.NEEDS_ATTENTION` rather than silently issuing a new anonymous tenant ID.

---

## 3. Remote Database Schema & Security Policies

### Remote Table: `cloud_memory_records`

Defined in `supabase/migrations/20261008000000_cloud_memory_records.sql`:

```sql
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

CREATE INDEX IF NOT EXISTS idx_cloud_memory_records_owner_change_seq
    ON cloud_memory_records (owner_id, change_seq ASC);
```

> **Field Notes**:
> - `owner_id`: Sourced from `auth.uid()`.
> - `nonce`: Base64-encoded 12-byte random initialization vector.
> - `ciphertext`: Base64-encoded AES-256-GCM ciphertext which **includes** the 128-bit authentication tag at its end (there is no standalone `tag` column).
> - `change_seq`: Monotonically increasing sequence from `cloud_memory_records_change_seq` for pull cursor pagination.
> - Maximum decoded ciphertext bound: 524,288 bytes (exactly 512 KiB).

### Row Level Security (RLS) & Grants

- **RLS Enabled**: `ALTER TABLE cloud_memory_records ENABLE ROW LEVEL SECURITY;`
- **Policies (Explicitly `TO authenticated`)**:
  - `SELECT`: `USING (auth.uid() IS NOT NULL AND owner_id = auth.uid())`
  - `INSERT`: `WITH CHECK (auth.uid() IS NOT NULL AND owner_id = auth.uid())`
  - `UPDATE`: `USING (auth.uid() IS NOT NULL AND owner_id = auth.uid()) WITH CHECK (auth.uid() IS NOT NULL AND owner_id = auth.uid())`
- **Least-Privilege Grants**:
  - `GRANT USAGE ON SCHEMA public TO authenticated;`
  - `GRANT SELECT, INSERT, UPDATE ON TABLE public.cloud_memory_records TO authenticated;` (No hard `DELETE` grant)
  - `GRANT USAGE, SELECT ON SEQUENCE public.cloud_memory_records_change_seq TO authenticated;`

### Server-Authoritative Upsert RPC: `upsert_cloud_memory_record`

- **Execution Context**: Defined as `SECURITY INVOKER` (executes with the privileges and `auth.uid()` of the authenticated caller, strictly respecting RLS).
- **Function Grants**:
  ```sql
  REVOKE ALL ON FUNCTION public.upsert_cloud_memory_record(...) FROM PUBLIC;
  REVOKE ALL ON FUNCTION public.upsert_cloud_memory_record(...) FROM anon;
  GRANT EXECUTE ON FUNCTION public.upsert_cloud_memory_record(...) TO authenticated;
  ```
- **Monotonic Revision Safety & Conflict Resolution**:
  ```sql
  INSERT INTO public.cloud_memory_records (
      owner_id, memory_id, revision, payload_version, nonce, ciphertext, is_tombstone, change_seq, updated_at
  ) VALUES (
      v_owner_id, p_memory_id, p_revision, p_payload_version, p_nonce, p_ciphertext, p_is_tombstone,
      nextval('public.cloud_memory_records_change_seq'), now()
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
  WHERE public.cloud_memory_records.revision < EXCLUDED.revision;
  ```
  - If `p_revision > stored.revision`: Updates record and returns status `ACCEPTED`.
  - If `p_revision == stored.revision`: Idempotent retry, returns status `IDEMPOTENT`.
  - If `p_revision < stored.revision`: Stale revision rejected, returns status `STALE`.

---

## 4. Cryptography Specification

1. **Key Derivation (HKDF-SHA256)**:
   - Root Key: 32-byte Recovery Root Key from `RecoveryKeyManager`.
   - Domain Info: `"CayanaCloudSyncKeyV1"` (strictly isolated from the Google Drive backup domain `"CayanaDriveBackupKeyV1"`).
   - Salt: 32-byte zero salt.
   - Output: 32-byte (256-bit) AES key.

2. **Authenticated Encryption (AES-256-GCM)**:
   - Cipher Transformation: `AES/GCM/NoPadding`
   - Nonce: 12-byte random bytes generated per record via `SecureRandom` (`GCM_NONCE_BYTES = 12`).
   - Tag: 128-bit authentication tag (`GCM_TAG_BITS = 128`), appended directly to ciphertext by `Cipher.doFinal()`.
   - Maximum Payload: Decoded ciphertext size must not exceed `MAX_CLOUD_CIPHERTEXT_BYTES = 524288` (512 KiB).

3. **Additional Authenticated Data (AAD)**:
   Canonical pipe-delimited format:
   ```text
   CAYANA_CLOUD_V1|<memoryId>|<revision>|<payloadVersion>|<isTombstone>
   ```
   Ensures ciphertext cannot be transplanted across different memory IDs, revisions, payload versions, or tombstone states without cryptographic tag mismatch.

---

## 5. Serialized Payloads

Implemented in `CloudPayloads.kt`:

### Active Memory Payload: `CloudMemoryPayloadV1`
```kotlin
@Serializable
data class CloudMemoryPayloadV1(
    val id: String,
    val sourceType: String,
    val createdAt: Long,
    val capturedAt: Long,
    val title: String? = null,
    val rawText: String? = null,
    val normalizedText: String? = null,
    val sourceUri: String? = null,
    val sourceUrl: String? = null,
    val sourceExists: Boolean = false,
    val metadataJson: String = "{}",
    val entitiesJson: String = "[]",
    val eventCandidatesJson: String = "[]",
    val processingState: String = "COMPLETED"
)
```

### Tombstone Payload: `CloudTombstonePayloadV1`
```kotlin
@Serializable
data class CloudTombstonePayloadV1(
    val memoryId: String,
    val revision: Long,
    val deleted: Boolean = true
)
```

### Tombstone Verification Protocol
- The server-visible boolean column `is_tombstone` is purely an unauthenticated routing flag.
- **Security Requirement**: The client **never** deletes local data based solely on the unauthenticated `is_tombstone` server column.
- The client must decrypt and verify the authenticated payload (`CloudTombstonePayloadV1`). Only when `tombstone.memoryId` matches, `tombstone.deleted == true`, and `remoteRecord.revision > localRevision` does the client execute local deletion.

---

## 6. Local Database Architecture

Defined in `CloudSyncEntities.kt`:

1. **`cloud_sync_state`**:
   ```kotlin
   @Entity(tableName = "cloud_sync_state")
   data class CloudSyncStateEntity(
       @PrimaryKey val id: Int = 1,
       val isInitialized: Boolean = false,
       val isEnabled: Boolean = false,
       val lastPullSeq: Long = 0L,
       val lastSuccessfulSyncAt: Long = 0L,
       val lastErrorCode: String? = null
   )
   ```
   *(Note: Authentication tokens are strictly excluded from this table).*

2. **`cloud_memory_sync_metadata`**:
   ```kotlin
   @Entity(tableName = "cloud_memory_sync_metadata")
   data class CloudMemorySyncMetadataEntity(
       @PrimaryKey val memoryId: String,
       val revision: Long,
       val lastSyncedRevision: Long = 0L
   )
   ```

3. **`cloud_sync_outbox`**:
   ```kotlin
   @Entity(
       tableName = "cloud_sync_outbox",
       indices = [
           Index(value = ["createdAt"]),
           Index(value = ["memoryId", "revision"])
       ]
   )
   data class CloudSyncOutboxEntity(
       @PrimaryKey val id: String,
       val memoryId: String,
       val revision: Long,
       val operation: String, // "UPSERT" or "DELETE"
       val createdAt: Long,
       val attemptCount: Int = 0
   )
   ```

---

## 7. Sync Protocol & Mutation Lifecycle

### Outbox Insertion (Local Write Gate)
When a memory is modified locally:
- `saveMemory(item, origin = MutationOrigin.LOCAL)`:
  - If cloud sync is initialized: increments `cloud_memory_sync_metadata.revision`, enqueues `"UPSERT"` outbox record with the new revision.
- `deleteMemory(id, origin = MutationOrigin.LOCAL)`:
  - If cloud sync is initialized: increments revision, enqueues `"DELETE"` outbox record.

### Push Phase
1. Reads pending entries from `cloud_sync_outbox` ordered by `createdAt ASC`.
2. Encrypts memory payload or tombstone payload with `CloudCryptoService`.
3. Calls PostgREST RPC `upsert_cloud_memory_record`.
4. On `ACCEPTED` or `IDEMPOTENT`: deletes outbox row and sets `lastSyncedRevision = revision`.
5. On `STALE`: deletes outbox row to prevent infinite retry loops against a newer server record.

### Pull Phase
1. Queries PostgREST: `SELECT * FROM cloud_memory_records WHERE change_seq > lastPullSeq ORDER BY change_seq ASC LIMIT 50`.
2. For each remote record:
   - Validates decoded nonce (12 bytes) and ciphertext (<= 512 KiB).
   - If `is_tombstone`: decrypts `CloudTombstonePayloadV1`. If `remoteRecord.revision > localRevision`, calls:
     ```kotlin
     memoryRepository.deleteMemory(
         id = tombstone.memoryId,
         origin = MutationOrigin.CLOUD_SYNC,
         remoteRevision = remoteRecord.revision
     )
     ```
   - If active: decrypts `CloudMemoryPayloadV1`. If `remoteRecord.revision > localRevision`, calls:
     ```kotlin
     memoryRepository.saveMemory(
         item = domainItem,
         origin = MutationOrigin.CLOUD_SYNC,
         remoteRevision = remoteRecord.revision
     )
     ```
   - Advances cursor: `cloudSyncStateDao.updateLastPullSeq(remoteRecord.change_seq)`.
3. Setting `origin = MutationOrigin.CLOUD_SYNC` ensures incoming remote records update the local database without echoing back into the outbox table.
