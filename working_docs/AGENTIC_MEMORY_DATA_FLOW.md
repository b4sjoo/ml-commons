# Agentic Memory Data Flow Diagram

Complete architecture and data flow documentation for the ML Commons Agentic Memory feature.

---

## Overview Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         AGENTIC MEMORY SYSTEM                               │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      REST API LAYER                                   │  │
│  │  - RestMLCreateMemoryContainerAction                                  │  │
│  │  - RestMLGetMemoryContainerAction                                     │  │
│  │  - RestMLUpdateMemoryContainerAction                                  │  │
│  │  - RestMLDeleteMemoryContainerAction                                  │  │
│  │  - RestMLSearchMemoryContainerAction                                  │  │
│  │  - RestMLAddMemoriesAction                                            │  │
│  │  - RestML[Get/Update/Delete/Search]MemoriesAction (×4 memory types)   │  │
│  │  - RestMLDeleteMemoriesByQueryAction                                  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    ↓                                        │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                   TRANSPORT ACTION LAYER                               │  │
│  │  - TransportCreateMemoryContainerAction                                │  │
│  │  - TransportAddMemoriesAction (Core Memory Processing)                 │  │
│  │  - TransportGetMemoryAction                                            │  │
│  │  - TransportUpdateMemoryAction                                         │  │
│  │  - TransportDeleteMemoryAction                                         │  │
│  │  - TransportSearchMemoriesAction                                       │  │
│  │  - TransportDeleteMemoriesByQueryAction                                │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    ↓                                          │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     BUSINESS LOGIC LAYER                               │  │
│  │  - MemoryProcessingService (LLM Integration)                           │  │
│  │  - MemoryOperationsService (CRUD Operations)                           │  │
│  │  - MemorySearchQueryBuilder (Query Construction)                       │  │
│  │  - MemoryContainerHelper (Utility Methods)                             │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    ↓                                          │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    FOUR-INDEX STORAGE LAYER                            │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐  ┌─────────────┐ │  │
│  │  │   Session   │  │   Working    │  │  Long-Term  │  │   History   │ │  │
│  │  │    Index    │  │Memory Index  │  │Memory Index │  │    Index    │ │  │
│  │  └─────────────┘  └──────────────┘  └─────────────┘  └─────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Data Flow: Create Memory Container API

### 1. Request Ingestion

**API Call:**
```http
POST /_plugins/_ml/memory_containers/_create
```

**Request Body:**
```json
{
  "name": "my-memory-container",
  "description": "Container for chatbot memories",
  "configuration": {
    "index_prefix": "chatbot",
    "use_system_index": true,
    "llm_id": "gpt-4-model-id",
    "embedding_model_type": "TEXT_EMBEDDING",
    "embedding_model_id": "sentence-transformer-id",
    "dimension": 768,
    "max_infer_size": 5,
    "disable_history": false,
    "disable_session": false,
    "parameters": {
      "session": {
        "max_summary_size": "10"
      }
    }
  },
  "strategies": [
    {
      "type": "SEMANTIC",
      "enabled": true,
      "namespace": ["user_id"],
      "configuration": {
        "system_prompt": "Extract factual information..."
      }
    },
    {
      "type": "USER_PREFERENCE",
      "enabled": true,
      "namespace": ["user_id", "agent_id"]
    }
  ]
}
```

**Processing:**
- `RestMLCreateMemoryContainerAction` receives request
- Checks `isAgenticMemoryEnabled()` feature flag
- Parses request body into `MLCreateMemoryContainerInput`
- Creates `MLCreateMemoryContainerRequest`

---

### 2. Configuration Validation

**TransportCreateMemoryContainerAction validates:**

#### 2.1 Strategy Validation
- **Strategy types:** Must be "semantic", "user_preference", or "summary" (case-insensitive)
- **Auto-generate strategy IDs:** If not provided, generates IDs with format `{type}_{UUID}`
  - Example: `semantic_a1b2c3d4-...`, `user_preference_b2c3d4e5-...`
- **Custom prompts:** If provided in `configuration.system_prompt`, validates JSON format with 'facts' array

#### 2.2 Embedding Model Validation
- **Both or neither:** `embedding_model_type` and `embedding_model_id` must be provided together
- **TEXT_EMBEDDING:** Requires `dimension` parameter
- **SPARSE_ENCODING:** Must NOT include `dimension` parameter
- **Model state:** Validates embedding model is DEPLOYED (skips for REMOTE models)

#### 2.3 LLM Model Validation
- **Optional:** LLM model is independent of semantic storage
- **Model state:** If provided, validates LLM is DEPLOYED (skips for REMOTE models)

