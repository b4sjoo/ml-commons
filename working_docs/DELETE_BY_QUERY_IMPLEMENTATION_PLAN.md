# Delete-by-Query API Implementation Plan

## Overview
This document outlines the implementation plan for adding a delete-by-query API to the agentic memory system in ML Commons. The API will allow bulk deletion of memories based on OpenSearch DSL queries.

## API Specification
- **Endpoint**: `POST /_plugins/_ml/memory_containers/{container_id}/memories/{memory_type}/_delete_by_query`
- **Supported Memory Types**: `session`, `working`, `long_term`, `history`
- **Request Body**: OpenSearch DSL query
- **Response**: BulkByScrollResponse with deletion count and failure details
- **Access Control**: Same as other memory APIs (feature flag, container access, owner filtering)

## Task Tracking

### Phase 1: Common Module Components ⏳
- [ ] **Task 1: Create MLDeleteMemoriesByQueryAction class**
  - Location: `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLDeleteMemoriesByQueryAction.java`
  - Extend `ActionType<BulkByScrollResponse>`
  - Action name: `cluster:admin/opensearch/ml/memory_containers/memories/delete_by_query`
  - Import: `org.opensearch.index.reindex.BulkByScrollResponse`

- [ ] **Task 2: Create MLDeleteMemoriesByQueryRequest class**
  - Location: `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLDeleteMemoriesByQueryRequest.java`
  - Fields: `String memoryContainerId`, `String memoryType`, `QueryBuilder query`
  - Implement StreamInput/StreamOutput serialization
  - Add validation in constructor
  - Override `ActionRequestValidationException validate()`

- [ ] **Task 3: Add DELETE_MEMORIES_BY_QUERY_PATH constant**
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java`
  - Add: `public static final String DELETE_MEMORIES_BY_QUERY_PATH = MEMORIES_PATH + "/{" + PARAMETER_MEMORY_TYPE + "}" + "/_delete_by_query";`

### Phase 2: Plugin Module Components ⏳
- [ ] **Task 4: Create RestMLDeleteMemoriesByQueryAction REST handler**
  - Location: `plugin/src/main/java/org/opensearch/ml/rest/RestMLDeleteMemoriesByQueryAction.java`
  - Parse path parameters: container_id, memory_type
  - Parse query from request body using XContentParser
  - Build MLDeleteMemoriesByQueryRequest
  - Format BulkByScrollResponse for client response

- [ ] **Task 5: Create TransportDeleteMemoriesByQueryAction**
  - Location: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportDeleteMemoriesByQueryAction.java`
  - Key implementation steps:
    1. Validate agentic memory feature enabled
    2. Get container and check access control
    3. Build DeleteByQueryRequest
    4. Apply owner filtering for non-admin users
    5. Execute using `client.execute(DeleteByQueryAction.INSTANCE, ...)`
    6. Handle system indices with ThreadContext.stashContext()
    7. Process BulkByScrollResponse

- [ ] **Task 6: Add helper method to MemoryContainerHelper**
  - File: `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java`
  - Add method: `executeDeleteByQuery(MemoryConfiguration, DeleteByQueryRequest, ActionListener<BulkByScrollResponse>)`
  - Handle system vs non-system index execution

- [ ] **Task 7: Register in MachineLearningPlugin**
  - File: `plugin/src/main/java/org/opensearch/ml/plugin/MachineLearningPlugin.java`
  - Add ActionHandler: `new ActionHandler<>(MLDeleteMemoriesByQueryAction.INSTANCE, TransportDeleteMemoriesByQueryAction.class)`
  - Add REST handler to getRestHandlers()

### Phase 3: Test Coverage ⏳
- [ ] **Task 8: Create RestMLDeleteMemoriesByQueryActionTests**
  - Location: `plugin/src/test/java/org/opensearch/ml/rest/RestMLDeleteMemoriesByQueryActionTests.java`
  - Test route registration
  - Test request parsing
  - Test parameter validation
  - Test error responses

- [ ] **Task 9: Create TransportDeleteMemoriesByQueryActionTests**
  - Location: `plugin/src/test/java/org/opensearch/ml/action/memorycontainer/memory/TransportDeleteMemoriesByQueryActionTests.java`
  - Test feature flag validation
  - Test container access control
  - Test owner filtering for non-admin users
  - Mock `client.execute(DeleteByQueryAction.INSTANCE, ...)`
  - Test BulkByScrollResponse handling (success, failures, timeout)
  - Test system index context stashing

