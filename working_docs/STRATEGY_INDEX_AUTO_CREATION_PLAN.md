# Strategy Index Auto-Creation Plan

## Problem Statement

When updating a memory container from "no strategies" to "has strategies", the long-term memory index and history index are not automatically created. This prevents users from immediately using the newly added strategies, resulting in runtime errors when trying to add memories.

**Current Behavior:**
```
User: Create container (no strategies) → Success ✓
User: Update container (add strategy) → Success ✓
User: Add memory with strategy → ERROR: Index not found ❌
```

**Expected Behavior:**
```
User: Create container (no strategies) → Success ✓
User: Update container (add strategy) → Success ✓ (auto-creates indices)
User: Add memory with strategy → Success ✓
```

---

## Solution Overview

Detect the "no strategies → has strategies" transition during container update and automatically create required indices (long-term memory + history) with the same validation and creation logic used during container creation.

---

## High-Level Data Flow

### Simplified Flow Diagram

```
UPDATE REQUEST
    ↓
Permission Check
    ↓
Merge Strategies (old + new)
    ↓
Basic Validation
    ↓
Detect Transition (No strategies → Has strategies?)
    ↓
┌───NO────────────────────────────┐
│                                 │
│  Skip index creation            │
│  Update metadata directly       │
│                                 │
└─────────────────────────────────┘
    ↓
┌───YES───────────────────────────┐
│                                 │
│  Validate LLM Model             │
│    ↓                            │
│  Validate Embedding Model       │
│    ↓                            │
│  Check Long-term Index Exists?  │
│    ↓                            │
│  ┌─YES────────┐  ┌─NO─────────┐│
│  │            │  │            ││
│  │ Validate   │  │ Create     ││
│  │ Compat.    │  │ Pipeline   ││
│  │            │  │ Create     ││
│  │ Create     │  │ LT Index   ││
│  │ History    │  │ Create     ││
│  │ Only       │  │ History    ││
│  └────────────┘  └────────────┘│
│                                 │
└─────────────────────────────────┘
    ↓
Update Container Metadata
    ↓
SUCCESS
```

---

## Detailed Validation Chain

### Scenario 1: No Existing Shared Index (Fresh Creation)

**Steps:**
1. **Detect transition**: Container had no strategies, now has strategies
2. **Validate models**:
   - LLM model exists and is REMOTE type
   - Embedding model exists and type matches (TEXT_EMBEDDING or SPARSE_ENCODING)
3. **Check index existence**: Long-term memory index does not exist
4. **Create infrastructure**:
   ```
   Create Ingest Pipeline
     └─ Name: {indexName}-embedding
     └─ Processor: text_embedding or sparse_encoding
     └─ Model ID: from configuration
     └─ Field mapping: memory → memory_embedding

   Create Long-term Index
     └─ Name: ml-memory-{prefix}-long-term
     └─ Mapping: memory_embedding field (knn_vector or rank_features)
     └─ Settings: KNN enabled (if TEXT_EMBEDDING), pipeline attached

   Create History Index (if disableHistory = false)
     └─ Name: ml-memory-{prefix}-history
     └─ Tracks all memory changes over time
   ```
5. **Update metadata** and return success

### Scenario 2: Shared Index Already Exists (Multi-Container)

**Steps:**
1. **Detect transition**: Same as above
2. **Validate models**: Same as above
3. **Check index existence**: Long-term memory index EXISTS (shared with other containers)
4. **Validate compatibility**:
   ```
   Extract from existing index:
     - Pipeline model_id
     - Embedding type (from processor name)
     - Dimension (from mapping)

   Compare with request:
     - Model ID must match
     - Embedding type must match
     - Dimension must match

   Result: PASS or FAIL
   ```
5. **Create only missing indices**:
   - Skip: Pipeline (already exists)
   - Skip: Long-term index (already exists)
   - Create: History index only (if not disabled)
6. **Update metadata** and return success

### Scenario 3: Incompatible Shared Index (Error)

**Steps:**
1. **Detect transition**: Same as above
2. **Validate models**: Same as above
3. **Check index existence**: Index exists
4. **Validate compatibility**: FAIL (different model_id, type, or dimension)
5. **Error response**:
   ```
   Cannot share long-term index - embedding configuration mismatch.

   Existing index configuration:
   - Model: model-B
   - Type: TEXT_EMBEDDING
   - Dimension: 768

   Requested configuration:
   - Model: model-A
   - Type: TEXT_EMBEDDING
   - Dimension: 1024

   Solution: Use a different index_prefix to create a separate index.
   ```

---

## Key Design Principles

### 1. **Automatic Detection**
The system automatically detects the transition by comparing:
- `currentConfig.getStrategies()` (before update)
- `mergedConfig.getStrategies()` (after update)

### 2. **Validation Reuse**
Reuse the same validation logic from `TransportCreateMemoryContainerAction`:
- LLM model validation
- Embedding model validation
- Shared index compatibility validation

