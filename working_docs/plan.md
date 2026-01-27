# Memory Container REST API Implementation

## Overview

This document describes the implemented REST API for creating memory container documents in ML Commons. The memory container stores and manages metadata about memory-related objects with optional semantic search capabilities.

## Memory Container Document Structure

```java
MLMemoryContainer {
    // Basic Fields
    String name;                 // Container name (required, from user input)
    String description;          // Container description (optional)
    User owner;                  // Owner information (nested User object)
    String tenantId;             // Tenant ID for multi-tenancy support
    Instant createdTime;         // Creation timestamp
    Instant lastUpdatedTime;     // Last update timestamp
    
    // Memory Storage Configuration (optional)
    MemoryStorageConfig memoryStorageConfig {
        String memoryIndexName;          // Optional custom memory index name (memory_index_name in JSON)
        boolean semanticStorageEnabled;  // Whether to enable semantic storage (semantic_storage_enabled in JSON)
        FunctionName embeddingModelType; // TEXT_EMBEDDING or SPARSE_ENCODING (embedding_model_type in JSON)
        String embeddingModelId;         // ML Commons model ID for embedding (embedding_model_id in JSON)
        String llmModelId;               // ML Commons model ID for summarization (llm_model_id in JSON)
        Integer dimension;               // Required for TEXT_EMBEDDING (dimension in JSON)
        Integer maxRecentMessages;       // Maximum rounds of conversations to store (default: 6)
        Integer maxInferSize;            // Number of top similar messages to return (default: 5)
    }
    
}
```

## REST API Endpoints

1. `POST /_plugins/_ml/memory_containers/_create` - Create memory container
2. `GET /_plugins/_ml/memory_containers/{memory_container_id}` - Get memory container
3. `POST /_plugins/_ml/memory_containers/{memory_container_id}/memories` - Add memory to container

## Index Structure

### 1. Memory Container Index
- **Index Name**: `.plugins-ml-agentic-memory-container`
- **Purpose**: Stores memory container metadata
- **Type**: System index with access control

### 2. Memory Data Indices
Based on container configuration, one of the following index types will be created:

#### Static Index (Default)
- **Index Name Pattern**: `ml-static-memory-{memoryContainerId}-{userId}`
- **Mapping**:
```json
{
    "mappings": {
        "properties": {
            "user_id": {
                "type": "keyword"
            },
            "agent_id": {
                "type": "keyword"
            },
            "session_id": {
                "type": "keyword"
            },
            "memory": {
                "type": "text"
            },
            "tags": {
                "type": "flat_object"
            },
            "tenant_id": {
                "type": "keyword"
            },
            "memory_type": {
                "type": "keyword"
            },
            "role": {
                "type": "text"
            },
            "created_time": {
                "type": "date",
                "format": "strict_date_time||epoch_millis"
            },
            "last_updated_time": {
                "type": "date",
                "format": "strict_date_time||epoch_millis"
            }
        }
    }
}
```

#### KNN Index (TEXT_EMBEDDING)
- **Index Name Pattern**: `ml-knn-memory-{memoryContainerId}-{userId}`
- **Mapping**:
```json
{
    "settings": {
        "index": {
            "knn": true,
            "knn.algo_param.ef_search": 100
        }
    },
    "mappings": {
        "properties": {
            "user_id": {
                "type": "keyword"
            },
            "agent_id": {
                "type": "keyword"
            },
            "session_id": {
                "type": "keyword"
            },
            "memory": {
                "type": "text"
            },
            "memory_embedding": {
                "type": "knn_vector",
                "dimension": 768,
                "method": {
                    "name": "hnsw",
                    "space_type": "cosinesimil",
                    "engine": "lucene"
                }
            },
            "tags": {
                "type": "flat_object"
            },
            "tenant_id": {
                "type": "keyword"
            },
            "memory_type": {
                "type": "keyword"
            },
            "role": {
                "type": "text"
            },
            "created_time": {
                "type": "date",
                "format": "strict_date_time||epoch_millis"
            },
            "last_updated_time": {
                "type": "date",
                "format": "strict_date_time||epoch_millis"
            }
        }
    }
}
```

#### Sparse Index (SPARSE_ENCODING)
- **Index Name Pattern**: `ml-sparse-memory-{memoryContainerId}-{userId}`
- **Mapping**:
```json
{
    "mappings": {
        "properties": {
            "user_id": {
                "type": "keyword"
            },
            "agent_id": {
                "type": "keyword"
            },
            "session_id": {
                "type": "keyword"
            },
            "memory": {
                "type": "text"
            },
            "memory_embedding": {
                "type": "rank_features"
            },
            "tags": {
                "type": "flat_object"
            },
            "tenant_id": {
                "type": "keyword"
            },
            "memory_type": {
                "type": "keyword"
            },
            "role": {
                "type": "text"
            },
            "created_time": {
                "type": "date",
                "format": "strict_date_time||epoch_millis"
            },
            "last_updated_time": {
                "type": "date",
                "format": "strict_date_time||epoch_millis"
            }
        }
    }
}
```

## Implementation Files (Completed)

### 1. Data Model (Common Module)
- ✅ `common/src/main/java/org/opensearch/ml/common/memorycontainer/MLMemoryContainer.java` - Persisted entity with system metadata
- ✅ `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryStorageConfig.java` - Configuration for memory storage (renamed from SemanticStorageConfig)
- ✅ `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java` - Constants and field names

### 2. Transport Classes (Common Module)
- ✅ `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/MLCreateMemoryContainerAction.java` - Action type definition
- ✅ `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/MLCreateMemoryContainerInput.java` - User input data model with required containerName
- ✅ `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/MLCreateMemoryContainerRequest.java` - Request wrapper
- ✅ `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/MLCreateMemoryContainerResponse.java` - Response with container ID

### 3. Transport Action (Plugin Module)
- ✅ `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/TransportCreateMemoryContainerAction.java`

### 4. REST Handler (Plugin Module)
- ✅ `plugin/src/main/java/org/opensearch/ml/rest/RestMLCreateMemoryContainerAction.java`

### 5. Index Mapping File
- ✅ `common/src/main/resources/index-mappings/ml_memory_container.json`

### 6. Updates to Existing Files
- ✅ `common/src/main/java/org/opensearch/ml/common/CommonValue.java`
  - Added `ML_MEMORY_CONTAINER_INDEX = ".plugins-ml-agentic-memory-container"`
  - Added `ML_MEMORY_CONTAINER_INDEX_MAPPING_PATH = "index-mappings/ml_memory_container.json"`
- ✅ `common/src/main/java/org/opensearch/ml/common/MLIndex.java`
  - Added `MEMORY_CONTAINER` enum entry
- ✅ `plugin/src/main/java/org/opensearch/ml/plugin/MachineLearningPlugin.java`
  - Added `RestMLCreateMemoryContainerAction` to `getRestHandlers()`
  - Added `MLCreateMemoryContainerAction` to `getActions()`

## API Examples

### 1. Create Basic Memory Container
```bash
POST /_plugins/_ml/memory_containers/_create
{
    "name": "conversation_history",
    "description": "Store raw conversation history"
}
```

Response:
```json
{
    "memory_container_id": "abc123",
    "status": "created"
}
```

### 2. Create Container with KNN Semantic Storage
```bash
POST /_plugins/_ml/memory_containers/_create
{
    "name": "semantic_conversations",
    "description": "Store conversations with semantic search",
    "memory_storage_config": {
        "semantic_storage_enabled": true,
        "embedding_model_type": "TEXT_EMBEDDING",
        "embedding_model_id": "embedding-model-456",
        "llm_model_id": "gpt-model-123",
        "dimension": 768
    }
}
```

### 3. Create Container with Sparse Encoding
```bash
POST /_plugins/_ml/memory_containers/_create
{
    "name": "sparse_search_memory",
    "description": "Store conversations with sparse search",
    "memory_storage_config": {
        "memory_index_name": "my-custom-memory-index",
        "semantic_storage_enabled": true,
        "embedding_model_type": "SPARSE_ENCODING",
        "embedding_model_id": "sparse-model-789",
        "llm_model_id": "summarization-model-321"
    }
}
```

## Key Implementation Details

### 1. MLIndicesHandler Integration
- Reuses existing `MLIndicesHandler` for creating the memory container index
- Creates custom memory data indices based on semantic storage configuration
- Uses `initMLIndexIfAbsent()` for system index initialization

