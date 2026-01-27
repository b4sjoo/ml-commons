# AgenticConversationMemory (low-level)

This document captures how `AgenticConversationMemory` works in this repo, based on code. It is the in-process memory implementation that stores agent conversations in the **Memory Container** subsystem (working memory), rather than the legacy conversation index.

Source files (primary):
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/memory/AgenticConversationMemory.java`
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/memory/RemoteAgenticConversationMemory.java` (remote variant)
- `common/src/main/java/org/opensearch/ml/common/transport/memorycontainer/memory/*` (transport inputs/outputs)
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLAgentExecutor.java` (integration)

## Overview
`AgenticConversationMemory` implements:
```
Memory<Message, CreateInteractionResponse, UpdateResponse>
```
It stores chat messages and tool traces as **working memories** inside a Memory Container, using the memory container transport actions:
- `MLCreateSessionAction`
- `MLAddMemoriesAction`
- `MLGetMemoryAction`
- `MLUpdateMemoryAction`
- `MLSearchMemoriesAction`

It requires a **memory container ID** and a **conversation/session ID**.

## Creation (Factory)
Factory: `AgenticConversationMemory.Factory`

Inputs in `create(Map<String, Object>)`:
- `memory_id` (optional)
- `memory_name` (optional but used when creating a session)
- `app_type` (unused here, passed through)
- `memory_container_id` (**required**)

Behavior:
- If `memory_container_id` is missing: fail fast with `IllegalArgumentException`.
- If `memory_id` is empty: create a new **session** via `MLCreateSessionAction`, then use the returned `session_id` as the memory ID.
- If `memory_id` is provided: use it directly.

The factory **does not** create indices; it relies on Memory Container APIs.

## Save (write path)
Method: `save(Message message, String parentId, Integer traceNum, String action, ActionListener<CreateInteractionResponse>)`

Key details:
- The method **casts** `Message` to `ConversationIndexMessage`. Passing other message types will throw `ClassCastException`.
- It builds a Memory Container document with:
  - `namespace`: `{ "session_id": conversationId }`
  - `metadata`: includes `type` (`message` or `trace`) and optional fields
  - `structured_data`: normalized payload with input/response and timestamps
  - `message_id`: set to `traceNum` for traces, `null` for messages
  - `infer=false`: disables long-term memory inference by default

### Message vs Trace
- **Trace** is identified by `traceNum != null`.
  - `metadata.type = "trace"`
  - `metadata.parent_message_id` (if provided)
  - `metadata.trace_number = traceNum`
  - `metadata.origin = action` (if provided)
  - `structured_data` mirrors those fields
- **Message** (`traceNum == null`)
  - `metadata.type = "message"`
  - `structured_data.final_answer` set when available

### Timestamps
- `structured_data.create_time` and `structured_data.updated_time` are set to `Instant.now().toString()`.

### Transport call
- Sends `MLAddMemoriesRequest(MLAddMemoriesInput)` via `MLAddMemoriesAction`.
- On success, returns `CreateInteractionResponse` with `working_memory_id` from the add response.

## Update
Method: `update(String messageId, Map<String, Object> updateContent, ActionListener<UpdateResponse>)`

Flow:
1. `MLGetMemoryAction` fetches the existing working memory.
2. Merges `updateContent` into `structured_data`.
3. Updates `structured_data.updated_time`.
4. Sends `MLUpdateMemoryAction` with:
   - `memory_container_id`
   - `memory_type = working`
   - `memory_id = messageId`
   - `update_content = { structured_data: merged }`

Notes:
- If the target working memory is missing, it fails with `IllegalStateException`.

## Read: getMessages()
Method: `getMessages(int size, ActionListener<List<Message>>)`

Search conditions:
- `namespace.session_id == conversationId`
- **must_not** `structured_data.trace_number` (exclude traces)
- sort by `created_time` ascending

Parsing:
- Extracts `structured_data.input` and `structured_data.response`.
- Timestamps:
  - Prefer `structured_data.create_time` / `structured_data.updated_time` if parseable.
  - Fallback to `created_time` / `last_updated_time` on the doc.
- Builds `Interaction` objects with `origin = "agentic_memory"`.

## Read: getTraces(parentMessageId)
Method: `getTraces(String parentMessageId, ActionListener<List<Interaction>>)`

Search conditions:
- `namespace.session_id == conversationId`
- `metadata.type == trace`
- `metadata.parent_message_id == parentMessageId`
- sort by `message_id` (trace number)

Parsing:
- Reads `structured_data.trace_number` (or falls back to root `message_id`).
- Creates `Interaction` entries with `traceNum` and `origin`.

## Not Implemented / Limitations
- `clear()` throws `UnsupportedOperationException`.
- `deleteInteractionAndTrace()` is stubbed; logs a warning and returns `false`.
- Requires `memory_container_id` for any operation; otherwise fails fast.
- Strongly coupled to `ConversationIndexMessage` as input type.

## Data Model Summary (fields written)
**Namespace:**
- `session_id`: conversation ID

**Metadata:**
- `type`: `message` or `trace`
- `parent_message_id` (trace only)
- `trace_number` (trace only)
- `origin` (trace only; from `action`)

**Structured Data:**
- `input`
- `response`
- `final_answer` (message only)
- `parent_message_id`, `trace_number`, `origin` (trace only)
- `create_time`, `updated_time`

**Other fields:**
- `message_id`: trace number for traces
- `infer`: false

## How agents use it
`MLAgentExecutor` uses the memory factory map to create memory instances. When agent input includes memory specs and a `memory_container_id`, it routes to `AgenticConversationMemory` (or to `RemoteAgenticConversationMemory` if inline connector metadata is present). It then:
1. Saves the root interaction (question/response) to memory.
2. Stores tool traces as `trace` entries with parent linkage.
3. Updates memory entries with additional info after execution.

See:
- `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLAgentExecutor.java`

## Remote variant (context)
`RemoteAgenticConversationMemory` performs the same logical operations, but **calls Memory Container APIs through a connector** (HTTP/AWS SigV4), and includes retry logic for update operations. It is selected when agent input includes inline connector metadata (e.g., `endpoint`).

---
If you want this document expanded with JSON request/response examples or call stacks from `MLAgentExecutor`, tell me which path to focus on.