#### 2.4 Configuration Defaults
- **use_system_index:** Defaults to `true`
- **max_infer_size:** Defaults to 5, maximum 10
- **disable_history:** Defaults to `false`
- **disable_session:** Defaults to `false`
- **index_prefix:**
  - System indices: Optional (uses `.plugins-ml-am` prefix)
  - Non-system indices: Auto-generates UUID if not provided

---

### 3. Index Creation

**MLIndicesHandler creates four indices:**

#### 3.1 Session Index
- **Name:** `{prefix}-memory-session`
- **Mapping:** Static mapping from `ml_memory_session.json`
- **Fields:** `owner_id`, `namespace`, `summary`, `created_time`

#### 3.2 Working Memory Index
- **Name:** `{prefix}-memory-working`
- **Mapping:** Static mapping from `ml_memory_working.json`
- **Fields:** `owner_id`, `namespace`, `namespace_size`, `messages`, `binary_data`, `structured_data`, `memory_type`, `metadata`, `tags`, `created_time`, `last_updated_time`

#### 3.3 Long-term Memory Index
- **Name:** `{prefix}-memory-long-term`
- **Base Mapping:** Loaded from `ml_memory_long_term.json`
- **Dynamic Fields:** Added based on `embedding_model_type`:

  **If TEXT_EMBEDDING:**
  ```json
  "embeddings": {
    "type": "knn_vector",
    "dimension": 768,
    "method": {
      "name": "hnsw",
      "engine": "lucene",
      "space_type": "l2"
    }
  }
  ```

  **If SPARSE_ENCODING:**
  ```json
  "embeddings": {
    "type": "rank_features"
  }
  ```

- **Fields:** `owner_id`, `namespace`, `namespace_size`, `memory`, `tags`, `memory_type`, `strategy_id`, `embeddings`, `created_time`, `last_updated_time`

#### 3.4 History Index
- **Name:** `{prefix}-memory-history`
- **Mapping:** Static mapping from `ml_memory_history.json`
- **Fields:** `owner_id`, `namespace`, `namespace_size`, `operation`, `memory_id`, `before`, `after`, `tags`, `created_time`

**Index Creation Flow:**
```
1. Generate index prefix (if needed)
2. Create session index (if not disabled)
3. Create working memory index
4. Create long-term memory index (with dynamic embedding mapping)
5. Create history index (if not disabled)
6. All indices created with system index flag if use_system_index=true
```

---

### 4. Access Control Setup

**Sets up ownership and access control:**
- **owner_id:** Extracted from authenticated user context
- **tenant_id:** Extracted from user context (for multi-tenancy)
- **backend_roles:** Copied from user's backend roles (for shared access)

---

### 5. Container Storage

**Create MLMemoryContainer:**
```java
MLMemoryContainer {
  name,
  description,
  configuration: MemoryConfiguration {
    indexPrefix,           // Generated or provided
    useSystemIndex,
    llmId,
    embeddingModelType,
    embeddingModelId,
    dimension,
    maxInferSize,
    disableHistory,
    disableSession,
    parameters
  },
  strategies: List<MemoryStrategy> {
    strategyId,            // Auto-generated with type prefix
    type,                  // SEMANTIC, USER_PREFERENCE, or SUMMARY
    enabled,
    namespace,
    strategyConfig
  },
  ownerId,
  tenantId,
  backendRoles,
  createdTime,
  lastUpdatedTime
}
```

**Store:**
- Index: `.plugins-ml-am-memory-container`
- ID: auto-generated container ID
- Uses: `sdkClient.indexDataObjectAsync()`

---

### 6. Response

**MLCreateMemoryContainerResponse:**
```json
{
  "container_id": "abc123def456",
  "name": "my-memory-container",
  "configuration": {
    "index_prefix": "chatbot",
    "indices": {
      "session": ".plugins-ml-am-chatbot-memory-session",
      "working": ".plugins-ml-am-chatbot-memory-working",
      "long_term": ".plugins-ml-am-chatbot-memory-long-term",
      "history": ".plugins-ml-am-chatbot-memory-history"
    }
  },
  "strategies": [
    {
      "strategy_id": "semantic_a1b2c3d4-e5f6-...",
      "type": "SEMANTIC",
      "enabled": true
    },
    {
      "strategy_id": "user_preference_b2c3d4e5-f6a7-...",
      "type": "USER_PREFERENCE",
      "enabled": true
    }
  ],
  "created_time": 1696204800000
}
```

---

### 7. Error Handling

**Common validation errors:**