### 2. Security
- Owner information stored using USER_MAPPING_PLACEHOLDER pattern
- Backend role-based access control through nested User object
- Multi-tenancy support with tenant ID validation using TenantAwareHelper
- User permissions extracted from thread context

### 3. Validation Rules
- `name` is required (validated in MLCreateMemoryContainerInput constructor)
- If `semantic_storage_enabled` is true:
  - `embedding_model_type` is required (must be TEXT_EMBEDDING or SPARSE_ENCODING)
  - `embedding_model_id` is required
  - `llm_model_id` is required
  - For `TEXT_EMBEDDING`: `dimension` is required
  - For `SPARSE_ENCODING`: `dimension` is not allowed

### 4. Memory Container ID Handling
- The `memory_container_id` is not stored within the MLMemoryContainer document itself
- OpenSearch document ID serves as the container ID (avoiding redundancy)
- CREATE API returns the generated container ID in the response
- GET API uses the container ID from the URL path but doesn't return it in the response body

### 5. Index Creation Flow
1. Initialize memory container index if it doesn't exist
2. Let OpenSearch auto-generate container ID (aligned with other entities like agents, connectors, models)
3. Create memory container document in `.plugins-ml-agentic-memory-container`
4. Based on memory storage config:
   - No config or semantic storage disabled → Create static index
   - TEXT_EMBEDDING → Create KNN index with specified dimension
   - SPARSE_ENCODING → Create sparse index with rank_feature mapping
5. If custom `memory_index_name` provided in config, use it; otherwise use default pattern
6. All index names are converted to lowercase (OpenSearch requirement)
7. Store the actual index name (provided or generated) in the memory container document

## Implementation Status

### Refactored Architecture (Commit b52818ee4)
- ✅ Four-index architecture (agentic-memory-sessions, agentic-memory-events, agentic-memory-memories, agentic-memory-memory-histories)
- ✅ Strategy-based memory processing system
- ✅ Namespace-based flexible organization
- ✅ Support for conversational and non-conversational data
- ✅ Binary data and structured JSON storage
- ✅ Complete audit trail with history tracking
- ✅ Ingest pipeline integration for embeddings
- ✅ Session management and lifecycle

### In Progress
- 🔄 Test coverage for multi-index operations
- 🔄 Documentation updates
- 🔄 Migration tools from single to multi-index

### Planned
- ⏳ Additional strategy implementations
- ⏳ Memory analytics dashboard
- ⏳ Bulk import/export tools

## Completed: GET Memory Container API

### GET API Implementation
`GET /_plugins/_ml/memory_containers/{memory_container_id}`

### Implementation Files

#### 1. Transport Classes (Common Module)
- ✅ `MLMemoryContainerGetAction.java` - Action type definition
- ✅ `MLMemoryContainerGetRequest.java` - Request with memory container ID and tenant ID validation
- ✅ `MLMemoryContainerGetResponse.java` - Response containing MLMemoryContainer

#### 2. REST Handler (Plugin Module)
- ✅ `RestMLGetMemoryContainerAction.java` - REST endpoint handler

#### 3. Transport Action (Plugin Module)
- ✅ `TransportGetMemoryContainerAction.java` - Business logic with access control

#### 4. Supporting Files Updated
- ✅ `MemoryContainerConstants.java` - Added PARAMETER_MEMORY_CONTAINER_ID constant
- ✅ `MachineLearningPlugin.java` - Registered GET action and REST handler

### GET API Example
```bash
GET /_plugins/_ml/memory_containers/abc123
```

Response:
```json
{
  "name": "conversation_history",
  "description": "Store raw conversation history",
  "owner": {
    "name": "admin",
    "backend_roles": ["admin"],
    "roles": ["all_access"]
  },
  "created_time": 1706745600000,
  "last_updated_time": 1706745600000,
  "memory_storage_config": {
    "memory_index_name": "ml-static-memory-abc123-admin",
    "semantic_storage_enabled": false
  }
}
```

### Security Implementation
- **Tenant validation**: Ensures request tenant matches container tenant
- **Access control**: 
  - Allows admin users (with "all_access" role)
  - Allows the container owner
  - Allows users with matching backend roles
- **Error handling**: 
  - 404 NOT_FOUND when container doesn't exist
  - 403 FORBIDDEN when user lacks permissions
  - 400 BAD_REQUEST when container ID is missing

## Completed: Memory Container Enhancement - LLM Model Support

### Enhancement Overview
Added support for LLM model ID to enable memory summarization capabilities, with field refactoring for clarity:
- ✅ Renamed `model_id` → `embedding_model_id`
- ✅ Renamed `model_type` → `embedding_model_type`
- ✅ Added new required field: `llm_model_id` (within SemanticStorageConfig)

### Updated MemoryStorageConfig Structure
```json
{
  "memory_index_name": "custom-memory-index",  // optional custom index name
  "semantic_storage_enabled": true,
  "embedding_model_type": "TEXT_EMBEDDING",  // renamed from modelType
  "embedding_model_id": "embedding-model-456", // renamed from modelId
  "llm_model_id": "gpt-model-123",  // REQUIRED field for summarization
  "dimension": 768
}
```

### Implementation Steps (Completed)
1. ✅ Updated field constants in MemoryContainerConstants.java
2. ✅ Refactored SemanticStorageConfig with new field names and llmModelId
3. ✅ Renamed SemanticStorageConfig to MemoryStorageConfig for more generic configuration
4. ✅ Moved memoryIndexName field into MemoryStorageConfig
5. ✅ Changed field name from semantic_storage to memory_storage_config in API
6. ✅ Updated index mapping in ml_memory_container.json
7. ✅ Updated all references in TransportCreateMemoryContainerAction
8. ✅ Updated validation to make llmModelId required when semantic storage is enabled
9. ✅ Updated API documentation and examples
10. ✅ Renamed createMemoryContainer method to buildMemoryContainer for clarity

## Completed: GET API Enhancement - Return Actual Index Name

### Problem
When users create a memory container without specifying an index name, the system generates a default index name but doesn't store it in the MLMemoryContainer document. This causes the GET API to return `indexName: null` even though an index was created.

### Solution
Refactored the CREATE flow to capture and persist the generated index name:

1. **Modified createMemoryDataIndices method**:
   - ✅ Changed return type from `ActionListener<Boolean>` to `ActionListener<String>`
   - ✅ Returns the actual index name that was created

2. **Updated the main flow**:
   - ✅ After generating the index name, update the memoryContainer object
   - ✅ Set the actual index name before persisting to the database

3. **Implementation Details**:
   - ✅ Changed createMemoryDataIndices to return the generated index name
   - ✅ Updated the ActionListener chain to pass the index name
   - ✅ Update/create MemoryStorageConfig with the actual index name before indexing
   - ✅ Verified MLMemoryContainer and MemoryStorageConfig have @Setter annotation for all fields

## Completed: Memory Container API Field Enhancements

### Latest Enhancements (Commits: 73f5c63d0 and d0f66df02)

#### 1. KNN Index Configuration Optimization
- ✅ Removed "parameters" field from KNN index method configuration
- ✅ Now uses OpenSearch default values for ef_construction and m parameters
- ✅ Maintains ef_search setting at index level for query-time performance

#### 2. New Memory Storage Configuration Fields
- ✅ Added `max_recent_messages` field (default: 6)
  - Controls maximum rounds of conversations to store
  - Configurable per memory container
- ✅ Added `max_infer_size` field (default: 5)
  - Controls number of top similar messages to return
  - Used during semantic search operations

#### 3. Implementation Details
- ✅ Constants added with default values in MemoryContainerConstants
  - `MAX_RECENT_MESSAGES_DEFAULT_VALUE = 6`
  - `MAX_INFER_SIZE_DEFAULT_VALUE = 5`
- ✅ Fields use @Builder.Default annotation for proper initialization
- ✅ Updated index mapping to include new fields
- ✅ All field names follow snake_case convention in JSON

