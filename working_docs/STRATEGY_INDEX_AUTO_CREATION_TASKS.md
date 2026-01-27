# Strategy Index Auto-Creation - Implementation Tasks

## Overview
This document breaks down the implementation of automatic index creation when adding strategies to a memory container into trackable, testable tasks.

**Feature:** Auto-create long-term and history indices when updating a container from "no strategies" to "has strategies"

**File:** `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/TransportUpdateMemoryContainerAction.java`

---

## Task Breakdown

### ✅ PHASE 1: Setup and Dependencies (Estimated: 30 min)

#### Task 1.1: Add Required Imports
**Description:** Add all necessary import statements for index creation and validation

**Changes:**
- Add imports for: `MLIndicesHandler`, `GetMappingsRequest/Response`, `GetPipelineRequest/Response`, `PutPipelineRequest`, `MappingMetadata`, `XContentFactory`, `XContentType`, `BytesReference`, `XContentBuilder`, `IndexNotFoundException`, `FunctionName`, `IOException`

**Location:** After line 46 (existing imports section)

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:compileJava
# Should compile without errors
```

**Acceptance Criteria:**
- [x] All imports added
- [x] No compilation errors
- [x] No unused import warnings

---

#### Task 1.2: Add MLIndicesHandler Field
**Description:** Add MLIndicesHandler as a class field for index creation operations

**Changes:**
- Add field declaration: `final MLIndicesHandler mlIndicesHandler;`
- Add parameter to constructor
- Add field assignment in constructor body

**Location:**
- Field: After line 62
- Constructor parameter: In constructor signature (line 65)
- Assignment: In constructor body (after line 83)

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:compileJava
# Should compile without errors
```

**Acceptance Criteria:**
- [x] Field declared as final
- [x] Constructor parameter added
- [x] Field properly assigned
- [x] No compilation errors

---

### ✅ PHASE 2: Transition Detection (Estimated: 45 min)

#### Task 2.1: Implement Transition Detection Logic
**Description:** Detect when container transitions from "no strategies" to "has strategies"

**Changes:**
Replace lines 158-178 in `doExecute()` method with:
```java
// Update current configuration
currentConfig.update(finalUpdateConfig);
currentConfig.validate();
MemoryConfiguration.validateStrategiesRequireModels(currentConfig);

// Detect no-strategies → has-strategies transition
boolean hadNoStrategies = container.getConfiguration().getStrategies() == null
    || container.getConfiguration().getStrategies().isEmpty();
boolean nowHasStrategies = currentConfig.getStrategies() != null
    && !currentConfig.getStrategies().isEmpty();

if (hadNoStrategies && nowHasStrategies) {
    log.info("Detected strategy addition to container {}, creating required indices", memoryContainerId);
    validateAndCreateIndices(container, currentConfig, updateFields, memoryContainerId, actionListener);
} else {
    updateFields.put(MEMORY_STORAGE_CONFIG_FIELD, currentConfig);
    updateFields.put(LAST_UPDATED_TIME_FIELD, Instant.now().toEpochMilli());
    performUpdate(ML_MEMORY_CONTAINER_INDEX, memoryContainerId, updateFields, actionListener);
}
```

