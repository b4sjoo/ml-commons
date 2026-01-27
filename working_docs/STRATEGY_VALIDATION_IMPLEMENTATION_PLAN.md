# Strategy Validation Implementation Plan

**STATUS: ✅ COMPLETED**

Implementation completed successfully. All 13 tasks finished, all tests passing.

**Test Coverage Achieved:**
- **MemoryConfigurationTests**: 72 unit tests
- **Methods Covered**: 14 methods in MemoryConfiguration class
  - validateStrategiesRequireModels (7 tests)
  - update (8 tests)
  - extractEmbeddingConfigFromMapping (6 tests)
  - asMap (3 tests)
  - asList (3 tests)
  - extractModelIdFromPipeline (7 tests)
  - compareEmbeddingConfig (6 tests)
  - getIndexName (7 tests)
  - validate (7 tests)
  - getSessionIndexName (3 tests)
  - getWorkingMemoryIndexName (4 tests)
  - getLongMemoryIndexName (3 tests)
  - getLongMemoryHistoryIndexName (3 tests)
  - getMemoryIndexMapping (5 tests)
- **Test Success Rate**: 100% (72/72 passing)

## Problem Statement

Users can create partially-configured memory containers that pass validation but fail at runtime:

1. **Strategies without LLM**: Container creates only 2 indices, strategies configured but unusable (no fact extraction)
2. **Strategies without embedding**: Runtime crash when trying semantic search (no embedding field in index)
3. **Partial configs create wrong indices**: LLM-only or embedding-only with strategies creates 2 indices instead of 4
4. **No upgrade path**: Cannot add strategies later if LLM/embedding not configured
5. **Silent failures**: Background processing fails without user notification

## Solution Design

### Core Principle
**"Strategies require complete AI infrastructure (LLM + Embedding)"**

- **No strategies** → Allow partial configs → Create 2 indices (session + working)
- **Has strategies** → Require BOTH llm_id AND embedding model → Create 4 indices

### Validation Rules

#### Rule 1: Strategy Creation Requirements
```
IF strategies.isNotEmpty() THEN
    REQUIRE llm_id != null
    REQUIRE embedding_model_id != null
    REQUIRE embedding_model_type != null
    REQUIRE dimension (if TEXT_EMBEDDING)
```

#### Rule 2: Strategy Update Requirements
```
IF adding/updating strategies THEN
    merged_config = current + update
    APPLY Rule 1 to merged_config
```

#### Rule 3: Embedding Immutability
```
IF current.embedding_model_id != null THEN
    IF update changes embedding config THEN
        REJECT (cannot change embedding once set)
    ELSE IF update re-specifies same values THEN
        ALLOW (idempotent update)
```

---

## Implementation Tasks

### Phase 1: Core Validation Logic (Tasks 1-3)

#### Task 1: Add validateStrategiesRequireModels() helper
**File**: `MemoryConfiguration.java`
**Location**: After line 425 (after validateDimensionRequirements)
**Code**:
```java
/**
 * Validates that strategies have required AI models.
 * Strategies require both LLM (for fact extraction) and embedding model (for semantic search).
 *
 * @param config The memory configuration to validate
 * @throws IllegalArgumentException if strategies exist without required models
 */
public static void validateStrategiesRequireModels(MemoryConfiguration config) {
    if (config.getStrategies() != null && !config.getStrategies().isEmpty()) {
        boolean hasLlm = config.getLlmId() != null;
        boolean hasEmbedding = config.getEmbeddingModelId() != null &&
                               config.getEmbeddingModelType() != null;

        if (!hasLlm || !hasEmbedding) {
            String missing = !hasLlm && !hasEmbedding ? "LLM model and embedding model" :
                            !hasLlm ? "LLM model (llm_id)" :
                            "embedding model (embedding_model_id, embedding_model_type, dimension)";

            throw new IllegalArgumentException(
                String.format(
                    "Strategies require both an LLM model and embedding model to be configured. Missing: %s. " +
                    "Strategies use LLM for fact extraction and embedding model for semantic search.",
                    missing
                )
            );
        }
    }
}
```