### Updated API Example (New Multi-Index Format)
```bash
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


## Completed: Memory Storage Config Validation Restrictions

### Validation Rules Implementation

#### Field Restrictions Based on Semantic Storage
1. **When `semantic_storage_enabled` is false**:
   - Only `max_recent_messages` and `memory_index_name` fields are allowed
   - Other fields (embedding_model_type, embedding_model_id, llm_model_id, dimension, max_infer_size) are cleared/ignored
   - `max_recent_messages` upper limit: 100
   - GET API returns null for restricted fields

2. **When `semantic_storage_enabled` is true**:
   - All fields are allowed
   - `max_recent_messages` upper limit: 10
   - Existing validation for required fields applies

3. **General Limits**:
   - `max_infer_size` upper limit: 10 (regardless of semantic storage setting)

#### Implementation Details
- ✅ Added validation method in MemoryStorageConfig class
- ✅ Added new error constants:
  - `MAX_RECENT_MESSAGES_SEMANTIC_LIMIT_ERROR`
  - `MAX_RECENT_MESSAGES_STATIC_LIMIT_ERROR`
  - `MAX_INFER_SIZE_LIMIT_ERROR`
  - `FIELD_NOT_ALLOWED_SEMANTIC_DISABLED_ERROR`
- ✅ Updated constructor and parse method to enforce validation
- ✅ Updated toXContent to only output allowed fields
- ✅ Updated TransportCreateMemoryContainerAction to validate before creation

## Model Validation Enhancement

### Model Validation Requirements
During memory container creation, the system validates that specified models exist and have the correct types:

1. **LLM Model Validation**:
   - Must exist in the ML Commons system
   - Must be a REMOTE model (local models are not supported)
   - Returns clear error if model not found or wrong type

2. **Embedding Model Validation**:
   - Must exist in the ML Commons system
   - Must be either:
     - The specified embedding type (TEXT_EMBEDDING or SPARSE_ENCODING), OR
     - A REMOTE model (which can handle any embedding type)
   - Returns clear error if model not found or type mismatch

### Implementation Details
- ✅ Added MLModelManager dependency to TransportCreateMemoryContainerAction
- ✅ Created validateModels() and validateEmbeddingModel() methods
- ✅ Validation is performed asynchronously before container creation
- ✅ Added error constants:
  - `LLM_MODEL_NOT_FOUND_ERROR`
  - `LLM_MODEL_NOT_REMOTE_ERROR`
  - `EMBEDDING_MODEL_NOT_FOUND_ERROR`
  - `EMBEDDING_MODEL_TYPE_MISMATCH_ERROR`

## Completed: ADD Memory API Implementation

### Overview
Implemented a REST API endpoint `POST /_plugins/_ml/memory_containers/{memory_container_id}/memories` to add memory messages to memory containers with automatic circular buffer management based on max_recent_messages.

### Key Features
1. **Memory Type Support**: Currently supports RAW_MESSAGE type
2. **Automatic ID Generation**:
   - memory_id: Uses provided ID or generates new UUID (serves as unique message identifier)
   - session_id: Reuses existing session_id for a memory_id or generates "sess_" + UUID
3. **Circular Buffer**: Automatically deletes oldest messages in a session when max_recent_messages limit is exceeded
   - Counts messages per session_id (conversation)
   - Maintains conversation history within configured limits
4. **Simplified Security**: Only validates container access, no tenant control at memory level

### Implementation Files

#### 1. Common Module - Data Models
- ✅ `MLCreateEventInput.java` - User input with message (API field), memory_type, and optional IDs
- ✅ `MLCreateEventRequest.java` - Transport request wrapper
- ✅ `MLCreateEventResponse.java` - Response with memory_id, session_id
- ✅ `MLCreateEventAction.java` - Action type definition

#### 2. Plugin Module - Implementation
- ✅ `TransportCreateEventAction.java` - Core business logic with circular buffer
- ✅ `RestMLCreateEventAction.java` - REST endpoint handler

#### 3. Constants Added
- ✅ `MEMORY_FIELD = "memory"`
- ✅ `MEMORY_EMBEDDING_FIELD = "memory_embedding"`
- ✅ `CREATED_TIME_FIELD = "created_time"` (reused for memory data)
- ✅ `LAST_UPDATED_TIME_FIELD = "last_updated_time"` (reused for memory data)
- ✅ `MEMORY_TYPE_RAW_MESSAGE = "RAW_MESSAGE"`
- ✅ `MEMORIES_PATH = "/_plugins/_ml/memory_containers/{memory_container_id}/memories"`

### API Example
```bash
POST /_plugins/_ml/memory_containers/abc123/memories
{
    "message": "What is machine learning?",
    "memory_type": "RAW_MESSAGE",
    "memory_id": "mem_456",      # Optional - unique memory/message identifier
    "session_id": "sess_789",    # Optional
    "agent_id": "agent_123",     # Optional
    "role": "human",             # Optional - specifies if memory belongs to human or llm
    "tags": {                    # Optional
        "topic": "ML basics"
    }
}

