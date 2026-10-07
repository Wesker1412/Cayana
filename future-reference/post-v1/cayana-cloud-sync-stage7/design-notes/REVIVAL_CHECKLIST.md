# Cayana Cloud Encrypted Memory Sync — Revival Checklist

Follow this checklist if and when the team decides to reintroduce Cayana Cloud Sync in a post-v1 release.

---

## Phase 1: Architectural Alignment & Requirements

- [ ] **Identity Model Decision**: Choose between:
  - Anonymous Auth + QR Code Pairing (recommended for zero PII).
  - Account Linking (Email / Magic Link / Passkeys).
- [ ] **Infrastructure Setup**: Deploy Supabase instance (or self-hosted Postgres with GoTrue) with production SSL and managed backups.
- [ ] **Batching Strategy**: Update the RPC interface to support batch upserts (e.g. `upsert_cloud_memory_records_batch`).
- [ ] **Tombstone Pruning Policy**: Define server-side retention (e.g., prune deleted records older than 60 days).

---

## Phase 2: Gradle & Dependencies

- [ ] Re-add Supabase dependencies to `gradle/libs.versions.toml`:
  - `supabase-bom`, `supabase-gotrue-kt`, `supabase-postgrest-kt`
  - `ktor-client-android`, `ktor-client-core`
  - `kotlinx-serialization-json`
- [ ] Re-add buildConfig fields in `app/build.gradle.kts`:
  - `CAYANA_SUPABASE_URL`
  - `CAYANA_SUPABASE_PUBLISHABLE_KEY`
- [ ] Enable `kotlinx.serialization` plugin in `app/build.gradle.kts` if needed.

---

## Phase 3: Room Database Migration

- [ ] Define Room migration (e.g. `MIGRATION_9_10` or next version).
- [ ] Re-create the three tables:
  ```sql
  CREATE TABLE IF NOT EXISTS cloud_sync_state (...);
  CREATE TABLE IF NOT EXISTS cloud_memory_sync_metadata (...);
  CREATE TABLE IF NOT EXISTS cloud_sync_outbox (...);
  ```
- [ ] Register `CloudSyncStateEntity`, `CloudMemorySyncMetadataEntity`, `CloudSyncOutboxEntity` in `CayanaDatabase`.
- [ ] Write Room migration unit tests verifying that existing `memories`, `memories_fts`, and `calendar_actions` are completely preserved.

---

## Phase 4: Code Integration

- [ ] Copy source files from `original-implementation/app-main/com/cayana/cloud` to `app/src/main/java/com/cayana/cloud/`.
- [ ] Copy `CloudModule.kt` to `app/src/main/java/com/cayana/core/di/`.
- [ ] Update `RoomMemoryRepository`:
  - Inject `CloudSyncOutboxDao` and `CloudMemorySyncMetadataDao`.
  - On `saveMemory(memory, origin)`: if `origin == MutationOrigin.LOCAL`, enqueue to outbox.
  - On `deleteMemory(id, origin)`: if `origin == MutationOrigin.LOCAL`, enqueue tombstone to outbox.
- [ ] Register `cloudModule` in `CayanaApplication`.
- [ ] Reconnect `SettingsViewModel` and `SettingsScreen` to display sync status and toggle.

---

## Phase 5: Verification & Testing

- [ ] Copy tests from `original-implementation/app-tests/` to `app/src/test/java/com/cayana/cloud/`.
- [ ] Run unit tests:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.cayana.cloud.*"
  ```
- [ ] Run Postgres RLS integration tests with embedded Postgres.
- [ ] Perform E2E sync test between two physical devices / emulators.
