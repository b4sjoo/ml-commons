# Strategy ID Filtering Implementation Tracker

## Overview
Add strategy_id field to memory documents and filter by it during similarity search to prevent cross-strategy interference.

## Problem
- During LLM auto-decision phase, similarity search finds memories from ALL strategies
- USER_PREFERENCES memories can interfere with SEMANTIC memories and vice versa
- This causes incorrect update/delete decisions across different strategy types

## Solution
Add `strategy_id` field to all long-term memory documents and filter by it during searches.

---

## Implementation Tasks

### Phase 1: Core Data Model Updates
- [ ] **Task 1.1**: Add STRATEGY_ID_FIELD constant
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java`
  - Action: Add `public static final String STRATEGY_ID_FIELD = "strategy_id";`
  - Priority: High
  - Dependencies: None

- [ ] **Task 1.2**: Update MLMemory class
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MLMemory.java`
  - Actions:
    - Add `private String strategyId;` field
    - Update constructor to include strategyId
    - Update builder to include strategyId
    - Add strategyId to StreamInput/StreamOutput serialization
    - Add strategyId to toXContent() method
    - Add strategyId to parse() method
    - Add strategyId to toIndexMap() method
  - Priority: High
  - Dependencies: Task 1.1

### Phase 2: Index Schema Updates
- [ ] **Task 2.1**: Update long-term memory index mapping
  - File: `ml-algorithms/src/main/java/org/opensearch/ml/engine/indices/MLIndicesHandler.java`
  - Action: Add `properties.put(STRATEGY_ID_FIELD, Map.of("type", "keyword"));` in createLongTermMemoryIndex() around line 215
  - Priority: High
  - Dependencies: Task 1.1

### Phase 3: Memory Creation Logic
- [ ] **Task 3.1**: Update executeMemoryOperations method
  - File: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryOperationsService.java`
  - Action: Add `.strategyId(strategy.getId())` to MLMemory.builder() in ADD case (line 90-99)
  - Priority: High
  - Dependencies: Task 1.2

- [ ] **Task 3.2**: Update createFactMemoriesFromList method
  - File: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryOperationsService.java`
  - Action: Add `.strategyId(strategy.getId())` to MLMemory.builder() (line 294-302)
  - Priority: High
  - Dependencies: Task 1.2

### Phase 4: Search Query Updates
- [ ] **Task 4.1**: Fix hardcoded SEMANTIC filter
  - File: `plugin/src/main/java/org/opensearch/ml/utils/MemorySearchQueryBuilder.java`
  - Actions:
    - Remove hardcoded line 143: `boolQuery.filter(QueryBuilders.termQuery(MEMORY_TYPE_FIELD, MemoryType.SEMANTIC.getValue()));`
    - Add dynamic memory type based on strategy
    - Add strategy_id filter
  - Priority: Critical
  - Dependencies: Task 1.1

- [ ] **Task 4.2**: Add getMemoryTypeFromStrategy helper
  - File: `plugin/src/main/java/org/opensearch/ml/utils/MemorySearchQueryBuilder.java`
  - Action: Add helper method to map strategy type to MemoryType enum
  - Priority: High
  - Dependencies: None

### Phase 5: Testing
- [ ] **Task 5.1**: Create unit tests for MLMemory with strategyId
  - File: `common/src/test/java/org/opensearch/ml/common/memorycontainer/MLMemoryTest.java`
  - Actions:
    - Test serialization/deserialization with strategyId
    - Test toIndexMap includes strategyId
    - Test parse method handles strategyId
  - Priority: Medium
  - Dependencies: Phase 1

- [ ] **Task 5.2**: Create integration test for strategy isolation
  - File: New test file in plugin/src/test
  - Actions:
    - Test memories from different strategies don't interfere
    - Test USER_PREFERENCES strategy isolation
    - Test SEMANTIC strategy isolation
  - Priority: Medium
  - Dependencies: All phases

- [ ] **Task 5.3**: Test backward compatibility
  - Actions:
    - Test that old memories without strategy_id still work
    - Test search handles missing strategy_id gracefully
  - Priority: Medium
  - Dependencies: All phases

### Phase 6: Documentation
- [ ] **Task 6.1**: Update CLAUDE.md
  - Action: Document the strategy_id field and filtering behavior
  - Priority: Low
  - Dependencies: All implementation phases

- [ ] **Task 6.2**: Update AGENTIC_MEMORY.md
  - Action: Add section about strategy isolation
  - Priority: Low
  - Dependencies: All implementation phases

---

## Implementation Order
1. Phase 1: Core Data Model Updates (Tasks 1.1, 1.2)
2. Phase 2: Index Schema Updates (Task 2.1)
3. Phase 3: Memory Creation Logic (Tasks 3.1, 3.2)
4. Phase 4: Search Query Updates (Tasks 4.1, 4.2)
5. Phase 5: Testing (Tasks 5.1, 5.2, 5.3)
6. Phase 6: Documentation (Tasks 6.1, 6.2)

---

## Code Changes Summary

### Files to Modify
1. `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java`
2. `common/src/main/java/org/opensearch/ml/common/memorycontainer/MLMemory.java`
3. `ml-algorithms/src/main/java/org/opensearch/ml/engine/indices/MLIndicesHandler.java`
4. `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryOperationsService.java`
5. `plugin/src/main/java/org/opensearch/ml/utils/MemorySearchQueryBuilder.java`

### Key Changes
- Add `strategy_id` field to memory documents
- Include `strategy_id` in index mapping as keyword type
- Populate `strategy_id` when creating memories
- Filter by `strategy_id` during similarity search
- Fix hardcoded `MemoryType.SEMANTIC` filter

---

## Risk Assessment
- **Low Risk**: Adding new field won't break existing functionality
- **Medium Risk**: Search query changes need careful testing
- **Backward Compatibility**: Old memories without strategy_id will still work

---

## Success Criteria
1. Memories from different strategies are completely isolated
2. USER_PREFERENCES memories don't interfere with SEMANTIC memories
3. Similarity search only returns memories from the same strategy
4. Existing memories without strategy_id continue to work
5. All tests pass

---

## Notes
- The hardcoded `MemoryType.SEMANTIC` in search query is a bug that needs fixing
- Strategy isolation is critical for correct memory management
- This change improves accuracy of LLM memory decisions