Response:
{
    "memory_id": "mem_456",
    "session_id": "sess_789",
    "status": "created"
}
```

### Technical Implementation Details

#### ID Management Logic
1. If memory_id is not provided, generate new UUID (serves as unique message identifier)
2. If session_id is not provided:
   - If memory_id was provided, search for its existing session_id
   - Otherwise generate new session_id

#### Circular Buffer Implementation
1. After successfully indexing new message
2. Count total messages with same session_id (conversation)
3. If count > max_recent_messages:
   - Search for oldest messages in the session (sorted by created_time ASC)
   - Delete excess messages using bulk delete
4. Return success even if deletion fails (message was added successfully)

## Recent Changes

### Field Updates
1. **Removed Fields**:
   - `model_ids_monitoring` from memory container (was used to specify which models to monitor)
   - `model_id_monitoring` from memory data indices
   - `tenant_id` from memory data indices (multi-tenancy now only at container level)
   - Imported `TENANT_ID_FIELD` from CommonValue to avoid redundancy

2. **Recent Field Refactoring** (from latest changes):
   - `raw_messages` → `memory`: More generic name for memory content
   - `embedding` → `memory_embedding`: More descriptive name
   - `timestamp` → `created_time` and `last_updated_time`: More specific timestamps
   - Removed `fact` field from non-semantic storage
   - Consolidated `message_id` and `memory_id`: Now memory_id serves as unique message identifier
   - API request field: `message` → stored as `memory` in index (API accepts "message" but stores as "memory")

## Recent Updates: Memory Type Enum and Infer Field

### Memory Type Enhancement
1. **Created MemoryType Enum**:
   - `RAW_MESSAGE`: Existing type for raw conversation messages
   - `FACT`: New type for future use (structured facts/knowledge)
   - Replaced string constants with type-safe enum

2. **Created MemoryCharacteristic Enum**:
   - `SHORT_TERM`: Memories subject to circular buffer deletion
   - `LONG_TERM`: Memories preserved regardless of count
   - Provides type safety for memory characteristics

3. **API Contract Revision**:
   - `memory_type` is now optional (defaults based on infer flag)
   - `memory_characteristic` removed from user input (auto-determined)
   - Added `infer` boolean field with specific behavior:
     - Semantic storage enabled: defaults to true, can be set to false
     - Semantic storage disabled: always false, throws exception if user tries to set true
   - Auto-determination logic:
     - infer = false: memory_type = RAW_MESSAGE, memory_characteristic = LONG_TERM
     - infer = true: memory_type = RAW_MESSAGE, memory_characteristic = SHORT_TERM

4. **Validation Changes**:
   - Removed max_short_term_memories restriction for semantic storage disabled
     - Since all memories are LONG_TERM when semantic storage is disabled, the limit doesn't apply
   - When infer = false, only RAW_MESSAGE type is allowed
   - Clear error message when trying to set infer = true in non-semantic storage

### Updated Add Memory API Example (Namespace-based Format)
```bash
# Add Conversation Memory
POST /_plugins/_ml/memory_containers/{id}/memories
{
    "memory_type": "conversation",
    "messages": [
        {"role": "user", "content": "I'm Bob, I like swimming."}
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

# Add Non-Conversational Data
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
# Result: memory_type = RAW_MESSAGE
# Note: role is optional when infer=true
```

## Memory Data Model Enhancement

### MLMemory Class
Created a dedicated `MLMemory` class to encapsulate memory data:

1. **Location**: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MLMemory.java`

2. **Key Features**:
   - Type-safe representation of memory entries
   - Supports serialization/deserialization (StreamInput/Output, XContent)
   - Provides `toIndexMap()` method for OpenSearch indexing
   - Uses builder pattern for flexible construction

3. **Fields**:
   - Core fields: memoryId, sessionId, memory, memoryType, memoryCharacteristic
   - Optional fields: userId, agentId, role, tags
   - System fields: createdTime, lastUpdatedTime
   - Embedding field: memoryEmbedding (for semantic storage)

4. **Benefits**:
   - Replaces untyped `Map<String, Object>` usage
   - Enables consistent data handling across memory operations
   - Facilitates future API development (search, update, delete)
   - Improves maintainability and reduces errors

### Folder Structure Reorganization
Organized memory-related classes under memorycontainer to avoid conflicts:

1. **Transport Classes**: `common/transport/memorycontainer/memory/`
   - MLCreateEventAction
   - MLCreateEventInput
   - MLCreateEventRequest
   - MLCreateEventResponse

2. **Action Classes**: `plugin/action/memorycontainer/memory/`
   - TransportCreateEventAction

3. **Benefits**:
   - Clear separation from existing memory APIs
   - Logical grouping of related functionality
   - Easier navigation and maintenance

## Recent Refactoring and Improvements

### 1. Removed memory_id from Index Mapping
- Memory ID is now stored as the OpenSearch document ID
- Removed redundant storage of memory_id as a field within documents
- Updated searchSessionId to use GET by document ID instead of search query
- Follows the same pattern as memory_container_id

### 2. Fixed Field Types for Exact Matching
- Changed user_id, agent_id, session_id from "text" to "keyword" type
- Enables exact matching with termQuery instead of matchQuery
- Removed misleading backward compatibility comments

### 3. KNN Engine Update
- Changed KNN engine from "faiss" to "lucene"
- Better compatibility with OpenSearch without additional native libraries
- No functional changes to the API

### 4. Fixed Sparse Embedding Field Type
- Changed from "rank_feature" to "rank_features" (plural)
- Properly supports sparse vector storage as key-value pairs

### 5. Simplified Embedding Extraction
- Dense embeddings now directly look for "sentence_embedding" tensor
- Removed complex dimension checking and tensor filtering logic
- 50% reduction in code complexity while maintaining functionality

### 6. Code Cleanup
- Fixed usage of INFER_FIELD constant in MLCreateEventInput
- All constants in MemoryContainerConstants are actively used
- No orphaned or unused constants found

### 7. Role Field Addition
- Added `role` field to MLMemory model to specify if memory belongs to human or LLM
- Field type: text (allows values like "human", "llm", "assistant", etc.)
- Added to all relevant components: MLMemory, MLCreateEventInput, index mapping, and TransportCreateEventAction
- Optional field that can be specified when adding memories via the API

### 8. Messages API Refactor
- Changed from single `message` string to `messages` array format
- Each message contains `role` and `content` fields
- Removed user-provided memory_id (now auto-generated by OpenSearch)
- Currently limited to one message per request for testing/performance
- The API accepts "messages" but internally stores as "memory" field

### 9. Semantic Storage Decoupling
- Made `semantic_storage_enabled` a passive field auto-determined by embedding configuration
- Users no longer specify `semantic_storage_enabled` in requests
- Decoupled LLM model from semantic storage (LLM is now optional)
- Changed `infer` validation to depend on LLM model presence instead of semantic storage
- Updated error messages to reflect new validation rules

## Semantic Storage Embedding Feature

### Automatic Embedding Generation
Implemented automatic embedding generation for semantic storage:

1. **Embedding Generation Process**:
   - When semantic storage is enabled, messages are automatically embedded
   - Uses the configured embedding model (TEXT_EMBEDDING or SPARSE_ENCODING)
   - Embeddings are generated regardless of infer flag value
   - Generated embeddings are stored in the memory_embedding field

2. **Implementation Details**:
   - Added `generateEmbedding()` method in TransportCreateEventAction
   - Creates MLInput with TextDocsInputDataSet containing the message
   - Invokes ML prediction API using the configured embedding model
   - Extracts embedding from ModelTensorOutput

3. **Embedding Types Support**:
   - TEXT_EMBEDDING: Dense vectors stored as float arrays
   - SPARSE_ENCODING: Sparse vectors stored as Map<String, Float>

4. **Error Handling**:
   - If embedding generation fails, memory is still saved without embedding
   - Ensures data preservation even when embedding service is unavailable
   - Errors are logged but don't block memory storage

5. **Embedding Generation Independence**:
   - Embeddings are generated regardless of infer value
   - All memories benefit from semantic search capabilities
   - The infer flag only controls LLM processing, not persistence

## Recent Refactoring: Removal of Memory Characteristics and Auto-Cleanup

### Overview
Removed the memory characteristic concept (SHORT_TERM/LONG_TERM) and abandoned the auto-cleanup logic that automatically deleted old memories based on limits. This simplifies the system by treating all memories equally without automatic deletion.

### Changes Made

#### 1. Removed Components
- **MemoryCharacteristic Enum**: Deleted the enum that defined SHORT_TERM and LONG_TERM memory types
- **Auto-cleanup Logic**: Removed circular buffer implementation that deleted oldest memories
- **max_short_term_memories Field**: Removed from MemoryStorageConfig as it's no longer needed
- **checkAndDeleteOldShortTermMemories() method**: Removed from TransportCreateEventAction
- **getMaxShortTermMemories() method**: Removed from TransportCreateEventAction

#### 2. Updated MLMemory Model
- Removed `memoryCharacteristic` field
- Simplified constructor and builder
- Updated serialization/deserialization methods
- Removed from index mapping

#### 3. Updated MemoryStorageConfig
- Removed `maxShortTermMemories` field and validation
- Removed related error constants
- Simplified validation logic

#### 4. Simplified TransportCreateEventAction
- Removed `checkAndDeleteOldShortTermMemories()` method
- Removed `getMaxShortTermMemories()` method
- Removed memory characteristic assignment based on `infer` field
- Simplified indexing logic - now just indexes the memory without cleanup

#### 5. Updated Index Mapping
- Removed `memory_characteristic` field from memory data index properties

### Benefits
1. **Simpler Logic**: No complex cleanup operations or memory type distinctions
2. **Better Performance**: No need to count and delete memories on each add operation
3. **More Predictable**: Users have full control over their memories without automatic deletions
4. **Cleaner Code**: Significant reduction in code complexity

### Impact
The `infer` field still exists but now only controls whether to process the message with the LLM model, without affecting memory persistence behavior. All memories are stored permanently until explicitly deleted by the user.

## API Contract Summary (Current State)

### Create Memory Container
```json
{
    "name": "required_name",
    "description": "optional",
    "memory_storage_config": {
        "memory_index_name": "optional_custom_name",
        // semantic_storage_enabled is auto-determined, not user-specified
        "embedding_model_type": "TEXT_EMBEDDING or SPARSE_ENCODING",
        "embedding_model_id": "model-id",
        "llm_model_id": "optional-llm-id",
        "dimension": 768,  // required for TEXT_EMBEDDING
        "max_infer_size": 5  // max: 10
    }
}
```

### Add Memory
```json
{
    "messages": [
        {
            "role": "user",  // optional when infer=true
            "content": "message content"  // required
        }
    ],
    "infer": true,  // defaults based on llm_model_id presence
    "session_id": "sess_123",  // auto-generated if not provided
    "agent_id": "agent_456",  // optional
    "tags": {  // optional
        "key": "value"
    }
}
```

## Recent Refactoring: Embedding Generation Following MLCommonsClientAccessor Pattern

### Overview
Refactored the embedding generation in TransportCreateEventAction to follow the MLCommonsClientAccessor pattern for better readability and maintainability.

### Changes Made

#### 1. TransportCreateEventAction Refactoring
- Removed retry logic (not needed for ML calling ML - if there's an error, retry won't help)
- Updated `generateEmbedding` method to directly use `client.execute(MLPredictionTaskAction.INSTANCE, ...)`
- Created `buildDenseEmbeddingFromResponse()` method for TEXT_EMBEDDING extraction
- Created `buildSparseEmbeddingFromResponse()` method for SPARSE_ENCODING extraction
- Removed the previous `extractEmbedding()` and `executePredictionWithRetry()` methods

#### 2. Benefits
- **Simpler Code**: No unnecessary retry complexity
- **Better Separation**: Separate methods for dense and sparse embedding extraction
- **Follows Patterns**: Consistent with MLCommonsClientAccessor approach
- **Direct Client Usage**: Uses the same `client.execute()` pattern as MachineLearningNodeClient
- **Maintainability**: Cleaner code structure that's easier to understand and modify

#### 3. Implementation Details
- Direct call to `client.execute(MLPredictionTaskAction.INSTANCE, predictionRequest, ...)` 
- Dense embeddings: Look for "sentence_embedding" tensor and convert to float[]
- Sparse embeddings: Extract from dataAsMap with nested response handling
- Error handling: Log errors and return null on failure (allows saving memory without embedding)

## LLM Integration for Fact Extraction

### Overview
When `infer=true`, the system uses the configured LLM model to extract structured facts from user messages. This enables intelligent memory organization beyond raw message storage.

### LLM Request Structure
When `infer=true`, messages are transformed into the following format for LLM processing:

```json
{
  "parameters": {
    "system_prompt": "<system_prompt><role>Personal Information Organizer</role>...[Full XML prompt]...</system_prompt>",
    "messages": [
      {
        "role": "user",  // or "assistant", defaults to "user" if not specified
        "content": [
          {
            "type": "text",
            "text": "Hi, my name is John. I am a software engineer."
          }
        ]
      }
    ]
  }
}
```

### LLM Response Format
The LLM returns extracted facts in JSON format:

```json
{
  "facts": [
    "Name is John",
    "Is a Software engineer"
  ]
}
```

### Memory Storage Strategy

When `infer=true`, each message results in multiple memory entries:

#### 1. RAW_MESSAGE Entry (Always Created)
- `memory_type`: RAW_MESSAGE
- `role`: From input message (default: "user")
- `memory`: Original message content
- `memory_embedding`: Generated if semantic storage is enabled
- All other fields preserved (sessionId, userId, agentId, tags)

#### 2. FACT Entries (One per Extracted Fact)
- `memory_type`: FACT
- `role`: null (facts don't have roles)
- `memory`: Individual fact text
- `memory_embedding`: Generated if semantic storage is enabled
- All other fields preserved (sessionId, userId, agentId, tags)

### Implementation Flow

```
if (infer == true && llmModelId != null) {
    1. Call LLM to extract facts
    2. Parse JSON response to get facts array
    3. Create memory entries:
       - One RAW_MESSAGE with original content
       - Multiple FACT entries (one per extracted fact)
    4. Generate embeddings for all entries (if enabled)
    5. Bulk index all memories
    6. Return all memory IDs and fact count
} else {
    // Existing flow - just save RAW_MESSAGE
}
```

### Error Handling
- **LLM Failure**: Log error but still save RAW_MESSAGE
- **JSON Parse Error**: Treat as empty facts array
- **Embedding Failure**: Save memories without embeddings

## SDK Client Migration for Bulk Operations

### Overview
Memory operations have been migrated from traditional OpenSearch client bulk operations to the new SDK client pattern for better consistency and async handling.

### Migration Pattern
Following commit 190b2dfc141a6709e18dcd6ca531cb3402877fa5, all bulk operations now use:
- `sdkClient.bulkDataObjectAsync()` instead of `client.bulk()`
- CompletableFuture pattern for async operations
- Proper error unwrapping with `SdkClientUtils`

### Request Class Mappings
| Old Class | New Class |
|-----------|-----------|
| `IndexRequest` | `PutDataObjectRequest` |
| `UpdateRequest` | `UpdateDataObjectRequest` |
| `DeleteRequest` | `DeleteDataObjectRequest` |
| `BulkRequest` | `BulkDataObjectRequest` |

### Implementation Example
```java
// Old pattern
BulkRequest bulkRequest = new BulkRequest();
client.bulk(bulkRequest, ActionListener.wrap(response -> {...}, error -> {...}));

// New pattern
BulkDataObjectRequest bulkRequest = BulkDataObjectRequest.builder()
    .globalIndex(indexName)
    .build();
sdkClient.bulkDataObjectAsync(bulkRequest).whenComplete((response, exception) -> {
    if (exception != null) {
        Exception cause = SdkClientUtils.unwrapAndConvertToException(exception, OpenSearchException.class);
        listener.onFailure(cause);
        return;
    }
    BulkResponse bulkResponse = BulkResponse.fromXContent(response.parser());
    // Process response...
});
```

### Benefits
1. **Consistency**: Aligns with OpenSearch's recommended SDK patterns
2. **Better Error Handling**: Proper exception unwrapping and conversion
3. **Improved Async**: CompletableFuture provides better async composition
4. **Future-Proof**: Ready for upcoming OpenSearch client improvements

### API Response Format Update

When `infer=true`, the response includes only FACT memories in a results array:

```json
{
  "results": [
    {
      "memory_id": "generated_id_1",
      "memory": "Had a meeting with John at 3pm",
      "event": "ADD"
    },
    {
      "memory_id": "generated_id_2", 
      "memory": "Discussed the new project",
      "event": "ADD"
    }
  ],
  "session_id": "sess_123"
}
```

When `infer=false`, the response includes the single stored message:

```json
{
  "results": [
    {
      "memory_id": "generated_id",
      "memory": "Yesterday, I had a meeting with John at 3pm",
      "event": "ADD"
    }
  ],
  "session_id": "sess_123"
}
```

**Note:** RAW_MESSAGE entries are always stored internally but not included in the response when `infer=true`.

### Example Workflow

**Input Message:**
```json
{
  "messages": [{
    "role": "user",
    "content": "Yesterday, I had a meeting with John at 3pm. We discussed the new project."
  }],
  "infer": true,
  "session_id": "sess_123"
}
```

**LLM Extraction:**
```json
{
  "facts": [
    "Had a meeting with John at 3pm",
    "Discussed the new project"
  ]
}
```

**Created Memories:**
1. RAW_MESSAGE: "Yesterday, I had a meeting with John at 3pm. We discussed the new project." (role: user)
2. FACT: "Had a meeting with John at 3pm" (role: null)
3. FACT: "Discussed the new project" (role: null)

**Response:**
```json
{
  "memory_ids": ["mem_001", "mem_002", "mem_003"],
  "session_id": "sess_123",
  "status": "created",
  "fact_count": 2
}
```

## Implementation Updates and Lessons Learned

### Recent Changes

#### 1. LLM Request Format Fix
- Changed from direct parameters object to wrapped format: `{parameters: {system_prompt, messages}}`
- Messages array uses structured format: `[{role, content: [{type: "text", text: "..."}]}]`
- Parameters must be `Map<String, String>` for RemoteInferenceInputDataSet, requiring JSON serialization of complex objects

#### 2. LLM Response Parsing Fix
- LLM response structure differs from initial assumptions
- Facts are in `dataMap.content[0].text` not `dataMap.response`
- Response structure: `{content: [{type: "text", text: "{\"facts\": [...]}"}], ...}`
- Added comprehensive logging to debug response structure issues

#### 3. Validation Enhancements
- **Role Validation**: When `infer=false`, role field is mandatory
- **Model State Validation**: Non-REMOTE embedding models must be in DEPLOYED state
- Added MLModelManager dependency for runtime model validation

#### 4. Response Format Update
- Changed from single memory_id/status to results array format
- Created MemoryResult class for individual results
- When `infer=true`: Only FACT memories in response
- When `infer=false`: Single stored message in response

#### 5. API Naming Refactoring
- Refactored all "AddMemory" classes to use plural "AddMemories" naming convention
- Changes include:
  - `MLAddMemoryAction` → `MLCreateEventAction`
  - `MLAddMemoryInput` → `MLCreateEventInput`
  - `MLAddMemoryRequest` → `MLCreateEventRequest`
  - `MLAddMemoryResponse` → `MLCreateEventResponse`
  - `TransportAddMemoryAction` → `TransportCreateEventAction`
  - `RestMLAddMemoryAction` → `RestMLCreateEventAction`
- This better reflects that the API accepts a messages array (even though currently limited to one message)

#### 6. Error Handling Enhancement
- Created `MemoryEvent` enum with ADD, UPDATE, DELETE, NONE values for type safety
- Updated `MemoryResult` to use `MemoryEvent` enum instead of String
- Enhanced error handling strategy:
  - **When infer=true**: Any failure in LLM call or fact parsing throws exception immediately (no partial storage)
  - **When infer=false**: Any failure propagates as exception
  - LLM failures throw `OpenSearchException` with detailed error messages
  - Parsing failures throw `IllegalArgumentException` with LLM response content
  - Embedding failures throw appropriate exceptions instead of silent fallback
- No custom exception classes - uses standard OpenSearch/Java exceptions

### Key Takeaways

1. **API Integration Complexity**: Remote model APIs have specific request/response formats that may differ from documentation
2. **Type Safety**: Java's type system requires careful handling of Map<String, Object> to Map<String, String> conversions
3. **Debugging Remote APIs**: Comprehensive logging is essential for debugging LLM integrations
4. **Response Structure**: Always verify actual response structure with real API calls, not just documentation

## Completed: Search Memories API

### Overview
Implemented a REST API endpoint `POST /_plugins/_ml/memory_containers/{memory_container_id}/memories/_search` to search memories with neural, neural_sparse, or text matching capabilities based on container configuration.

### Key Features
1. **Adaptive Search**: Automatically uses the appropriate search type based on container configuration
   - Neural search for TEXT_EMBEDDING
   - Neural sparse search for SPARSE_ENCODING  
   - Match query for non-semantic storage
2. **Access Control**: Same security model as other memory container APIs
3. **Result Limiting**: Honors max_infer_size configuration
4. **Field Exclusion**: Excludes memory_embedding field from responses

### Implementation Files

#### 1. Common Module - Data Models
- ✅ `MLSearchMemoriesInput.java` - User input with query validation
- ✅ `MLSearchMemoriesRequest.java` - Transport request with tenant support
- ✅ `MLSearchMemoriesResponse.java` - OpenSearch-style search response
- ✅ `MLSearchMemoriesAction.java` - Action type definition
- ✅ `MemorySearchResult.java` - Individual search hit with score and metadata

#### 2. Plugin Module - Implementation  
- ✅ `TransportSearchMemoriesAction.java` - Core search logic with query building
- ✅ `RestMLSearchMemoriesAction.java` - REST endpoint handler

#### 3. Constants Added
- ✅ `SEARCH_MEMORIES_PATH = MEMORIES_PATH + "/_search"`
- ✅ `QUERY_FIELD = "query"`

### API Example
```bash
# Using POST method
POST /_plugins/_ml/memory_containers/abc123/memories/_search
{
    "query": "machine learning concepts"
}

# Using GET method (same request body)
GET /_plugins/_ml/memory_containers/abc123/memories/_search
{
    "query": "machine learning concepts"
}

Response:
{
    "timed_out": false,
    "hits": {
        "total": 3,
        "max_score": 0.87,
        "hits": [
            {
                "memory_id": "mem_456",
                "memory": "Machine learning is a subset of artificial intelligence",
                "_score": 0.87,
                "session_id": "sess_789",
                "user_id": "user_123",
                "memory_type": "RAW_MESSAGE",
                "role": "assistant",
                "created_time": 1706745600000
            }
        ]
    }
}
```

### Technical Implementation Details

#### Query Building Strategy
Based on container's semantic storage configuration:

1. **Neural Search (TEXT_EMBEDDING)**:
```json
{
    "neural": {
        "memory": {
            "query_text": "search text",
            "model_id": "embedding-model-id"
        }
    }
}
```

2. **Neural Sparse Search (SPARSE_ENCODING)**:
```json
{
    "neural_sparse": {
        "memory": {
            "query_text": "search text", 
            "model_id": "sparse-model-id"
        }
    }
}
```

3. **Text Match (Non-semantic)**:
```json
{
    "match": {
        "memory": "search text"
    }
}
```

#### Key Design Decisions
- **No Neural-Search Dependencies**: Built queries using raw JSON/XContent to avoid plugin dependencies
- **SdkClient Integration**: Uses sdkclient for container retrieval following ML Commons patterns
- **Access Control**: Reuses checkMemoryContainerAccess method for consistent security
- **Response Format**: Matches OpenSearch search response structure for familiarity

### Search Response Fields
- `memory_id`: Unique identifier of the memory
- `memory`: The actual memory content
- `_score`: Relevance score
- `session_id`, `agent_id`, `user_id`: Memory metadata
- `memory_type`: RAW_MESSAGE or FACT
- `role`: human/assistant/null
- `tags`: Custom metadata
- `created_time`, `last_updated_time`: Timestamps

## Completed: DELETE Memory API

### Overview
Implemented a REST API endpoint `DELETE /_plugins/_ml/memory_containers/{memory_container_id}/memories/{memory_id}` to delete individual memories from memory containers.

### Key Features
1. **Simple Deletion**: Deletes a single memory by its ID
2. **Access Control**: Validates container access permissions
3. **No Multi-tenancy**: As requested, no tenant-level controls at memory level
4. **Standard Response**: Returns OpenSearch DeleteResponse format

### Implementation Files

#### 1. Common Module - Data Models
- ✅ `MLDeleteMemoriesAction.java` - Action type definition using plural naming
- ✅ `MLDeleteMemoryRequest.java` - Request with container and memory IDs (singular)

#### 2. Plugin Module - Implementation
- ✅ `TransportDeleteMemoryAction.java` - Core deletion logic (singular)
- ✅ `RestMLDeleteMemoryAction.java` - REST endpoint handler (singular)

#### 3. Constants Added
- ✅ `PARAMETER_MEMORY_ID = "memory_id"`
- ✅ `DELETE_MEMORY_PATH = MEMORIES_PATH + "/{" + PARAMETER_MEMORY_ID + "}"`

### API Example
```bash
DELETE /_plugins/_ml/memory_containers/abc123/memories/mem_456

Response:
{
    "_index": "ml-static-memory-abc123-user123",
    "_id": "mem_456",
    "_version": 2,
    "result": "deleted",
    "_shards": {
        "total": 2,
        "successful": 1,
        "failed": 0
    }
}
```

## Completed: UPDATE Memory API

### Overview
Implemented a REST API endpoint `PUT /_plugins/_ml/memory_containers/{memory_container_id}/memories/{memory_id}` to update the text content of existing memories with automatic embedding regeneration.

### Key Features
1. **Text Update**: Updates memory field with new content
2. **Embedding Regeneration**: Automatically regenerates embeddings if semantic storage is enabled
3. **Timestamp Update**: Updates last_updated_time field
4. **Access Control**: Validates container access permissions
5. **Model State Validation**: Ensures embedding models are deployed before use

### Implementation Files

#### 1. Common Module - Data Models
- ✅ `MLUpdateMemoriesAction.java` - Action type definition using plural naming
- ✅ `MLUpdateMemoryInput.java` - Input with single text field (singular)
- ✅ `MLUpdateMemoryRequest.java` - Request with input and IDs (singular)

#### 2. Plugin Module - Implementation
- ✅ `TransportUpdateMemoryAction.java` - Core update logic with embedding regeneration (singular)
- ✅ `RestMLUpdateMemoryAction.java` - REST endpoint handler (singular)

#### 3. Constants Added
- ✅ `UPDATE_MEMORY_PATH = MEMORIES_PATH + "/{" + PARAMETER_MEMORY_ID + "}"`
- ✅ `TEXT_FIELD = "text"`

### API Example
```bash
PUT /_plugins/_ml/memory_containers/abc123/memories/mem_456
{
    "text": "Updated content with new information about machine learning"
}

Response:
{
    "_index": "ml-knn-memory-abc123-user123",
    "_id": "mem_456",
    "_version": 2,
    "result": "updated",
    "_shards": {
        "total": 2,
        "successful": 1,
        "failed": 0
    }
}
```

### Technical Implementation Details

#### Update Process
1. Validates container access permissions
2. Checks if memory exists (404 if not found)
3. Updates memory field with new text
4. Updates last_updated_time to current timestamp
5. If semantic storage enabled:
   - Validates embedding model is deployed
   - Generates new embedding using configured model
   - Updates memory_embedding field
6. Returns standard UpdateResponse

#### Error Handling
- **404 NOT_FOUND**: Memory or container doesn't exist
- **403 FORBIDDEN**: User lacks permissions
- **400 BAD_REQUEST**: Invalid request format
- **500 INTERNAL_SERVER_ERROR**: Model not deployed or embedding generation failure

## Completed: Fact Search Enhancement for ADD Memory API

### Overview
Enhanced the ADD Memory API with `infer=true` to search for similar facts within the same session after LLM fact extraction. This feature helps identify related facts that have been previously stored.

### Key Changes

#### 1. MemoryStorageConfig Update
- Changed `maxInferSize` association from `semanticStorageEnabled` to `llmModelId` presence
- Now `maxInferSize` is available whenever an LLM model is configured, regardless of semantic storage

#### 2. MemorySearchQueryBuilder Utility Class
Created a shared utility class at `plugin/src/main/java/org/opensearch/ml/utils/MemorySearchQueryBuilder.java`:
- `buildNeuralQuery()`: Builds neural search queries for TEXT_EMBEDDING
- `buildNeuralSparseQuery()`: Builds neural sparse search queries for SPARSE_ENCODING
- `buildMatchQuery()`: Builds match queries for non-semantic storage
- `buildQueryByStorageType()`: Automatically selects the right query type based on storage config
- `buildFactSearchQuery()`: Builds complete bool queries with filters for fact search

#### 3. Fact Search Implementation
Added to `TransportCreateEventAction`:
- **FactSearchResult** data structure: Stores search results with id, text, and score
- **searchSimilarFactsForSession()** method: 
  - Only executes when sessionId is provided (skips if null)
  - Searches for similar facts using appropriate query type
  - Returns Map<String, List<FactSearchResult>>
- **Integration**: Called after fact extraction but before indexing new memories
- **Logging**: Comprehensive logging of search results for debugging

#### 4. Search Query Structure
For each extracted fact, builds a query with:
- **Filters**: 
  - `session_id` = provided session ID (required)
  - `memory_type` = "FACT"
- **Search**: Neural/neural_sparse/match query based on storage configuration
- **Size**: Limited by `maxInferSize` configuration
- **Source**: Only includes "memory" field

#### 5. Refactoring
- Updated `TransportSearchMemoriesAction` to use the new `MemorySearchQueryBuilder` utility class
- Removed duplicate query building logic

### Benefits
1. **Fact Context**: Provides context about related facts within the same session
2. **Foundation for Deduplication**: Search results can be used for future fact deduplication
3. **Code Reuse**: Shared utility class reduces code duplication
4. **Performance**: Only searches when session context is available

### Future Enhancements
- Use search results for fact deduplication
- Include related facts in API response
- Implement fact merging/updating based on similarity scores

## Completed: Multiple Message Support for ADD Memory API

### Overview
Enhanced the ADD Memory API to support multiple messages (up to 10) in a single request. When `infer=true`, all messages are sent to the LLM in a single call to extract facts from the entire conversation context.

### Key Changes

#### 1. Removed Single Message Limitation
- Updated `MLCreateEventInput` validation to allow multiple messages
- Added upper limit of 10 messages per request (MAX_MESSAGES_PER_REQUEST)
- Proper error message when limit is exceeded

#### 2. Refactored TransportCreateEventAction
- Created `processMessagesWithLLM()` and `processMessagesWithoutLLM()` methods
- Renamed `extractFactsWithLLM()` to `extractFactsFromConversation()`
- Updated `storeMessagesAndFacts()` to handle multiple raw messages and facts

#### 3. LLM Request Format for Multiple Messages
When `infer=true`, all messages are sent to LLM in a single request:
```json
{
  "parameters": {
    "system_prompt": "...",
    "messages": [
      {"role": "user", "content": [{"type": "text", "text": "Hi, I'm Bob"}]},
      {"role": "assistant", "content": [{"type": "text", "text": "Nice to meet you!"}]},
      {"role": "user", "content": [{"type": "text", "text": "I work on ML"}]}
    ]
  }
}
```

#### 4. Storage Strategy
- Each message in the array is stored as a separate RAW_MESSAGE entry
- Facts extracted from the entire conversation are stored as FACT entries
- All entries share the same session_id and metadata

#### 5. Response Format
- When `infer=true`: Only FACT entries are returned in the response
- When `infer=false`: All stored messages are returned in the response
- Maintains backward compatibility with single message requests

### API Examples

#### Multiple Messages with Fact Extraction
```bash
POST /_plugins/_ml/memory_containers/abc123/memories
{
    "messages": [
        {"role": "user", "content": "Hi, I'm Alice. I work at OpenAI."},
        {"role": "assistant", "content": "Nice to meet you Alice! What do you do there?"},
        {"role": "user", "content": "I work on large language models."}
    ],
    "session_id": "sess_123",
    "infer": true
}

Response:
{
    "results": [
        {"memory_id": "fact_1", "memory": "User's name is Alice", "event": "ADD"},
        {"memory_id": "fact_2", "memory": "Works at OpenAI", "event": "ADD"},
        {"memory_id": "fact_3", "memory": "Works on large language models", "event": "ADD"}
    ],
    "session_id": "sess_123"
}
```

#### Multiple Messages without LLM Processing
```bash
POST /_plugins/_ml/memory_containers/abc123/memories
{
    "messages": [
        {"role": "user", "content": "First message"},
        {"role": "assistant", "content": "Second message"},
        {"role": "user", "content": "Third message"}
    ],
    "session_id": "sess_456",
    "infer": false
}

Response:
{
    "results": [
        {"memory_id": "msg_1", "memory": "First message", "event": "ADD"},
        {"memory_id": "msg_2", "memory": "Second message", "event": "ADD"},
        {"memory_id": "msg_3", "memory": "Third message", "event": "ADD"}
    ],
    "session_id": "sess_456"
}
```

### Benefits
1. **Better Context**: LLM receives full conversation context for more accurate fact extraction
2. **Efficiency**: Single LLM call instead of multiple calls
3. **Consistency**: Facts are extracted considering the entire conversation flow
4. **Backward Compatible**: Single message requests continue to work as before

## Completed: GET Method Support for Search Memories API

### Overview
Added GET method support to the Search Memories API to align with OpenSearch REST API conventions, where search endpoints typically support both GET and POST methods.

### Implementation
- Updated `RestMLSearchMemoriesAction.java` to include both GET and POST routes
- No changes required to request handling logic - both methods use the same request body format
- Follows the pattern established by other ML Commons search APIs (e.g., `RestMLSearchModelAction`)

### Benefits
1. **Standards Compliance**: Aligns with OpenSearch REST API conventions
2. **Consistency**: Matches other ML Commons search API patterns
3. **Flexibility**: Users can choose their preferred HTTP method
4. **Simplicity**: Minimal code change with no logic modifications

## Completed: Memory Decision System Implementation

### Overview
Implemented an LLM-based memory decision system that intelligently manages memory operations (ADD, UPDATE, DELETE, NONE) when processing new facts. This system prevents duplicates and maintains memory consistency by comparing existing memories with newly extracted facts.

### Key Features

#### 1. Memory Decision Workflow
When `infer=true` and sessionId is provided:
1. Extract facts from messages using LLM
2. Search for similar existing facts in the session
3. Send all old memories and new facts to LLM for decision making
4. Execute bulk operations based on LLM decisions
5. Return comprehensive results showing all decisions made

#### 2. LLM Update Memory Prompt
- Stored in `DEFAULT_UPDATE_MEMORY_PROMPT` constant
- XML-formatted system prompt for the Memory Manager role
- Analyzes old memories and new facts to make decisions
- Returns structured JSON with memory operations

#### 3. Data Structures
- **MemoryDecision**: Represents a decision (id, text, event, oldMemory)
- **MemoryDecisionRequest**: Request format for LLM with old memories and retrieved facts
- **MemoryResult**: Enhanced to include oldMemory field for UPDATE events

#### 4. Implementation Details
- **consolidateAndMakeMemoryDecisions()**: Makes single LLM call for all decisions
- **executeMemoryOperations()**: Performs bulk ADD, UPDATE, DELETE operations
- **Consolidated Search**: Gathers all similar facts before making decisions
- **JSON Parsing Fix**: Handles LLM responses wrapped in markdown code blocks
- **ID Generation Fix**: Uses OpenSearch auto-generated IDs for ADD operations
- **RefreshPolicy Fix**: Sets refresh policy on BulkRequest, not individual requests

#### 5. Response Format Enhancement
- All decisions (including NONE) are included in the response
- Field names changed from `memory_id`/`memory` to `id`/`text`
- Added `old_memory` field for UPDATE events
- Shows complete transparency of memory decision process

Example Response:
```json
{
  "results": [
    {
      "id": "vtdReJgB9FbCD_QNvvFt",
      "text": "User is vegan",
      "event": "NONE"
    },
    {
      "id": "T_TneJgB1izWszNdKeXd",
      "text": "Alice lives in San Francisco",
      "event": "UPDATE",
      "old_memory": "Alice lives in Boston"
    },
    {
      "id": "XPTpeJgB1izWszNdnuWz",
      "text": "User loves the weather in San Francisco",
      "event": "ADD"
    }
  ],
  "session_id": "sess_475f9271-76f3-4bcd-a786-ab074832a5c4"
}
```

### Memory Decision Examples

#### Initial Memory State
```bash
POST /_plugins/_ml/memory_containers/abc123/memories
{
    "messages": [{"role": "user", "content": "I'm Alice from Boston. I'm vegan."}],
    "session_id": "sess_123",
    "infer": true
}
```

Stored facts:
- "User's name is Alice" (id: fact_001)
- "Lives in Boston" (id: fact_002) 
- "User is vegan" (id: fact_003)

#### Memory Update with Decisions
```bash
POST /_plugins/_ml/memory_containers/abc123/memories
{
    "messages": [{"role": "user", "content": "I moved to San Francisco. I love the weather here."}],
    "session_id": "sess_123",
    "infer": true
}
```

Response showing all decisions:
```json
{
  "results": [
    {
      "id": "fact_001",
      "text": "User's name is Alice",
      "event": "NONE"
    },
    {
      "id": "fact_002",
      "text": "Lives in San Francisco",
      "event": "UPDATE",
      "old_memory": "Lives in Boston"
    },
    {
      "id": "fact_003",
      "text": "User is vegan",
      "event": "NONE"
    },
    {
      "id": "fact_004",
      "text": "User loves the weather in San Francisco",
      "event": "ADD"
    }
  ],
  "session_id": "sess_123"
}
```

### Benefits
1. **Intelligent Memory Management**: Prevents duplicates and maintains consistency
2. **Efficiency**: Single LLM call for all memory decisions
3. **Transparency**: Full visibility into all decisions made
4. **Bulk Operations**: Efficient execution of multiple memory operations
5. **Error Handling**: Fails fast with clear error messages

## Recent Simplification: Removed Historical Message Fetching

### Overview
Removed the functionality that fetched recent RAW_MESSAGE entries when processing new messages with LLM. This simplification eliminates redundant processing since:
- The factual extraction LLM will always return the same facts for the same input
- The memory decision system will always return NONE for these historical messages
- This creates unnecessary LLM calls without any benefit

### Changes Made
1. **Removed fetchRecentMessagesFromSession()**: Deleted the entire method (62 lines)
2. **Simplified processMessagesWithLLM()**: Now only processes new messages provided in request
3. **Removed MAX_RECENT_MESSAGES_FETCH constant**: No longer needed

### Benefits
1. **Eliminates Redundant Processing**: No re-processing of historical messages
2. **Reduces Latency**: No extra search query for historical messages  
3. **Better Performance**: Fewer LLM calls and database queries
4. **Simpler Code**: Much cleaner and easier to understand logic

## Next Steps

### 1. Testing Implementation
- Unit tests for memory decision system
- Unit tests for DELETE memory functionality
- Unit tests for UPDATE memory functionality
- Integration tests for all memory APIs
- YAML REST tests for end-to-end scenarios

### 2. Future API Operations
- **Container Management**
  - `PUT /_plugins/_ml/memory_containers/{container_id}` - Update container
  - `DELETE /_plugins/_ml/memory_containers/{container_id}` - Delete container
  - `GET /_plugins/_ml/memory_containers/_list` - List containers
  - `POST /_plugins/_ml/memory_containers/_search` - Search containers

### 3. Advanced Features
- Memory TTL and automatic cleanup (user-controlled, not automatic)
- Memory versioning and history
- Cross-container memory sharing
- Bulk operations support
- Aggregations and analytics on memories
- Memory deduplication and merging

### 4. Current Limitations & Future Work
- **LLM Support**: Currently optimized for Claude models only
- **Processing**: All operations are synchronous (no async support)
- **Message Limit**: Maximum 10 messages per request
- **Language**: English-focused prompts and processing
- **Caching**: No caching layer for frequent queries

### 5. Test Coverage Exclusions
The following memory-related classes are excluded from test coverage requirements:
- Transport actions: TransportCreateMemoryContainerAction, TransportCreateEventAction, etc.
- REST handlers: RestMLCreateMemoryContainerAction, RestMLCreateEventAction, etc.
- Utility classes: MemorySearchQueryBuilder
- Helper classes: MemoryContainerHelper, MemoryEmbeddingHelper

These will be covered in future test implementation phases.

## Future Enhancements (Based on Refactor)

### Additional Strategy Types
- Beyond SEMANTIC, implement strategies for:
  - TIME_BASED: Memory management based on temporal relevance
  - IMPORTANCE_BASED: Prioritize memories by importance scores
  - CONTEXTUAL: Context-aware memory processing

### Cross-Container Features
- Memory sharing between containers
- Memory migration between indices
- Container inheritance and templates

### Advanced Aggregations
- Memory analytics dashboard
- Usage patterns and insights
- Memory relationship graphs

### Memory Versioning
- Version control for memories
- Branching and merging strategies
- Rollback capabilities

### Real-time Features
- Memory streaming APIs
- WebSocket support for live updates
- Event-driven memory processing

### Storage Optimization
- Memory compression algorithms
- Archival strategies for old memories
- Tiered storage integration

## Recent Refactoring: Helper Classes for Code Reusability

### Overview
Refactored transport actions to use helper classes for better code organization and reusability. This reduced code duplication and improved maintainability across memory container operations.

### Helper Classes Created

#### 1. MemoryContainerHelper
Location: `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java`

**Purpose**: Centralizes memory container access control and validation logic

**Key Methods**:
- `checkMemoryContainerAccess()`: Validates user permissions for container access
- `getMemoryContainer()`: Retrieves container with access control checks
- `getMemoryContainerWithTenantId()`: Tenant-aware container retrieval
- `validateMemoryIndexExists()`: Validates memory index existence

**Used By**:
- TransportDeleteMemoryAction
- TransportUpdateMemoryAction
- TransportSearchMemoriesAction
- TransportCreateEventAction

#### 2. MemoryEmbeddingHelper
Location: `plugin/src/main/java/org/opensearch/ml/helper/MemoryEmbeddingHelper.java`

**Purpose**: Handles all embedding generation operations for memories

**Key Methods**:
- `generateEmbeddingForMemory()`: Single memory embedding generation
- `generateEmbeddingsForMultipleTexts()`: Batch embedding generation
- `validateEmbeddingModelDeployed()`: Model state validation
- `buildDenseEmbeddingFromResponse()`: Dense vector extraction
- `buildSparseEmbeddingFromResponse()`: Sparse vector extraction

**Used By**:
- TransportUpdateMemoryAction
- TransportCreateEventAction

### Refactoring Results

#### Code Reduction Summary
- **TransportDeleteMemoryAction**: 155 → 117 lines (~24% reduction)
- **TransportUpdateMemoryAction**: 329 → 181 lines (~45% reduction)
- **TransportSearchMemoriesAction**: 330 → 259 lines (~22% reduction)
- **TransportCreateEventAction**: 1788 → 1704 lines (~5% reduction, more potential)

**Total Lines Removed**: ~434 lines

#### Benefits Achieved
1. **Code Reusability**: Common logic shared across multiple transport actions
2. **Maintainability**: Centralized logic easier to update and fix
3. **Consistency**: Ensures uniform behavior across all memory operations
4. **Testability**: Helper classes can be unit tested independently
5. **Separation of Concerns**: Clear boundaries between access control, embedding, and business logic

### Future Refactoring Opportunities

#### Potential Additional Helpers
1. **MemoryLLMHelper**: Extract LLM interaction logic from TransportCreateEventAction
   - Fact extraction with LLM
   - Memory decision making
   - LLM request/response handling

2. **MemoryIndexingHelper**: Extract bulk indexing operations
   - Bulk memory indexing
   - Memory update operations
   - Delete operations

3. **MemorySearchHelper**: Further consolidate search-related logic
   - Query building beyond what MemorySearchQueryBuilder provides
   - Search result processing

These additional helpers could further reduce TransportCreateEventAction complexity and improve code organization.