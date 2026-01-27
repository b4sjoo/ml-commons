
# Implementation Task Breakdown for Remote Agentic Memory

> Status: The initial `RemoteAgenticConversationMemory` implementation was reverted because of import/build issues. The checklist below reflects the outstanding work that must be redone unless otherwise marked.

## Stage 1 – Project Setup
- [x] Register new memory type constant (e.g. `REMOTE_AGENTIC_MEMORY`) in shared enums/constants.
- [ ] Wire the new memory factory into `MachineLearningPlugin` factory map (registration currently blocked because the implementation is absent).
- [ ] Ensure gradle modules compile after type registration.

## Stage 2 – Remote Memory Implementation
- [ ] Create `RemoteAgenticConversationMemory` extending the `Memory` interface.
- [ ] Share common logic with `AgenticConversationMemory` (refactor helpers for message conversion / timestamp handling).
- [ ] Implement `save` using inline connector execution (build connector + call executor directly).
- [ ] Implement `getMessages` / `getTraces` using inline connector execution for search.
- [ ] Implement `update` (get + update) via inline connector calls.

## Stage 3 – Factory & Runtime Integration
- [ ] Implement `RemoteAgenticConversationMemory.Factory` that builds the inline connector input from agent parameters.
- [ ] Update `AgentUtils.createMemoryParams`/`MLAgentExecutor` if needed to hand over metadata cleanly (ensure backwards compatibility).
- [ ] Modify agent runner selection logic to pick remote factory when inline metadata present.

## Stage 4 – Connector Execution Plumbing
- [ ] Implement inline connector builder/executor utilities inside the remote memory (or shared helper).
- [ ] Ensure trusted endpoint enforcement and credential clean-up mirror the transport path.
- [ ] Hardcode protocol to `aws_sigv4` (or configurable default) for generated connectors.

## Stage 5 – Testing
- [ ] Unit tests for factory selection and inline connector construction.
- [ ] Unit tests for `RemoteAgenticConversationMemory` methods (mock connector helper, verify invocation).
- [ ] Integration test: agent execution with inline metadata hitting a mock remote endpoint.
- [ ] Regression test: ensure legacy agentic memory path with stored `connector_id` still works.

## Stage 6 – Documentation & Cleanup
- [ ] Update design doc (link back to this task list once completed).
- [ ] Add developer notes or README entry describing how to configure inline connector memory.
- [ ] Manual validation checklist (CLI invocation, runtime logs, remote data verification).