**Why static**: Can be called from both creation and update flows without instance.

#### Task 2: Update container creation validation
**File**: `TransportCreateMemoryContainerAction.java`
**Location**: After line 112 (after validateConfiguration call)
**Code**:
```java
// Validate strategies require complete AI infrastructure
try {
    MemoryConfiguration.validateStrategiesRequireModels(input.getConfiguration());
} catch (IllegalArgumentException e) {
    listener.onFailure(e);
    return;
}
```

#### Task 3: Simplify index creation decision logic
**File**: `TransportCreateMemoryContainerAction.java`
**Location**: Line 179
**Change**:
```java
// BEFORE:
if (configuration.getLlmId() == null || configuration.getStrategies().isEmpty()) {

// AFTER:
if (configuration.getStrategies().isEmpty()) {
```

**Rationale**:
- Validation ensures strategies → LLM + embedding exist
- Only need to check strategies, not LLM
- Simpler, clearer logic

---

### Phase 2: Update Flow Logic (Tasks 4-6)

#### Task 4: Add validateEmbeddingUpdate() method
**File**: `TransportUpdateMemoryContainerAction.java`
**Location**: Add new method after existing update logic (around line 165)
**Code**:
```java
/**
 * Validates embedding configuration updates.
 * Prevents changing existing embedding config (would require index recreation).
 * Allows re-specifying same values (idempotent updates).
 */
private void validateEmbeddingUpdate(
    MemoryConfiguration currentConfig,
    MemoryConfiguration updateConfig,
    ActionListener<Void> listener
) {
    boolean hasExistingEmbedding = currentConfig.getEmbeddingModelId() != null;
    boolean isUpdatingEmbedding = updateConfig.getEmbeddingModelId() != null ||
                                  updateConfig.getEmbeddingModelType() != null ||
                                  updateConfig.getDimension() != null;

    if (hasExistingEmbedding && isUpdatingEmbedding) {
        // Check if actually changing (vs re-specifying same values)
        boolean isChangingModelId = updateConfig.getEmbeddingModelId() != null &&
            !updateConfig.getEmbeddingModelId().equals(currentConfig.getEmbeddingModelId());
        boolean isChangingType = updateConfig.getEmbeddingModelType() != null &&
            updateConfig.getEmbeddingModelType() != currentConfig.getEmbeddingModelType();
        boolean isChangingDimension = updateConfig.getDimension() != null &&
            !updateConfig.getDimension().equals(currentConfig.getDimension());

        if (isChangingModelId || isChangingType || isChangingDimension) {
            listener.onFailure(new IllegalArgumentException(
                "Embedding configuration (embedding_model_id, embedding_model_type, dimension) " +
                "cannot be changed once set. This would require recreating the long-term memory index " +
                "with new embedding field mappings. Please create a new memory container with the desired " +
                "embedding configuration."
            ));
            return;
        }
    }

    listener.onResponse(null);
}
```

