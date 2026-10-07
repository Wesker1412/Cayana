# Cayana Cloud Encrypted Memory Sync — Revival Checklist

> **CRITICAL WARNING**:
> Before reviving, read `KNOWN_ISSUES.md` and rerun a current security review. Do not assume the archived implementation is production-ready.
> The archived baseline is commit `c918752a76a8c23dc812719369b6af9904bcf251` (`c918752`).

Follow this checklist if and when the team decides to revive Cayana Cloud Sync in a post-v1 release.

---

## Phase 1: Architectural Alignment & Unresolved Blockers

Resolve the open blockers identified in `KNOWN_ISSUES.md` before writing code:

- [ ] **Identity & Pairing Model**: Choose between:
  - Anonymous Auth + Local QR Code ECDH pairing (zero PII, multi-device).
  - Account Linking (Passkey, Google Sign-In, or Magic Link).
- [ ] **Reinstall Recovery Strategy**: Design identity transfer so users do not orphan remote records upon `pm clear` or device migration.
- [ ] **Batching Strategy**: Update the RPC interface to support batch upserts (`upsert_cloud_memory_records_batch`) to avoid sequential HTTP calls on large initial syncs.
- [ ] **Tombstone Pruning Policy**: Define server-side retention (e.g., prune soft-deleted records older than 30 or 60 days).
- [ ] **Key Rotation Protocol**: Define re-encryption flow if a user rotates their root recovery key.

---

## Phase 2: Dependencies & Gradle Configuration

Exact dependencies from commit `c918752`:

- [ ] In `gradle/libs.versions.toml`:
  ```toml
  [versions]
  supabase = "3.0.3"
  ktor = "3.0.1"
  embeddedPostgres = "2.2.0"

  [libraries]
  supabase-bom = { group = "io.github.jan-tennert.supabase", name = "bom", version.ref = "supabase" }
  supabase-auth = { group = "io.github.jan-tennert.supabase", name = "auth-kt" }
  supabase-postgrest = { group = "io.github.jan-tennert.supabase", name = "postgrest-kt" }
  ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
  embedded-postgres = { group = "io.zonky.test", name = "embedded-postgres", version.ref = "embeddedPostgres" }

  [plugins]
  kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
  ```
- [ ] In `build.gradle.kts` (root):
  ```kotlin
  alias(libs.plugins.kotlin.serialization) apply false
  ```
- [ ] In `app/build.gradle.kts`:
  - Re-add plugin: `alias(libs.plugins.kotlin.serialization)`
  - Re-add buildConfig fields:
    ```kotlin
    val supabaseUrl = (project.findProperty("CAYANA_SUPABASE_URL") as? String)
        ?: System.getenv("CAYANA_SUPABASE_URL")
        ?: ""
    val supabasePublishableKey = (project.findProperty("CAYANA_SUPABASE_PUBLISHABLE_KEY") as? String)
        ?: System.getenv("CAYANA_SUPABASE_PUBLISHABLE_KEY")
        ?: ""
    buildConfigField("String", "CAYANA_SUPABASE_URL", "\"$supabaseUrl\"")
    buildConfigField("String", "CAYANA_SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
    ```
  - Re-add libraries:
    ```kotlin
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation(libs.embedded.postgres)
    ```

---

## Phase 3: Room Database Forward Migration

- [ ] Bump `CayanaDatabase.version` to next version (e.g. 10).
- [ ] Re-register entities in `CayanaDatabase`:
  - `com.cayana.cloud.data.CloudSyncStateEntity::class`
  - `com.cayana.cloud.data.CloudMemorySyncMetadataEntity::class`
  - `com.cayana.cloud.data.CloudSyncOutboxEntity::class`
- [ ] Re-register abstract DAOs:
  - `abstract fun cloudSyncStateDao(): com.cayana.cloud.data.CloudSyncStateDao`
  - `abstract fun cloudMemorySyncMetadataDao(): com.cayana.cloud.data.CloudMemorySyncMetadataDao`
  - `abstract fun cloudSyncOutboxDao(): com.cayana.cloud.data.CloudSyncOutboxDao`