1. **Invalid Strategy Type**
   - Error: `"Invalid strategy type: {type}. Must be one of: semantic, user_preference, summary"`
   - Status: 400 BAD_REQUEST

2. **Missing Dimension for TEXT_EMBEDDING**
   - Error: `"Dimension is required when embedding_model_type is TEXT_EMBEDDING"`
   - Status: 400 BAD_REQUEST

3. **Dimension Provided for SPARSE_ENCODING**
   - Error: `"Dimension must not be specified when embedding_model_type is SPARSE_ENCODING"`
   - Status: 400 BAD_REQUEST

4. **Incomplete Embedding Configuration**
   - Error: `"Both embedding_model_type and embedding_model_id must be provided together"`
   - Status: 400 BAD_REQUEST

5. **Max Infer Size Exceeded**
   - Error: `"max_infer_size cannot exceed 10"`
   - Status: 400 BAD_REQUEST

6. **Model Not Deployed**
   - Error: `"Model {model_id} is not in DEPLOYED state"`
   - Status: 400 BAD_REQUEST

7. **Invalid Custom Prompt Format**
   - Error: `"Invalid custom prompt format - must specify JSON response format with 'facts' array"`
   - Status: 400 BAD_REQUEST

8. **Feature Disabled**
   - Error: `"The Agentic Memory APIs are not enabled..."`
   - Status: 403 FORBIDDEN

---

## Detailed Data Flow: Add Memory API

### 1. Request Ingestion

**API Call:**
```http
POST /_plugins/_ml/memory_containers/{id}/memories
```

**Request Body:**
```json
{
  "messages": [{"role": "user", "content": "Hello"}],
  "namespace": {
    "session_id": "sess_123",
    "user_id": "user_456",
    "agent_id": "agent_789"
  },
  "infer": true,
  "metadata": {...},
  "tags": {...}
}
```

**Processing:**
- `RestMLAddMemoriesAction` receives request
- Checks `isAgenticMemoryEnabled()` feature flag
- Extracts `container_id` from URL path
- Creates `MLAddMemoriesRequest`

---

### 2. Container Validation

**TransportAddMemoriesAction validates:**
- `memoryContainerId` is not null
- At least one data field provided (messages, binaryData, or structuredData)
- Auto-determines `memoryType` (CONVERSATIONAL/DATA)
- Auto-determines `infer` flag based on type

**Fetch Memory Container:**
- Uses `sdkClient.getDataObjectAsync()`
- Index: `.plugins-ml-am-memory-container`
- Retrieves `MemoryConfiguration` and `strategies`
- Validates access control (owner, tenant)

---

### 3. Session Management

**If creating new session:**

1. **Generate Summary using LLM**
   - Uses: `SESSION_SUMMARY_PROMPT`
   - Input: messages from request
   - Max size: configurable (default 10 words)

2. **Create MLMemorySession**
   - Fields: `owner_id`, `namespace`, `summary`, `created_time`

3. **Store in Session Index**
   - Index: `{prefix}-memory-session`
   - ID: auto-generated session_id

---

### 4. Working Memory Storage

**Create MLWorkingMemory:**
```java
MLWorkingMemory {
  owner_id,
  namespace,              // Complete namespace from request
  namespace_size,
  messages,              // If CONVERSATIONAL type
  message_id,            // For message tracking
  binary_data,           // If DATA type
  structured_data,       // If DATA type
  payload_type,          // CONVERSATIONAL or DATA
  metadata,
  tags,
  created_time,
  last_updated_time
}
```

**Store:**
- Index: `{prefix}-memory-working`
- ID: auto-generated
- Uses: `sdkClient.indexDataObjectAsync()`

---

### 5. Strategy-based Processing

**For each enabled strategy in container configuration:**

#### Step 5.1: Fact Extraction via LLM

**MemoryProcessingService:**
- Checks strategy type:
  - `SEMANTIC` → `SEMANTIC_FACTS_EXTRACTION_PROMPT`
  - `USER_PREFERENCE` → `USER_PREFERENCE_FACTS_EXTRACTION_PROMPT`
  - `SUMMARY` → `SUMMARY_FACTS_EXTRACTION_PROMPT`

- **Custom prompt support:**
  - Checks `strategy.strategyConfig` for `system_prompt`
  - Falls back to default prompts
  - Validates JSON format with 'facts' array

- **Calls LLM:**
  - Model: `container.configuration.llm_id`
  - Input: messages + system_prompt
  - Output: `List<String> facts`

#### Step 5.2: Semantic Search for Similar Facts