**Location:** Lines 158-178

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:compileJava
# Will fail - validateAndCreateIndices() not yet implemented (expected)
```

**Acceptance Criteria:**
- [x] Transition detection logic added
- [x] Both boolean conditions correct
- [x] Conditional branching implemented
- [x] Log message added
- [x] Code compiles (after stub method added in Task 3.1)

---

### ✅ PHASE 3: Validation Chain (Estimated: 2 hours)

#### Task 3.1: Implement validateAndCreateIndices() Method
**Description:** Entry point for validation chain - validates LLM model

**Changes:**
Add method after `performUpdate()`:
```java
private void validateAndCreateIndices(
    MLMemoryContainer container,
    MemoryConfiguration config,
    Map<String, Object> updateFields,
    String memoryContainerId,
    ActionListener<UpdateResponse> listener
) {
    // Validates LLM model if configured, then proceeds to embedding validation
    // See detailed implementation in plan
}
```

**Location:** After `performUpdate()` method (after line 249)

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:compileJava
# Should compile
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testValidateAndCreateIndices*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] LLM model validation logic implemented
- [x] ThreadContext properly managed
- [x] Error handling for model not found
- [x] Proceeds to next validation step on success
- [x] Unit test passes

**Dependencies:** Requires Task 3.2 stub

---

#### Task 3.2: Implement validateEmbeddingModelAndCreateIndices() Method
**Description:** Validates embedding model type matches configuration

**Changes:**
Add method after `validateAndCreateIndices()`:
```java
private void validateEmbeddingModelAndCreateIndices(
    MLMemoryContainer container,
    MemoryConfiguration config,
    Map<String, Object> updateFields,
    String memoryContainerId,
    ActionListener<UpdateResponse> listener
) {
    // Validates embedding model exists and type matches
    // See detailed implementation in plan
}
```

**Location:** After Task 3.1 method

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testEmbeddingModelValidation*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] Embedding model validation logic implemented
- [x] Type mismatch detection works
- [x] Error handling for model not found
- [x] Proceeds to next validation step on success
- [x] Unit test passes

**Dependencies:** Requires Task 3.3 stub

---

#### Task 3.3: Implement validateSharedIndexAndCreateIndices() Method
**Description:** Check if long-term index already exists (shared scenario)

**Changes:**
Add method after `validateEmbeddingModelAndCreateIndices()`:
```java
private void validateSharedIndexAndCreateIndices(
    MLMemoryContainer container,
    MemoryConfiguration config,
    Map<String, Object> updateFields,
    String memoryContainerId,
    ActionListener<UpdateResponse> listener
) {
    // Checks index existence via GetMappingsRequest
    // Routes to appropriate handler based on existence
    // See detailed implementation in plan
}
```

**Location:** After Task 3.2 method

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testSharedIndexDetection*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] GetMappingsRequest properly constructed
- [x] IndexNotFoundException handled correctly
- [x] Routes to validation on index exists
- [x] Routes to creation on index not exists
- [x] Unit test passes

**Dependencies:** Requires Task 3.4 and Task 4.2 stubs

---

#### Task 3.4: Implement validateExistingIndexAndCreateHistory() Method
**Description:** Validate existing index configuration matches request

**Changes:**
Add method after `validateSharedIndexAndCreateIndices()`:
```java
private void validateExistingIndexAndCreateHistory(
    MLMemoryContainer container,
    MemoryConfiguration config,
    Map<String, Object> updateFields,
    String memoryContainerId,
    String longTermIndexName,
    GetMappingsResponse mappingResponse,
    ActionListener<UpdateResponse> listener
) {
    // Extracts embedding config from mapping
    // Validates pipeline configuration
    // Compares configs using MemoryConfiguration.compareEmbeddingConfig()
    // See detailed implementation in plan
}
```

**Location:** After Task 3.3 method

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testCompatibilityValidation*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] Mapping extraction works correctly
- [x] Pipeline validation works correctly
- [x] Config comparison uses existing utility
- [x] Error messages are clear and actionable
- [x] Proceeds to history creation on success
- [x] Unit test passes for compatible config
- [x] Unit test passes for incompatible config (fails correctly)

**Dependencies:** Requires Task 4.1 stub

---

### ✅ PHASE 4: Index Creation (Estimated: 2 hours)

#### Task 4.1: Implement createHistoryIndexOnly() Method
**Description:** Create only history index (long-term already exists)

**Changes:**
Add method after `validateExistingIndexAndCreateHistory()`:
```java
private void createHistoryIndexOnly(
    MemoryConfiguration config,
    Map<String, Object> updateFields,
    String memoryContainerId,
    ActionListener<UpdateResponse> listener
) {
    // Creates history index if not disabled
    // Proceeds to performUpdate after creation
    // See detailed implementation in plan
}
```

**Location:** After Task 3.4 method

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testHistoryIndexCreation*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] Respects disableHistory flag
- [x] Uses MLIndicesHandler.createLongTermMemoryHistoryIndex()
- [x] Error handling for creation failure
- [x] Proceeds to performUpdate on success
- [x] Unit test passes with disableHistory=false
- [x] Unit test passes with disableHistory=true (skips creation)

---

#### Task 4.2: Implement createLongTermAndHistoryIndices() Method
**Description:** Create both long-term and history indices (fresh creation)

**Changes:**
Add method after `createHistoryIndexOnly()`:
```java
private void createLongTermAndHistoryIndices(
    MLMemoryContainer container,
    MemoryConfiguration config,
    Map<String, Object> updateFields,
    String memoryContainerId,
    ActionListener<UpdateResponse> listener
) {
    // Creates long-term index via pipeline creation
    // Creates history index if not disabled
    // See detailed implementation in plan
}
```

**Location:** After Task 4.1 method

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testFullIndexCreation*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] Calls createLongTermMemoryIngestPipeline()
- [x] Handles history index creation correctly
- [x] Proper error handling at each step
- [x] Proceeds to performUpdate on success
- [x] Unit test passes

**Dependencies:** Requires Task 4.3 stub

---

#### Task 4.3: Implement createLongTermMemoryIngestPipeline() Method
**Description:** Create ingest pipeline and long-term index

**Changes:**
Add method after `createLongTermAndHistoryIndices()`:
```java
private void createLongTermMemoryIngestPipeline(
    String indexName,
    MemoryConfiguration config,
    ActionListener<Boolean> listener
) {
    // Checks if embedding configured
    // Creates pipeline via createTextEmbeddingPipeline()
    // Creates index via MLIndicesHandler
    // See detailed implementation in plan
}
```

**Location:** After Task 4.2 method

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testPipelineCreation*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] Handles null embedding type correctly
- [x] Constructs correct pipeline name
- [x] Uses MLIndicesHandler for index creation
- [x] Error handling works
- [x] Unit test passes

**Dependencies:** Requires Task 4.4 stub

---

#### Task 4.4: Implement createTextEmbeddingPipeline() Method
**Description:** Create embedding pipeline (check existence first)

**Changes:**
Add method after `createLongTermMemoryIngestPipeline()`:
```java
private void createTextEmbeddingPipeline(
    String pipelineName,
    MemoryConfiguration config,
    ActionListener<Boolean> listener
) throws IOException {
    // Checks if pipeline already exists (shared scenario)
    // Skips creation if exists
    // Creates via createPipelineInternal() if not exists
    // See detailed implementation in plan
}
```

**Location:** After Task 4.3 method

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testPipelineExistenceCheck*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] GetPipelineRequest properly constructed
- [x] Skips creation when pipeline exists
- [x] Creates pipeline when not exists
- [x] Error handling for both paths
- [x] Unit test passes for existing pipeline
- [x] Unit test passes for new pipeline

**Dependencies:** Requires Task 4.5 stub

---

#### Task 4.5: Implement createPipelineInternal() Method
**Description:** Actually create the ingest pipeline

**Changes:**
Add method after `createTextEmbeddingPipeline()`:
```java
private void createPipelineInternal(
    String pipelineName,
    MemoryConfiguration config,
    ActionListener<Boolean> listener
) throws IOException {
    // Constructs pipeline JSON with XContentBuilder
    // Determines processor type (text_embedding vs sparse_encoding)
    // Executes PutPipelineRequest
    // See detailed implementation in plan
}
```

**Location:** After Task 4.4 method

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionTests.testPipelineCreationInternal*"
```