- [ ] Add forward Room migration (e.g. `MIGRATION_9_10`):
  ```kotlin
  val MIGRATION_9_10 = object : Migration(9, 10) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("""
              CREATE TABLE IF NOT EXISTS `cloud_sync_state` (
                  `id` INTEGER NOT NULL,
                  `isInitialized` INTEGER NOT NULL DEFAULT 0,
                  `isEnabled` INTEGER NOT NULL DEFAULT 0,
                  `lastPullSeq` INTEGER NOT NULL DEFAULT 0,
                  `lastSuccessfulSyncAt` INTEGER NOT NULL DEFAULT 0,
                  `lastErrorCode` TEXT,
                  PRIMARY KEY(`id`)
              )
          """.trimIndent())
          db.execSQL("""
              CREATE TABLE IF NOT EXISTS `cloud_memory_sync_metadata` (
                  `memoryId` TEXT NOT NULL,
                  `revision` INTEGER NOT NULL,
                  `lastSyncedRevision` INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY(`memoryId`)
              )
          """.trimIndent())
          db.execSQL("""
              CREATE TABLE IF NOT EXISTS `cloud_sync_outbox` (
                  `id` TEXT NOT NULL,
                  `memoryId` TEXT NOT NULL,
                  `revision` INTEGER NOT NULL,
                  `operation` TEXT NOT NULL,
                  `createdAt` INTEGER NOT NULL,
                  `attemptCount` INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY(`id`)
              )
          """.trimIndent())
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_sync_outbox_createdAt` ON `cloud_sync_outbox` (`createdAt`)")
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_sync_outbox_memoryId_revision` ON `cloud_sync_outbox` (`memoryId`, `revision`)")
      }
  }
  ```
- [ ] Add `MIGRATION_9_10` to `DatabaseModule.kt` and register the three DAOs in Koin.
- [ ] Export schema JSON and create `Stage10MigrationTest` verifying canonical tables are preserved and cloud tables are created.

---

## Phase 4: Code Re-integration

- [ ] Copy Kotlin sources from `original-implementation/app-main/cloud/` into `app/src/main/java/com/cayana/cloud/`.
- [ ] Copy `CloudModule.kt` to `app/src/main/java/com/cayana/core/di/`.
- [ ] Register `cloudModule` in `CayanaApplication.kt` `startKoin`.
- [ ] Update `MemoryRepository` interface & `MutationOrigin`:
  ```kotlin
  enum class MutationOrigin {
      LOCAL,
      CLOUD_SYNC,
      RESTORE
  }

  suspend fun saveMemory(
      item: MemoryItem,
      origin: MutationOrigin = MutationOrigin.LOCAL,
      remoteRevision: Long? = null
  )

  suspend fun deleteMemory(
      id: String,
      origin: MutationOrigin = MutationOrigin.LOCAL,
      remoteRevision: Long? = null
  )
  ```
- [ ] Re-wire `RoomMemoryRepository`:
  - Inject `cloudSyncStateDao`, `cloudMemorySyncMetadataDao`, `cloudSyncOutboxDao`.
  - In `saveMemory`: if `origin == MutationOrigin.LOCAL`, enqueue outbox item; if `origin == MutationOrigin.CLOUD_SYNC`, update metadata to `remoteRevision`.
  - In `deleteMemory`: if `origin == MutationOrigin.LOCAL`, enqueue outbox delete item; if `origin == MutationOrigin.CLOUD_SYNC`, update metadata.
- [ ] In `ViewModelModule.kt`: inject `cloudSyncManager` into `SettingsViewModel`.
- [ ] In `SettingsViewModel.kt` & `SettingsScreen.kt`: re-enable the Cloud Sync toggle, status flow, and manual sync action.

---

## Phase 5: Backend Deployment & Testing

- [ ] Apply `original-implementation/supabase/migrations/20261008000000_cloud_memory_records.sql` to target Supabase instance.
- [ ] Verify `upsert_cloud_memory_record` has `SECURITY INVOKER` and is executable only by `authenticated`.
- [ ] Copy tests from `original-implementation/app-tests/` to `app/src/test/java/com/cayana/cloud/`.
- [ ] Run test suite:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.cayana.cloud.*"
  ```
- [ ] Perform real hosted Supabase E2E acceptance test (multi-tenant RLS, offline transition, process death recovery).
