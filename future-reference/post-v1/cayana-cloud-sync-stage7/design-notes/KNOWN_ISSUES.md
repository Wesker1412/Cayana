# Cayana Cloud Encrypted Memory Sync — Known Issues & Resolution Status

This document reflects the exact status of Cayana Cloud Sync at the time of archival.

It separates issues that were fixed in commit `c918752a76a8c23dc812719369b6af9904bcf251` (`c918752`) from architectural gaps that remain unresolved and disqualified this subsystem from the v1 MVP release.

---

## 1. Resolved Before Archival (in commit c918752)

Prior to archival, commit `c918752` resolved the initial prototype (`b91b13f`) blockers across security, database consistency, and cryptography:

1. **Explicit Supabase Data API Grants**:
   - Added explicit `GRANT USAGE ON SCHEMA public TO authenticated;`
   - Added explicit `GRANT SELECT, INSERT, UPDATE ON TABLE public.cloud_memory_records TO authenticated;`
   - Added explicit `GRANT USAGE, SELECT ON SEQUENCE public.cloud_memory_records_change_seq TO authenticated;`
   - Withheld hard `DELETE` grant from the `authenticated` role to enforce soft tombstones.

2. **Authenticated-Only RLS Policy Targets**:
   - RLS policies on `cloud_memory_records` (`SELECT`, `INSERT`, `UPDATE`) explicitly target `TO authenticated`.

3. **Session Refresh Failure No Longer Creates Duplicate Anonymous Tenants**:
   - Decoupled `initialSignInAnonymously()` from `restoreSession()` and `refreshSession()`.
   - If an existing session refresh fails (e.g. invalid or revoked refresh token), the system transitions to `CloudAuthStatus.NEEDS_ATTENTION` rather than silently calling `signInAnonymously()` which would orphan previously synced records under a new tenant ID.

4. **Atomic Initial Enable Transaction**:
   - Sync initialization now bundles `cloud_sync_state`, `cloud_memory_sync_metadata`, and `cloud_sync_outbox` initializations inside a single Room database transaction.

5. **Concurrency-Safe & Revision-Safe RPC**:
   - `upsert_cloud_memory_record` uses `INSERT ... ON CONFLICT (owner_id, memory_id) DO UPDATE ... WHERE public.cloud_memory_records.revision < EXCLUDED.revision`.
   - Correctly returns `ACCEPTED`, `IDEMPOTENT`, or `STALE`, preventing concurrent out-of-order writes from clobbering newer revisions.
   - Defined as `SECURITY INVOKER` to execute under the caller's authenticated context.

6. **Owner Validation in RPC & RLS**:
   - RPC strictly validates `auth.uid() IS NOT NULL`, preventing unauthenticated caller execution.

7. **Remote Structural Validation**:
   - Validates that nonces are valid Base64 and exactly 12 bytes.
   - Validates that ciphertexts are non-empty and valid Base64.

8. **Decoded Ciphertext Size Bound Alignment**:
   - Unified maximum payload bounds between client Kotlin code (`MAX_CLOUD_CIPHERTEXT_BYTES = 524288`) and PostgreSQL PL/pgSQL validation (`c_max_ciphertext_bytes = 524288`, exactly 512 KiB decoded bytes).

9. **Dummy Backend Config Removed**:
   - Client requires non-blank Supabase URL and publishable key; fails fast with a typed configuration error instead of attempting connections to placeholder endpoints.

10. **`sourceExists` Canonical Mutation Path Fix**:
    - Ensured updates to `sourceExists` route through canonical `saveMemory(item, origin = MutationOrigin.LOCAL)`, properly enqueuing outbox events when local media is deleted or restored.

---

## 2. Still Unresolved / Not Production-Accepted

The following architectural and operational limitations remain unsolved in the archived implementation and must be addressed before any future revival:

### 1. Real Hosted Supabase E2E Acceptance Not Completed
While unit tests and embedded Postgres integration tests passed, end-to-end verification against a real hosted Supabase production infrastructure was never executed:
- **No hosted anonymous Auth validation**: Untested against real Supabase GoTrue rate limiters and session expiration policies.
- **No hosted PostgREST validation**: Untested against real edge HTTP proxies and latency profiles.
- **No real multi-tenant RLS isolation test**: Never verified with two distinct physical accounts (Tenant A vs Tenant B) on hosted Supabase.
- **No real process termination during sync**: Untested under Android OS process death mid-stream during large payload uploads.
- **No real network flapping test**: Untested under aggressive mobile offline/online transitions.
- **No remote ciphertext plaintext scan**: Untested for accidental metadata leaks on a real cloud database instance.

### 2. Anonymous Identity Lifecycle & Reinstall Data Loss
- **App Data Wipe / Reinstall**:
  Because authentication uses anonymous auth without account credentials (email, phone, passkeys), clearing app storage (`adb shell pm clear`) or reinstalling the app permanently destroys the local refresh token.
- **Orphaned Cloud Data**:
  When this happens, the user is assigned a fresh anonymous `auth.uid()`. Their previously uploaded cloud records remain stranded in the database under the old `owner_id` with no recovery path.

### 3. Multi-Device Identity & Cross-Device Pairing
- Stage 7 was strictly scoped to a single device tenant.
- No protocol was designed or implemented for:
  - Cryptographic device pairing (e.g., ephemeral ECDH key exchange via QR code).
  - Account linking (linking an anonymous session to an identity provider).
  - Multi-device cryptographic root key synchronization.

### 4. Tombstone Accumulation & Garbage Collection
- **Unbounded Growth**:
  When a memory is deleted, a record with `is_tombstone = true` is permanently stored on the cloud server so pulling clients can observe the deletion.
- **No Pruning Window**:
  There is currently no server-side compaction job or retention policy (e.g., purging tombstones older than 30 or 60 days). The remote `cloud_memory_records` table grows monotonically.

### 5. Cryptographic Key Rotation
- If a user suspects key compromise or changes their recovery key, there is no protocol to re-encrypt previously synchronized cloud records.
- Rotating the key would require downloading, decrypting, re-encrypting, and uploading all remote records in an atomic distributed transaction.

### 6. Large Initial Sync Optimization
- The current implementation processes outbox items via single-row RPC calls.
- While acceptable for small incremental edits, synchronizing hundreds of existing memories during initial setup results in sequential HTTP requests. A batched RPC endpoint (`upsert_cloud_memory_records_batch`) was not implemented.