**Acceptance Criteria:**
- [x] Method signature correct
- [x] Processor type determined correctly
- [x] XContentBuilder constructs valid JSON
- [x] Field mapping is correct (memory → memory_embedding)
- [x] PutPipelineRequest properly constructed
- [x] Handles acknowledgment correctly
- [x] Error handling works
- [x] Unit test passes for TEXT_EMBEDDING
- [x] Unit test passes for SPARSE_ENCODING

---

### ✅ PHASE 5: Testing (Estimated: 3 hours)

#### Task 5.1: Create Test File Structure
**Description:** Set up test file with mocks and test fixtures

**Changes:**
Create new file: `plugin/src/test/java/org/opensearch/ml/action/memorycontainer/TransportUpdateMemoryContainerActionIndexCreationTests.java`

**Content:**
```java
@RunWith(MockitoJUnitRunner.class)
public class TransportUpdateMemoryContainerActionIndexCreationTests {
    @Mock private Client client;
    @Mock private MLIndicesHandler mlIndicesHandler;
    @Mock private MLModelManager mlModelManager;
    // ... other mocks

    private TransportUpdateMemoryContainerAction action;

    @Before
    public void setup() {
        // Initialize action with mocks
    }
}
```

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:compileTestJava
```

**Acceptance Criteria:**
- [x] Test file created
- [x] All necessary mocks declared
- [x] Setup method initializes action
- [x] Test compiles

---

#### Task 5.2: Write Transition Detection Tests
**Description:** Test that transition is correctly detected

**Test Cases:**
1. `testNoStrategies_ToHasStrategies_DetectsTransition()`
2. `testNoStrategies_ToNoStrategies_NoTransition()`
3. `testHasStrategies_ToMoreStrategies_NoTransition()`
4. `testNull_ToHasStrategies_DetectsTransition()`

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionIndexCreationTests.test*Transition*"
```

**Acceptance Criteria:**
- [x] All 4 test cases written
- [x] All tests pass
- [x] Code coverage >80% for transition logic

---

