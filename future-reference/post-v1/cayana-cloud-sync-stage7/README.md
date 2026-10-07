# Post-v1 Archive: Cayana Cloud Encrypted Memory Sync (Stage 7)

## Overview

This directory preserves the entire architecture, source code, tests, and database migrations for **Cayana Cloud Encrypted Memory Sync**, originally developed during Stage 7 (commit `c918752`).

## Why is this post-v1?

In accordance with the **Cayana v1 MVP Boundary Definition**:
- Cayana v1 is strictly a **100% on-device private memory companion** with zero external backend reliance.
- Backup in v1 is handled through client-side encrypted snapshots to the user's private Google Drive (`drive.appdata`).
- Features involving cloud servers, central storage, cross-device synchronization, anonymous cloud accounts, and database replication across tenants are deferred to a post-v1 timeline.

To ensure the production app remains lean, robust, and free of unnecessary third-party network dependencies or database surface area, this implementation was extracted and decoupled from the main repository.

## Structure

```text
cayana-cloud-sync-stage7/
├── README.md
├── design-notes/
│   ├── STAGE7_ARCHITECTURE.md
│   ├── KNOWN_ISSUES.md
│   └── REVIVAL_CHECKLIST.md
└── original-implementation/
    ├── app-main/      # Production Kotlin source code (com/cayana/cloud, CloudModule.kt)
    ├── app-tests/     # Unit and integration tests (Postgres RLS, Sync, Crypto, Outbox)
    └── supabase/      # Supabase migrations, RLS policies, and RPC definitions
```