### 3. **Index Creation Reuse**
Reuse the same index creation methods from `TransportCreateMemoryContainerAction`:
- `createLongTermMemoryIngestPipeline()`
- `createTextEmbeddingPipeline()`
- `createPipelineInternal()`
- Plus use `MLIndicesHandler` for actual index creation

### 4. **Shared Index Safety**
Multiple containers can share the same long-term index (via same `index_prefix`):
- First container: Creates everything
- Later containers: Validates compatibility, creates only history index
- Prevents conflicts: Blocks incompatible configurations

### 5. **History Flag Respect**
The original `disableHistory` flag cannot be changed during update:
- If originally `false`: Creates history index when adding strategies
- If originally `true`: Skips history index creation

### 6. **Error Handling**
All async operations properly chain errors to final listener with clear error messages.

---

## Components Modified

### File: TransportUpdateMemoryContainerAction.java

**Dependencies Added:**
- `MLIndicesHandler` (for index creation)
- `GetMappingsRequest/Response` (for index validation)
- `GetPipelineRequest/Response` (for pipeline validation)
- `PutPipelineRequest` (for pipeline creation)
- Various utilities (XContentFactory, etc.)

**Methods Added:**
1. `validateAndCreateIndices()` - Entry point for validation chain
2. `validateEmbeddingModelAndCreateIndices()` - Embedding validation
3. `validateSharedIndexAndCreateIndices()` - Shared index check
4. `validateExistingIndexAndCreateHistory()` - Compatibility validation
5. `createHistoryIndexOnly()` - Creates only history index
6. `createLongTermAndHistoryIndices()` - Creates both indices
7. `createLongTermMemoryIngestPipeline()` - Pipeline + index creation
8. `createTextEmbeddingPipeline()` - Pipeline existence check
9. `createPipelineInternal()` - Actual pipeline creation

**Main Flow Modified:**
- Added transition detection after strategy merge
- Added conditional index creation before metadata update

---

## Testing Strategy

### Unit Tests (TransportUpdateMemoryContainerActionTests)

**Test Cases:**
1. ✅ Update no-strategies → has-strategies (creates long-term + history)
2. ✅ Update no-strategies → has-strategies with disableHistory=true (creates long-term only)
3. ✅ Update with compatible shared index (validates, creates history only)
4. ✅ Update with incompatible shared index (fails validation)
5. ✅ Update with invalid LLM model (fails validation)
6. ✅ Update with invalid embedding model (fails validation)
7. ✅ Update has-strategies → more strategies (no index creation)
8. ✅ Update with missing embedding model in config (fails)
9. ✅ Update with missing LLM model in config (fails)

### Integration Tests (YAML REST tests)

**Test Scenarios:**
1. End-to-end: Create container → Update with strategy → Add memory (success)
2. Shared index: Create 2 containers with same prefix → Both add strategies
3. Error case: Create container → Update with invalid model → Verify error

---

## Migration Path

**No migration required.** This is a backward-compatible enhancement:
- Existing containers: No change in behavior
- New updates: Automatically benefit from index creation
- No breaking changes to APIs or data structures

---

## Performance Considerations

**Index Creation Time:**
- Pipeline creation: ~50ms
- Long-term index creation: ~100-200ms
- History index creation: ~100-200ms
- **Total added latency**: ~250-450ms per update (only when adding first strategy)

**Optimization:**
- Index creation is async (non-blocking)
- Shared index scenario is faster (only creates history index)
- Validation queries are cached by OpenSearch

---

## Error Recovery

**If index creation fails mid-way:**
1. Container metadata is NOT updated (atomic operation)
2. User receives error response
3. Partial indices (if created) are orphaned but harmless
4. User can retry the update operation

**Idempotent Operations:**
- Pipeline creation checks existence first
- Index creation is idempotent (creates if not exists)
- Safe to retry failed updates

---

## Security Considerations

**Access Control:**
- User must have permissions to update container (checked first)
- User must have access to LLM model (validated)
- User must have access to embedding model (validated)
- No privilege escalation risks

**Data Isolation:**
- Each container's indices are isolated by index prefix
- Shared indices enforce configuration compatibility
- No cross-container data leakage

---

## Monitoring & Logging

**Log Events:**
- INFO: Index creation started
- INFO: Index creation completed
- ERROR: Validation failures with details
- ERROR: Index creation failures with details

**Metrics:**
- Track update latency (with/without index creation)
- Track validation failure rates
- Track shared index reuse rate

---

## Future Enhancements

1. **Batch index creation**: Create pipeline + long-term + history in parallel
2. **Index template support**: Pre-define index templates for faster creation
3. **Async index creation**: Return immediately, create indices in background
4. **Index health check**: Verify indices are actually usable after creation

---

## Summary

This enhancement makes the agentic memory system more user-friendly by automatically handling index lifecycle management. Users no longer need to understand the underlying index architecture - they can simply add strategies to containers and immediately start using them.

**Key Benefits:**
- ✅ Automatic index creation
- ✅ Safe shared index handling
- ✅ Clear error messages
- ✅ Backward compatible
- ✅ Production ready