#### Task 5: Add strategy validation to update flow
**File**: `TransportUpdateMemoryContainerAction.java`
**Location**: In update logic, before applying update (around line 130-160)
**Pseudocode**:
```java
// After merging strategies but before updating
if (updateConfiguration != null) {
    try {
        MemoryConfiguration currentConfig = container.getConfiguration();

        // Merge strategies
        final MemoryConfiguration finalUpdateConfig;
        if (updateConfiguration.getStrategies() != null && !updateConfiguration.getStrategies().isEmpty()) {
            List<MemoryStrategy> mergedStrategies = StrategyMergeHelper
                .mergeStrategies(currentConfig.getStrategies(), updateConfiguration.getStrategies());
            finalUpdateConfig = MemoryConfiguration.builder()
                .llmId(updateConfiguration.getLlmId())
                .maxInferSize(updateConfiguration.getMaxInferSize())
                .embeddingModelId(updateConfiguration.getEmbeddingModelId())
                .embeddingModelType(updateConfiguration.getEmbeddingModelType())
                .dimension(updateConfiguration.getDimension())
                .strategies(mergedStrategies)
                .build();
        } else {
            finalUpdateConfig = updateConfiguration;
        }

        // Build merged config for validation
        MemoryConfiguration mergedConfig = MemoryConfiguration.builder()
            .llmId(finalUpdateConfig.getLlmId() != null ? finalUpdateConfig.getLlmId() : currentConfig.getLlmId())
            .embeddingModelId(finalUpdateConfig.getEmbeddingModelId() != null ?
                            finalUpdateConfig.getEmbeddingModelId() : currentConfig.getEmbeddingModelId())
            .embeddingModelType(finalUpdateConfig.getEmbeddingModelType() != null ?
                              finalUpdateConfig.getEmbeddingModelType() : currentConfig.getEmbeddingModelType())
            .dimension(finalUpdateConfig.getDimension() != null ?
                      finalUpdateConfig.getDimension() : currentConfig.getDimension())
            .strategies(finalUpdateConfig.getStrategies() != null && !finalUpdateConfig.getStrategies().isEmpty() ?
                       finalUpdateConfig.getStrategies() : currentConfig.getStrategies())
            .build();

        // Validate strategies require models
        MemoryConfiguration.validateStrategiesRequireModels(mergedConfig);

        // Validate embedding update rules
        validateEmbeddingUpdate(currentConfig, finalUpdateConfig, ActionListener.wrap(
            success -> {
                // Proceed with update
                currentConfig.update(finalUpdateConfig);
                updateFields.put(MEMORY_STORAGE_CONFIG_FIELD, currentConfig);
                log.info("Updated configuration for container {}", memoryContainerId);
            },
            actionListener::onFailure
        ));

    } catch (IllegalArgumentException e) {
        log.error("Configuration validation failed: {}", e.getMessage());
        actionListener.onFailure(e);
        return;
    } catch (Exception e) {
        log.error("Failed to update configuration for container {}", memoryContainerId, e);
        actionListener.onFailure(e);
        return;
    }
}
```

#### Task 6: Extend MemoryConfiguration.update()
**File**: `MemoryConfiguration.java`
**Location**: Existing update() method (around line 432-442)
**Change**:
```java
public void update(MemoryConfiguration updateContent) {
    if (updateContent.getLlmId() != null) {
        this.llmId = updateContent.getLlmId();
    }
    if (updateContent.getStrategies() != null && !updateContent.getStrategies().isEmpty()) {
        this.strategies = updateContent.getStrategies();
    }
    if (updateContent.getMaxInferSize() != null) {
        this.maxInferSize = updateContent.getMaxInferSize();
    }

    // ADD: Support embedding field updates
    if (updateContent.getEmbeddingModelId() != null) {
        this.embeddingModelId = updateContent.getEmbeddingModelId();
    }
    if (updateContent.getEmbeddingModelType() != null) {
        this.embeddingModelType = updateContent.getEmbeddingModelType();
    }
    if (updateContent.getDimension() != null) {
        this.dimension = updateContent.getDimension();
    }

    // Note: Validation in TransportUpdateMemoryContainerAction prevents
    // changing existing embedding config once set
}
```

---

### Phase 3: Testing (Tasks 7-11)

#### Task 7: Update existing TransportUpdateMemoryContainerActionTests
**File**: `TransportUpdateMemoryContainerActionTests.java`
**Changes needed**:
- Update tests that add strategies to also include embedding model
- Add mocks for embedding validation