#### Task 5.3: Write Model Validation Tests
**Description:** Test LLM and embedding model validation

**Test Cases:**
1. `testValidLlmModel_Passes()`
2. `testInvalidLlmModel_Fails()`
3. `testNonRemoteLlmModel_Fails()`
4. `testValidEmbeddingModel_Passes()`
5. `testInvalidEmbeddingModel_Fails()`
6. `testEmbeddingTypeMismatch_Fails()`

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionIndexCreationTests.test*Model*"
```

**Acceptance Criteria:**
- [x] All 6 test cases written
- [x] All tests pass
- [x] Mock interactions verified
- [x] Error messages validated

---

#### Task 5.4: Write Shared Index Validation Tests
**Description:** Test shared index compatibility validation

**Test Cases:**
1. `testSharedIndex_Compatible_CreatesHistoryOnly()`
2. `testSharedIndex_IncompatibleModel_Fails()`
3. `testSharedIndex_IncompatibleDimension_Fails()`
4. `testSharedIndex_IncompatibleType_Fails()`
5. `testSharedIndex_MissingPipeline_Fails()`

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionIndexCreationTests.testSharedIndex*"
```

**Acceptance Criteria:**
- [x] All 5 test cases written
- [x] All tests pass
- [x] Compatibility logic validated
- [x] Error messages are clear

---

#### Task 5.5: Write Index Creation Tests
**Description:** Test index creation paths

**Test Cases:**
1. `testNoExistingIndex_CreatesBoth()`
2. `testDisableHistory_SkipsHistoryIndex()`
3. `testPipelineAlreadyExists_SkipsCreation()`
4. `testIndexCreationFailure_PropagatesError()`
5. `testHistoryCreationFailure_PropagatesError()`

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionIndexCreationTests.test*Creation*"
```

**Acceptance Criteria:**
- [x] All 5 test cases written
- [x] All tests pass
- [x] MLIndicesHandler interactions verified
- [x] Error propagation validated

---

#### Task 5.6: Write Integration Tests
**Description:** End-to-end test with all components

**Test Cases:**
1. `testEndToEnd_NoStrategiesToStrategies_CreatesIndices()`
2. `testEndToEnd_SharedIndex_ValidatesAndCreatesHistory()`
3. `testEndToEnd_InvalidConfig_FailsGracefully()`

**Verification:**
```bash
./gradlew :opensearch-ml-plugin:test --tests "*TransportUpdateMemoryContainerActionIndexCreationTests.testEndToEnd*"
```

**Acceptance Criteria:**
- [x] All 3 test cases written
- [x] All tests pass
- [x] Full workflow validated
- [x] Code coverage >80% overall

---

### ✅ PHASE 6: Final Verification (Estimated: 1 hour)

#### Task 6.1: Code Quality Checks
**Description:** Run static analysis and formatting

**Commands:**
```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew :opensearch-ml-plugin:checkstyleMain
```

**Acceptance Criteria:**
- [x] Code formatted correctly
- [x] No checkstyle violations
- [x] No spotbugs warnings

---

#### Task 6.2: Full Test Suite
**Description:** Run all tests to ensure no regression

**Commands:**
```bash
./gradlew :opensearch-ml-plugin:test
./gradlew :opensearch-ml-plugin:jacocoTestCoverageVerification
```

**Acceptance Criteria:**
- [x] All existing tests pass
- [x] All new tests pass
- [x] Code coverage >80%
- [x] No test failures

---

#### Task 6.3: Build Verification
**Description:** Full clean build

**Commands:**
```bash
./gradlew clean build -x integTest
```

**Acceptance Criteria:**
- [x] Build succeeds
- [x] No compilation warnings
- [x] Plugin artifact generated

---

#### Task 6.4: Manual Testing Guide
**Description:** Create manual test scenarios

**Document:** Create `MANUAL_TEST_SCENARIOS.md` with:
1. Create container without strategies
2. Update to add strategy (verify indices created)
3. Add memory with strategy (verify works)
4. Create two containers with same prefix (verify shared index)
5. Attempt incompatible update (verify error message)

**Acceptance Criteria:**
- [x] Manual test guide documented
- [x] All scenarios tested manually
- [x] Results documented

---

## Task Summary

| Phase | Tasks | Estimated Time | Status |
|-------|-------|----------------|--------|
| Phase 1: Setup | 2 | 30 min | ⬜ Not Started |
| Phase 2: Transition | 1 | 45 min | ⬜ Not Started |
| Phase 3: Validation | 4 | 2 hours | ⬜ Not Started |
| Phase 4: Index Creation | 5 | 2 hours | ⬜ Not Started |
| Phase 5: Testing | 6 | 3 hours | ⬜ Not Started |
| Phase 6: Verification | 4 | 1 hour | ⬜ Not Started |
| **TOTAL** | **22 tasks** | **~9.25 hours** | **0% Complete** |

---

## Progress Tracking

### Completion Checklist

**Phase 1: Setup and Dependencies**
- [ ] Task 1.1: Add Required Imports
- [ ] Task 1.2: Add MLIndicesHandler Field

**Phase 2: Transition Detection**
- [ ] Task 2.1: Implement Transition Detection Logic

**Phase 3: Validation Chain**
- [ ] Task 3.1: Implement validateAndCreateIndices()
- [ ] Task 3.2: Implement validateEmbeddingModelAndCreateIndices()
- [ ] Task 3.3: Implement validateSharedIndexAndCreateIndices()
- [ ] Task 3.4: Implement validateExistingIndexAndCreateHistory()

**Phase 4: Index Creation**
- [ ] Task 4.1: Implement createHistoryIndexOnly()
- [ ] Task 4.2: Implement createLongTermAndHistoryIndices()
- [ ] Task 4.3: Implement createLongTermMemoryIngestPipeline()
- [ ] Task 4.4: Implement createTextEmbeddingPipeline()
- [ ] Task 4.5: Implement createPipelineInternal()

**Phase 5: Testing**
- [ ] Task 5.1: Create Test File Structure
- [ ] Task 5.2: Write Transition Detection Tests
- [ ] Task 5.3: Write Model Validation Tests
- [ ] Task 5.4: Write Shared Index Validation Tests
- [ ] Task 5.5: Write Index Creation Tests
- [ ] Task 5.6: Write Integration Tests

**Phase 6: Final Verification**
- [ ] Task 6.1: Code Quality Checks
- [ ] Task 6.2: Full Test Suite
- [ ] Task 6.3: Build Verification
- [ ] Task 6.4: Manual Testing Guide

---

## Dependencies Graph

```
Task 1.1 (Imports)
    ↓
