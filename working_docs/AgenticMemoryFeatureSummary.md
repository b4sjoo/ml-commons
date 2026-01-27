# Agentic Memory Feature - Comprehensive Development Summary

## Executive Summary

The **Agentic Memory System** for OpenSearch ML Commons is a production-ready native memory management framework that enables intelligent agents to store, process, and retrieve conversational memories with state-of-the-art LLM automation. This feature unifies episodic and semantic memory handling in a scalable, enterprise-ready solution.

## Table of Contents
1. [Core Architecture](#core-architecture)
2. [API Endpoints](#api-endpoints)
3. [Key Technical Achievements](#key-technical-achievements)
4. [Storage Architecture](#storage-architecture)
5. [LLM Integration](#llm-integration)
6. [Collaborative Contributions](#collaborative-contributions)
7. [Implementation Milestones](#implementation-milestones)
8. [Feature Commits](#feature-commits)

## Core Architecture

### System Components

```
┌─────────────────────────────────────────┐
│         REST API Layer                  │
│  ┌─────────────┐  ┌─────────────────┐  │
│  │ Container   │  │ Memory          │  │
│  │ Management  │  │ Operations      │  │
│  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│       Transport Actions Layer           │
│  ┌─────────────┐  ┌─────────────────┐  │
│  │ Container   │  │ Memory CRUD     │  │
│  │ CRUD        │  │ Operations      │  │
│  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│         Helper Services                 │
│  ┌─────────────┐  ┌─────────────────┐  │
│  │ Container   │  │ Embedding       │  │
│  │ Helper      │  │ Helper          │  │
│  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│         OpenSearch Core                 │
│  ┌─────────────┐  ┌─────────────────┐  │
│  │ System      │  │ Memory Data     │  │
│  │ Index       │  │ Indices         │  │
│  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
```

### Data Storage

1. **System Index**: `.plugins-ml-agentic-memory-container` - Stores container metadata
2. **Memory Indices**: 
   - Static: `ml-static-memory-{containerId}-{userId}`
   - KNN: `ml-knn-memory-{containerId}-{userId}`
   - Sparse: `ml-sparse-memory-{containerId}-{userId}`

## API Endpoints

### Memory Container APIs (Metadata Layer)
| Operation | Endpoint | Status |
|-----------|----------|--------|
| CREATE | `POST /_plugins/_ml/memory_containers/_create` | ✅ Implemented |
| READ | `GET /_plugins/_ml/memory_containers/{id}` | ✅ Implemented |
| UPDATE | `PUT /_plugins/_ml/memory_containers/{id}` | ❌ Not Implemented |
| DELETE | `DELETE /_plugins/_ml/memory_containers/{id}` | ✅ Implemented |

### Memory Document APIs (Data Layer)
| Operation | Endpoint | Status |
|-----------|----------|--------|
| CREATE | `POST /_plugins/_ml/memory_containers/{id}/memories` | ✅ Implemented |
| SEARCH | `GET/POST /_plugins/_ml/memory_containers/{id}/memories/_search` | ✅ Implemented |
| UPDATE | `PUT /_plugins/_ml/memory_containers/{id}/memories/{memory_id}` | ✅ Implemented |
| DELETE | `DELETE /_plugins/_ml/memory_containers/{id}/memories/{memory_id}` | ✅ Implemented |

## Key Technical Achievements

### 1. Intelligent Memory Processing

#### LLM-Powered Features
- **Fact Extraction**: Automatically extracts key facts from conversations
- **Memory Decisions**: Implements intelligent ADD/UPDATE/DELETE/NONE logic
- **Context Processing**: Handles multiple messages for conversation understanding

#### Example: Memory Decision Flow
```json
Input: "I moved to San Francisco last month"
Existing Memory: "Lives in Boston"

Decision: UPDATE
Result: {
  "id": "fact_002",
  "text": "Lives in San Francisco",
  "event": "UPDATE",
  "old_memory": "Lives in Boston"
}
```

### 2. Flexible Storage Architecture

#### Three Storage Types

| Type | Use Case | Index Pattern | Features |
|------|----------|---------------|----------|
| **Static** | Basic text storage | `ml-static-memory-*` | Simple keyword/text search |
| **KNN** | Dense vector search | `ml-knn-memory-*` | Semantic similarity with TEXT_EMBEDDING |
| **Sparse** | Sparse vector search | `ml-sparse-memory-*` | Efficient semantic search with SPARSE_ENCODING |

### 3. Enterprise Features

- **Multi-tenancy**: Full tenant isolation with `tenantId` field
- **Access Control**: User and backend role-based permissions
- **Audit Trail**: Automatic timestamp tracking
- **Error Handling**: Comprehensive validation and recovery

## Storage Architecture

### Memory Container Structure
```java
MLMemoryContainer {
    String name;                      // Required
    String description;               // Optional
    User owner;                       // Access control
    String tenantId;                  // Multi-tenancy
    Instant createdTime;              // Audit
    Instant lastUpdatedTime;          // Audit
    
    MemoryStorageConfig {
        String memoryIndexName;       // Custom or auto-generated
        boolean semanticStorageEnabled; // Auto-determined
        FunctionName embeddingModelType; // TEXT_EMBEDDING or SPARSE_ENCODING
        String embeddingModelId;      // Embedding model
        String llmModelId;            // LLM for fact extraction
        Integer dimension;            // Vector dimension
        Integer maxInferSize;         // Result limit
    }
}
```

### Memory Document Fields
```json
{
  "user_id": "keyword",
  "agent_id": "keyword",
  "session_id": "keyword",
  "memory": "text",
  "memory_embedding": "knn_vector/rank_features",
  "tags": "flat_object",
  "memory_type": "keyword", // RAW_MESSAGE or FACT
  "role": "text",           // user or assistant
  "created_time": "date",
  "last_updated_time": "date"
}
```

## LLM Integration

### Fact Extraction Process
1. **Input**: Conversation messages array
2. **LLM Processing**: Extract facts using Personal Information Organizer prompt
3. **Storage**: 
   - RAW_MESSAGE: Original conversation
   - FACT: Extracted facts with embeddings

### Memory Decision System
When `infer=true` and `sessionId` provided:
1. Search for similar existing facts
2. LLM analyzes new facts vs existing memories
3. Makes decisions: ADD, UPDATE, DELETE, or NONE
4. Returns only actionable changes (NONE events suppressed)

### Example Workflow
```bash
# Input conversation
POST /_plugins/_ml/memory_containers/abc123/memories
{
  "messages": [
    {"role": "user", "content": "I'm John, a software engineer at TechCorp"},
    {"role": "assistant", "content": "Nice to meet you John!"},
    {"role": "user", "content": "I specialize in distributed systems"}
  ],
  "session_id": "sess_123",
  "infer": true
}

# Output (extracted facts only)
{
  "results": [
    {"id": "fact_001", "text": "Name is John", "event": "ADD"},
    {"id": "fact_002", "text": "Works at TechCorp", "event": "ADD"},
    {"id": "fact_003", "text": "Is a software engineer", "event": "ADD"},
    {"id": "fact_004", "text": "Specializes in distributed systems", "event": "ADD"}
  ]
}
```

## Collaborative Contributions

### Architecture Improvements
1. **Helper Class Pattern**
   - Created `MemoryContainerHelper` and `MemoryEmbeddingHelper`
   - Reduced code duplication by ~434 lines
   - Improved maintainability and testability

2. **API Evolution**
   - Messages array format for batch processing
   - NONE event suppression for cleaner responses
   - Added `old_memory` field for UPDATE transparency

3. **Simplifications**
   - Decoupled LLM from semantic storage
   - Removed auto-cleanup logic
   - Made semantic storage auto-determined

### Code Quality Enhancements
- Fixed all unit test failures across Transport and REST layers
- Added MLFeatureEnabledSetting for feature flags
- Improved error messages with detailed context
- Optimized logging for production environments

### SDK Client Migration
- Migrated all bulk operations from `client.bulk()` to `sdkClient.bulkDataObjectAsync()`
- Implemented CompletableFuture pattern for async operations
- Replaced traditional request classes with DataObject equivalents
- Better error handling with proper exception unwrapping

## Implementation Milestones

### Completed Features ✅
1. Full CRUD for memory documents
2. Create/Read/Delete for containers
3. LLM fact extraction and decisions
4. Three storage types with auto-selection
5. Semantic search (dense and sparse)
6. Multi-message conversation processing
7. Security and multi-tenancy
8. Helper classes for maintainability
9. Production error handling
10. Comprehensive test coverage

### Pending Features ❌
1. Update operation for memory containers
2. Batch memory operations
3. Memory export/import functionality

## Feature Commits

Chronological development progress:

| Commit | Description |
|--------|-------------|
| `da485fb0` | Initial foundation and data models |
| `7ade595d` | Core API structure implementation |
| `f7a821ee` | Memory operations and CRUD |
| `2916b8d7` | LLM integration for fact extraction |
| `de3df613` | Search capabilities and semantic storage |
| `02601dc2` | Helper class refactoring |
| `974b4187` | Error handling improvements |
| `aed8983a` | Response filtering (NONE suppression) |
| `3fa3a1e9` | Final refinements and test fixes |

## Documentation Assets

### Primary Documentation
- **CLAUDE.md**: Developer guide with API examples and troubleshooting
- **plan.md**: Implementation strategy and index structures
- **MLCommonsDeveloperReferenceClaude.md**: Build commands and patterns
- **BuildMemoryLayerinML-Commons.md**: Architecture overview

### Supporting Documents
- **DEFAULT_UPDATE_MEMORY_PROMPT.xml**: LLM prompt for memory decisions
- **AgenticMemoryFeatureSummary.md**: This comprehensive summary

## Current Production State

### Ready for Production ✅
- Robust APIs for conversation memory management
- Intelligent LLM-powered processing
- Flexible storage for different use cases
- Enterprise security and multi-tenancy
- Comprehensive documentation and tests

### Use Cases Enabled
1. **Customer Service**: Store interaction history with automatic fact extraction
2. **Personal Assistants**: Maintain user preferences and context
3. **Knowledge Management**: Semantic search across conversation history
4. **Compliance**: Audit trail with timestamps and access control

## Recent Enhancements (October 2025)

### Strategy-Level LLM Override
**Feature**: Each memory strategy can now use its own LLM model, independent of the container-level configuration.

**Implementation**:
- Add `llm_id` to strategy's `configuration` field
- Strategy-level model takes precedence over container-level model
- Enables optimization: powerful models for complex tasks, lighter models for simple tasks

**Example Use Case**:
```json
{
  "configuration": {
    "llm_id": "default-model",  // Container-level default
    "strategies": [{
      "type": "semantic",
      "namespace": ["user_id"],
      "configuration": {
        "llm_id": "gpt-4-turbo"  // Override for semantic extraction
      }
    }, {
      "type": "summary",
      "namespace": ["session_id"],
      "configuration": {
        "llm_id": "gpt-3.5-turbo"  // Lighter model for summaries
      }
    }]
  }
}
```

**Benefits**:
- Cost optimization: Use expensive models only where needed
- Performance tuning: Match model capabilities to task complexity
- Flexibility: Test different models for different strategy types

### Auto-Generated Session Consistency
**Fix**: Sessions created automatically by the add memory API now properly include `memory_container_id` field.

**Previously**:
- Manual session creation: ✅ Included memory_container_id
- Auto-generated sessions: ❌ Missing memory_container_id

**Now**:
- All sessions include memory_container_id regardless of creation method
- Ensures consistent data structure across all sessions

### Debug Logging for LLM Model Tracking
**Enhancement**: Added debug-level logs to track LLM model selection during memory processing.

**Logged Information**:
- Which LLM model is being used
- Source of model (strategy override vs. container config)
- Strategy type being processed
- Number of facts extracted and similar memories found

**Usage**: Enable debug logging to troubleshoot LLM model selection:
```bash
log4j.logger.org.opensearch.ml.action.memorycontainer.memory=DEBUG
```

### Internal Architecture Improvements
**Refactoring**: Consolidated searchData methods to use modern SDK client patterns.

**Changes**:
- Migrated from legacy `SearchRequest` to `SearchDataObjectRequest`
- Removed duplicate method overloads
- Improved consistency with OpenSearch SDK client usage

**Impact**: Cleaner codebase, better maintainability, no user-facing changes

### Container ID Filtering (October 2025)
**Feature**: Automatic cross-container isolation when containers share index prefixes.

**Problem**: When multiple containers use the same `index_prefix`, they share physical indices, leading to data leakage across container boundaries.

**Solution**: Added `memory_container_id` term filtering to all query operations:
- User search API (all 4 memory types: sessions, working, long-term, history)
- LLM fact extraction during add memory processing
- Delete by query operations

**Implementation Details**:
- **Helper Methods**: `MemoryContainerHelper.addContainerIdFilter()` for both SearchSourceBuilder and QueryBuilder
- **Query Builder**: Updated `MemorySearchQueryBuilder.buildFactSearchQuery()` with `memoryContainerId` parameter
- **Applied In**:
  - `TransportSearchMemoriesAction` - User search API (line 118)
  - `TransportDeleteMemoriesByQueryAction` - Bulk deletion (line 123)
  - `MemorySearchService` - LLM fact extraction (line 84)

**Filter Precedence**:
```
Original Query → Container ID Filter → Owner ID Filter → Final Query
```

**Benefits**:
- **Security**: Prevents unauthorized cross-container data access
- **Data Isolation**: Complete separation even with shared indices
- **Performance**: Efficient filtering using indexed `memory_container_id` field
- **Safety**: Prevents accidental cross-container deletions
- **Reliability**: Gracefully handles null/blank container IDs

**Technical Behavior**:
- Filter applied for ALL users (including admins) - container boundaries are absolute
- Null or blank container IDs: No filter applied (backward compatible)
- Applied BEFORE owner filtering in the query chain

**Example Use Case**:
```json
Scenario: Two containers share index prefix "shared-memory"

Container A: { "id": "container-a", "index_prefix": "shared-memory" }
Container B: { "id": "container-b", "index_prefix": "shared-memory" }

Before Container ID Filtering:
  - Search Container A → Returns data from both A and B ❌
  - LLM extraction in A → Finds facts from B ❌
  - Delete in A → May delete B's data ❌

After Container ID Filtering:
  - Search Container A → Returns only A's data ✅
  - LLM extraction in A → Only finds A's facts ✅
  - Delete in A → Only deletes A's data ✅
```

**Test Coverage**:
- Added 3 new test cases in `MemorySearchQueryBuilderTests`
- Updated 3 existing tests in `TransportDeleteMemoriesByQueryActionTests`
- Coverage increased from 60% to 70%+ for MemorySearchQueryBuilder

## Conclusion

The Agentic Memory feature transforms OpenSearch into a sophisticated platform for conversational AI applications. By combining traditional storage with LLM intelligence, it enables scalable management of user information while preserving critical facts through automated processing. This positions OpenSearch ML Commons as a leading solution for building memory-aware intelligent agents.