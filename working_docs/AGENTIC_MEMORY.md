# Agentic Memory Feature Documentation

This document contains comprehensive documentation for the Agentic Memory feature in ML Commons, including the refactored four-index architecture, APIs, implementation details, and development guidelines.

## Table of Contents
- [Overview](#overview)
- [Architecture](#architecture)
- [APIs](#apis)
- [Implementation Status](#implementation-status)
- [Development Guidelines](#development-guidelines)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)

## Overview

The Agentic Memory system in ML Commons provides a sophisticated multi-index architecture for managing conversational and non-conversational memories with semantic search, fact extraction, and intelligent memory lifecycle management.

### Latest Refactor (Commit b52818ee4)
The system has been completely redesigned from a single memory index per container to a **four-index hierarchy** with strategy-based processing and namespace organization.

## Architecture

### Four-Index Hierarchy

Each memory container creates up to four indices with the following naming patterns:

#### System Index Mode (default: `use_system_index: true`)
When using system indices, the naming pattern is:
- **With prefix**: `.plugins-ml-am-{prefix}-memory-{type}`
- **Without prefix**: `.plugins-ml-am-memory-{type}`

#### Non-System Index Mode (`use_system_index: false`)
When not using system indices, the naming pattern is:
- **With prefix**: `{prefix}-memory-{type}`
- **Without prefix**: `{container-id}-memory-{type}` (uses container ID as prefix)

#### Index Types:

1. **Session Index** (`-memory-session`)
   - Tracks conversation sessions and their metadata
   - Stores session summaries and lifecycle information
   - Can be disabled with `disableSession: true`

2. **Working Memory Index** (`-memory-working`)
   - Stores immediate messages (conversational type) and data (data type)
   - Supports rich message content including text and images
   - Temporary storage before fact extraction

3. **Long-term Memory Index** (`-memory-long-term`)
   - Stores extracted facts and persistent knowledge
   - Supports KNN (dense), neural sparse, or standard indexing
   - Uses ingest pipelines for automatic embedding generation

4. **Memory History Index** (`-memory-history`)
   - Complete audit trail of memory operations (ADD/UPDATE/DELETE)
   - Tracks before/after states for all changes
   - Can be disabled with `disableHistory: true`

#### Memory Container Index
The memory container metadata is always stored in: `.plugins-ml-am-memory-container`

### Memory Container Structure (Refactored)

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

### New Components

#### MemoryType Enum (Memory Index Types)
```java
public enum MemoryType {
    SESSIONS("sessions", "sessions"),
    WORKING("working", "working"),
    LONG_TERM("long-term", "long-term"),
    HISTORY("history", "history");

    // Methods:
    String getValue()           // Returns API value (e.g., "sessions")
    String getIndexSuffix()     // Returns index suffix (e.g., "sessions")
    boolean isDisableable()     // True for SESSIONS and HISTORY
    String toIndexName(prefix)  // Constructs full index name (prefix + "-memory-" + suffix)
    static MemoryType fromString(value)  // Parse string to enum
    static boolean isValid(value)             // Check if string is valid
    static List<String> getAllValues()        // Get all valid values
}
```

##### Memory Type Features
- **Type-safe validation**: Compile-time checking instead of runtime string validation
- **Centralized patterns**: Index naming suffixes defined with their types
- **API compatibility**: Accepts string values like "sessions", "working", "long-term", "history"
- **Helper methods**: Built-in conversion, validation, and listing utilities

#### MemoryStrategyType Enum (Strategy Types)
```java
public enum MemoryStrategyType {
    SEMANTIC("SEMANTIC"),
    USER_PREFERENCE("USER_PREFERENCE"),
    SUMMARY("SUMMARY");

    // Methods:
    String getValue()                          // Returns strategy type value
    static MemoryStrategyType fromString(value) // Parse string to enum (case-insensitive)
}
```

**Purpose**: Defines the type of memory processing strategy for long-term memory extraction.
- **SEMANTIC**: Extract factual information and knowledge from conversations
- **USER_PREFERENCE**: Track user preferences and choices
- **SUMMARY**: Generate summaries of conversation sessions

#### PayloadType Enum (Working Memory Payload Types)
```java
public enum PayloadType {
    CONVERSATIONAL("CONVERSATIONAL"),
    DATA("DATA");

    // Methods:
    String getValue()                    // Returns payload type value
    static PayloadType fromString(value) // Parse string to enum (case-insensitive)
}
```

**Purpose**: Distinguishes between conversational messages and structured data in working memory.
- **CONVERSATIONAL**: Chat messages with role/content structure
- **DATA**: Binary or structured data storage

#### MemoryStrategy Class
```java
MemoryStrategy {
    String id;                  // Unique strategy identifier (auto-generated if not provided)
    boolean enabled;            // Whether strategy is active (defaults to true)
    String type;                // Strategy type ("semantic", "user_preference", or "summary")
    List<String> namespace;     // Namespace fields to match (REQUIRED)
    Map<String, Object> strategyConfig; // Optional strategy configuration (e.g., custom prompts, llm_id override)
}
```

##### Strategy Configuration Options
The `strategyConfig` field supports the following optional configurations:

- **`llm_id`**: Override the container-level LLM model for this specific strategy
  - Takes precedence over container configuration's `llm_id`
  - Enables different LLM models for different strategy types
  - Example use case: Use a powerful model for semantic extraction, lighter model for summaries

**LLM Model Selection Priority**:
1. Strategy-level `llm_id` (from `strategyConfig`)
2. Container-level `llm_id` (from `configuration`)
3. No LLM (operations requiring LLM will fail)

**Example with LLM Override**:
```json
{
  "strategies": [{
    "type": "semantic",
    "enabled": true,
    "namespace": ["user_id"],
    "configuration": {
      "llm_id": "gpt-4-for-facts"  // Override container LLM for this strategy
    }
  }, {
    "type": "summary",
    "enabled": true,
    "namespace": ["session_id"],
    "configuration": {
      "llm_id": "gpt-3.5-for-summaries"  // Different LLM for summaries
    }
  }]
}
```

##### Strategy Validation Rules
- **Type validation**: Only `"semantic"`, `"user_preference"`, or `"summary"` (case-insensitive) are valid
- **Namespace requirement**: Must be non-null and non-empty array
- **ID generation**: If not provided, auto-generated as `{type}_{8-char-UUID}` (e.g., `semantic_a1b2c3d4`)
- **Enabled default**: If not specified, defaults to `true`

#### PayloadType Enum
```java
enum PayloadType {
    CONVERSATIONAL("conversational"),  // Chat messages
    DATA("data")                       // Non-conversational data
}
```

#### Memory Input Structure (Refactored)
```java
MLAddMemoriesInput {
    String memoryContainerId;
    PayloadType payloadType;          // Auto-determined based on fields
    List<MessageInput> messages;      // For conversational content
    String binaryData;                // For binary data content
    Map<String, Object> structuredData; // For structured data content
    Map<String, String> namespace;    // Flexible namespace (replaces sessionId/agentId)
    boolean infer;                    // Auto-determined based on memoryType
    Map<String, String> metadata;     // Additional metadata
    Map<String, String> tags;         // Additional tags
}
```

##### Auto-determination Logic:
1. **memoryType determination**:
   - If `binaryData` or `structuredData` is provided → `DATA`
   - If only `messages` is provided → `CONVERSATIONAL`
   - If not explicitly set and no fields → validation error

2. **infer field defaults**:
   - `CONVERSATIONAL` type → `infer = true`
   - `DATA` type → `infer = false`
   - Can be overridden by explicit setting

3. **Validation**:
   - At least one of `messages`, `binaryData`, or `structuredData` must be provided
   - `CONVERSATIONAL` type requires `messages`
   - Inference requires `messages` to be provided

### LLM Prompt Configuration

The memory system uses specialized prompts for fact extraction and memory management decisions. These prompts are embedded in the codebase and can be customized:

#### Default Prompts
- **Fact Extraction Prompt** (`DEFAULT_FACT_EXTRACTOR_PROMPT`): Extracts personal information and facts from conversations
  - Focus: Names, relationships, preferences, professional details, goals, etc.
  - Output: JSON with "facts" array containing extracted information

- **Memory Update Prompt** (`DEFAULT_UPDATE_MEMORY_PROMPT`): Makes memory management decisions
  - Operations: ADD, UPDATE, DELETE, or NONE for each memory
  - Analyzes similarity scores between old and new memories
  - Merges complementary information and resolves conflicts
  - Output: JSON with "memory_decision" array

#### Prompt Structure
The prompts follow a structured XML format:
```xml
<system_prompt>
  <role>Personal Information Organizer</role>
  <objective>Extract and organize personal information</objective>
  <instructions>...</instructions>
  <response_format>
    {"facts": ["fact1", "fact2", ...]}
  </response_format>
</system_prompt>
```

#### External Prompt File
`DEFAULT_UPDATE_MEMORY_PROMPT.xml` contains additional prompt variations for:
- Smart memory manager with ADD/UPDATE/DELETE/NONE operations
- Personal information extraction with specific categories
- Guidelines for memory operations with detailed examples

## APIs

The Agentic Memory feature provides **3 API groups** for managing memory containers and memory data:

### API Groups Overview

#### Group 1: Memory Container Management APIs
Container-level operations (CRUD + Search):
- `POST /_plugins/_ml/memory_containers/_create` - Create new memory container
- `GET /_plugins/_ml/memory_containers/{container_id}` - Get container details
- `PUT /_plugins/_ml/memory_containers/{container_id}` - Update container (name, description, backend_roles, llm_id, strategies)
- `DELETE /_plugins/_ml/memory_containers/{container_id}` - Delete container
- `GET /_plugins/_ml/memory_containers/_search` - Search containers

#### Group 2: Add Memory API
Specialized API for adding new memories (will integrate into unified API in future):
- `POST /_plugins/_ml/memory_containers/{container_id}/memories` - Add memory (working memory)

#### Group 3: Unified Memory APIs
**Primary API pattern** for all memory types (session, working, long-term, history):
- `GET /_plugins/_ml/memory_containers/{container_id}/memories/{type}/{memory_id}` - Get memory by type and ID
- `PUT /_plugins/_ml/memory_containers/{container_id}/memories/{type}/{memory_id}` - Update memory by type and ID
- `DELETE /_plugins/_ml/memory_containers/{container_id}/memories/{type}/{memory_id}` - Delete memory by type and ID
- `GET /_plugins/_ml/memory_containers/{container_id}/memories/{type}/_search` - Search memories by type

Where `{type}` can be: `session`, `working`, `long-term`, `history`

**Note:** The unified API pattern is the recommended approach for all memory operations. Type-specific APIs (like dedicated Working Memory GET/DELETE endpoints) are transitional and will be consolidated into the unified pattern.

### API Examples

#### Group 1: Memory Container Management APIs

##### Create Memory Container
```json
POST /_plugins/_ml/memory_containers/_create
{
  "name": "chatbot container",
  "description": "Store conversations with semantic search and summarization",
  "configuration": {
    "index_prefix": "plugin-ml",  // Optional (defaults to "plugin-ml"); custom values like "chatbot", "test1" (no dot prefix allowed)
    "embedding_model_type": "TEXT_EMBEDDING",
    "embedding_model_id": "embedding-model-123",
    "embedding_dimension": 1024,
    "llm_id": "llm-model-456",
    "strategies": [
      {
        "enabled": true,
        "type": "semantic",  // Must be: "semantic", "user_preference", or "summary"
        "namespace": ["user_id"]  // REQUIRED: Non-empty array of namespace fields
      }
    ],
    "index_settings": {
      "session_index": {
        "index": {
          "number_of_shards": "2",
          "number_of_replicas": "1"
        }
      },
      "working_memory_index": {
        "index": {
          "number_of_shards": "2",
          "number_of_replicas": "1"
        }
      }
    },
    "disable_history": false,
    "disable_session": false,
    "use_system_index": true  // Defaults to true; creates system indices with .plugins-ml-am- prefix
  }
}
```

##### Get Memory Container
```json
GET /_plugins/_ml/memory_containers/{container_id}

# Response includes configuration, strategies, and index settings
```

##### Update Memory Container
```json
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "name": "updated container name",
  "description": "updated description",
  "backend_roles": ["role1", "role2"],  // Users with these backend roles can access container
  "strategies": [  // Optional: Update strategies
    {
      "id": "semantic_123",  // Existing strategy ID - will update this strategy
      "enabled": false       // Disable this strategy
    },
    {
      // No ID provided - will add as new strategy
      "type": "user_preference",
      "namespace": ["user_id", "session_id"],
      "enabled": true
    }
  ]
}
```

**Strategy Update and Merge Behavior:**
- Strategies with `id` field: Updates existing strategy (must exist)
- Strategies without `id` field: Adds as new strategy (auto-generates ID)
- Only provided fields are updated (null fields retain current values)
- New strategies are validated (type and namespace requirements)
- Strategy IDs follow format: `{type}_{8-char-UUID}`

**Update LLM Model:**
```json
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "llm_id": "new-llm-model-789"
}
```

**Update Multiple Fields:**
```json
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "name": "Updated Container",
  "description": "Updated description",
  "llm_id": "new-llm-model-xyz",
  "strategies": [
    {
      "id": "semantic_123",
      "enabled": false
    }
  ]
}
```

**Configuration Update Behavior:**
- `llm_id`: When provided, updates the LLM model configuration
- `strategies`: When provided, merges with existing strategies (see Strategy Update behavior above)
- When either `llm_id` or `strategies` is updated, the entire configuration is rebuilt preserving all other settings
- Null fields in update request preserve existing values

**⚠️ Embedding Configuration Updates** (Added in commit 0178e0587):

Container configurations can be partially updated, but embedding fields have special restrictions:

**Updatable Configuration Fields:**
- ✅ `name` - Container name (always updatable)
- ✅ `description` - Container description (always updatable)
- ✅ `backend_roles` - Access control roles (always updatable)
- ✅ `llm_id` - LLM model ID (always updatable)
- ✅ `strategies` - Memory strategies (always updatable, merges with existing)
- ✅ `max_infer_size` - Maximum inference results (always updatable)
- ⚠️ `embedding_model_id` - **Conditionally updatable** (see restrictions below)
- ⚠️ `embedding_model_type` - **Conditionally updatable** (see restrictions below)
- ⚠️ `dimension` - **Conditionally updatable** (see restrictions below)

**Embedding Field Update Restrictions:**

Embedding configuration (`embedding_model_id`, `embedding_model_type`, `dimension`) can ONLY be updated if:
- Container has **NO strategies configured**, OR
- Update sets the **exact same values** (idempotent update)

**Why Restricted?** Once strategies are configured, the long-term memory index is created with a fixed schema. The `memory_embedding` field mapping cannot be changed without recreating the index.

**Allowed Scenarios:**
```json
# Scenario 1: Initial embedding setup (no strategies yet)
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "embedding_model_id": "model-123",
  "embedding_model_type": "TEXT_EMBEDDING",
  "dimension": 768
}

# Scenario 2: Update other fields (embedding unchanged)
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "llm_id": "new-llm-model",
  "max_infer_size": 8
}

# Scenario 3: Idempotent update (same embedding values)
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "embedding_model_id": "model-123",  // Same as current
  "embedding_model_type": "TEXT_EMBEDDING",  // Same as current
  "dimension": 768  // Same as current
}
```

**Blocked Scenarios (after strategies configured):**
```json
# ❌ WILL FAIL: Changing embedding after strategies exist
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "embedding_model_id": "different-model-456"  // Different from current
}

# Error: "Cannot change embedding configuration once strategies are configured.
#         The long-term memory index already exists with specific embedding mappings.
#         Current: {embedding_model_id=model-123, embedding_model_type=TEXT_EMBEDDING, dimension=768}.
#         Create a new memory container if you need different embedding configuration."
```

**Workaround for Embedding Changes:**
If you need to change embedding configuration after strategies are configured, create a new memory container with the desired configuration.

**Complete Configuration Update Example:**
```json
PUT /_plugins/_ml/memory_containers/{container_id}
{
  "name": "Updated Container Name",
  "description": "Updated description",
  "llm_id": "new-llm-model-xyz",
  "max_infer_size": 7,
  "strategies": [
    {
      "id": "semantic_abc123",
      "enabled": false  // Disable existing strategy
    },
    {
      // Add new strategy
      "type": "summary",
      "namespace": ["session_id"],
      "enabled": true
    }
  ]
  // Note: embedding fields omitted - will retain current values
}
```

##### Delete Memory Container
```json
# Delete container only (keeps all memory indices)
DELETE /_plugins/_ml/memory_containers/{container_id}

# Delete container and all associated memory indices
DELETE /_plugins/_ml/memory_containers/{container_id}?delete_all_memories=true

# Delete container and specific memory indices
DELETE /_plugins/_ml/memory_containers/{container_id}?delete_memories=sessions,working

# Alternative: Specify in request body
DELETE /_plugins/_ml/memory_containers/{container_id}
{
  "delete_memories": ["sessions", "long-term"]  // Duplicates are automatically removed
}
```

**Notes:**
- Only the container owner can delete the container (not backend roles)
- `delete_all_memories` and `delete_memories` are mutually exclusive
- Valid memory types for selective deletion: `sessions`, `working`, `long-term`, `history`
- URL parameters take precedence over request body values
- Memory types are validated using `MemoryType` enum for type safety

**Shared Index Prefix Protection:**

When deleting a container with `delete_all_memories=true` or `delete_memories` specified, the system performs a critical safety check to prevent accidental data loss:

- The system counts how many containers share the same `index_prefix`
- If multiple containers (count > 1) share the prefix, deletion is **blocked** with a `409 CONFLICT` error
- This prevents deleting indices that are used by other containers

**Example Error Response:**
```json
{
  "error": {
    "root_cause": [{
      "type": "open_search_status_exception",
      "reason": "Cannot delete memory indices as multiple containers share the index prefix 'my-memory'. Please delete the container without index deletion, or use delete_by_query API for data cleanup."
    }],
    "status": 409
  }
}
```

**Workarounds when sharing index prefix:**
1. Delete the container only (without index deletion): `DELETE /_plugins/_ml/memory_containers/{container_id}`
2. Use the delete_by_query API to selectively clean up memory data before deleting the container
3. Ensure each container uses a unique `index_prefix` during creation to avoid this restriction

##### Search Memory Containers
```json
GET /_plugins/_ml/memory_containers/_search
{
  "query": {
    "match_all": {}
  }
}
```

#### Group 2: Add Memory API

##### Add Conversational Memory
```json
POST /_plugins/_ml/memory_containers/{container_id}/memories
{
  "messages": [
    {
      "role": "user",
      "content": "I'm Bob, I really like swimming."
    },
    {
      "role": "assistant",
      "content": "Cool, nice. Hope you enjoy your life."
    }
  ],
  "namespace": {
    "user_id": "bob"
  },
  "tags": {
    "topic": "personal info"
  },
  "infer": true  // optional, defaults to true for conversational
}

# Response
{
  "session_id": "XSEuiJkBeh2gPPwzjYVh",
  "working_memory_id": "XyEuiJkBeh2gPPwzjYWM"
}
```

##### Create Session Explicitly
```json
POST /_plugins/_ml/memory_containers/{container_id}/memories/sessions
{
  "namespace": {
    "session_id": "custom-session-123",
    "user_id": "bob",
    "agent_id": "agent-456"
  },
  "summary": "Discussion about ML algorithms and model optimization",
  "metadata": {
    "created_by": "admin",
    "purpose": "technical_discussion"
  }
}

# Response
{
  "session_id": "custom-session-123",
  "created_time": 1633104000000
}
```

##### Add Data Memory with Binary Data
```json
POST /_plugins/_ml/memory_containers/{container_id}/memories
{
  "binary_data": "base64_encoded_document_content",
  "namespace": {
    "document_id": "doc456"
  }
  // Auto-determined: memoryType = DATA, infer = false
}
```

##### Add Data Memory with Structured Data
```json
POST /_plugins/_ml/memory_containers/{container_id}/memories
{
  "structured_data": {
    "time_start": "2025-09-11",
    "time_end": "2025-09-15",
    "metric_value": 100
  },
  "namespace": {
    "agent_id": "agent_123",
    "user_id": "bob"
  },
  "metadata": {
    "source": "metrics_api"
  }
  // Auto-determined: memoryType = DATA, infer = false
}

# Response
{
  "session_id": "sess_456",
  "working_memory_id": "wm_xyz789"
}
```

##### Mixed Fields (DATA type takes precedence)
```json
POST /_plugins/_ml/memory_containers/{container_id}/memories
{
  "messages": [{"role": "user", "content": "Store this"}],
  "binary_data": "document_content_base64",
  "namespace": {
    "session_id": "sess789"
  }
  // Auto-determined: memoryType = DATA (binary_data present), infer = false
}
```

#### Group 3: Unified Memory APIs

##### Get Memory by Type and ID
```json
# Get working memory
GET /_plugins/_ml/memory_containers/{container_id}/memories/working/{memory_id}

# Get session
GET /_plugins/_ml/memory_containers/{container_id}/memories/session/{session_id}

# Get long-term memory
GET /_plugins/_ml/memory_containers/{container_id}/memories/long-term/{memory_id}

# Get history entry
GET /_plugins/_ml/memory_containers/{container_id}/memories/history/{history_id}

# Example response (working memory)
{
  "memory_container_id": "HudqiJkB1SltqOcZusVU",
  "memory_type": "conversational",
  "messages": [
    {
      "role": "user",
      "content_text": "I'm Bob, I really like swimming."
    },
    {
      "role": "assistant",
      "content_text": "Cool, nice. Hope you enjoy your life."
    }
  ],
  "namespace": {
    "user_id": "bob",
    "session_id": "S-dqiJkB1SltqOcZ1cYO"
  },
  "infer": true,
  "tags": {
    "topic": "personal info"
  },
  "created_time": 1758930326804,
  "last_updated_time": 1758930326804
}
```

##### Update Memory by Type and ID
```json
# Update session
PUT /_plugins/_ml/memory_containers/{container_id}/memories/session/{session_id}
{
  "additional_info": {
    "key1": "value1"
  }
}

# Update long-term memory
PUT /_plugins/_ml/memory_containers/{container_id}/memories/long-term/{memory_id}
{
  "text": "Updated memory content"
}

# Note: History entries cannot be updated (audit trail)
```

##### Delete Memory by Type and ID
```json
# Delete working memory
DELETE /_plugins/_ml/memory_containers/{container_id}/memories/working/{memory_id}

# Delete session
DELETE /_plugins/_ml/memory_containers/{container_id}/memories/session/{session_id}

# Delete long-term memory
DELETE /_plugins/_ml/memory_containers/{container_id}/memories/long-term/{memory_id}

# Delete history entry
DELETE /_plugins/_ml/memory_containers/{container_id}/memories/history/{history_id}
```

##### Delete Memories by Query
```json
# Delete memories matching a query
POST /_plugins/_ml/memory_containers/{container_id}/memories/{memory_type}/_delete_by_query
{
  "query": {
    "match": {
      "namespace.user_id": "bob"
    }
  }
}

# Delete all memories in a type
POST /_plugins/_ml/memory_containers/{container_id}/memories/session/_delete_by_query
{
  "query": {
    "match_all": {}
  }
}

# Delete memories older than a date
DELETE /_plugins/_ml/memory_containers/{container_id}/memories/working/_delete_by_query
{
  "query": {
    "range": {
      "created_time": {
        "lt": "2025-01-01"
      }
    }
  }
}

# Response format
{
  "took": 147,
  "timed_out": false,
  "deleted": 23,
  "batches": 1,
  "version_conflicts": 0,
  "noops": 0,
  "retries": {
    "bulk": 0,
    "search": 0
  },
  "throttled_millis": 0,
  "requests_per_second": -1.0,
  "throttled_until_millis": 0
}
```

##### Search Memories by Type
```json
# Search sessions
GET /_plugins/_ml/memory_containers/{container_id}/memories/session/_search
{
  "query": {
    "match_all": {}
  }
}

# Search working memories
GET /_plugins/_ml/memory_containers/{container_id}/memories/working/_search
{
  "query": {
    "term": {
      "namespace.user_id": "bob"
    }
  }
}

# Search long-term memories (semantic search)
GET /_plugins/_ml/memory_containers/{container_id}/memories/long-term/_search
{
  "query": {
    "match": {
      "text": "machine learning concepts"
    }
  }
}

# Search history
GET /_plugins/_ml/memory_containers/{container_id}/memories/history/_search
{
  "query": {
    "range": {
      "created_time": {
        "gte": "2025-01-01"
      }
    }
  }
}
```

### API Validation Rules

1. **Request Type Detection**
   - If `messages` field is present → Conversational type
   - If `data` field is present → Data type
   - Cannot specify both `messages` and `data` (error)
   - Must specify either `messages` or `data` (error if neither)

2. **Infer Field Behavior**
   - **Conversational**: `infer` defaults to `true` if not specified, user can set to `false`
   - **Data**: `infer` is always `false`, error if user tries to set to `true`

3. **Field Constraints**
   - `messages` and `data` are mutually exclusive
   - `metadata` is optional for both types
   - `namespace` is required for strategy matching

### MemoryConfiguration Validation Rules

The following validation rules are enforced in `MemoryConfiguration` class:

#### 1. **Embedding Model Validation**
- If `embedding_model_id` is provided, `embedding_model_type` is required
- If `embedding_model_type` is provided, `embedding_model_id` is required
- Supported types: `TEXT_EMBEDDING`, `SPARSE_ENCODING` only
- Other types will throw: "Invalid embedding model type"

#### 2. **Dimension Requirements**
- **TEXT_EMBEDDING**: `dimension` field is required
  - Error: "TEXT_EMBEDDING requires dimension to be specified"
- **SPARSE_ENCODING**: `dimension` field is not allowed
  - Error: "SPARSE_ENCODING does not support dimension"

#### 3. **Max Infer Size Validation**
- Maximum allowed value: 10
- Only applies when `llm_id` is configured
- Error: "max_infer_size exceeds maximum limit of 10"

#### 4. **Strategy Model Requirements Validation** (Added in commit 0178e0587)
- Strategies require BOTH an LLM model (`llm_id`) and embedding model to be configured
- Validation occurs at container creation time in `MemoryConfiguration.validateStrategiesRequireModels()`
- If strategies are specified but models are missing, detailed error indicates what's missing
- Error format: "Strategies require both an LLM model and embedding model to be configured. Missing: {missing_items}. Strategies use LLM for fact extraction and embedding model for semantic search."
- **Why**: Strategies perform semantic search (requires embedding) and fact extraction (requires LLM)

#### 5. **Shared Index Validation (CREATE)** (Added in commit 0178e0587)
When creating a memory container with an `index_prefix` that another container already uses:
- **Validates**: `embedding_model_id`, `embedding_model_type`, `dimension` must match existing configuration
- **Implementation**: `TransportCreateMemoryContainerAction.validateExistingIndexConfig()`
- **Checks**:
  1. Retrieves existing index mapping via `GetMappingsRequest`
  2. Extracts embedding configuration from `memory_embedding` field
  3. Retrieves existing ingest pipeline (`{index_name}-embedding`)
  4. Extracts `model_id` from pipeline processors
  5. Compares requested config with existing config
- **Error**: If mismatch detected, provides detailed comparison showing conflicts and resolution options
- **Resolution**:
  1. Use a different `index_prefix` for the new container, OR
  2. Match the existing embedding configuration exactly

**Example Error**:
```
Cannot create memory container: Embedding configuration conflicts with existing shared index.

Index prefix 'my-memory' is already in use with different settings:
  • embedding_model_id: existing='model-123', requested='model-456'
  • dimension: existing=768, requested=1024

This shared index was configured with:
  embedding_model_id: "model-123"
  embedding_model_type: "TEXT_EMBEDDING"
  dimension: 768

To resolve this issue, you can either:
1. Use a different index_prefix for this container
2. Match the existing configuration in your request
```

#### 6. **Embedding Update Validation (UPDATE)** (Added in commit 0178e0587)
When updating a memory container's configuration:
- **Restriction**: Cannot change embedding configuration once strategies are configured and long-term memory index exists
- **Implementation**: `TransportUpdateMemoryContainerAction.validateEmbeddingUpdate()`
- **Protected fields**: `embedding_model_id`, `embedding_model_type`, `dimension`
- **Logic**:
  - If container has NO strategies → Embedding configuration CAN be updated (long-term index doesn't exist yet)
  - If container HAS strategies → Embedding configuration is LOCKED (long-term index exists with schema)
  - Idempotent updates (same values) are always allowed
- **Why**: Long-term memory index already has a fixed schema with specific embedding field mappings. Changing embedding config would require index recreation.
- **Error**: "Cannot change embedding configuration once strategies are configured. The long-term memory index already exists with specific embedding mappings. Current: {current_config}. Create a new memory container if you need different embedding configuration."
- **Workaround**: Create a new memory container with the desired embedding configuration

### MLAddMemoriesInput Validation Rules

The following validation is performed in `MLAddMemoriesInput.validate()`:

#### 1. **Messages Field Validation**
- If `infer=true` and `messages` is null/empty:
  - Error: "No messages provided when inferring memory"
- If `memoryType=CONVERSATIONAL` and `messages` is null/empty:
  - Error: "No messages provided for conversational memory"

#### 2. **Memory Container ID**
- Always required (passed from URL path)
- Error: "No memory container id provided"

### Default Configuration Values

The following defaults are applied in commit 6c52005b0:

#### MemoryConfiguration Defaults
```
useSystemIndex: true        // System indices enabled by default
indexPrefix: ""             // Empty string (BUG - should be "plugin-ml")
maxInferSize: 5             // When llm_id is present
disableHistory: false       // History index enabled
disableSession: false       // Session index enabled
```

#### MLAddMemoriesInput Defaults
```
payloadType: PayloadType.CONVERSATIONAL      // Default to conversational
infer: false                                  // Don't infer by default
```

#### System Index Configuration
```
System prefix: ".plugins-ml-am-"
Pattern: {system_prefix}{index_prefix}-memory-{type}

Example with empty prefix (current bug):
- .plugins-ml-am--memory-session (double dash)
- .plugins-ml-am--memory-working
```

## Memory Processing Workflow

### When Adding Conversation Memory with `infer=true`

1. **Session Management**
   - Check if `session_id` exists in namespace
   - Create new session if needed
   - Store session info in session index

2. **Working Memory Storage**
   - Save conversation messages to working memory index
   - Include namespace and metadata

3. **Strategy Execution**
   For each configured strategy:
   - Check namespace matching
   - If namespace matches:
     - Select LLM model (strategy override or container config)
     - Invoke LLM to extract facts
     - Search existing memories with same namespace
     - Make memory decisions (ADD/UPDATE/DELETE)
     - Execute operations on long-term memory index
     - Record changes in history index

   **LLM Model Selection**: Each strategy can specify its own `llm_id` in the `configuration` field, overriding the container-level LLM model. This allows using different models for different types of memory processing.

4. **Response**
   - Return working memory ID
   - Return session ID

## Index Mappings

### Session Index
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

### Working Memory Index
```json
{
  "properties": {
    "memory_container_id": { "type": "keyword" },
    "working_memory_type": { "type": "keyword" },
    "messages": {
      "type": "object",
      "properties": {
        "role": { "type": "keyword" },
        "content_text": { "type": "text" },
        "content": { "type": "nested" }
      }
    },
    "data": { "type": "flat_object" },
    "namespace": { "type": "flat_object" },
    "metadata": { "type": "flat_object" },
    "created_time": { "type": "date" },
    "last_updated_time": { "type": "date" }
  }
}
```

### Long-term Memory Index
Varies based on embedding configuration:
- **KNN Index**: Includes `knn_vector` field for dense embeddings
- **Neural Sparse**: Includes `rank_features` field for sparse embeddings
- **Standard**: Text-only fields for non-semantic storage

### History Index
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

## Migration from Old to New Architecture

### Field Mapping
- `sessionId` → `namespace.session_id`
- `agentId` → `namespace.agent_id`
- `memoryIndexName` → `indexPrefix`
- `llmModelId` → `llmId`

### Configuration Changes
- `MemoryStorageConfig` → `MemoryConfiguration`
- Added `strategies` list for processing rules
- Added `indexSettings` for per-index configuration
- Added flags to disable session/history indices

### API Contract Changes
- Use namespace instead of fixed fields
- Specify `memory_type` for non-conversational data
- Strategy-based processing replaces simple infer flag

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

## Development Guidelines

### Key Design Decisions

1. **Multi-Index Architecture**: Separate indices for different memory types enable better performance and scalability
2. **Strategy System**: Flexible memory processing logic through configurable strategies
3. **Namespace Organization**: Replace fixed fields with flexible namespace mapping
4. **Audit Trail**: Complete history tracking of all memory operations
5. **Rich Data Types**: Support for structured JSON storage

### Implementation Files

#### Common Module - Data Models
- `MLMemoryContainer.java` - Container entity
- `MemoryConfiguration.java` - Configuration model
- `MLLongTermMemory.java` - Long-term memory data model
- `MLWorkingMemory.java` - Working memory data model
- `MLCreateEventInput.java` - Event creation input
- `MemoryStrategy.java` - Strategy configuration

#### Plugin Module - Transport Actions
- `TransportCreateMemoryContainerAction.java`
- `TransportCreateEventAction.java`
- `TransportSearchMemoriesAction.java`
- `TransportUpdateMemoryAction.java`
- `TransportDeleteMemoryAction.java`

#### Helper Classes
- `MemoryContainerHelper.java` - Access control and validation
- `MemoryEmbeddingHelper.java` - Embedding generation
- `MemorySearchQueryBuilder.java` - Query building utilities
- `StrategyMergeHelper.java` - Strategy merging logic for container updates

#### Enum Classes
- `MemoryType.java` - Memory types (SESSIONS, WORKING, LONG_TERM, HISTORY)
- `MemoryStrategyType.java` - Memory strategy types (SEMANTIC, USER_PREFERENCE, SUMMARY)
- `PayloadType.java` - Payload types (CONVERSATIONAL, DATA)

### Code Patterns

#### Invoking ML Prediction for Embeddings
```java
MLInput mlInput = MLInput.builder()
    .algorithm(FunctionName.TEXT_EMBEDDING)
    .inputDataset(TextDocsInputDataSet.builder()
        .docs(Arrays.asList("Text to embed"))
        .build())
    .build();

MLPredictionTaskRequest predictionRequest = MLPredictionTaskRequest.builder()
    .modelId(embeddingModelId)
    .mlInput(mlInput)
    .build();

client.execute(MLPredictionTaskAction.INSTANCE, predictionRequest, ActionListener.wrap(
    response -> {
        // Extract embeddings from response
    },
    error -> { /* handle error */ }
));
```

#### SDK Client Usage for Bulk Operations
```java
BulkDataObjectRequest bulkRequest = BulkDataObjectRequest.builder()
    .globalIndex(indexName)
    .build();

sdkClient.bulkDataObjectAsync(bulkRequest).whenComplete((response, exception) -> {
    if (exception != null) {
        Exception cause = SdkClientUtils.unwrapAndConvertToException(exception);
        listener.onFailure(cause);
        return;
    }
    BulkResponse bulkResponse = BulkResponse.fromXContent(response.parser());
    // Process response
});
```

## Testing

### Test Coverage Achievements (Commit 0178e0587)

**Transport Actions Coverage** - Significantly improved in recent commits:
- **TransportCreateMemoryContainerAction**: 80%+ line coverage (was 70%, now exceeds 0.8 threshold)
  - Added 8 new validation test scenarios for shared index validation
  - Tests cover all code paths in `validateExistingIndexConfig()` method
  - Total: 50 tests, +953 test lines
- **TransportUpdateMemoryContainerAction**: Enhanced with update validation tests
  - Added tests for embedding update restrictions
  - Added tests for strategy merge logic
  - Total: +393 test lines
- **TransportAddMemoriesActionTests**: +124 test lines
- **RestMLDeleteMemoryActionTests**: +17 test lines

**Total Test Coverage Improvement**: +2,149 lines across 10 files

### Validation Test Scenarios (TransportCreateMemoryContainerAction)

The following 8 test scenarios cover shared index validation logic:

1. **testCreateContainer_SharedIndexConfigMatch** - Success when existing index config matches request
2. **testCreateContainer_SharedIndexConfigMismatch** - Failure when configs don't match (dimension/model_id mismatch)
3. **testCreateContainer_IndexExistsMappingNull** - Failure when mapping metadata is null
4. **testCreateContainer_IndexExistsNoEmbeddingField** - Failure when embedding field is missing from mapping
5. **testCreateContainer_IndexExistsPipelineNotFound** - Failure when ingest pipeline doesn't exist
6. **testCreateContainer_PipelineExistsNoModelId** - Failure when pipeline has no model_id
7. **testCreateContainer_GetPipelineValidationError** - Error handler when getPipeline fails
8. **testCreateContainer_GetMappingsValidationError** - Error handler when getMappings fails

### Test Strategies
1. **Unit Tests**: Test individual components with mocks
   - Mock OpenSearch admin clients (IndicesAdminClient, ClusterAdminClient)
   - Create real `PipelineConfiguration` objects (final class, cannot be mocked)
   - Use actual index names from configuration for realistic testing
2. **Integration Tests**: Test with real OpenSearch cluster
3. **YAML REST Tests**: End-to-end API testing

### Running Tests

```bash
# Run all TransportCreateMemoryContainerAction tests
./gradlew :opensearch-ml-plugin:test --tests TransportCreateMemoryContainerActionTests

# Verify JaCoCo coverage
./gradlew :opensearch-ml-plugin:jacocoTestCoverageVerification

# View coverage report
# Open: plugin/build/reports/jacoco/test/html/index.html
```

## Troubleshooting

### Common Issues

1. **Empty Facts Response from LLM**
   - Check LLM response structure
   - Verify response contains `content[0].text` with JSON facts
   - Enable debug logging in TransportCreateEventAction

2. **Model State Validation Failures**
   - Ensure embedding models are DEPLOYED before use
   - REMOTE models skip state validation
   - Check model status with GET model API

3. **Parser Issues with Large Embeddings**
   - Fixed by using `parser.list()` instead of `parser.map()` for arrays
   - Follows ModelTensor pattern for array parsing

4. **Namespace vs Fixed Fields**
   - Use namespace map instead of sessionId/agentId
   - Namespace allows flexible field definitions

5. **Strategy Not Executing**
   - Verify namespace fields match strategy configuration
   - Check that strategy is enabled
   - Ensure LLM model is configured if using SEMANTIC strategy

6. **Shared Index Configuration Conflicts** (Added in commit 0178e0587)
   - **Error**: "Embedding configuration conflicts with existing shared index"
   - **Cause**: Multiple containers attempting to use the same `index_prefix` with different embedding configurations
   - **Resolution**:
     1. Check existing containers using the same prefix: `GET /_plugins/_ml/memory_containers/_search`
     2. Either use a different `index_prefix` for the new container
     3. Or match the existing embedding configuration (`embedding_model_id`, `embedding_model_type`, `dimension`)
   - **Prevention**: Use unique index prefixes per embedding configuration

7. **Embedding Update Restrictions** (Added in commit 0178e0587)
   - **Error**: "Cannot change embedding configuration once strategies are configured"
   - **Cause**: Attempting to modify `embedding_model_id`, `embedding_model_type`, or `dimension` after long-term memory index exists
   - **Why Restricted**: Long-term memory index has a fixed schema with specific embedding field mappings
   - **Resolution**:
     1. If you need different embedding configuration, create a new memory container
     2. For initial setup, add embedding config BEFORE adding strategies
   - **Allowed**: Idempotent updates (setting same values) are permitted

8. **Strategy Validation Failures** (Added in commit 0178e0587)
   - **Error**: "Strategies require both an LLM model and embedding model to be configured. Missing: {items}"
   - **Cause**: Strategies specified without required models
   - **Resolution**:
     - Ensure `llm_id` is configured (for fact extraction)
     - Ensure embedding model is configured: `embedding_model_id`, `embedding_model_type`, `dimension` (for semantic search)
   - **Why Both Required**: Strategies use LLM for fact extraction AND embedding for semantic similarity search

### Debug Commands

```bash
# Check container configuration
GET /_plugins/_ml/memory_containers/{container_id}

# Search for memories in a session
POST /_plugins/_ml/memory_containers/{container_id}/memories/_search
{
  "query": "session_id:sess_123"
}

# Check model status
GET /_plugins/_ml/models/{model_id}
```

## Future Enhancements

### Additional Strategy Types
- TIME_BASED: Memory management based on temporal relevance
- IMPORTANCE_BASED: Prioritize memories by importance scores
- CONTEXTUAL: Context-aware memory processing

### Cross-Container Features
- Memory sharing between containers
- Memory migration between indices
- Container inheritance and templates

### Advanced Features
- Memory versioning and branching
- Real-time memory streaming
- Memory compression and archival
- Memory relationship graphs

## Revert Impact Analysis and Recovery Plan

> **⚠️ HISTORICAL INFORMATION**: The sections below contain historical information about previous implementations, reverted commits, and old bugs. Much of this is **now outdated** due to recent refactoring (October 2025):
> - **Enum naming has changed**: `MemoryType` now refers to memory index types (SESSIONS, WORKING, LONG_TERM, HISTORY)
> - **MemoryStrategyType** (lines 125-141): Formerly called `MemoryType`, now renamed for clarity
> - **PayloadType** (lines 143-157): Formerly called `WorkingMemoryType`, now renamed
> - **Model renamed**: `MLMemory.java` → `MLLongTermMemory.java`
> - **Many listed bugs have been fixed** in commits from October 2025
> - For current implementation details, see lines 100-180 and the Implementation Files section

### Recent Reverts (September 25, 2025)

Two critical commits were reverted that removed significant refactoring work:

#### Reverted Commit 1: "Rename type to strategy" (cde15f4dc)
**Changes Lost:**
- MemoryStrategy field `strategy` → reverted to `type` (String)
- MemoryStrategyType enum (SEMANTIC value) → removed
- ShortTermMemoryType renamed to MemoryType → reverted
- Enum-based type safety → lost

#### Reverted Commit 2: "Aligning agentic memory user experience with Bedrock AgentCore Memory" (860f0642e)
**Major Changes Lost:**

| Feature | Before Revert | After Revert |
|---------|---------------|--------------|
| **Main API** | CREATE event API | ADD memories API |
| **Endpoint** | `/events/_create` | `/memories` |
| **Get Event** | `GET /events/{event_id}` | Not available |
| **Delete Event** | `DELETE /events/{event_id}` | Not available |
| **Input Class** | MLCreateEventInput | MLAddMemoriesInput |
| **Response Class** | MLCreateEventResponse | MLAddMemoriesResponse |
| **Event Retrieval** | MLGetEventResponse | Not available |

### Current State Assessment

#### What Works:
- ✅ Four-index architecture (sessions, events, memories, memory-histories)
- ✅ Main code compiles successfully
- ✅ ADD memories API functional
- ✅ Memory container creation/retrieval
- ✅ Search memories functionality

#### Recent Fixes (September 29, 2025):

##### Session Index Creation Fix (Commit c2bce2500)
- **Problem Fixed**: Session index was not created for memory containers without LLM configuration
- **Root Cause**: When `configuration.getLlmId() == null || configuration.getStrategies().isEmpty()`, the code only created working memory index
- **Solution**: Now properly creates session index before working memory index when sessions are enabled
- **Impact**: All memory container configurations now properly initialize their required indices

##### Memory History Refactoring (Commit c0515f91f)
- **Enhancement**: MLMemoryHistory now uses flexible Map structure for before/after snapshots
- **Changes**:
  - `before` and `after` fields changed from `MLMemory` to `Map<String, Object>`
  - Added `namespace` and `tags` fields to history records
  - Index mapping changed to `flat_object` type for flexible storage
- **Benefits**:
  - More flexible storage of arbitrary memory states
  - Better tracking with namespace and tags
  - Simplified serialization/deserialization

##### Update Memory API History Check (Commit c2bce2500)
- **Problem Fixed**: History index detection was too restrictive
- **Solution**: Changed from exact match to `endsWith("-memory-history")` pattern
- **Impact**: Properly prevents updates to any history index variant

#### Known Issues:
- ✅ ~~ShortTermMemoryType has "CONVERSATION" not "CONVERSATIONAL"~~ (Fixed: Now PayloadType with CONVERSATIONAL)
- ✅ ~~MemoryType error message mentions "RAW_MESSAGE or FACT"~~ (Fixed: Now MemoryStrategyType with SEMANTIC)
- ✅ ~~MemoryStrategy.enabled defaults to false~~ (Fixed: Now defaults to true)
- ✅ ~~No GET/DELETE event APIs~~ (Fixed: Working Memory GET/DELETE APIs added in Phase 3)
- ✅ ~~Session index not created for containers without LLM~~ (Fixed: Session index now properly created - Commit c2bce2500)
- ⚠️ Test compilation failures (Test files need updating for refactored APIs)
- ⚠️ Documentation partially outdated (Being updated in current session)
- 🐛 **CRITICAL BUG #1 - Field Name Mismatch (Commit e0f8fb0a)**:
  - **Problem**: `MLAddMemoriesInput` uses constant `WORKING_MEMORY_TYPE_FIELD = "working_memory_type"` but `ml_memory_working.json` index mapping defines field as `"memory_type"`. Similarly, `MLWorkingMemory` uses `MEMORY_TYPE_FIELD = "memory_type"`.
  - **Impact**: Runtime storage/retrieval failures - data written with field name "working_memory_type" but index expects "memory_type"
  - **Fix Required**:
    - Remove `WORKING_MEMORY_TYPE_FIELD` constant from `MemoryContainerConstants.java` (line 68)
    - Update `MLAddMemoriesInput.java` to import and use `MEMORY_TYPE_FIELD` instead
    - Rationale: Since we're already in working memory index context, field name `"memory_type"` is sufficient and matches the index mapping
  - **Files Affected**:
    - `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java`
    - `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLAddMemoriesInput.java`
    - `common/src/main/resources/index-mappings/ml_memory_working.json` (already correct)

- 🐛 **BUG #2 - Default Index Prefix is Empty String (Commit 6c52005b0)**:
  - **Problem**: `MemoryConfiguration` constructor defaults `indexPrefix` to empty string `""` when null, creating invalid index names starting with dash (e.g., `-memory-session`, `-memory-working`)
  - **Impact**: When users don't specify `index_prefix`, creates invalid index names:
    - Without system index: `-memory-session`, `-memory-working`
    - With system index: `.plugins-ml-am--memory-session` (double dash)
  - **Workaround**: Always explicitly specify `"index_prefix"` in container creation request
  - **Fix Required**: Change default from `""` to `"plugin-ml"` in `MemoryConfiguration.java` line 97
  - **File Affected**: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryConfiguration.java`

- 🐛 **BUG #3 - NPE When Messages Field is Null (Commit 6c52005b0)**:
  - **Problem**: In `TransportAddMemoriesAction.createNewSessionIfAbsent()`, code attempts `messages.get(0).getContentText()` without null check
  - **Error**: `java.lang.NullPointerException: Cannot invoke "java.util.List.get(int)" because "messages" is null`
  - **Impact**: 500 error when calling ADD memories API without messages field or with null messages
  - **Location**: `TransportAddMemoriesAction.java` line 163
  - **Workaround**: Always provide `messages` field in ADD memories API request, even if empty array
  - **Fix Required**: Add null check before accessing `messages.get(0)` in `createNewSessionIfAbsent()` method
  - **File Affected**: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportAddMemoriesAction.java`

- ⚠️ **Issue #4 - Conflicting Validation Logic (Commit 6c52005b0)**:
  - **Problem**: `MLAddMemoriesInput.validate()` throws error "No messages provided for conversational memory" when messages is null and memoryType is CONVERSATIONAL
  - **Impact**: Cannot properly handle DATA type memories or empty message scenarios
  - **Location**: `MLAddMemoriesInput.java` validate() method
  - **File Affected**: `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLAddMemoriesInput.java`

### Task Breakdown for Recovery

#### Phase 1: Critical Fixes (Priority: HIGH) ✅ COMPLETED
- [x] **Fix WorkingMemoryType enum** (originally ShortTermMemoryType, renamed in Phase 5)
  - Enum values: CONVERSATION, DATA
  - Updated all references in MLAddMemoriesInput and TransportAddMemoriesAction
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/WorkingMemoryType.java`

- [x] **Fix MemoryType error message**
  - Updated error message from "RAW_MESSAGE or FACT" to "SEMANTIC"
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryType.java`

- [x] **Fix MemoryStrategy defaults**
  - Changed `enabled` default from false to true in parse()
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryStrategy.java`

- [x] **Fix test compilation**
  - Rewrote MLMemoryTest to use namespace map pattern
  - Removed all references to sessionId/agentId getters/setters
  - File: `common/src/test/java/org/opensearch/ml/common/memorycontainer/MLMemoryTest.java`

#### Phase 2: Restore Type-to-Strategy Refactoring (Priority: HIGH) ❌ REVERTED
**Note**: This phase was completed but later reverted. The current implementation uses the original enum structure:
- `WorkingMemoryType` enum with CONVERSATION, DATA values (renamed from ShortTermMemoryType in Phase 5)
- `MemoryType` enum with SEMANTIC value only
- `MemoryStrategy.type` remains as String field (not enum-based)

**Original Plan (Not Implemented):**
- [x] **Rename enums** (REVERTED)
  - Rename MemoryType → MemoryStrategyType
  - Rename ShortTermMemoryType → MemoryType
  - Update all imports and references

- [x] **Update MemoryStrategy**
  - Change `type` field to `strategy` with MemoryStrategyType enum
  - Update ID generation: `strategy.getValue().toLowerCase() + "_" + UUID`
  - Add field constant STRATEGY_FIELD = "strategy"

- [x] **Update all references**
  - Update imports in ~20 files
  - Update test files
  - Ensure backward compatibility

#### Phase 3: Working Memory APIs Implementation (Priority: MEDIUM) ✅ COMPLETED

##### 3.1 Rename Short-Term Memory to Working Memory
- [x] **Update MemoryContainerConstants.java**
  - Rename `SHORT_TERM_MEMORY_INDEX` → `WORKING_MEMORY_INDEX`
  - Rename `SHORT_TERM_MEMORY_ID_FIELD` → `WORKING_MEMORY_ID_FIELD`
  - Add `WORKING_MEMORIES_PATH` constant
  - Add `PARAMETER_WORKING_MEMORY_ID` constant
  - Add `GET_WORKING_MEMORY_PATH` constant
  - Add `DELETE_WORKING_MEMORY_PATH` constant

- [x] **Update MLAddMemoriesResponse.java**
  - Fix typo: `shorTermMemoryId` → `workingMemoryId`
  - Update getter/setter methods
  - Update StreamInput/Output serialization
  - Update toXContent to use `WORKING_MEMORY_ID_FIELD`

- [x] **Update MemoryConfiguration.java**
  - Rename `getShortTermMemoryIndexName()` → `getWorkingMemoryIndexName()`
  - Return `indexPrefix + "-working-memory"`

- [x] **Update TransportAddMemoriesAction.java**
  - Use `configuration.getWorkingMemoryIndexName()`
  - Set `workingMemoryId` in response

- [x] **Update TransportCreateMemoryContainerAction.java**
  - Update index creation to use working memory index name

##### 3.2 Implement GET Working Memory API
- [x] **Create MLGetWorkingMemoryAction.java** (common module)
  - ActionType with NAME = "cluster:admin/opensearch/ml/memory_containers/memory/working/get"

- [x] **Create MLGetWorkingMemoryRequest.java** (common module)
  - Fields: `memoryContainerId`, `workingMemoryId`
  - Implement StreamInput/Output and validation

- [x] **Create MLGetWorkingMemoryResponse.java** (common module)
  - Wrap MLAddMemoriesInput (follow MLModelGroupGetResponse pattern)
  - Delegate toXContent() to wrapped input

- [x] **Create TransportGetWorkingMemoryAction.java** (plugin module)
  - Query working memory index by ID
  - Reconstruct MLAddMemoriesInput from stored data
  - Return wrapped response

- [x] **Create RestMLGetWorkingMemoryAction.java** (plugin module)
  - Endpoint: `GET /_plugins/_ml/memory_containers/{container_id}/memories/working/{working_memory_id}`
  - Parse parameters and call transport action

##### 3.3 Implement DELETE Working Memory API
- [x] **Create MLDeleteWorkingMemoryAction.java** (common module)
  - ActionType with NAME = "cluster:admin/opensearch/ml/memory_containers/memory/working/delete"

- [x] **Create MLDeleteWorkingMemoryRequest.java** (common module)
  - Fields: `memoryContainerId`, `workingMemoryId`
  - Implement StreamInput/Output

- [x] **Create TransportDeleteWorkingMemoryAction.java** (plugin module)
  - Delete from working memory index by ID
  - Return standard DeleteResponse

- [x] **Create RestMLDeleteWorkingMemoryAction.java** (plugin module)
  - Endpoint: `DELETE /_plugins/_ml/memory_containers/{container_id}/memories/working/{working_memory_id}`
  - Parse parameters and call transport action

##### 3.4 Plugin Registration
- [x] **Update MachineLearningPlugin.java**
  - Add imports for new Working Memory actions
  - Register actions in getActions()
  - Register REST handlers in getRestHandlers()

##### 3.5 Update Index Mappings and Fix Compilation
- [x] **Updated index mappings**
  - Renamed `ml_memory_short_term.json` to `ml_memory_working.json`
  - Updated CommonValue constant to ML_WORKING_MEMORY_INDEX_MAPPING_PATH
  - Fixed MLIndicesHandler method name to createWorkingMemoryDataIndex
  - Fixed all compilation errors (Client imports, MemoryType references)

#### Phase 4: Alignment and Documentation (Priority: LOW)
- [ ] **Update Create Container contract**
  - Validate strategies array uses `strategy` field
  - Ensure proper defaults
  - Test namespace configuration

- [ ] **Update documentation**
  - Remove references to non-existent features
  - Add migration guide
  - Update API examples

- [ ] **Add integration tests**
  - Test event lifecycle (create, get, delete)
  - Test strategy execution
  - Test namespace matching

### Phase 3 Implementation Summary (September 25, 2025)

Phase 3 has been successfully completed with the transformation from "short-term memory" to "working memory" concept:

#### Key Changes Implemented:
1. **Constants and Fields**
   - Renamed all SHORT_TERM references to WORKING throughout the codebase
   - Fixed typo in MLAddMemoriesResponse (shorTermMemoryId → workingMemoryId)
   - Updated index mapping file from ml_memory_short_term.json to ml_memory_working.json

2. **New Working Memory APIs**
   - GET Working Memory: `GET /_plugins/_ml/memory_containers/{container_id}/memories/working/{working_memory_id}`
   - DELETE Working Memory: `DELETE /_plugins/_ml/memory_containers/{container_id}/memories/working/{working_memory_id}`
   - Both APIs follow OpenSearch action patterns with proper transport/REST handlers

3. **Compilation Fixes**
   - Fixed Client import issues (org.opensearch.transport.client.Client)
   - Resolved MemoryType.SEMANTIC references (changed to CONVERSATIONAL)
   - Updated MemoryProcessingService to use getStrategy() instead of getType()
   - Fixed MLIndicesHandler method name and constants

4. **Index Configuration**
   - Working memory index name: `{indexPrefix}-working-memory`
   - Properly integrated with MemoryConfiguration class
   - Updated TransportCreateMemoryContainerAction for index creation

#### Phase 4: Parser Standardization and Index Naming (Priority: MEDIUM) ✅ COMPLETED

##### 4.1 Add Missing Field Constants
- [x] **Update MemoryContainerConstants.java**
  - Add `ID_FIELD = "id"` constant
  - Add `ENABLED_FIELD = "enabled"` constant
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java`

##### 4.2 Fix MemoryStrategy Parser
- [x] **Standardize MemoryStrategy.java parser**
  - Replace hard-coded `"id"` with `ID_FIELD` in toXContent() and parse()
  - Replace hard-coded `"enabled"` with `ENABLED_FIELD` in toXContent() and parse()
  - Replace hard-coded `"strategy"` with `STRATEGY_FIELD` in toXContent() and parse()
  - Replace hard-coded `"namespace"` with `NAMESPACE_FIELD` in toXContent() and parse()
  - Add static imports for the field constants
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryStrategy.java`

##### 4.3 Rename Index Suffixes
- [x] **Update MemoryConfiguration.java index name methods**
  - Change `getSessionIndexName()`: `{prefix}-session` → `{prefix}-memory-session`
  - Change `getWorkingMemoryIndexName()`: `{prefix}-working-memory` → `{prefix}-memory-working`
  - Change `getLongMemoryIndexName()`: `{prefix}-long-term-memory` → `{prefix}-memory-long-term`
  - Change `getLongMemoryHistoryIndexName()`: `{prefix}-long-term-memory-history` → `{prefix}-memory-history`
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryConfiguration.java`

##### 4.4 Change Default Index Prefix
- [x] **Update MemoryConfiguration.java default prefix**
  - Change default from `"memory-" + UUID.randomUUID()` to `".ml-plugin"`
  - Update line 90 in constructor
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryConfiguration.java`

##### 4.5 Add System Index Descriptor
- [x] **Add constant in CommonValue.java**
  - Add `ML_AGENTIC_MEMORY_INDEX_PATTERN = ".ml-plugin-memory*"` constant
  - File: `common/src/main/java/org/opensearch/ml/common/CommonValue.java`

- [x] **Register system index in MachineLearningPlugin.java**
  - Import `ML_AGENTIC_MEMORY_INDEX_PATTERN` constant
  - Add single descriptor: `new SystemIndexDescriptor(ML_AGENTIC_MEMORY_INDEX_PATTERN, "ML Commons Agentic Memory Index Pattern")`
  - File: `plugin/src/main/java/org/opensearch/ml/plugin/MachineLearningPlugin.java`

### Phase 4 Implementation Summary (September 25, 2025)

Phase 4 has been successfully completed with parser standardization and index naming improvements:

#### Key Changes Implemented:
1. **Parser Standardization**
   - Added `ID_FIELD = "id"` and `ENABLED_FIELD = "enabled"` constants to MemoryContainerConstants
   - Updated MemoryStrategy to use field constants instead of hard-coded strings
   - All parsers now follow consistent XXX_FIELD pattern

2. **Index Naming Standardization**
   - Session index: `{prefix}-session` → `{prefix}-memory-session`
   - Working memory: `{prefix}-working-memory` → `{prefix}-memory-working`
   - Long-term: `{prefix}-long-term-memory` → `{prefix}-memory-long-term`
   - History: `{prefix}-long-term-memory-history` → `{prefix}-memory-history`

3. **Default Prefix Change**
   - Changed from `"memory-{UUID}"` to `".ml-plugin"`
   - All agentic memory indices now start with `.ml-plugin` by default
   - Ensures proper system index protection

4. **System Index Registration**
   - Added `ML_AGENTIC_MEMORY_INDEX_PATTERN = ".ml-plugin-memory*"` constant in CommonValue.java
   - Registered single wildcard pattern in MachineLearningPlugin.getSystemIndexDescriptors()
   - Covers all four agentic memory indices with one descriptor

#### Phase 5: WorkingMemoryType Refactor and Field Rename (Priority: HIGH) ✅ COMPLETED

##### Background
After reverting Phase 2 (enum refactoring), the system maintained `ShortTermMemoryType` with CONVERSATION/DATA values and `MemoryType` with SEMANTIC value. Phase 5 addresses the naming inconsistency by renaming `ShortTermMemoryType` to `WorkingMemoryType` and changing the field name from `"memory_type"` to `"working_memory_type"` in the working memory index.

##### 5.1 Add Working Memory Type Field Constant
- [x] **Update MemoryContainerConstants.java**
  - Add `WORKING_MEMORY_TYPE_FIELD = "working_memory_type"` constant
  - Keep existing `MEMORY_TYPE_FIELD = "memory_type"` for long-term memory (MLMemory)
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java`

##### 5.2 Rename Enum Class
- [x] **Rename ShortTermMemoryType → WorkingMemoryType**
  - Delete: `ShortTermMemoryType.java`
  - Create: `WorkingMemoryType.java` with CONVERSATION, DATA enum values
  - Update javadoc: "Enum representing the type of working memory entry"
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/WorkingMemoryType.java`

##### 5.3 Update MLAddMemoriesInput
- [x] **Change field type and constant usage**
  - Update import: `MEMORY_TYPE_FIELD` → `WORKING_MEMORY_TYPE_FIELD`
  - Update field type: `ShortTermMemoryType` → `WorkingMemoryType`
  - Update toXContent(): Use `WORKING_MEMORY_TYPE_FIELD` (lines 179, 214)
  - Update parse(): Case `WORKING_MEMORY_TYPE_FIELD` instead of `MEMORY_TYPE_FIELD` (line 264)
  - Update default value: `WorkingMemoryType.CONVERSATIONAL`
  - Update validation: Check against `WorkingMemoryType.CONVERSATIONAL`
  - File: `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLAddMemoriesInput.java`

##### 5.4 Update Working Memory Index Mapping
- [x] **Update ml_memory_working.json**
  - Change field name: `"memory_type"` → `"working_memory_type"`
  - Keep type: `"keyword"`
  - File: `common/src/main/resources/index-mappings/ml_memory_working.json`

##### 5.5 Update Plugin Actions
- [x] **Update TransportAddMemoriesAction.java**
  - Update import: `ShortTermMemoryType` → `WorkingMemoryType`
  - Update comparison: `WorkingMemoryType.CONVERSATIONAL`
  - File: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportAddMemoriesAction.java`

- [x] **Update TransportGetWorkingMemoryAction.java**
  - Add import: `WORKING_MEMORY_TYPE_FIELD` constant
  - Update import: `ShortTermMemoryType` → `WorkingMemoryType`
  - Replace hardcoded string: `source.get("memory_type")` → `source.get(WORKING_MEMORY_TYPE_FIELD)`
  - Update fromString call: `WorkingMemoryType.fromString()`
  - File: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportGetWorkingMemoryAction.java`

### Phase 5 Implementation Summary (September 26, 2025)

Phase 5 has been successfully completed with the WorkingMemoryType refactor and field rename:

#### Key Changes Implemented:
1. **Enum Renamed**
   - `ShortTermMemoryType` → `WorkingMemoryType`
   - Values remain: CONVERSATIONAL, DATA
   - Updated all imports and references across 6 files

2. **Field Name Changed**
   - Working memory field: `"memory_type"` → `"working_memory_type"`
   - Added `WORKING_MEMORY_TYPE_FIELD` constant
   - Long-term memory still uses `"memory_type"` (unchanged)

3. **Improved Naming Consistency**
   - Working memory explicitly identified with "working" prefix
   - Clear separation between working memory type and long-term memory type
   - Index mapping and code now aligned with "working memory" terminology

4. **Compilation Status**
   - ✅ `:opensearch-ml-common:compileJava` - BUILD SUCCESSFUL
   - ✅ `:opensearch-ml-plugin:compileJava` - BUILD SUCCESSFUL

#### Important Note:
The `MEMORY_TYPE_FIELD = "memory_type"` constant is preserved and used exclusively for:
- MLMemory class (long-term memories)
- MemoryType.SEMANTIC enum value
- Long-term memory index mappings

This separation ensures no confusion between working memory types (CONVERSATIONAL/DATA) and long-term memory types (SEMANTIC).

#### Phase 6: CONVERSATION → CONVERSATIONAL Value Refactor (Priority: HIGH) ✅ COMPLETED

##### Background
After completing Phase 5 (WorkingMemoryType enum creation), the enum values were CONVERSATION and DATA. Phase 6 standardizes the naming to use adjective forms (CONVERSATIONAL instead of CONVERSATION) for consistency with other enum patterns in the codebase.

##### 6.1 Update WorkingMemoryType Enum
- [x] **Rename enum value and string representation**
  - Change: `CONVERSATION("conversation")` → `CONVERSATIONAL("conversational")`
  - Update error message in fromString() to reference CONVERSATIONAL
  - File: `common/src/main/java/org/opensearch/ml/common/memorycontainer/WorkingMemoryType.java`

##### 6.2 Update Main Source Files
- [x] **Update MLAddMemoriesInput.java** (2 references)
  - Constructor default value: `WorkingMemoryType.CONVERSATIONAL`
  - StreamInput deserialization: `WorkingMemoryType.CONVERSATIONAL`
  - File: `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLAddMemoriesInput.java`

- [x] **Update TransportAddMemoriesAction.java** (1 reference)
  - Session creation check: `WorkingMemoryType.CONVERSATIONAL`
  - File: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportAddMemoriesAction.java`

##### 6.3 Update Test Files
- [x] **Update MLAddMemoriesInputTest.java** (6 references + import)
  - Import: `org.opensearch.ml.common.memorycontainer.WorkingMemoryType`
  - All test cases using `WorkingMemoryType.CONVERSATIONAL`
  - File: `common/src/test/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLAddMemoriesInputTest.java`

- [x] **Update MLAddMemoriesRequestTest.java** (1 reference + import)
  - Import: `org.opensearch.ml.common.memorycontainer.WorkingMemoryType`
  - Test case using `WorkingMemoryType.CONVERSATIONAL`
  - File: `common/src/test/java/org/opensearch/ml/common/transport/memorycontainer/memory/MLAddMemoriesRequestTest.java`

- [x] **Update MemorySearchServiceTests.java** (1 reference + import)
  - Import: `org.opensearch.ml.common.memorycontainer.WorkingMemoryType`
  - Test case using `WorkingMemoryType.CONVERSATIONAL`
  - File: `plugin/src/test/java/org/opensearch/ml/action/memorycontainer/memory/MemorySearchServiceTests.java`

### Phase 6 Implementation Summary (September 26, 2025)

Phase 6 has been successfully completed with the CONVERSATION → CONVERSATIONAL enum value refactor:

#### Key Changes Implemented:
1. **Enum Value Renamed**
   - `CONVERSATION("conversation")` → `CONVERSATIONAL("conversational")`
   - DATA value remains unchanged
   - Error message updated to reference CONVERSATIONAL

2. **Exhaustive Code Updates**
   - Updated 3 main source files (9 total files including tests)
   - Updated 3 test files with new enum value
   - All imports verified for WorkingMemoryType

3. **Naming Consistency Achieved**
   - Adjective form used for enum value (CONVERSATIONAL, not CONVERSATION)
   - Matches naming patterns like SEMANTIC in MemoryType
   - String representation also uses adjective: "conversational"

4. **No Backward Compatibility**
   - Changes applied without backward compatibility (per user request)
   - All references systematically updated
   - Clean implementation without legacy code paths

### Implementation Notes

1. **Compilation Priority**: Fix Phase 1 issues first to restore test compilation
2. **Backward Compatibility**: Not required for Phase 4-5 - focus on clean implementation
3. **Testing**: Run full test suite after each phase
4. **Documentation**: Update inline with code changes

### Success Criteria
- All code compiles (main and test)
- Event APIs match Bedrock AgentCore pattern
- Strategy system uses enums for type safety
- Parser uses field constants consistently
- Index naming follows standardized pattern
- System indices properly registered
- Documentation accurately reflects implementation
- All tests pass

## Recent Updates

### LLM Model Update Support (October 2025)
- **Feature**: Support updating `llm_id` through memory container update API
- **Implementation**:
  - Added `llm_id` field to `MLUpdateMemoryContainerInput`
  - Configuration rebuild triggered when either `llm_id` or `strategies` updated
  - Null handling: null means "don't update", preserving existing value
- **API Usage**:
  - Update llm_id alone: `{"llm_id": "new-model"}`
  - Combined with strategies: `{"llm_id": "new-model", "strategies": [...]}`
  - Combined with simple fields: `{"name": "...", "llm_id": "new-model"}`
- **Benefits**:
  - No need to recreate container to change LLM model
  - Supports dynamic model switching for memory processing
  - Follows same partial update pattern as other configuration fields

### Strategy Configuration Null Safety and Field Name Standardization (October 2025)
- **Feature**: Improved null handling and consistent field naming for strategy configurations
- **Strategy Configuration Null Handling**:
  - Changed `strategyConfig` to use `null` instead of empty HashMap for absent configurations
  - Reason: Distinguish "not specified" (null) vs "clear configuration" (empty map) during updates
  - Impact: Partial updates now preserve existing configurations correctly
  - Files modified: `MemoryStrategy.java` (constructor line 52, StreamInput line 74, parse() lines 131-132)
- **Field Name Standardization**:
  - Standardized strategy configuration field name to `"configuration"` in API requests/responses
  - Constant: `STRATEGY_CONFIG_FIELD = "configuration"` (not "strategy_config")
  - Updated tests and documentation for consistency across all endpoints
  - Files affected: `MemoryContainerConstants.java`, test files, documentation
- **NPE Prevention in MemoryProcessingService**:
  - Added null safety when accessing `strategy.getStrategyConfig().get("llm_result_path")`
  - Used Optional chaining pattern: `.ofNullable(strategyConfig).map(config -> config.get(...))`
  - Multiple null checks added at lines 106, 125, 134, 287-290
  - Prevents NPE when strategies have no configuration specified
- **Test Coverage**:
  - Added `testParseWithStrategyConfig()` in `MLUpdateMemoryContainerInputTests`
  - Verifies configuration parsing with non-empty config maps
  - All existing tests updated to use correct field name
- **Benefits**:
  - **Safer partial updates**: Updating strategy namespace without specifying configuration no longer clears existing config
  - **Consistent API**: All endpoints use same field name for strategy configuration
  - **No NPE**: Processing strategies without custom configuration doesn't crash
  - **Clear semantics**: null = "keep existing", empty map = "clear all", populated map = "set to these values"

### Strategy ID Filtering Enhancement (September 29, 2025)
- **Added strategy_id field** to MLMemory for proper strategy isolation during similarity search
- **Fixed critical bug**: Removed hardcoded `MemoryType.SEMANTIC` filter in MemorySearchQueryBuilder that was causing all searches to only find SEMANTIC memories
- **Implementation**:
  - Added `STRATEGY_ID_FIELD` constant to MemoryContainerConstants
  - Updated MLMemory class with strategyId field (constructor, builder, serialization, parsing, toIndexMap)
  - Updated long-term memory index mapping to include strategy_id as keyword field
  - Modified MemoryOperationsService to populate strategy_id when creating memories
  - Updated MemorySearchQueryBuilder to filter by strategy_id
- **Benefits**: Prevents cross-strategy interference during LLM memory auto-decisions, ensuring USER_PREFERENCE memories don't interfere with SEMANTIC memories

### Strategy ID Generation Enhancement
- **Enhanced format**: Strategy IDs now use format `{type}_{8-char-UUID}` (e.g., `semantic_a1b2c3d4`, `user_preference_b2c3d4e5`, `summary_c3d4e5f6`)
- **Added helper method**: `MemoryStrategy.generateStrategyId(String type)` for consistent ID generation
- **Auto-generation**: IDs automatically generated if not provided, with type prefix for better identification
- **Shortened UUID**: Reduced from full UUID to 8 characters for better readability while maintaining uniqueness
- **Backward compatible**: Existing IDs preserved if provided

### Strategy Type Validation Enhancement
- **Early validation**: Added strategy type validation during memory container creation instead of at runtime
- **Renamed method**: `validateModels()` → `validateConfiguration()` for better clarity
- **Accepted types**: Only "semantic", "user_preference", or "summary" (case-insensitive) are valid
- **Clear error message**: `"Invalid strategy type: {type}. Must be one of: semantic, user_preference, summary"`
- **Prevents runtime errors**: Catches invalid types at creation time, not during memory processing

### SUMMARY Strategy Type Addition
- **New strategy type**: Added SUMMARY as a third memory strategy type alongside SEMANTIC and USER_PREFERENCE
- **Implementation details**:
  - Added `SUMMARY("SUMMARY")` to MemoryType enum as third option
  - Added `SUMMARY_FACTS_EXTRACTION_PROMPT` constant in MemoryContainerConstants (currently uses same prompt as placeholder)
  - Updated validation logic in TransportCreateMemoryContainerAction to accept "summary" strategy type
  - Enhanced MemoryProcessingService to route SUMMARY strategies to appropriate LLM prompts
- **Test coverage**:
  - Added comprehensive tests for SUMMARY type in MemoryTypeTest (enum values, parsing, error messages)
  - Updated MemoryStrategyTest with SUMMARY type ID generation tests
  - Added MemoryProcessingServiceTests for SUMMARY strategy routing and fact extraction
- **Use case**: Enables memory strategies focused on content summarization and extraction of key points from conversations
- **Future enhancement**: Custom SUMMARY-specific prompt can be configured when requirements are defined

### Custom Prompt Support for Memory Strategies (September 30, 2025)
- **Feature**: Strategies can now use custom prompts for fact extraction instead of default prompts
- **Configuration**: Add `system_prompt` to the strategy's `configuration` field
- **Validation**: Custom prompts must specify JSON response format with a 'facts' array
- **Fallback**: If no custom prompt provided, falls back to strategy type defaults
- **Example configuration**:
  ```json
  {
    "strategies": [{
      "type": "semantic",
      "enabled": true,
      "namespace": ["user_id"],
      "configuration": {
        "system_prompt": "<your_custom_prompt>... {\"facts\": [\"fact1\", \"fact2\"]}</your_custom_prompt>"
      }
    }]
  }
  ```
- **Supported for all strategy types**: SEMANTIC, USER_PREFERENCE, and SUMMARY
- **Benefits**: Allows fine-tuning fact extraction for specific use cases without modifying code

### Automatic Session Summarization (September 30, 2025)
- **Feature**: LLM automatically generates session summaries when creating new sessions
- **Trigger**: When adding memories without providing a session_id
- **Configuration**:
  - Uses `SESSION_SUMMARY_PROMPT` constant for LLM instructions
  - Configurable max summary size via container parameters
  - Default max summary size: 10 words
- **Parameter configuration**:
  ```json
  {
    "configuration": {
      "llm_id": "model-123",
      "parameters": {
        "session": {
          "max_summary_size": "20"
        }
      }
    }
  }
  ```
- **Implementation**: `MemoryProcessingService.summarizeMessages()` method
- **Benefits**: Better session context understanding without manual summarization

### Memory Search Query Optimization (September 29, 2025)
- **Removed redundant filtering**: Eliminated memory type filter from similarity search since strategy_id is unique and sufficient
- **Cleaner queries**: Search queries now rely solely on strategy_id for filtering
- **Performance improvement**: Slightly faster queries with one less filter
- **Simplified codebase**: Removed unused `getMemoryTypeFromStrategy()` method from MemorySearchQueryBuilder

### USER_PREFERENCE Naming Standardization (September 29, 2025)
- **Renamed enum value**: Changed `USER_PREFERENCES` (plural) to `USER_PREFERENCE` (singular) throughout the codebase
- **Updated constants**: Renamed `USER_PREFERENCES_FACTS_EXTRACTION_PROMPT` to `USER_PREFERENCE_FACTS_EXTRACTION_PROMPT`
- **Consistency**: All references now use singular form matching user's original intent
- **Files updated**: MemoryType.java, MemoryContainerConstants.java, all validation logic, and tests

### Files Modified in Recent Enhancements

#### Custom Prompt and Session Summarization (September 30, 2025)
- `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java` - Added SESSION_SUMMARY_PROMPT constant
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryProcessingService.java` - Added custom prompt support and summarizeMessages method
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportAddMemoriesAction.java` - Integrated automatic session summarization
- `plugin/src/test/java/org/opensearch/ml/action/memorycontainer/memory/MemoryProcessingServiceTests.java` - Tests for custom prompts and session summarization

#### Unit Test Coverage Expansion (September 29-30, 2025)
- Over 3,600 lines of new test code added across multiple files:
- `TransportAddMemoriesActionTests.java` - Added sophisticated private method testing with reflection
- New test files for comprehensive coverage:
  - `TransportSearchMemoryContainerActionTests.java`
  - `TransportUpdateMemoryContainerActionTests.java`
  - `MemoryInfoTests.java`
  - `MemoryOperationsServiceTests.java`
  - `TransportDeleteWorkingMemoryActionTests.java`
  - `TransportGetWorkingMemoryActionTests.java`
  - `TransportSearchMemoriesActionTests.java`
  - `TransportUpdateMemoryActionTests.java`
  - REST API tests: `RestMLDeleteWorkingMemoryActionTests.java`, `RestMLGetWorkingMemoryActionTests.java`, `RestMLUpdateMemoryContainerActionTests.java`

#### Strategy ID Filtering Implementation
- `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java` - Added STRATEGY_ID_FIELD constant
- `common/src/main/java/org/opensearch/ml/common/memorycontainer/MLMemory.java` - Added strategyId field with full serialization support
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/indices/MLIndicesHandler.java` - Added strategy_id to index mapping
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryOperationsService.java` - Populate strategy_id when creating memories
- `plugin/src/main/java/org/opensearch/ml/utils/MemorySearchQueryBuilder.java` - Removed memory type filter, now filters only by strategy_id

#### Strategy ID Generation
- `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryStrategy.java` - Enhanced ID generation with 8-char UUID
- `common/src/test/java/org/opensearch/ml/common/memorycontainer/MemoryStrategyTest.java` - Comprehensive test coverage

#### Strategy Type Validation
- `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java` - Added INVALID_STRATEGY_TYPE_ERROR
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/TransportCreateMemoryContainerAction.java` - Added validateConfiguration method

#### USER_PREFERENCE Standardization
- `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryType.java` - Enum value changed to singular
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryProcessingService.java` - Updated string comparisons
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryOperationsService.java` - Updated getMemoryTypeFromStrategy
- All test files updated with singular form

#### SUMMARY Strategy Type Addition
- `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryType.java` - Added SUMMARY enum value
- `common/src/main/java/org/opensearch/ml/common/memorycontainer/MemoryContainerConstants.java` - Added SUMMARY_FACTS_EXTRACTION_PROMPT constant and updated error messages
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/TransportCreateMemoryContainerAction.java` - Updated validation to accept "summary" type
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryProcessingService.java` - Added SUMMARY strategy routing and prompt selection
- `common/src/test/java/org/opensearch/ml/common/memorycontainer/MemoryTypeTest.java` - Added tests for SUMMARY enum value
- `common/src/test/java/org/opensearch/ml/common/memorycontainer/MemoryStrategyTest.java` - Added tests for SUMMARY strategy ID generation
- `plugin/src/test/java/org/opensearch/ml/action/memorycontainer/memory/MemoryProcessingServiceTests.java` - Added tests for SUMMARY strategy processing

## Recent Feature Updates (October 2025)

### Strategy-Level LLM Override and Session Improvements
- **LLM Override Feature**: Strategies can now specify their own `llm_id` in the `configuration` field
  - Strategy-level `llm_id` takes precedence over container-level configuration
  - Enables using different LLM models for different strategy types (e.g., powerful model for semantic extraction, lighter model for summaries)
  - Implementation: `MemoryProcessingService.getEffectiveLlmId()` helper method
  - Usage: Add `"llm_id": "model-id"` to strategy's `configuration` field

- **Auto-Generated Session Fix**: Sessions created via add memory API now properly include `memory_container_id` field
  - Previously: Only manually created sessions had memory_container_id
  - Now: All sessions (manual and auto-generated) include this field
  - Fixed in: `TransportAddMemoriesAction.createNewSessionIfAbsent()` method
  - Impact: Consistent session field population regardless of creation method

- **Debug Logging Enhancement**: Added debug-level logging for LLM model tracking
  - Logs which LLM model is being used for fact extraction
  - Shows whether model is from strategy override or container config
  - Logs facts count and similar memories count for memory decisions
  - Location: `MemoryProcessingService.extractFactsFromConversation()` and `makeMemoryDecisions()`
  - Level: `log.debug()` for non-intrusive production logging

- **Internal Improvements**: Consolidated searchData methods to use SDK client pattern
  - Migrated from legacy SearchRequest to modern SearchDataObjectRequest
  - Removed duplicate searchData method overloads
  - Better consistency with SDK client usage patterns

### Container ID Filtering for Cross-Container Isolation
- **Feature**: Automatic filtering by `memory_container_id` in all query operations
  - Prevents data leakage when containers share the same `index_prefix`
  - Applied to: User search API, LLM fact extraction, delete by query
  - Filter precedence: Container ID → Owner ID → Final query

- **Problem Solved**:
  - Previously: Containers sharing `index_prefix` could see each other's data
  - Now: Complete data isolation through indexed field filtering

- **Implementation**:
  - `MemoryContainerHelper.addContainerIdFilter()` - Helper methods for both SearchSourceBuilder and QueryBuilder
  - `MemorySearchQueryBuilder.buildFactSearchQuery()` - Added `memoryContainerId` parameter
  - Applied in: `TransportSearchMemoriesAction`, `TransportDeleteMemoriesByQueryAction`, `MemorySearchService`
  - Location: Lines 414-443 in MemoryContainerHelper.java, lines 148-151 in MemorySearchQueryBuilder.java

- **Behavior**:
  - Container ID filter applied for ALL users (including admins)
  - Gracefully handles null/blank container IDs (no filter applied)
  - Performance: Uses indexed field for efficient filtering

- **Example Scenario**:
  ```
  Container A: prefix="shared", namespace=["user_id"]
  Container B: prefix="shared", namespace=["user_id"]

  Before: Search Container A with user_id="bob" → Returns facts from both A and B ❌
  After:  Search Container A with user_id="bob" → Returns only Container A facts ✅

  Before: LLM extraction in Container A → Finds similar facts from Container B ❌
  After:  LLM extraction in Container A → Only finds facts from Container A ✅

  Before: Delete by query in Container A → May delete Container B's data ❌
  After:  Delete by query in Container A → Only deletes Container A's data ✅
  ```

### Memory Index Type Enum Refactoring
- **New Enum**: Created `MemoryType` enum to replace string constants
- **Type Safety**: Compile-time checking for memory types (SESSIONS, WORKING, LONG_TERM, HISTORY)
- **Centralized Logic**: Index naming patterns and validation in single enum class
- **Benefits**:
  - Simplified validation with `isValid()` method
  - Consistent index suffix patterns (`-memory-session`, `-memory-working`, etc.)
  - Helper methods for conversion and listing (`fromString()`, `getAllValues()`)
  - Cleaner switch statements using enum values
- **API Compatibility**: String values still accepted and parsed to enum

### Strategy Update and Merging
- **Feature**: Update container strategies dynamically through PUT API
- **Merge Logic**:
  - Strategies with ID: Updates existing strategy fields
  - Strategies without ID: Adds as new strategy with auto-generated ID
  - Partial updates supported (null fields retain current values)
- **Implementation**: Uses `StrategyMergeHelper` for merge operations

### Enhanced Validation
- **Namespace Requirement**: Now a required field for all strategies
- **Strategy Type Validation**: Only accepts "semantic", "user_preference", or "summary" (case-insensitive)
- **Error Messages**: Clear validation errors at container creation time

### Delete Memory Control
- **Selective Deletion**: Delete specific memory types when removing container
- **Deduplication**: Uses Set internally to prevent duplicate memory type processing
- **Owner-only**: Only container owner can delete (not backend roles)

### Refactored Architecture
- **Validation**: Moved to `MemoryStrategy.validate()` static method
- **Merging**: Separated into dedicated `StrategyMergeHelper` class
- **Memory Types**: New `MemoryType` enum for type-safe memory handling
- **Single Responsibility**: Each class has focused purpose

## References

- [plan_refactored.md](./plan_refactored.md) - Detailed refactoring plan
- [plan.md](./plan.md) - Original implementation plan
- [CLAUDE.md](./CLAUDE.md) - Main project documentation
- [STRATEGY_ID_FILTERING_TRACKER.md](./STRATEGY_ID_FILTERING_TRACKER.md) - Strategy ID filtering implementation tracker
