# Future Reference

This directory contains research, architectural designs, and implementations that have been explored during Cayana's development but are explicitly **out of scope for Cayana v1 MVP**.

## Purpose & Architecture Boundaries

Cayana v1 keeps canonical Memory and retrieval **local-first**:
1. **Local-First Canonical Memory & Processing**:
   - On-device media observation and OCR (ML Kit).
   - On-device speech-to-text (sherpa-onnx SenseVoice INT8).
   - Local Full-Text Search (FTS4) and on-device vector retrieval.
   - Canonical memory records remain strictly on the local device.
2. **Zero-Knowledge Cloud Backup**:
   - Client-side AES-256-GCM encrypted backup directly to the user's private Google Drive (`drive.appdata` hidden app folder).
3. **No Cayana Cloud Sync / No Account Ecosystem**:
   - Cayana v1 does not require Cayana Cloud Memory Sync, multi-tenant database synchronization, or user account registration.
4. **Optional Cayana Ask**:
   - When the user asks natural language questions, only bounded, locally retrieved Top-K memory context is sent to the Cayana LLM inference service. Canonical memories and indexing stay strictly on-device.

Any features, backend database sync architectures, or legacy roadmap documents that fall outside the v1 MVP scope are safely archived here so they:
- Do not add bloat or third-party backend dependencies to the v1 production APK.
- Do not complicate v1 data consistency or Room database schemas.
- Remain fully preserved for future reference and post-v1 releases.

## Directory Structure

- **`post-v1/`**: Feature implementations, architectures, and prototypes intended for post-v1 evaluation (e.g., Cayana Cloud encrypted sync).
- **`legacy-plans/`**: Historic versions of product plans and roadmaps (such as v0.2) that included post-v1 features. The canonical v1 MVP documents live at the repository root (`Cayana_Product_Plan_v1_MVP.md` and `Cayana_Agent_Development_Roadmap_v1_MVP.md`).