#### Task 8: Add test for strategy creation requiring models
**File**: New or existing creation test file
**Test cases**:
```java
// Test 1: Strategies without LLM - should fail
@Test
public void testCreateContainer_StrategiesWithoutLLM_Fails() {
    MemoryConfiguration config = MemoryConfiguration.builder()
        .embeddingModelId("emb-123")
        .embeddingModelType(FunctionName.TEXT_EMBEDDING)
        .dimension(768)
        .strategies(Arrays.asList(createStrategy()))
        .build(); // Missing llmId

    Exception e = assertThrows(IllegalArgumentException.class, () -> {
        MemoryConfiguration.validateStrategiesRequireModels(config);
    });
    assertTrue(e.getMessage().contains("LLM model"));
}

// Test 2: Strategies without embedding - should fail
@Test
public void testCreateContainer_StrategiesWithoutEmbedding_Fails() {
    MemoryConfiguration config = MemoryConfiguration.builder()
        .llmId("llm-123")
        .strategies(Arrays.asList(createStrategy()))
        .build(); // Missing embedding

    Exception e = assertThrows(IllegalArgumentException.class, () -> {
        MemoryConfiguration.validateStrategiesRequireModels(config);
    });
    assertTrue(e.getMessage().contains("embedding model"));
}

// Test 3: Strategies with both models - should pass
@Test
public void testCreateContainer_StrategiesWithBothModels_Success() {
    MemoryConfiguration config = MemoryConfiguration.builder()
        .llmId("llm-123")
        .embeddingModelId("emb-123")
        .embeddingModelType(FunctionName.TEXT_EMBEDDING)
        .dimension(768)
        .strategies(Arrays.asList(createStrategy()))
        .build();

    // Should not throw
    MemoryConfiguration.validateStrategiesRequireModels(config);
}

// Test 4: No strategies with partial config - should pass
@Test
public void testCreateContainer_NoStrategiesPartialConfig_Success() {
    MemoryConfiguration config = MemoryConfiguration.builder()
        .llmId("llm-123")
        .build(); // No embedding, no strategies - OK

    // Should not throw
    MemoryConfiguration.validateStrategiesRequireModels(config);
}
```

#### Task 9: Add test for strategy update validation
**Test cases**:
```java
// Test 1: Add strategies without embedding already configured - should fail
@Test
public void testUpdateContainer_AddStrategiesWithoutEmbedding_Fails() {
    // Current: llmId only
    // Update: add strategies
    // Expected: Fail - need embedding
}

// Test 2: Add embedding first, then strategies - should pass
@Test
public void testUpdateContainer_AddEmbeddingThenStrategies_Success() {
    // Update 1: Add embedding
    // Update 2: Add strategies
    // Expected: Both succeed
}
```

#### Task 10: Add test for embedding update rejection
**Test cases**:
```java
// Test 1: Change embedding model ID - should fail
@Test
public void testUpdateContainer_ChangeEmbeddingModelId_Fails() {
    // Current: embedding_model_id = "emb-123"
    // Update: embedding_model_id = "emb-456"
    // Expected: Fail - cannot change
}

// Test 2: Change embedding type - should fail
@Test
public void testUpdateContainer_ChangeEmbeddingType_Fails() {
    // Current: TEXT_EMBEDDING
    // Update: SPARSE_ENCODING
    // Expected: Fail - cannot change
}

// Test 3: Change dimension - should fail
@Test
public void testUpdateContainer_ChangeDimension_Fails() {
    // Current: dimension = 768
    // Update: dimension = 1024
    // Expected: Fail - cannot change
}
```

#### Task 11: Add test for allowing same embedding values
**Test cases**:
```java
// Test: Re-specify same embedding values - should pass
@Test
public void testUpdateContainer_RespectifySameEmbedding_Success() {
    // Current: embedding_model_id="emb-123", type=TEXT_EMBEDDING, dim=768
    // Update: embedding_model_id="emb-123", type=TEXT_EMBEDDING, dim=768
    // Expected: Success (idempotent)
}
```

