# Agentic Memory — Implementation Deep Dive

This document provides a low‑level, code‑referenced summary of the Agentic Memory feature implemented in ML Commons. It consolidates behavior from REST and Transport layers, common models, helpers, and utilities into one technical overview.

## Executive Summary

- Purpose: Native memory system for intelligent agents with unified short/long‑term handling and production‑ready APIs.
- Layers: Memory Container (metadata/config) and Memories (CRUD + search).
- Intelligence: LLM‑powered fact extraction and memory decisions (ADD/UPDATE/DELETE/NONE) with dense or sparse semantic storage.

## Code Layout (key paths)

- REST: `plugin/src/main/java/org/opensearch/ml/rest`
- Transport (containers): `plugin/src/main/java/org/opensearch/ml/action/memorycontainer`
- Transport (memories): `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory`
- Models: `common/src/main/java/org/opensearch/ml/common/memorycontainer`
- Transport I/O: `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer` (+ `/memory`)
- Helpers/Utils: `plugin/src/main/java/org/opensearch/ml/helper`, `plugin/src/main/java/org/opensearch/ml/utils`

## Data Models

- `MLMemoryContainer`
  - Fields: `name`, `description`, `owner (User)`, `tenantId`, `createdTime`, `lastUpdatedTime`, `memoryStorageConfig`.
  - Source: `common/.../memorycontainer/MLMemoryContainer.java`

- `MemoryStorageConfig`
  - Fields: `memoryIndexName`, derived `semanticStorageEnabled`, `embeddingModelType` (`TEXT_EMBEDDING`/`SPARSE_ENCODING`), `embeddingModelId`, optional `llmModelId`, `dimension` (required for dense), `maxInferSize` (used when `llm_model_id` is set; max 10).
  - Behavior: `semanticStorageEnabled` auto‑determined when both `embeddingModelType` and `embeddingModelId` are provided; validation enforces type/dimension rules and limits.
  - Source: `common/.../memorycontainer/MemoryStorageConfig.java`

- `MLMemory`
  - Fields: `sessionId`, `memory` (text), `memoryType` (`RAW_MESSAGE`|`FACT`), optional `userId`, `agentId`, `role`, `tags`, timestamps, optional `memoryEmbedding`.
  - Source: `common/.../memorycontainer/MLMemory.java`

- Decisions & Events
  - `MemoryDecision` (id, text, event, oldMemory) and `MemoryDecisionRequest` (oldMemory[id,text,score], retrievedFacts).
  - `MemoryEvent`: `ADD` | `UPDATE` | `DELETE` | `NONE`.
  - Sources: `common/.../memorycontainer/MemoryDecision*.java`, `common/.../transport/memorycontainer/memory/MemoryEvent.java`

## Indices & Mappings

- System index (containers): `.plugins-ml-agentic-memory-container` (initialized via `MLIndicesHandler`).
- Memory data indices (auto‑name if not provided; lowercased):
  - Static: `ml-static-memory-{containerId}-{userId}` (plain text search)
  - KNN (dense): `ml-knn-memory-{containerId}-{userId}` with `knn_vector` field `memory_embedding`, Lucene HNSW (`ef_search=100`, `ef_construction=100`, `m=16`)
  - Sparse: `ml-sparse-memory-{containerId}-{userId}` with `rank_features` field `memory_embedding`
- Common fields: `user_id`/`agent_id`/`session_id` (keyword), `memory` (text), `tags` (flat_object), `memory_type` (keyword), `role` (text), `created_time`/`last_updated_time` (date).
- Source: `TransportCreateMemoryContainerAction.createMemoryDataIndex`

## API Endpoints (summary)

### Memory Containers
- Create: `POST /_plugins/_ml/memory_containers/_create`
  - REST: `RestMLCreateMemoryContainerAction`
  - Transport: `TransportCreateMemoryContainerAction`
  - Validates models, writes container, creates memory data index, persists resolved `memoryIndexName`. Returns generated ID.

