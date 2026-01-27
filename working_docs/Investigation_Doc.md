# Agentic Memory & Remote Storage Investigation

## 1. Feature Highlights
- `AgenticConversationMemory` is the primary `Memory` implementation for agent execution. It persists conversations in memory containers, enforces the configured container id, and retries updates to absorb AOSS latency (`ml-algorithms/src/main/java/org/opensearch/ml/engine/memory/AgenticConversationMemory.java`).
- `MLAgentExecutor` dynamically resolves memory types, creates or reuses sessions before tool execution, and wires regeneration runs through the same memory id (`ml-algorithms/.../MLAgentExecutor.java`).
- Agent definitions already record memory metadata (type, container id, optional session id) so factories can instantiate the correct backend (`ml-algorithms/.../AgentUtils.java`).
- Shared models and plugin wiring recognise `AGENTIC_MEMORY`, and the plugin registers its factory alongside the legacy conversation-index implementation (`plugin/src/main/java/org/opensearch/ml/plugin/MachineLearningPlugin.java`).

## 2. Agent Execution Flow (Current)
1. **Agent lookup** – `MLAgentExecutor` validates tenant access and loads the agent document.
2. **Memory handshake** – `AgenticConversationMemory.Factory` creates or reuses a session via `MLCreateSessionAction`.
3. **Root interaction** – The executor saves an empty parent interaction, invoking `MLAddMemoriesAction`.
4. **Conversation priming** – Agent runners call `memory.getMessages`, which executes `MLSearchMemoriesAction`.
5. **Execution loop** – Streaming thoughts and final answers call `memory.save` (additional `MLAddMemoriesAction` calls) and ultimately `memory.update`, which issues `MLGetMemoryAction` followed by `MLUpdateMemoryAction`.
6. **Trace inspection** – `memory.getTraces` runs `MLSearchMemoriesAction` with trace filters.

These touchpoints ensure every agent run exercises session create/search/update flows against the configured memory container.

## 3. Remote Storage Today
- Memory containers may reference remote storage via `RemoteStore`.
- When a `connector_id` is present, all CRUD/search operations go through `MemoryContainerHelper`, which in turn calls `RemoteStorageHelper` to execute `MLExecuteConnectorAction` with that stored connector id.
- When a container is created with inline connector metadata (endpoint, credential, etc.), the server auto-creates a persisted connector and stores the generated id on the container. Inline metadata is not retained.

## 4. Remote Agentic Memory Strategy (Inline Connector Runtime)

### 4.1 Motivation
- Future agent flows may provide connector metadata only at execution time. Persisting it on the container or pushing it through transport requests is undesirable for security and compatibility reasons.
- We want to keep the connector execution stack centralised while isolating inline behaviour to agent execution.

### 4.2 Proposed Behaviour
1. **Memory selection** – Introduce a `RemoteAgenticConversationMemory` (new memory type). When the agent’s memory spec includes inline connector metadata, the factory map instantiates this remote variant instead of the existing `AgenticConversationMemory`.
2. **Connector ownership** – The remote memory builds an `MLCreateConnectorInput` from the inline metadata once, keeps it in-memory, and reuses it for the lifetime of the agent invocation.
3. **Execution path** – Each memory operation (`save`, `update`, `getMessages`, `getTraces`) constructs an inline connector instance and executes it through the existing `RemoteConnectorExecutor`, bypassing the transport actions that expect a stored connector id.
4. **Legacy path** – When a container still references a stored `connector_id`, the existing `AgenticConversationMemory` and transport actions remain untouched.

### 4.3 Validation & Safety
- Reuse trusted-endpoint validation and signing logic when building the inline connector payload.
- Keep credentials ephemeral: never persist them on the container document or send them through unrelated requests.
- Ensure each execution removes credentials from the connector instance, matching existing transport behaviour.

### 4.4 Testing Considerations
- **Unit** – Verify factory selection, inline connector construction, and per-operation execution paths.
- **Integration** – Exercise agent execution against a mock remote endpoint using inline metadata; confirm the stored-connector flow still works.
- **Regression** – Ensure local/system-index memories and existing connector-id containers behave exactly as before.

## 5. Agent Execution Overrides

### 5.1 Memory Container Override
- `_execute` requests may include `parameters.memory_container_id`.
- `MLAgentExecutor` trims the value, clones the stored `MLMemorySpec`, overrides the container id for this invocation only, and feeds it to `AgentUtils.createMemoryParams`.
- Downstream access controls stay intact; the override simply redirects the runtime to a different container for this request.

### 5.2 Inline Connector Metadata
- `AgentUtils.createMemoryParams` still copies any non-empty `endpoint`, `region`, and `credential` parameters into the map passed to memory factories, parsing credentials with the same XContent logic used by connectors.
- The remote agentic memory factory consumes those fields to build its inline connector input; the legacy factory ignores them.
- Because inline connectors never traverse the transport layer, no changes are required for `MLAddMemoriesRequest`, `MLGetMemoryRequest`, etc.

## 6. Open Items
- Implement `RemoteAgenticConversationMemory` and register the new memory type.
- Factor out shared logic between local and remote agentic memories to minimise duplication.
- Add targeted unit and integration coverage for the remote path while ensuring the stored `connector_id` flow remains stable.

## 7. Implementation Status Overview

### 7.1 Alignments
- Remote memory type constant remains defined in `common/src/main/java/org/opensearch/ml/common/MLMemoryType.java`, keeping the enum ready for the reintroduction effort.
- Agent request parameters still flow endpoint/region/credential metadata into future factory inputs through `AgentUtils.createMemoryParams` (`ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/AgentUtils.java:1026`).

### 7.2 Gaps
- `RemoteAgenticConversationMemory` was reverted; the class no longer exists in `ml-algorithms`, so the planned inline execution path needs to be rebuilt.
- `MachineLearningPlugin` still imports/registers `RemoteAgenticConversationMemory` (`plugin/src/main/java/org/opensearch/ml/plugin/MachineLearningPlugin.java:852`) even though the class is gone, so compilation will fail until either the implementation returns or the registration is gated.
- Factory selection continues to rely solely on the agent’s stored memory type, leaving no runtime path to choose a remote implementation when inline metadata is provided (`ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLAgentExecutor.java:282`, `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/agent/MLChatAgentRunner.java:191`).
- Trusted-endpoint validation from `RemoteStorageHelper` still has not been ported into the prospective remote factory path.
- Section 6 intentionally keeps the “implement remote memory” item open; nothing from that list has landed after the revert.
