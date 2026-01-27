# Strategy Update Implementation Plan

## Overview
Enable updating strategies field in the memory container update API with intelligent merge logic that preserves existing strategies while allowing additions and modifications.

## Key Requirements
1. **No deletion** - Strategies are disabled via `enabled: false`, not deleted
2. **Type immutability** - Strategy type cannot be changed once created
3. **Default enabled** - New strategies without explicit `enabled` field default to `true`
4. **404 for not found** - Strategy ID not found returns 404 NOT_FOUND, not 400 BAD_REQUEST
5. **Partial updates** - Only provided fields are updated, others preserved

## Implementation Tasks

### Phase 1: Extend Input Model [Estimated: 2 hours]

#### Task 1.1: Update MLUpdateMemoryContainerInput class
- [ ] Add `List<MemoryStrategy> strategies` field
- [ ] Update constructor to accept strategies parameter
- [ ] Update `writeTo()` method to serialize strategies
- [ ] Update `StreamInput` constructor to deserialize strategies
- [ ] Update `toXContent()` to include strategies in output
- [ ] Update `parse()` method to parse strategies from JSON
- [ ] Add unit tests for serialization/deserialization

**File**: `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLUpdateMemoryContainerInput.java`

#### Task 1.2: Update MLUpdateMemoryContainerRequest validation
- [ ] Add validation for strategies field if present
- [ ] Ensure no duplicate strategy IDs in request
- [ ] Add unit tests for validation logic

**File**: `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLUpdateMemoryContainerRequest.java`

### Phase 2: Implement Strategy Merge Logic [Estimated: 3 hours]

#### Task 2.1: Create StrategyMergeHelper class
- [ ] Create new class `StrategyMergeHelper` in helper package
- [ ] Implement `mergeStrategies(List<MemoryStrategy> existing, List<MemoryStrategy> updates)` method
- [ ] Add logic to handle strategy with ID (update existing)
- [ ] Add logic to handle strategy without ID (add new with generated ID)
- [ ] Implement field-level merge logic (only update non-null fields)
- [ ] Add type change validation (throw error if type changed)
- [ ] Add NOT_FOUND exception for missing strategy IDs
- [ ] Set default `enabled: true` for new strategies without explicit value

**File**: `plugin/src/main/java/org/opensearch/ml/helper/StrategyMergeHelper.java`

#### Task 2.2: Modify MemoryStrategy for nullable enabled field
- [ ] Change `boolean enabled` to `Boolean enabled` to detect null
- [ ] Update constructors and builder
- [ ] Update serialization/deserialization methods
- [ ] Update `isEnabled()` method to handle null (return true if null)
- [ ] Add unit tests for nullable enabled field

**File**: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryStrategy.java`

### Phase 3: Update Transport Action [Estimated: 4 hours]

#### Task 3.1: Modify TransportUpdateMemoryContainerAction
- [ ] Add logic to check if strategies field is provided
- [ ] Fetch full container with existing configuration if strategies update requested
- [ ] Extract current strategies list from configuration
- [ ] Call StrategyMergeHelper to merge strategies
- [ ] Create new MemoryConfiguration with updated strategies
- [ ] Build update document with full configuration field
- [ ] Handle merge errors and return appropriate status codes
- [ ] Add comprehensive logging for debugging

**File**: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/TransportUpdateMemoryContainerAction.java`

#### Task 3.2: Update error handling
- [ ] Return 404 NOT_FOUND for strategy ID not found
- [ ] Return 400 BAD_REQUEST for invalid strategy type
- [ ] Return 400 BAD_REQUEST for attempting to change strategy type
- [ ] Add descriptive error messages for each scenario

### Phase 4: Testing [Estimated: 4 hours]

#### Task 4.1: Unit tests for StrategyMergeHelper
- [ ] Test merging with existing strategy ID
- [ ] Test adding new strategy without ID
- [ ] Test error when strategy ID not found
- [ ] Test error when changing strategy type
- [ ] Test partial field updates
- [ ] Test default enabled=true for new strategies
- [ ] Test preserving unmodified strategies

**File**: `plugin/src/test/java/org/opensearch/ml/helper/StrategyMergeHelperTests.java`

#### Task 4.2: Unit tests for MLUpdateMemoryContainerInput
- [ ] Test serialization with strategies
- [ ] Test deserialization with strategies
- [ ] Test parsing JSON with strategies
- [ ] Test toXContent with strategies
- [ ] Test backward compatibility (no strategies field)

**File**: `common/src/test/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLUpdateMemoryContainerInputTests.java`

#### Task 4.3: Integration tests for TransportUpdateMemoryContainerAction
- [ ] Test successful strategy update with ID
- [ ] Test successful new strategy addition
- [ ] Test combined update (modify existing + add new)
- [ ] Test 404 error for non-existent strategy ID
- [ ] Test 400 error for invalid strategy type
- [ ] Test 400 error for changing strategy type
- [ ] Test update with name/description and strategies together
- [ ] Test concurrent update scenarios