#### Task 12: Run all tests and fix breaking changes
**Commands**:
```bash
# Run specific test classes
./gradlew :opensearch-ml-common:test --tests MemoryConfigurationTests
./gradlew :opensearch-ml-plugin:test --tests TransportCreateMemoryContainerActionTests
./gradlew :opensearch-ml-plugin:test --tests TransportUpdateMemoryContainerActionTests

# Run full test suite
./gradlew test

# Check coverage
./gradlew jacocoTestCoverageVerification
```

**Expected breaking tests**:
- Tests that create containers with strategies but no LLM/embedding
- Tests that add strategies without checking for models
- Need to update these tests to include required models

---

### Phase 4: Documentation (Task 13)

#### Task 13: Update documentation
**Files to update**:
1. `AGENTIC_MEMORY.md` - Add validation rules section
2. `AgenticMemoryFeatureSummary.md` - Document strategy requirements
3. API documentation - Update create/update examples

**Content to add**:
```markdown
## Strategy Configuration Requirements

Strategies require complete AI infrastructure to function:

### Required Components
- **LLM Model** (`llm_id`): For fact extraction from conversations
- **Embedding Model** (`embedding_model_id`, `embedding_model_type`): For semantic search
- **Dimension** (if TEXT_EMBEDDING): Vector dimension for KNN search

### Validation Rules

#### At Creation
If you specify strategies, you MUST also specify:
- `llm_id`
- `embedding_model_id`
- `embedding_model_type`
- `dimension` (if `embedding_model_type` is TEXT_EMBEDDING)

#### At Update
- You can add strategies IF the container already has LLM + embedding configured
- You can add embedding model BEFORE adding strategies
- You CANNOT change embedding configuration once set (requires new container)

### Examples

**✅ Valid Creation - All Components**
```json
{
  "name": "my-container",
  "configuration": {
    "llm_id": "llm-model-123",
    "embedding_model_id": "embedding-model-456",
    "embedding_model_type": "TEXT_EMBEDDING",
    "dimension": 768,
    "strategies": [{
      "type": "semantic",
      "namespace": ["user_id"]
    }]
  }
}
```

**✅ Valid Creation - Gradual Configuration**
```json
// Step 1: Create without strategies
{
  "name": "my-container",
  "configuration": {
    "llm_id": "llm-model-123",
    "embedding_model_id": "embedding-model-456",
    "embedding_model_type": "TEXT_EMBEDDING",
    "dimension": 768
  }
}

// Step 2: Add strategies later
PUT /_plugins/_ml/memory_containers/{id}
{
  "configuration": {
    "strategies": [{
      "type": "semantic",
      "namespace": ["user_id"]
    }]
  }
}
```

**❌ Invalid - Strategies without LLM**
```json
{
  "configuration": {
    "embedding_model_id": "embedding-model-456",
    "embedding_model_type": "TEXT_EMBEDDING",
    "dimension": 768,
    "strategies": [...]  // ❌ ERROR: Need llm_id
  }
}
```

**❌ Invalid - Strategies without Embedding**
```json
{
  "configuration": {
    "llm_id": "llm-model-123",
    "strategies": [...]  // ❌ ERROR: Need embedding model
  }
}
```

**❌ Invalid - Changing Embedding**
```json
// Current: embedding_model_id = "emb-123"
PUT /_plugins/_ml/memory_containers/{id}
{
  "configuration": {
    "embedding_model_id": "emb-456"  // ❌ ERROR: Cannot change
  }
}
```
```

---

## Validation Flow Diagrams

### Container Creation
```
User Request
    ↓
Parse Configuration
    ↓
[Constructor Validation]
    ├─ Embedding pairing check
    ├─ Dimension requirements
    └─ Max infer size
    ↓
[Strategy Validation] ← NEW
    └─ If strategies exist
        ├─ Check llm_id != null
        └─ Check embedding_model_id + type != null
    ↓
[Runtime Model Validation]
    ├─ LLM model exists (if specified)
    └─ Embedding model exists and type matches (if specified)
    ↓
[Index Creation Decision]
    └─ strategies.isEmpty() ? 2 indices : 4 indices
    ↓
SUCCESS
```

