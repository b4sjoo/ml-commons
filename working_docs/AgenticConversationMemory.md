# AgenticConversationMemory - Technical Documentation

## Overview

AgenticConversationMemory is a **Memory Container-backed** conversation storage system that stores conversation history (messages and tool traces) in OpenSearch's Memory Container service, rather than directly in conversation indices.

It was introduced to support:
1. **Structured trace storage** - Tool calls and intermediate steps linked to parent messages
2. **Remote memory** - Storing conversation history on remote OpenSearch clusters (e.g., AOSS)
3. **Session-scoped queries** - Efficient retrieval of conversation history by session

---

## Architecture & Class Hierarchy

```
Memory<T, R, S> (Interface)
    │
    ├── ConversationIndexMemory     (stores in .plugins-ml-conversation-* indices)
    │
    ├── AgenticConversationMemory   (stores in Memory Container - local cluster)
    │
    └── RemoteAgenticConversationMemory (stores in Memory Container - remote cluster via connector)
```

### Type Parameters
- `T extends Message` - The message type (e.g., `Interaction`)
- `R` - Save response type (e.g., `CreateInteractionResponse`)
- `S` - Update response type (e.g., `UpdateResponse`)

### Key Source Files

| File | Description |
|------|-------------|
| `ml-algorithms/src/main/java/org/opensearch/ml/engine/memory/AgenticConversationMemory.java` | Local Memory Container implementation |
| `ml-algorithms/src/main/java/org/opensearch/ml/engine/memory/RemoteAgenticConversationMemory.java` | Remote Memory Container implementation |
| `common/src/main/java/org/opensearch/ml/common/memory/Memory.java` | Memory interface |
| `ml-algorithms/src/main/java/org/opensearch/ml/engine/memory/ConversationIndexMessage.java` | Message implementation |
| `common/src/main/java/org/opensearch/ml/common/conversation/Interaction.java` | Interaction data class |

---

## Data Structure

Each memory entry stored in the Memory Container has this structure:

```json
{
  "namespace": {
    "session_id": "conversation_123"
  },
  "metadata": {
    "type": "message",
    "parent_message_id": "msg_456",
    "trace_number": "1",
    "origin": "SearchTool"
  },
  "structured_data_blob": {
    "input": "What is the weather?",
    "response": "It's sunny today",
    "final_answer": true,
    "create_time": "2026-01-27T10:30:00Z",
    "updated_time": "2026-01-27T10:30:05Z"
  },
  "message_id": 1,
  "infer": false,
  "created_time": 1706383200000,
  "last_updated_time": 1706383200000
}
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `namespace.session_id` | String | Groups messages by conversation session |
| `metadata.type` | String | `"message"` for Q&A pairs, `"trace"` for tool calls |
| `metadata.parent_message_id` | String | For traces: links to the parent message |
| `metadata.trace_number` | String | For traces: ordering within parent |
| `metadata.origin` | String | For traces: tool/action name |
| `structured_data_blob.input` | String | User question or tool input |
| `structured_data_blob.response` | String | Assistant response or tool output |
| `structured_data_blob.final_answer` | Boolean | Whether this is the final response |
| `structured_data_blob.create_time` | String | ISO-8601 creation timestamp |
| `structured_data_blob.updated_time` | String | ISO-8601 last update timestamp |
| `message_id` | Integer | Trace number (null for messages) |
| `infer` | Boolean | For long-term memory inference (default: false) |

### Messages vs Traces

| Type | `traceNum` | `metadata.type` | Purpose |
|------|------------|-----------------|---------|
| **Message** | `null` | `"message"` | User Q&A pairs (final conversation turns) |
| **Trace** | `1, 2, 3...` | `"trace"` | Tool calls, intermediate steps (linked to parent) |

---

## Core Operations

### 1. Factory Creation

```java
// Get factory from registry
Memory.Factory factory = memoryFactoryMap.get(MLMemoryType.AGENTIC_MEMORY.name());

// Create memory instance
Map<String, Object> params = Map.of(
    "memory_id", "",                    // Empty = create new session
    "memory_container_id", "container_123",
    "app_type", "chat"
);

factory.create(params, ActionListener.wrap(
    memory -> { /* use memory */ },
    error -> { /* handle error */ }
));
```

**Flow:**
```
params: { memory_id, memory_container_id, app_type }
        ↓
If memory_id is empty:
  └─ MLCreateSessionAction → creates new session in Memory Container
        ↓
Return AgenticConversationMemory(conversationId, memoryContainerId, client)
```

### 2. Save Message

```java
ConversationIndexMessage message = ConversationIndexMessage.builder()
    .question("What is the weather?")
    .response("It's sunny today")
    .finalAnswer(true)
    .build();

memory.save(message, null, null, "final_answer", ActionListener.wrap(
    response -> {
        String messageId = response.getId();
    },
    error -> { /* handle error */ }
));
```

**Flow:**
```
save(message, parentId, traceNum, action, listener)
        ↓