- [ ] **Task 10: Create MLDeleteMemoriesByQueryRequestTests**
  - Location: `common/src/test/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLDeleteMemoriesByQueryRequestTests.java`
  - Test serialization/deserialization
  - Test validation logic
  - Test XContent parsing

### Phase 4: Validation & Documentation ⏳
- [ ] **Task 11: Run tests and verify implementation**
  - Run all new unit tests
  - Integration test with actual memory data
  - Verify access control works correctly
  - Test with different memory types

- [ ] **Task 12: Update documentation**
  - Update CLAUDE.md with new API details
  - Update AGENTIC_MEMORY.md with delete-by-query feature
  - Add usage examples

## Implementation Details

### Key Imports Required
```java
// For DeleteByQuery operations
import org.opensearch.index.reindex.BulkByScrollResponse;
import org.opensearch.index.reindex.DeleteByQueryAction;
import org.opensearch.index.reindex.DeleteByQueryRequest;

// For query building
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.TermQueryBuilder;
```

### Access Control Implementation
```java
// Three-level access control
1. Feature flag check:
   if (!mlFeatureEnabledSetting.isAgenticMemoryEnabled()) {
       throw new OpenSearchStatusException(ML_COMMONS_AGENTIC_MEMORY_DISABLED_MESSAGE, RestStatus.FORBIDDEN);
   }

2. Container access check:
   if (!memoryContainerHelper.checkMemoryContainerAccess(user, container)) {
       throw new OpenSearchStatusException("User doesn't have permissions", RestStatus.FORBIDDEN);
   }

3. Owner filtering for non-admin:
   if (user != null && !user.getRoles().contains("all_access")) {
       BoolQueryBuilder filtered = QueryBuilders.boolQuery()
           .must(request.getQuery())
           .filter(QueryBuilders.termQuery(OWNER_ID_FIELD, user.getName()));
       deleteRequest.setQuery(filtered);
   }
```

### DeleteByQuery Execution Pattern
```java
// Based on MLMemoryManager and DeleteModelTransportAction patterns
DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest(memoryIndexName);
deleteByQueryRequest.setQuery(finalQuery);
deleteByQueryRequest.setRefresh(true);

// For system indices
if (configuration.isUseSystemIndex()) {
    try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
        client.execute(DeleteByQueryAction.INSTANCE, deleteByQueryRequest,
            ActionListener.runBefore(actionListener, context::restore));
    }
} else {
    client.execute(DeleteByQueryAction.INSTANCE, deleteByQueryRequest, actionListener);
}
```

### Response Handling Pattern
```java
ActionListener<BulkByScrollResponse> responseListener = ActionListener.wrap(response -> {
    // Check for failures
    if (response.getBulkFailures() != null && !response.getBulkFailures().isEmpty()) {
        log.error("Bulk failures during delete by query: {}", response.getBulkFailures());
    }
    if (response.getSearchFailures() != null && !response.getSearchFailures().isEmpty()) {
        log.error("Search failures during delete by query: {}", response.getSearchFailures());
    }
    if (response.isTimedOut()) {
        log.warn("Delete by query operation timed out");
    }

    // Return response with deleted count
    actionListener.onResponse(response);
}, error -> {
    log.error("Failed to execute delete by query", error);
    actionListener.onFailure(error);
});
```

## Test Strategy

### Unit Test Patterns
```java
// Mock DeleteByQueryAction execution (from DeleteModelTransportActionTests)
doAnswer(invocation -> {
    ActionListener<BulkByScrollResponse> listener = invocation.getArgument(2);
    BulkByScrollResponse response = new BulkByScrollResponse(
        new ArrayList<>(),  // bulk failures
        null                // search failures
    );
    listener.onResponse(response);
    return null;
}).when(client).execute(eq(DeleteByQueryAction.INSTANCE), any(), any());

// Test failure scenarios
BulkByScrollResponse response = mock(BulkByScrollResponse.class);
when(response.getBulkFailures()).thenReturn(List.of(mockFailure));
when(response.getSearchFailures()).thenReturn(Collections.emptyList());
when(response.isTimedOut()).thenReturn(false);
```