Task 1.2 (Field)
    ↓
Task 2.1 (Transition Detection)
    ↓
Task 3.1 (LLM Validation) ──→ Task 5.3 (Model Tests)
    ↓
Task 3.2 (Embedding Validation) ──→ Task 5.3 (Model Tests)
    ↓
Task 3.3 (Shared Index Check) ──→ Task 5.4 (Shared Index Tests)
    ↓                ↓
Task 3.4            Task 4.2 (Create Both)
(Validate)              ↓
    ↓               Task 4.3 (Pipeline+Index)
Task 4.1                ↓
(History Only)      Task 4.4 (Pipeline Check)
    ↓                   ↓
    └─────→ Task 4.5 (Pipeline Create)
                ↓
            Task 5.5 (Creation Tests)
                ↓
            Task 5.6 (Integration Tests)
                ↓
            Task 6.1-6.4 (Verification)
```

---

## Risk Mitigation

### High-Risk Areas
1. **Async callback chains** - Many nested ActionListeners
   - Mitigation: Careful error handling at each step
   - Testing: Mock all async calls, verify error propagation

2. **Shared index scenarios** - Complex validation logic
   - Mitigation: Reuse existing validation from TransportCreateMemoryContainerAction
   - Testing: Extensive test coverage for all scenarios

3. **Race conditions** - Multiple containers updating simultaneously
   - Mitigation: OpenSearch handles concurrent index creation
   - Testing: Manual stress testing with parallel updates

### Medium-Risk Areas
1. **Test complexity** - Many mocks needed
   - Mitigation: Create helper methods for common mock setups
   - Testing: Start with simple cases, build up complexity

2. **Code duplication** - Pipeline creation logic copied from Create action
   - Mitigation: Accept duplication for now, refactor later if needed
   - Note: Each action remains self-contained

---

## Notes for Implementation

1. **Implement in order** - Each phase builds on previous phases
2. **Test as you go** - Write unit tests immediately after each method
3. **Commit frequently** - Commit after each completed task
4. **Document assumptions** - Add code comments for complex logic
5. **Ask for review** - Get feedback after completing each phase

---

## Success Criteria

The implementation is complete when:
- ✅ All 22 tasks completed
- ✅ All unit tests pass (>80% coverage)
- ✅ All integration tests pass
- ✅ Manual testing scenarios validated
- ✅ Code quality checks pass
- ✅ Full build succeeds
- ✅ Documentation updated