**File**: `plugin/src/test/java/org/opensearch/ml/action/memorycontainer/TransportUpdateMemoryContainerActionTests.java`

#### Task 4.4: REST API tests
- [ ] Test REST endpoint with strategies in request body
- [ ] Test parsing of strategies from JSON
- [ ] Test error responses for invalid requests

**File**: `plugin/src/test/java/org/opensearch/ml/rest/RestMLUpdateMemoryContainerActionTests.java`

### Phase 5: Documentation [Estimated: 2 hours]

#### Task 5.1: Update AGENTIC_MEMORY.md
- [ ] Add strategy update section in API documentation
- [ ] Add examples for updating existing strategies
- [ ] Add examples for adding new strategies
- [ ] Add examples for disabling/re-enabling strategies
- [ ] Document error scenarios
- [ ] Document that type cannot be changed
- [ ] Document default enabled=true behavior

**File**: `AGENTIC_MEMORY.md`

#### Task 5.2: Update CLAUDE.md
- [ ] Add strategy update feature in Recent Updates section
- [ ] Add implementation details for strategy merge logic
- [ ] Add key design decisions
- [ ] Add troubleshooting section for common errors

**File**: `CLAUDE.md`

#### Task 5.3: Create API examples file
- [ ] Create comprehensive examples for all update scenarios
- [ ] Include curl commands for testing
- [ ] Add expected responses for each scenario

**File**: `docs/examples/strategy_update_examples.md`

### Phase 6: Integration and Final Testing [Estimated: 2 hours]

#### Task 6.1: End-to-end testing
- [ ] Test creating container with strategies
- [ ] Test updating strategies multiple times
- [ ] Test with maximum number of strategies
- [ ] Test performance with large strategy configurations
- [ ] Test backward compatibility with existing containers

#### Task 6.2: Code review preparation
- [ ] Ensure all tests pass
- [ ] Check code coverage meets requirements
- [ ] Run spotless for code formatting
- [ ] Update any affected JavaDocs
- [ ] Prepare PR description with examples

## Testing Checklist

### Unit Tests
- [ ] MemoryStrategy with nullable enabled field
- [ ] MLUpdateMemoryContainerInput with strategies
- [ ] StrategyMergeHelper all scenarios
- [ ] Serialization/deserialization tests

### Integration Tests
- [ ] TransportUpdateMemoryContainerAction with strategies
- [ ] REST API with strategies
- [ ] Error handling for all scenarios
- [ ] Concurrent updates

### Manual Testing
- [ ] Create container with strategies
- [ ] Update existing strategy by ID
- [ ] Add new strategy without ID
- [ ] Disable and re-enable strategy
- [ ] Update strategy config
- [ ] Combined updates (name + strategies)
- [ ] Error scenarios (404, 400)

## API Examples

### Update Existing Strategy
```json
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "strategies": [
    {
      "id": "semantic_abc123",
      "enabled": false,
      "namespace": ["user_id", "session_id"]
    }
  ]
}
```

### Add New Strategy
```json
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "strategies": [
    {
      "type": "USER_PREFERENCE",
      "namespace": ["user_id"],
      "configuration": {
        "system_prompt": "Custom prompt"
      }
    }
  ]
}
```

### Combined Update
```json
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "name": "Updated Name",
  "description": "Updated description",
  "strategies": [
    {
      "id": "semantic_abc123",
      "enabled": false
    },
    {
      "type": "SUMMARY",
      "namespace": ["agent_id"]
    }
  ]
}
```

## Error Scenarios

### Strategy Not Found (404)
Request:
```json
{
  "strategies": [{"id": "non_existent", "enabled": false}]
}
```
Response: `404 Not Found - "Strategy with id non_existent not found"`

### Invalid Strategy Type (400)
Request:
```json
{
  "strategies": [{"type": "INVALID", "namespace": ["user_id"]}]
}
```
Response: `400 Bad Request - "Invalid strategy type: INVALID"`

### Type Change Not Allowed (400)
Request:
```json
{
  "strategies": [{"id": "semantic_abc123", "type": "USER_PREFERENCE"}]
}
```
Response: `400 Bad Request - "Cannot change strategy type from SEMANTIC to USER_PREFERENCE"`

## Success Criteria
1. All unit tests pass with >80% coverage
2. All integration tests pass
3. Manual testing confirms expected behavior
4. Documentation is complete and accurate
5. Code review approved
6. No performance degradation

## Estimated Total Time: 17 hours

## Dependencies
- Existing memory container infrastructure
- MemoryStrategy class with ID field
- MemoryConfiguration with strategies list
- OpenSearch Update API understanding

## Risks and Mitigations
1. **Risk**: Breaking existing update API
   - **Mitigation**: Strategies field is optional, backward compatible

2. **Risk**: Concurrent update conflicts
   - **Mitigation**: Use versioning or optimistic concurrency control

3. **Risk**: Performance impact with large strategy lists
   - **Mitigation**: Limit maximum strategies per container

4. **Risk**: Complex merge logic bugs
   - **Mitigation**: Comprehensive unit testing of all scenarios