**For each extracted fact:**
- Generate embedding (if configured)
- Search long-term index:
  - Filter by: `strategy_id`, `memory_type`, `namespace` (strategy-specific)
  - KNN search (if TEXT_EMBEDDING)
  - Rank features (if SPARSE_ENCODING)
- Returns: `List<FactSearchResult>` with similarity scores

#### Step 5.3: LLM Decision Making

**MemoryProcessingService.makeLLMDecisions():**
- **Input:**
  - New facts (extracted)
  - Old facts (from search results)
  - Similarity scores
- **Prompt:** `DEFAULT_UPDATE_MEMORY_PROMPT`
- **LLM Output:** `List<MemoryDecision>`
  - `operation`: ADD, UPDATE, DELETE, NONE
  - `memory_id`: (for UPDATE/DELETE)
  - `memory_text`: (new/updated fact)

#### Step 5.4: Execute Memory Operations

**For each MemoryDecision:**

**ADD Operation:**
```java
MLLongTermMemory {
  owner_id,
  namespace,           // Filtered by strategy requirements
  namespace_size,
  memory,              // Fact text
  embedding,           // If configured
  tags,
  memory_strategy_type, // SEMANTIC/USER_PREFERENCE/SUMMARY
  strategy_id
}
```
- Store in Long-term Index
- Create History Entry (CREATE event)

**UPDATE Operation:**
- Fetch existing memory
- Update `memory` field
- Update `last_updated_time`
- Store in Long-term Index
- Create History Entry (UPDATE event) with `before` and `after` states

**DELETE Operation:**
- Fetch existing memory
- Delete from Long-term Index
- Create History Entry (DELETE event) with `before` state

**NONE Operation:**
- Skip (no action needed)

---

### 6. History Tracking

**Create History Records:**
```java
MLMemoryHistory {
  owner_id,
  namespace,           // Filtered by strategy
  namespace_size,
  operation,           // CREATE, UPDATE, DELETE
  memory_id,
  before,              // Map<String, Object> - old state
  after,               // Map<String, Object> - new state
  tags,
  created_time
}
```
- Index: `{prefix}-memory-history`

---

### 7. Response

**MLAddMemoriesResponse:**
```json
{
  "working_memory_id": "wm_abc123",
  "session_id": "sess_123",
  "message": "Memory added successfully"
}
```

---

## Key Components Detail

### 1. Four-Index Architecture

#### Container Index
- **Name:** `.plugins-ml-am-memory-container`
- **Stores:** Container configuration, strategies
- **Fields:** `name`, `description`, `configuration`, `strategies`, `owner`, `tenant_id`

#### Session Index
- **Name:** `{prefix}-memory-session`
- **Stores:** Conversation session metadata
- **Fields:** `owner_id`, `namespace`, `summary`, `created_time`
- **Purpose:** Track conversation contexts

#### Working Memory Index
- **Name:** `{prefix}-memory-working`
- **Stores:** Raw conversational data and inputs
- **Fields:** `owner_id`, `namespace` (complete), `messages`, `binary_data`, `structured_data`, `memory_type`, `metadata`, `tags`
- **Purpose:** Short-term storage of all inputs

#### Long-term Memory Index
- **Name:** `{prefix}-memory-long-term`
- **Stores:** Extracted facts with embeddings
- **Fields:** `owner_id`, `namespace` (strategy-filtered), `memory` (fact text), `embedding` (vector), `memory_type`, `strategy_id`, `tags`
- **Purpose:** Semantic search and retrieval
- **Mapping:** Dynamic (supports TEXT_EMBEDDING or SPARSE_ENCODING)

#### History Index
- **Name:** `{prefix}-memory-history`
- **Stores:** Audit trail of all memory operations
- **Fields:** `owner_id`, `namespace` (strategy-filtered), `operation`, `memory_id`, `before`, `after`, `tags`
- **Purpose:** Audit and rollback capability

---

### 2. Strategy System

#### Strategy Configuration

```json
{
  "strategy_id": "semantic_a1b2c3d4-...",
  "type": "SEMANTIC",
  "enabled": true,
  "namespace": ["user_id", "agent_id"],
  "configuration": {
    "system_prompt": "Custom prompt..."
  }
}
```

#### Strategy Types

**1. SEMANTIC** - General factual knowledge
- Prompt: `SEMANTIC_FACTS_EXTRACTION_PROMPT`
- Stores: Facts, events, statements

**2. USER_PREFERENCE** - User preferences and settings
- Prompt: `USER_PREFERENCE_FACTS_EXTRACTION_PROMPT`
- Stores: Likes, dislikes, preferences

**3. SUMMARY** - Content summarization
- Prompt: `SUMMARY_FACTS_EXTRACTION_PROMPT`
- Stores: Summaries, key points

