# Post-v1 Archive: Cayana Cloud Encrypted Memory Sync (Stage 7)

## Overview

This directory preserves the entire architecture, source code, tests, and database migrations for **Cayana Cloud Encrypted Memory Sync**, developed during Stage 7.

### Baseline Commits
- **Initial Implementation**: Commit `b91b13f` introduced the initial prototype of Cayana Cloud sync with Supabase anonymous auth and AES-GCM crypto.
- **Final Archived Baseline**: Commit `c918752a76a8c23dc812719369b6af9904bcf251` (`c918752`) is the authoritative source for this archive. It resolved multiple production reliability gaps (least-privilege grants, authenticated RLS targets, atomic enable transaction, monotonic revision-safe RPC, byte length bounds, and decoupled refresh token lifecycle).

## Why is this post-v1?

In accordance with the **Cayana v1 MVP Boundary Definition**:
- Cayana v1 keeps canonical Memory and retrieval **local-first**. It does not require Cayana Cloud Memory Sync or a Cayana account ecosystem.
- Remote backup in v1 is handled through client-side AES-256-GCM encrypted snapshots directly to the user's private Google Drive (`drive.appdata` hidden app folder).
- Features involving multi-tenant cloud databases, central storage, cross-device synchronization, anonymous cloud accounts, and database replication across tenants are deferred to post-v1 releases.
- Optional natural language queries (Cayana Ask) send only bounded, locally retrieved Top-K memory context to the Cayana LLM inference service; raw canonical memories are never stored on a centralized server.

To ensure the production app remains lean, robust, and free of unnecessary third-party network dependencies or database surface area, this implementation was extracted and decoupled from the main repository.

## Structure

```text
cayana-cloud-sync-stage7/
├── README.md
├── design-notes/
│   ├── STAGE7_ARCHITECTURE.md   # Exact technical architecture from archived code
│   ├── KNOWN_ISSUES.md          # Resolved vs. unresolved items
│   └── REVIVAL_CHECKLIST.md     # Step-by-step checklist based on commit c918752
└── original-implementation/
    ├── app-main/      # Production Kotlin source code (com/cayana/cloud, CloudModule.kt)
    ├── app-tests/     # Unit and integration tests (Postgres RLS, Sync, Crypto, Outbox)
    └── supabase/      # Supabase migrations, RLS policies, and RPC definitions
```
