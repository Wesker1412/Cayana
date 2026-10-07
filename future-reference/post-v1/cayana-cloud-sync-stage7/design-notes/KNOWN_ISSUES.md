# Cayana Cloud Encrypted Memory Sync — Known Issues & Limitations

This document captures architectural trade-offs, unsolved problems, and known limitations identified during the Stage 7 implementation.

---

## 1. Anonymous Identity vs. Device Lifecycle

- **App Data Wipe / Reinstall**:
  Because authentication uses anonymous auth without account credentials, clearing app storage or reinstalling generates a fresh Supabase `auth.uid()`. As a result, the old cloud tenant becomes unreachable from the new installation.
- **Single-Device Isolation**:
  Stage 7 was scoped to a single device tenant. Cross-device syncing requires either account linking (email/OAuth) or a cryptographic pairing protocol (e.g., QR code scanning, local peer-to-peer key exchange).

---

## 2. Tombstone Accumulation & Compaction

- **Soft Deletes**:
  When a memory is deleted, a record with `is_deleted = true` is published to the cloud so pulling clients can observe the deletion.
- **Unbounded Growth**:
  Without a server-side garbage collection policy or client tombstone compaction window (e.g., pruning tombstones older than 30 days), the remote `cloud_memory_records` table grows monotonically.

---

## 3. Network Performance & Batching

- **Per-Record Upserts**:
  The prototype implementation processed outbox entries individually via single-row RPC calls. On mobile networks with high latency, syncing hundreds of memories during an initial sync or after prolonged offline use is slow.
- **Batch RPC Needed**:
  A production implementation requires a batched upsert RPC (e.g., accepting an array of encrypted record structs in a single HTTP request).

---

## 4. Key Management & Key Rotation

- **Key Loss**:
  If a user loses their recovery key or clears device credentials without backup, encrypted cloud records cannot be decrypted.
- **Key Rotation**:
  There is currently no protocol to rotate the Cloud Sync symmetric key across previously encrypted cloud records. Rotating the key would require re-encrypting all remote records in a single coordinated transaction.