### Test Coverage Requirements
1. **Feature Flag Tests**
   - Test disabled feature returns 403
   - Test enabled feature proceeds

2. **Access Control Tests**
   - Test admin user can delete any memories
   - Test non-admin user can only delete own memories
   - Test unauthorized container access returns 403

3. **Query Filtering Tests**
   - Test owner filter added for non-admin users
   - Test no filter added for admin users
   - Test complex queries preserved correctly

4. **Response Handling Tests**
   - Test successful deletion response
   - Test bulk failure handling
   - Test search failure handling
   - Test timeout handling

5. **System Index Tests**
   - Test ThreadContext stashing for system indices
   - Test direct execution for non-system indices

## Usage Examples

### Example 1: Delete all memories in a session
```bash
POST /_plugins/_ml/memory_containers/container123/memories/session/_delete_by_query
{
  "query": {
    "match_all": {}
  }
}
```

### Example 2: Delete memories by namespace
```bash
POST /_plugins/_ml/memory_containers/container123/memories/working/_delete_by_query
{
  "query": {
    "term": {
      "namespace.session_id": "session456"
    }
  }
}
```

### Example 3: Delete old memories
```bash
POST /_plugins/_ml/memory_containers/container123/memories/long_term/_delete_by_query
{
  "query": {
    "range": {
      "created_time": {
        "lte": "2025-01-01"
      }
    }
  }
}
```

### Example Response
```json
{
  "took": 147,
  "timed_out": false,
  "total": 42,
  "deleted": 42,
  "batches": 1,
  "version_conflicts": 0,
  "noops": 0,
  "retries": {
    "bulk": 0,
    "search": 0
  },
  "throttled_millis": 0,
  "requests_per_second": -1.0,
  "throttled_until_millis": 0,
  "failures": []
}
```

## Files to Create/Modify Summary

### New Files (6)
1. `common/.../MLDeleteMemoriesByQueryAction.java`
2. `common/.../MLDeleteMemoriesByQueryRequest.java`
3. `plugin/.../RestMLDeleteMemoriesByQueryAction.java`
4. `plugin/.../TransportDeleteMemoriesByQueryAction.java`
5. `plugin/.../RestMLDeleteMemoriesByQueryActionTests.java`
6. `plugin/.../TransportDeleteMemoriesByQueryActionTests.java`
7. `common/.../MLDeleteMemoriesByQueryRequestTests.java`

### Modified Files (3)
1. `common/.../MemoryContainerConstants.java` - Add path constant
2. `plugin/.../MemoryContainerHelper.java` - Add helper method
3. `plugin/.../MachineLearningPlugin.java` - Register action and REST handler

## Risk Assessment

### Low Risk
- Uses established patterns from existing codebase
- Leverages native OpenSearch DeleteByQuery functionality
- Follows existing access control mechanisms

### Medium Risk
- Bulk operations could impact performance if not limited
- Need to ensure proper error handling for partial failures

### Mitigation
- Consider adding max documents limit for single operation
- Implement proper logging for audit trail
- Add metrics for monitoring delete operations

## Success Criteria
1. ✅ API endpoint responds correctly to POST requests
2. ✅ Access control properly enforced (feature flag, container, owner)
3. ✅ Queries correctly filtered for non-admin users
4. ✅ System indices handled with proper context stashing
5. ✅ BulkByScrollResponse properly formatted for client
6. ✅ All unit tests pass with >80% coverage
7. ✅ Documentation updated with examples

## Timeline Estimate
- Phase 1 (Common Module): 2 hours
- Phase 2 (Plugin Module): 4 hours
- Phase 3 (Test Coverage): 3 hours
- Phase 4 (Validation & Docs): 1 hour
- **Total**: ~10 hours

## Notes
- The implementation follows patterns from `MLMemoryManager` and `DeleteModelTransportAction`
- No SdkClient usage required - native OpenSearch client handles DeleteByQuery
- ThreadContext stashing is critical for system index operations
- Owner filtering must be applied at query level for security

---
*Implementation plan created: September 30, 2025*
*Last updated: September 30, 2025*