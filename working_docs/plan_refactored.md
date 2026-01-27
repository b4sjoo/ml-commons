# Agentic Memory System - Refactored Architecture

## Overview

This document describes the refactored Agentic Memory system in ML Commons (commit b52818ee4), which provides a sophisticated multi-index architecture for managing conversational and non-conversational memories with semantic search, fact extraction, and intelligent memory lifecycle management.

## Major Architecture Changes

### From Single-Index to Multi-Index Hierarchy

The system has been completely redesigned from a single memory index per container to a **four-index hierarchy**:

1. **Session Index** (`{prefix}-agentic-memory-sessions`)
   - Tracks conversation sessions and their metadata
   - Stores session summaries and lifecycle information
   - Can be disabled with `disableSession: true`

2. **Events Index** (`{prefix}-agentic-memory-events`)
   - Stores immediate messages (CONVERSATION type) and data (DATA type)
   - Supports rich message content including text, images, and binary data
   - Temporary storage before fact extraction

3. **Memories Index** (`{prefix}-agentic-memory-memories`)
   - Stores extracted facts and persistent knowledge
   - Supports KNN (dense), neural sparse, or standard indexing
   - Uses ingest pipelines for automatic embedding generation

4. **Memory Histories Index** (`{prefix}-agentic-memory-memory-histories`)
   - Complete audit trail of memory operations (ADD/UPDATE/DELETE)
   - Tracks before/after states for all changes
   - Can be disabled with `disableHistory: true`

## Memory Container Structure (Refactored)

```java
MLMemoryContainer {
    // Basic Fields (unchanged)
    String name;                 // Container name (required)
    String description;          // Container description (optional)
    User owner;                  // Owner information
    String tenantId;             // Multi-tenancy support
    Instant createdTime;         // Creation timestamp
    Instant lastUpdatedTime;     // Last update timestamp

    // Memory Configuration (formerly MemoryStorageConfig)
    MemoryConfiguration configuration {
        // Index Configuration
        String indexPrefix;              // Prefix for all indices (replaces memoryIndexName)
        Map<String, Map<String, Object>> indexSettings; // Per-index custom settings
        boolean disableHistory;          // Skip history index creation
        boolean disableSession;          // Skip session index creation

        // Model Configuration
        FunctionName embeddingModelType; // TEXT_EMBEDDING or SPARSE_ENCODING
        String embeddingModelId;         // Embedding model ID
        String llmId;                    // LLM model ID (renamed from llmModelId)
        Integer embeddingDimension;      // Vector dimension for TEXT_EMBEDDING
        Integer maxInferSize;            // Max search results

        // Strategy System (NEW)
        List<MemoryStrategy> strategies; // Memory processing strategies

        // Multi-tenancy
        String tenantId;
    }
}
```

## New Components

### MemoryStrategy Class

```java
MemoryStrategy {
    String id;                  // Unique strategy identifier
    boolean enabled;            // Whether strategy is active
    String type;                // Strategy type (e.g., "SEMANTIC")
    List<String> namespace;     // Namespace fields to match
}
```

### ShortTermMemoryType Enum

```java
enum ShortTermMemoryType {
    CONVERSATION("conversation"),  // Chat messages
    DATA("data")                   // Non-conversational data
}
```

## REST API Endpoints

### Core Endpoints
1. `POST /_plugins/_ml/memory_containers/_create` - Create memory container
2. `GET /_plugins/_ml/memory_containers/{memory_container_id}` - Get memory container
3. `POST /_plugins/_ml/memory_containers/{memory_container_id}/memories` - Add memory
4. `POST /_plugins/_ml/memory_containers/{memory_container_id}/memories/_search` - Search memories
5. `DELETE /_plugins/_ml/memory_containers/{memory_container_id}/memories/{memory_id}` - Delete memory
6. `PUT /_plugins/_ml/memory_containers/{memory_container_id}/memories/{memory_id}` - Update memory

## Memory Input Structure (Refactored)

### Old Structure
```java
MLCreateEventInput {
    String memoryContainerId;
    List<MessageInput> messages;
    String sessionId;        // Fixed field
    String agentId;          // Fixed field
    Boolean infer;
    Map<String, String> tags;
}
```

### New Structure
```java
MLCreateEventInput {
    String memoryContainerId;
    ShortTermMemoryType memoryType;  // CONVERSATION or DATA
    List<MessageInput> messages;
    String binaryData;                // Binary data support
    Map<String, Object> structuredData; // JSON object storage
    Map<String, String> namespace;    // Flexible namespace (replaces sessionId/agentId)
    boolean infer;
    Map<String, String> metadata;     // Additional metadata
    Map<String, String> tags;
}
```

## Memory Processing Workflow

### When Adding Conversation Memory with `infer=true`

1. **Session Management**
   - Check if `session_id` exists in namespace
   - Create new session if needed
   - Store session info in session index

2. **Short-term Storage**
   - Save conversation messages to short-term memory index
   - Include namespace, metadata, and tags

3. **Strategy Execution**
   For each configured strategy:
   - Check namespace matching
   - If namespace matches:
     - Invoke LLM to extract facts
     - Search existing memories with same namespace
     - Make memory decisions (ADD/UPDATE/DELETE)
     - Execute operations on long-term memory index
     - Record changes in history index