Build structured_data_blob: { input, response, timestamps }
Build metadata: { type: isTrace ? "trace" : "message", ... }
Build namespace: { session_id: conversationId }
        ↓
MLAddMemoriesAction → stores in Memory Container
        ↓
Returns workingMemoryId (the message's unique ID)
```

### 3. Save Trace (Tool Call)

```java
ConversationIndexMessage traceMessage = ConversationIndexMessage.builder()
    .question("search_query: weather forecast")
    .response("{\"temperature\": 72, \"condition\": \"sunny\"}")
    .finalAnswer(false)
    .build();

memory.save(traceMessage, parentMessageId, 1, "SearchTool", listener);
```

### 4. Get Messages (Excluding Traces)

```java
memory.getMessages(10, ActionListener.wrap(
    messages -> {
        for (Message msg : messages) {
            // Process conversation history
        }
    },
    error -> { /* handle error */ }
));
```

**Query:**
```json
{
  "bool": {
    "must": [
      { "term": { "namespace.session_id": "conversation_123" } }
    ],
    "must_not": [
      { "term": { "metadata.type": "trace" } }
    ]
  }
}
```

### 5. Get Traces for a Message

```java
memory.getTraces(parentMessageId, ActionListener.wrap(
    traces -> {
        for (Interaction trace : traces) {
            // Process tool call traces
        }
    },
    error -> { /* handle error */ }
));
```

**Query:**
```json
{
  "bool": {
    "must": [
      { "term": { "namespace.session_id": "conversation_123" } },
      { "term": { "metadata.type": "trace" } },
      { "term": { "metadata.parent_message_id": "msg_456" } }
    ]
  }
}
```

### 6. Update Message

```java
Map<String, Object> updateContent = Map.of(
    "response", "Updated response text"
);

memory.update(messageId, updateContent, ActionListener.wrap(
    updateResponse -> { /* success */ },
    error -> { /* handle error */ }
));
```

**Flow:**
```
update(messageId, updateContent, listener)
        ↓
MLGetMemoryAction → fetch existing memory
        ↓
Merge updateContent into existing structured_data_blob
Update timestamp
        ↓
MLUpdateMemoryAction → save back
```

---

## Integration with Agent Framework

### MLChatAgentRunner Integration

```java
// 1. Create memory via Factory
String memoryType = MLMemoryType.from(mlAgent.getMemory().getType()).name();
Memory.Factory factory = memoryFactoryMap.get(memoryType);

Map<String, Object> memoryParams = createMemoryParams(title, memoryId, appType, mlAgent, params);
factory.create(memoryParams, ActionListener.wrap(memory -> {
    // 2. Run agent with memory
    runAgent(mlAgent, params, listener, memory, memoryId, functionCalling);
}, listener::onFailure));
```

### Agent Execution Flow

```
1. Create memory via Factory
   └─ memoryFactoryMap.get(MLMemoryType.AGENTIC_MEMORY.name())

2. During agent execution:
   ├─ Save user message (traceNum=null)
   │     └─ memory.save(userMsg, null, null, "user_input", listener)
   │
   ├─ For each tool call: save trace (traceNum=1,2,3...)
   │     └─ memory.save(toolMsg, parentId, traceNum, toolName, listener)
   │
   └─ Save final answer (traceNum=null, finalAnswer=true)
         └─ memory.save(finalMsg, null, null, "final_answer", listener)

3. For context building:
   └─ memory.getMessages(limit) → inject into prompt
```

---

## RemoteAgenticConversationMemory

This variant uses an **inline HTTP connector** to communicate with a remote Memory Container service (e.g., on Amazon OpenSearch Serverless - AOSS).

### Key Differences from AgenticConversationMemory

| Aspect | AgenticConversationMemory | RemoteAgenticConversationMemory |
|--------|---------------------------|--------------------------------|
| Communication | Direct client calls | HTTP via connector |
| Authentication | Cluster internal | AWS SigV4 or HTTP basic |
| Retry Logic | None | Exponential backoff |
| Use Case | Local cluster | Remote AOSS/OpenSearch |

### Inline Connector Creation

```java
// Parameters for remote memory
Map<String, Object> params = Map.of(
    "memory_id", "",
    "memory_container_id", "container_123",
    "endpoint", "https://xxx.aoss.us-west-2.amazonaws.com",
    "region", "us-west-2",
    "credential", "{\"roleArn\": \"arn:aws:iam::123456789:role/MyRole\"}"
);
```

**Connector Actions Created:**

| Action | HTTP Method | Endpoint |
|--------|-------------|----------|
| `create_session` | POST | `/_plugins/_ml/memory_containers/{id}/memories/sessions` |
| `add_memory` | POST | `/_plugins/_ml/memory_containers/{id}/memories` |
| `search_memories` | GET | `/_plugins/_ml/memory_containers/{id}/memories/{type}/_search` |
| `get_memory` | GET | `/_plugins/_ml/memory_containers/{id}/memories/{type}/{id}` |
| `update_memory` | PUT | `/_plugins/_ml/memory_containers/{id}/memories/{type}/{id}` |
| `delete_memory` | DELETE | `/_plugins/_ml/memory_containers/{id}/memories/{type}/{id}` |

### Retry Logic

RemoteAgenticConversationMemory implements exponential backoff retry for transient failures:

```
Retry delays: 500ms → 1s → 2s → 4s → 8s (max 5 retries)

Retryable errors:
├─ 404 / "found":false (AOSS refresh latency)
├─ version_conflict (concurrent updates)
├─ timeout errors
└─ 502/503 service unavailable

Non-retryable:
├─ Authentication failures
├─ Validation errors
└─ 400 bad request
```

**Implementation:**
```java
private void updateWithRetry(String messageId, Map<String, Object> content,
                             ActionListener<UpdateResponse> listener, int attemptNumber) {
    if (attemptNumber >= MAX_RETRIES) {
        listener.onFailure(new RuntimeException("Max retries exceeded"));
        return;
    }

    // Attempt update...
    // On retryable failure:
    long delayMs = 500 * (long) Math.pow(2, attemptNumber);
    client.threadPool().schedule(
        () -> updateWithRetry(messageId, content, listener, attemptNumber + 1),
        TimeValue.timeValueMillis(delayMs),
        AGENTIC_MEMORY_THREAD_POOL
    );
}
```

---

## Async Pattern

All operations use OpenSearch's `ActionListener` callback pattern:

```java
// Success/failure callbacks
memory.save(message, null, null, "action", ActionListener.wrap(
    response -> {
        // Success path
        String messageId = response.getId();
    },
    exception -> {
        // Error path
        log.error("Failed to save", exception);
    }
));

// Chaining operations
memory.save(msg, null, null, "action", ActionListener.wrap(
    saveResponse -> {
        memory.getMessages(10, ActionListener.wrap(
            messages -> { /* use messages */ },
            error -> { /* handle error */ }
        ));
    },
    error -> { /* handle error */ }
));
```

---

## Comparison with ConversationIndexMemory

| Aspect | ConversationIndexMemory | AgenticConversationMemory |
|--------|------------------------|---------------------------|
| **Storage** | Direct index writes to `.plugins-ml-conversation-*` | Via Memory Container service |
| **Traces** | Stored in separate interactions index | Stored with `metadata.type="trace"` |
| **Remote Support** | Not supported | Supported via RemoteAgenticConversationMemory |
| **Session Scoping** | By conversation ID in index | By `namespace.session_id` |
| **Use Case** | Traditional conversation memory | Agent-centric with tool tracing |
| **Memory Type Enum** | `CONVERSATION_INDEX` | `AGENTIC_MEMORY` / `REMOTE_AGENTIC_MEMORY` |

---

## Configuration

### Memory Type Registration

In the ML plugin initialization:

```java
memoryFactoryMap.put(
    MLMemoryType.CONVERSATION_INDEX.name(),
    conversationIndexMemoryFactory
);
memoryFactoryMap.put(
    MLMemoryType.AGENTIC_MEMORY.name(),
    agenticConversationMemoryFactory
);
memoryFactoryMap.put(
    MLMemoryType.REMOTE_AGENTIC_MEMORY.name(),
    remoteAgenticConversationMemoryFactory
);
```

### Agent Registration with Memory

```json
POST /_plugins/_ml/agents/_register
{
  "name": "My Agent",
  "type": "conversational",
  "memory": {
    "type": "conversation_index"
  },
  "tools": [...]
}
```

For remote agentic memory, the memory configuration is provided at execution time with endpoint/credentials.

---

## Error Handling

### Common Errors

| Error | Cause | Resolution |
|-------|-------|------------|
| `Memory container ID is not configured` | Missing `memory_container_id` param | Provide valid container ID |
| `Endpoint is required` | Remote memory without endpoint | Provide endpoint URL |
| `Failed create memory from id` | Invalid memory ID or container | Verify IDs exist |

### Unsupported Operations

```java
// These operations throw UnsupportedOperationException
memory.clear();  // Not implemented

// This returns false and logs warning
memory.deleteInteractionAndTrace(id);  // Not fully implemented
```

---

## Historical Context

Introduced in commit `fed7e520d55b6fc59cd884f16bb481eb65f24fb0` ("refactor memory interface; add agentic conversation memory #4434"):

- Created generic `Memory<T, R, S>` interface (replaced monolithic implementation)
- Added `AgenticConversationMemory` for Memory Container backend
- Added `MLMemoryType` enum for type-safe memory selection
- Extended `Interaction` class with `parentInteractionId` and `traceNum` fields
- Refactored agent runners to use factory pattern for memory creation
