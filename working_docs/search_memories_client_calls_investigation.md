# SEARCH Memories API – Client & SDK Call Investigation

## Executive Summary
- **sdkClient usage:** 1 (`getDataObjectAsync`) to load the memory container up front.
- **client usage:** 3 categories – user-context lookup, local/system-index searches (`client.search`), and connector execution for remote stores (`client.execute(MLExecuteConnectorAction.INSTANCE, …)` via helpers.
- **Remote path:** both query execution and optional search pipeline parameters are routed through connector actions (`search_index`).

---

## 1. Flow Overview (`TransportSearchMemoriesAction`)
1. **Request sanity:** `MLSearchMemoriesRequest` is validated (container ID required, tenant validated) (`plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportSearchMemoriesAction.java:30-55`).
2. **Container lookup:** `memoryContainerHelper.getMemoryContainer(containerId, tenantId, …)` fetches the config and enforces multi-tenancy (`…TransportSearchMemoriesAction.java:58-87`).
3. **Security check:** `RestActionUtils.getUserContext(client)` retrieves the current user; `memoryContainerHelper.checkMemoryContainerAccess` enforces permissions (`…TransportSearchMemoriesAction.java:71-76`).
4. **Request shaping:** `memoryContainerHelper.addContainerIdFilter(...)` injects a container filter into the provided `SearchSourceBuilder`; owner filter can be added if enabled (`…TransportSearchMemoriesAction.java:90-112`, `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:720-792`).
5. **Search dispatch:**  
   - **Local/system index:** `memoryContainerHelper.searchData(config, searchRequest, listener)` → `client.search` (with thread-context stash for system indices).  
   - **Remote store:** `memoryContainerHelper.searchDataFromRemoteStorage(config, indexName, queryJson, listener)` → `remoteMemoryStoreHelper.searchDocuments` → connector `search_index` action via `MLExecuteConnectorAction`.

---

## 2. `sdkClient` Interaction

| Stage | Method | Action | Purpose | References |
|-------|--------|--------|---------|-----------|
| Container fetch | `sdkClient.getDataObjectAsync(getDataObjectRequest)` (inside `memoryContainerHelper.getMemoryContainer`) | `indices:data/read/get` on `.ml-memory-container` | Retrieve container metadata, config, and remote store info before running the search | `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportSearchMemoriesAction.java:58-75`; `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:255-285` |

No other `sdkClient` calls occur in the search flow.

---

## 3. `client` Interaction – Local/System Indices

| Stage | Method | Action Name | Trigger Condition | Reference |
|-------|--------|-------------|-------------------|-----------|
| User context | `RestActionUtils.getUserContext(client)` | (thread-context read) | Always executed after container lookup | `…TransportSearchMemoriesAction.java:71` |
| Search (system index) | `client.search(searchRequest, listener)` with thread-context stash | `indices:data/read/search` | Container config `useSystemIndex=true`; executed via `memoryContainerHelper.searchData` | `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:288-309` |
| Search (data index) | `client.search(searchRequest, listener)` | `indices:data/read/search` | Container uses custom data index | `MemoryContainerHelper.java:288-309` |

Thread context is stashed/restored for system indices so security headers propagate correctly.

---

## 4. Remote Store Path (Connector-backed Search)

When the memory container specifies a `RemoteStore`, both the query issue and any search pipeline are executed through `RemoteMemoryStoreHelper` using connectors.

| Stage | Method | Connector Action | Underlying `client` call | Reference |
|-------|--------|------------------|--------------------------|-----------|
| Remote search | `remoteMemoryStoreHelper.searchDocuments(remoteStore, indexName, queryJson, pipeline, listener)` | `search_index` | `client.execute(MLExecuteConnectorAction.INSTANCE, request, …)` | `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportSearchMemoriesAction.java:103-116`; `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:312-332`; `plugin/src/main/java/org/opensearch/ml/helper/RemoteMemoryStoreHelper.java:603-659` |

The helper builds a `RemoteInferenceInputDataSet` with parameters:
- `index_name` – target remote index (`Working`, `Long-term`, etc.)
- `input` – serialized JSON from `SearchSourceBuilder`
- optional `?search_pipeline=` suffix appended to the URL via the `SEARCH_PIPELINE_FIELD`.

`MLExecuteConnectorAction` wraps the call, so from OpenSearch’s standpoint it’s a standard connector execution.

---

## 5. Execution Diagram

```mermaid
flowchart TD
    A[TransportSearchMemoriesAction.doExecute] --> B[sdkClient.getDataObjectAsync<br/>(memory container)]
    B --> C[RestActionUtils.getUserContext(client)]
    C --> D{Remote store?}
    D -->|No| E[client.search<br/>(MemoryContainerHelper.searchData)]
    D -->|Yes| F[MLExecuteConnectorAction<br/>(RemoteMemoryStoreHelper.searchDocuments)]
    E --> G[SearchResponse -> caller]
    F --> G
```

This diagram captures the only external operations: the initial container lookup (sdkClient) and the eventual search (either native OpenSearch search or connector-driven remote search).

---

## 6. Key Files & References
- `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportSearchMemoriesAction.java`
- `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java` (container fetch, search dispatch, filter helpers)
- `plugin/src/main/java/org/opensearch/ml/helper/RemoteMemoryStoreHelper.java` (`searchDocuments`, connector execution)