### Container Update
```
User Update Request
    ↓
Parse Update Configuration
    ↓
[Constructor Validation] (on update config)
    ↓
Merge with Current Config
    ↓
[Strategy Validation] ← NEW
    └─ Check merged config strategies requirements
    ↓
[Embedding Update Validation] ← NEW
    └─ If existing embedding != null
        └─ If trying to change → REJECT
    ↓
[Apply Update]
    └─ currentConfig.update(updateConfig)
    ↓
SUCCESS
```

---

## Test Coverage Matrix

| Scenario | Creation | Update | Expected Result |
|----------|----------|--------|-----------------|
| No strategies, no models | ✅ | - | 2 indices |
| No strategies, only LLM | ✅ | - | 2 indices |
| No strategies, only embedding | ✅ | - | 2 indices |
| No strategies, both models | ✅ | - | 2 indices (can add strategies later) |
| Strategies + both models | ✅ | - | 4 indices |
| Strategies + only LLM | 🔴 | 🔴 | Error: need embedding |
| Strategies + only embedding | 🔴 | 🔴 | Error: need LLM |
| Strategies + no models | 🔴 | 🔴 | Error: need both |
| Add strategies (has models) | - | ✅ | Success |
| Add strategies (missing models) | - | 🔴 | Error |
| Change embedding | - | 🔴 | Error: immutable |
| Re-specify same embedding | - | ✅ | Success |

### Achieved Test Coverage

| Class | Test File | Test Count | Coverage Status |
|-------|-----------|------------|-----------------|
| MemoryConfiguration | MemoryConfigurationTests.java | 72 tests | ✅ All passing |
| TransportCreateMemoryContainerAction | TransportCreateMemoryContainerActionTests.java | Enhanced | ✅ All passing |
| TransportUpdateMemoryContainerAction | TransportUpdateMemoryContainerActionTests.java | Enhanced | ✅ All passing |
| MemoryContainerModelValidator | (covered in integration tests) | N/A | ✅ Validated |
| MemoryContainerSharedIndexValidator | (covered in integration tests) | N/A | ✅ Validated |
| MemoryContainerPipelineHelper | MemoryContainerPipelineHelperTests.java | Added | ✅ All passing |

**Key Achievement**: MemoryConfiguration has comprehensive test coverage with 72 unit tests covering all public methods and critical validation logic paths.

---

## Implementation Order

### Day 1: Core Logic
1. Task 1: Add validateStrategiesRequireModels()
2. Task 2: Update creation validation
3. Task 3: Simplify index decision logic
4. Test locally with manual API calls

### Day 2: Update Flow
5. Task 4: Add validateEmbeddingUpdate()
6. Task 5: Add update strategy validation
7. Task 6: Extend MemoryConfiguration.update()
8. Test locally with update scenarios

### Day 3: Testing
9. Task 7: Update existing tests
10. Task 8-11: Add new test cases
11. Task 12: Run full test suite and fix issues

### Day 4: Documentation & Polish
12. Task 13: Update documentation
13. Code review and refinement
14. Final integration testing

---

## Success Criteria

✅ All validation tests pass
✅ No partially-configured containers can be created with strategies
✅ Clear error messages guide users to correct configuration
✅ Embedding configuration cannot be changed once set
✅ Gradual configuration path works (add embedding → add strategies)
✅ Existing tests updated and passing
✅ Test coverage ≥ 70%
✅ Documentation complete and accurate

---

## Risks & Mitigation

### Risk 1: Breaking Existing Containers
**Mitigation**: Validation only applies to NEW operations, existing containers unaffected

### Risk 2: Users with Partial Configs
**Mitigation**: Clear error messages explain what's needed and why

### Risk 3: Test Coverage Gaps
**Mitigation**: Comprehensive test matrix covers all scenarios

### Risk 4: Performance Impact
**Mitigation**: Validation is lightweight (simple null checks), negligible impact