- Get: `GET /_plugins/_ml/memory_containers/{memory_container_id}`
  - REST: `RestMLGetMemoryContainerAction`
  - Transport: `TransportGetMemoryContainerAction`
  - Enforces access (admin/owner/backend role). Tenant validation via `TenantAwareHelper`.

- Delete: `DELETE /_plugins/_ml/memory_containers/{memory_container_id}`
  - REST: `RestMLDeleteMemoryContainerAction`
  - Transport: `TransportDeleteMemoryContainerAction`
  - Validates access, deletes container via `SdkClient`. No cascading deletes.

Note: Container UPDATE not implemented.

### Memories (per‑container)
- Create event: `POST /_plugins/_ml/memory_containers/{id}/events/_create`
  - REST: `RestMLCreateEventAction`
  - Transport: `TransportCreateEventAction`
  - Input: `MLCreateEventInput` with `messages[]`, optional `session_id`, `agent_id`, `infer`, `tags`.
  - Session: auto‑generates `sess_{uuid}` if missing.
  - Infer rules: if container has `llm_model_id` and `infer` is null → defaults true; `infer=true` without `llm_model_id` → error; `infer=false` requires `role` on every message.
  - Non‑LLM path: stores `RAW_MESSAGE` per message; if semantic storage, batches embedding generation; response includes stored entries.
  - LLM path: extracts facts with system prompt; if `session_id` provided, searches similar `FACT`s (dense/sparse/match). Builds a decision request, calls LLM for memory decisions, executes ADD/UPDATE/DELETE (suppresses NONE). If semantic storage, regenerates embeddings for resulting ADD/UPDATE documents.
  - Services: `MemoryProcessingService` (LLM), `MemorySearchService`, and `MemoryOperationsService`.
  - Output: `MLCreateEventResponse` with `event_id` and `session_id` identifiers.

- Search: `GET|POST /_plugins/_ml/memory_containers/{id}/memories/_search`
  - REST: `RestMLSearchMemoriesAction`
  - Transport: `TransportSearchMemoriesAction`
  - Auto‑selects `neural` / `neural_sparse` / `match` per storage config; excludes `memory_embedding` in `_source`. No hard size cap.
  - Output: `MLSearchMemoriesResponse` with `hits[]` (id, memory, score, metadata, timestamps), `total`, `max_score`, `timed_out`.

- Get a memory: `GET /_plugins/_ml/memory_containers/{id}/memories/{memory_id}`
  - REST: `RestMLGetMemoryAction`; Transport: `TransportGetMemoryAction`.

- Update a memory: `PUT /_plugins/_ml/memory_containers/{id}/memories/{memory_id}`
  - REST: `RestMLUpdateMemoryAction`; Transport: `TransportUpdateMemoryAction`.
  - Input: `{ text }`. Updates `memory` + `last_updated_time`; regenerates embedding when semantic storage is enabled (best effort).

- Delete a memory: `DELETE /_plugins/_ml/memory_containers/{id}/memories/{memory_id}`
  - REST: `RestMLDeleteMemoryAction`; Transport: `TransportDeleteMemoryAction`.

## SDK Client Migration for Bulk Operations

- **Context**: Memory operations migrated from traditional `client.bulk()` to `sdkClient.bulkDataObjectAsync()` following commit 190b2dfc.
- **Implementation**: `MemoryOperationsService` now uses SDK client for all bulk operations.
- **Request Mappings**:
  - `IndexRequest` → `PutDataObjectRequest`
  - `UpdateRequest` → `UpdateDataObjectRequest`
  - `DeleteRequest` → `DeleteDataObjectRequest`
  - `BulkRequest` → `BulkDataObjectRequest`
- **Pattern**: Uses CompletableFuture with `.whenComplete()` for async handling; error unwrapping via `SdkClientUtils.unwrapAndConvertToException()`.
- **Benefits**: Consistent with OpenSearch SDK patterns, better async composition, improved error handling.
- **Testing**: All 18 test cases updated with proper SDK client mocking and CompletableFuture handling.

## Security & Tenancy