4. **Response**
   - Return session ID
   - Return short-term memory ID
   - Include operation results

## API Examples

### Create Memory Container (New Format)

```json
POST /_plugins/_ml/memory_containers/_create
{
  "name": "agentic memory test",
  "description": "Multi-strategy memory container",
  "configuration": {
    "index_prefix": "test1",
    "embedding_model_type": "TEXT_EMBEDDING",
    "embedding_model_id": "embedding-model-123",
    "embedding_dimension": 1024,
    "llm_id": "llm-model-456",
    "strategies": [
      {
        "enabled": true,
        "type": "SEMANTIC",
        "namespace": ["user_id"]
      }
    ],
    "index_settings": {
      "short_term_memory_index": {
        "index": {
          "number_of_shards": "2",
          "number_of_replicas": "1"
        }
      }
    },
    "disable_history": false,
    "disable_session": false
  }
}
```

### Add Conversation Memory

```json
POST /_plugins/_ml/memory_containers/{id}/memories
{
  "memory_type": "conversation",
  "messages": [
    {
      "role": "user",
      "content": "I'm Bob, I like swimming."
    }
  ],
  "namespace": {
    "user_id": "bob",
    "session_id": "sess_123"
  },
  "infer": true,
  "tags": {
    "topic": "personal"
  }
}
```

### Add Data Memory (Non-conversational)

```json
POST /_plugins/_ml/memory_containers/{id}/memories
{
  "memory_type": "data",
  "structured_data": {
    "time_range": {
      "start": "2025-09-11",
      "end": "2025-09-15"
    }
  },
  "namespace": {
    "agent_id": "agent_123"
  },
  "infer": false
}
```

## Index Mappings

### Session Index Mapping
```json
{
  "properties": {
    "summary": { "type": "text" },
    "create_time": { "type": "date" },
    "updated_time": { "type": "date" },
    "additional_info": { "type": "flat_object" },
    "owner": USER_MAPPING_PLACEHOLDER,
    "tenant_id": { "type": "keyword" }
  }
}
```

### Short-term Memory Index Mapping
```json
{
  "properties": {
    "memory_container_id": { "type": "keyword" },
    "memory_type": { "type": "keyword" },
    "messages": {
      "type": "object",
      "properties": {
        "role": { "type": "keyword" },
        "content_text": { "type": "text" },
        "content": { "type": "nested" }
      }
    },
    "binary_data": { "type": "binary" },
    "structured_data": { "type": "flat_object" },
    "namespace": { "type": "flat_object" },
    "metadata": { "type": "flat_object" },
    "tags": { "type": "flat_object" },
    "created_time": { "type": "date" },
    "last_updated_time": { "type": "date" }
  }
}
```

### Long-term Memory Index Mapping
Varies based on embedding configuration:
- **KNN Index**: Includes `knn_vector` field for dense embeddings
- **Neural Sparse**: Includes `rank_features` field for sparse embeddings
- **Standard**: Text-only fields for non-semantic storage

### History Index Mapping
```json
{
  "properties": {
    "memory_id": { "type": "keyword" },
    "action": { "type": "keyword" },
    "before": { "type": "object" },
    "after": { "type": "object" },
    "created_time": { "type": "date" }
  }
}
```

## Key Benefits of Refactored Architecture

1. **Scalability**: Separate indices for different memory types enable better performance
2. **Flexibility**: Strategy system allows custom memory processing logic
3. **Namespace-based Organization**: Replace fixed fields with flexible namespace mapping
4. **Auditability**: Complete history tracking of all memory operations
5. **Rich Data Types**: Support for binary data and structured JSON storage
6. **Session Management**: Automatic session tracking and lifecycle
7. **Configurable Indices**: Per-index settings for optimized performance

## Migration Considerations

### From Old to New System

1. **Index Structure**: Migrate from single index to multi-index architecture
2. **Field Mapping**:
   - `sessionId` → `namespace.session_id`
   - `agentId` → `namespace.agent_id`
   - `memoryIndexName` → `indexPrefix`
3. **Configuration**: Update `MemoryStorageConfig` to `MemoryConfiguration`
4. **API Contracts**: Update requests to use namespace instead of fixed fields
5. **Memory Types**: Specify `memory_type` for non-conversational data

## Future Enhancements

1. Additional strategy types beyond SEMANTIC
2. Cross-container memory sharing
3. Advanced aggregations on memory data
4. Memory versioning and branching
5. Real-time memory streaming
6. Memory compression and archival

## Implementation Status

### Completed
- ✅ Four-index architecture implementation
- ✅ Strategy-based memory processing
- ✅ Namespace-based organization
- ✅ Session management
- ✅ Long-term memory extraction
- ✅ History tracking
- ✅ Binary and structured data support
- ✅ Ingest pipeline integration

### In Progress
- 🔄 Test coverage for multi-index operations
- 🔄 Documentation updates
- 🔄 Migration tools

### Planned
- ⏳ Additional strategy implementations
- ⏳ Memory analytics dashboard
- ⏳ Bulk import/export tools