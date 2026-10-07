# Future Reference

This directory contains research, architectural designs, and implementations that have been explored during Cayana's development but are explicitly **out of scope for Cayana v1 MVP**.

## Purpose

Cayana v1 MVP is strictly focused on:
1. **100% On-Device Local Processing**: OCR, Whisper STT, FTS search, vector embeddings, local retrieval, and notification capture.
2. **Zero-Knowledge Backup**: Client-side AES-256-GCM encrypted backup directly to the user's private Google Drive (`drive.appdata` hidden folder).
3. **No External Accounts / No Cloud Sync**: No central server, no accounts, no subscriptions, no third-party data tracking.

Any features, backend sync architectures, or legacy roadmap documents that fall outside this scope are safely archived here so they:
- Do not add bloat or dependencies to the v1 production APK.
- Do not complicate v1 data consistency and Room database schemas.
- Remain fully preserved for future reference and post-v1 releases.

## Directory Structure

- **`post-v1/`**: Feature implementations, architectures, and prototypes intended for post-v1 evaluation (e.g., Cayana Cloud encrypted sync).
- **`legacy-plans/`**: Historic versions of product plans and roadmaps (such as v0.2) that included post-v1 features. The canonical v1 MVP documents live at the repository root (`Cayana_Product_Plan_v1_MVP.md` and `Cayana_Agent_Development_Roadmap_v1_MVP.md`).