- Access control (`MemoryContainerHelper.checkMemoryContainerAccess`):
  - Allows: no‑security, admin role (`all_access`), owner match, or backend role intersection.
- Multi‑tenancy: tenant validated on container ops; memory ops rely on container’s index (no per‑doc tenant field).
- Owner is stored on container; memory docs carry `user_id`/`agent_id`/`session_id`.

## Validation & Limits

- Container name required.
- `MemoryStorageConfig`:
  - `semanticStorageEnabled` derived from embedding config (not user‑set).
  - Dense requires `dimension`; sparse forbids `dimension`.
  - `maxInferSize` ≤ 10; only meaningful when `llm_model_id` exists.
- Models (create container):
  - `llm_model_id` (if present) must be `REMOTE`.
  - `embedding_model_id` must exist and be of expected type or `REMOTE`.
- Add Memories:
  - `messages` required; when `infer=false`, `role` required per message; `infer=true` requires `llm_model_id` in container.

## Query Construction

- Centralized in `MemorySearchQueryBuilder`:
  - `buildQueryByStorageType(query, config)` → `neural`/`neural_sparse`/`match` JSON.
  - `buildFactSearchQuery(fact, sessionId, config)` → bool filter on `session_id` and `memory_type=FACT` + appropriate query clause.

## Error Handling & Logging

- Fail‑fast for LLM failures when `infer=true` (prevents partial/inconsistent saves).
- Clear errors for model/type/dimension mismatches and permission denials.
- Robust parsing for LLM responses (supports `content[]`, code fences, etc.).
- Logging tuned to debug for verbose/sensitive details.

## Notable Design Decisions

- Container and memory IDs use OpenSearch document `_id` (not duplicated in `_source`).
- `semantic_storage_enabled` is derived; users don’t set it explicitly.
- LLM decoupled from semantic storage (can use either independently).
- Removed memory auto‑cleanup and `memory_characteristic`; all entries persist until explicit deletion.
- KNN engine is Lucene for compatibility.

## Key Classes (selected)

- REST: `RestMLCreateMemoryContainerAction`, `RestMLGetMemoryContainerAction`, `RestMLDeleteMemoryContainerAction`, `RestMLCreateEventAction`, `RestMLSearchMemoriesAction`, `RestMLGetMemoryAction`, `RestMLUpdateMemoryAction`, `RestMLDeleteMemoryAction`
- Transport: `TransportCreateMemoryContainerAction`, `TransportGetMemoryContainerAction`, `TransportDeleteMemoryContainerAction`, `TransportCreateEventAction`, `TransportSearchMemoriesAction`, `TransportGetMemoryAction`, `TransportUpdateMemoryAction`, `TransportDeleteMemoryAction`
- Models: `MLMemoryContainer`, `MemoryStorageConfig`, `MLMemory`, `MemoryType`, `MemoryDecision`, `MemoryDecisionRequest`
- Transport I/O: `MLCreateMemoryContainer*`, `MLSearchMemories*`, `MLCreateEvent*`, `MLGetMemory*`, `MLUpdateMemory*`, `MLDeleteMemory*`
- Helpers: `MemoryContainerHelper`, `MemoryEmbeddingHelper`, `MemorySearchQueryBuilder`

## Prompts & LLM Behavior

- Fact extraction (system prompt: Personal Information Organizer): returns `{ "facts": [ ... ] }`.
- Memory decisions prompt (`DEFAULT_UPDATE_MEMORY_PROMPT`): returns `{ "memory_decision": [ { id, text, event, old_memory? } ] }` covering existing IDs (NONE/UPDATE/DELETE) and new facts (ADD).

## References

- Working docs: `CLAUDE.md`, `plan.md`, `MLCommonsDeveloperReferenceClaude.md`, `AgenticMemoryLowLevelSummary.md`, `AgenticMemoryFeatureSummary.md`.
- Feature commits (see `CLAUDE.md` → “Feature Commits”): core scaffolding → CRUD → LLM integration → search → helpers refactor → error handling → response shaping → final fixes.