#### Namespace Filtering

- **Working Memory:** Stores complete namespace from request
- **Long-term/History:** Stores only fields declared in strategy

**Example:**
```
Request namespace: {session_id, agent_id, user_id}
Strategy namespace: ["user_id"]
→ Long-term stores only: {user_id}
```

---

### 3. Access Control Flow

#### 1. Feature Gate Check
- `MLFeatureEnabledSetting.isAgenticMemoryEnabled()`
- Returns 403 FORBIDDEN if disabled

#### 2. Container Access
- Verify user is owner or has access
- Check backend roles (if access control enabled)
- Validate `tenant_id` (if multi-tenancy enabled)

#### 3. Memory Operations
- Filter by `owner_id` for queries
- Non-admin users: only see their own memories
- Admin users: can see all memories

#### 4. Delete By Query
- Automatically adds owner filter for non-admin
- Supports system indices with `ThreadContext.stashContext()`

---

## API Endpoints Summary

### Group 1: Container Management (5 endpoints)
```http
POST   /_plugins/_ml/memory_containers/_create
GET    /_plugins/_ml/memory_containers/{id}
PUT    /_plugins/_ml/memory_containers/{id}
DELETE /_plugins/_ml/memory_containers/{id}?delete_all_memories={true|false}
POST   /_plugins/_ml/memory_containers/_search
```

### Group 2: Memory Operations
```http
POST   /_plugins/_ml/memory_containers/{id}/memories        # Add memory
POST   /_plugins/_ml/memory_containers/{id}/memories/sessions  # Create session
```

### Group 3: Unified Memory APIs (16 combinations)
**Pattern:** `/_plugins/_ml/memory_containers/{id}/memories/{type}/{memory_id}`

**Memory Types:** `session`, `working`, `long_term`, `history`

**Operations:** GET, PUT, DELETE, POST _search

**Examples:**
```http
GET    .../{id}/memories/working/{mem_id}
PUT    .../{id}/memories/long_term/{mem_id}
DELETE .../{id}/memories/session/{sess_id}
POST   .../{id}/memories/history/_search
```

### Group 4: Delete By Query (4 endpoints)
```http
POST/DELETE .../{id}/memories/{type}/_delete_by_query
```

---

## Data Models

### MLMemoryContainer
```java
{
  id,
  name,
  description,
  configuration: MemoryConfiguration,
  strategies: List<MemoryStrategy>,
  owner,
  tenant_id,
  created_time,
  last_updated_time
}
```

### MemoryConfiguration
```java
{
  index_prefix,
  use_system_index,
  llm_id,                        // Optional
  embedding_model_type,          // TEXT_EMBEDDING or SPARSE_ENCODING
  embedding_model_id,
  dimension,                     // Required for TEXT_EMBEDDING
  max_infer_size,
  disable_history,
  disable_session
}
```

### MLWorkingMemory
```java
{
  owner_id,
  namespace: Map<String, String>,
  namespace_size,
  messages: List<MessageInput>,
  message_id,                    // New field for message tracking
  binary_data,
  structured_data,
  payload_type,                  // CONVERSATIONAL or DATA (renamed from memory_type)
  metadata,
  tags,
  created_time,
  last_updated_time
}
```

### MLLongTermMemory (renamed from MLMemory)
```java
{
  owner_id,
  namespace,                     // Strategy-filtered
  namespace_size,
  memory,                        // Fact text
  embedding,                     // Vector or sparse
  memory_strategy_type,          // SEMANTIC, USER_PREFERENCE, SUMMARY (renamed from memory_type)
  strategy_id,
  tags,
  created_time,
  last_updated_time
}
```

### MLMemorySession
```java
{
  owner_id,
  namespace,
  summary,
  created_time
}
```

### MLMemoryHistory
```java
{
  owner_id,
  namespace,                     // Strategy-filtered
  namespace_size,
  operation,                     // CREATE, UPDATE, DELETE
  memory_id,
  before: Map<String, Object>,
  after: Map<String, Object>,
  tags,
  created_time
}
```

---

## Summary

This comprehensive data flow diagram shows the complete agentic memory feature architecture, from API ingestion through strategy-based processing to multi-index storage with full audit trails. The system provides:

- **Four-index architecture** for efficient data organization
- **Strategy-based processing** with LLM integration for intelligent memory management
- **Namespace filtering** for precise context-aware memory storage
- **Complete audit trail** with before/after state tracking
- **Access control** at multiple levels (feature, container, memory)
- **Flexible configuration** supporting multiple embedding types and custom prompts
