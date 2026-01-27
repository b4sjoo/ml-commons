# UPDATE Memories API – Client & SDK Call Investigation

## Executive Summary
- **client usages:** 4 categories – user-context lookup, `GetAction`, `IndexAction`, and connector execution (`MLExecuteConnectorAction`) depending on storage mode.
- **sdkClient usages:** 1 (`getDataObjectAsync`) to fetch the memory-container document before any write.
- **Remote store:** both the pre-check `get` and the final `update` route through connector actions (`get_doc`, `update_doc`) using `client.execute(MLExecuteConnectorAction.INSTANCE, …)`.

---

## 1. Flow Snapshot (TransportUpdateMemoryAction)
1. **Request decoding** – `MLUpdateMemoryRequest.fromActionRequest` extracts container/memory IDs and tenant (`plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportUpdateMemoryAction.java:90`).
2. **Container lookup** – `memoryContainerHelper.getMemoryContainer(...)` fetches the container metadata (uses `sdkClient`, see §2) (`...TransportUpdateMemoryAction.java:96-113`).
3. **Security check** – `RestActionUtils.getUserContext(client)` pulls the thread-context user, then `memoryContainerHelper.checkMemoryContainerAccess` validates ownership (`...TransportUpdateMemoryAction.java:99-134`).
4. **Document fetch** – `GetRequest` is executed via `memoryContainerHelper.getData(...)` (local/system index → `client.get`; remote store → connector `get_doc`) (`...TransportUpdateMemoryAction.java:123-150` & `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:255-285`).
5. **Document write** – the updated document flows through `memoryContainerHelper.indexData(...)`. Local/system index paths call `client.index`, while remote store dispatches to `remoteMemoryStoreHelper.updateDocument` → connector `update_doc` (`...TransportUpdateMemoryAction.java:139-148`, `MemoryContainerHelper.java:346-389`, `RemoteMemoryStoreHelper.java:688-759`).

---

## 2. `sdkClient` Touchpoints

| Call Site | Method | Underlying Action | Purpose |
|-----------|--------|-------------------|---------|
| `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportUpdateMemoryAction.java:96-151` → `MemoryContainerHelper.getMemoryContainer` | `sdkClient.getDataObjectAsync(getDataObjectRequest)` | `indices:data/read/get` (system index `.ml-memory-container`) | Fetch container doc + configuration before any mutation (`plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:255-285`). |

No other `sdkClient` invocations occur in the update path; once the container is loaded, subsequent reads/writes use the OpenSearch `client` or connector helpers.

---

## 3. `client` Touchpoints (local & system indices)

| Stage | Method | Action Name | When Triggered | Reference |
|-------|--------|-------------|----------------|-----------|
| User context | `RestActionUtils.getUserContext(client)` | (thread-context read) | Always, immediately after the container load | `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/TransportUpdateMemoryAction.java:99` |
| Fetch document (system index) | `client.get(getRequest, ...)` (wrapped with thread-context stash) | `indices:data/read/get` | Container configured with `useSystemIndex=true` | `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:255-265` |
| Fetch document (data index) | `client.get(getRequest, listener)` | `indices:data/read/get` | Container uses data index in tenant space | `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:255-265` |
| Update document (system index) | `client.index(indexRequest, ...)` | `indices:data/write/index` | Non-remote container deployed in system index | `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:346-358` |
| Update document (data index) | `client.index(indexRequest, listener)` | `indices:data/write/index` | Non-remote container, custom index | `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:346-358` |

All of the above use async `ActionListener` callbacks and respect thread-context stashing when operating on system indices.

---

## 4. Remote Store Path (connector-backed)

When the container’s configuration carries a `RemoteStore` (connector ID or inline connector), both the read-before-write and the final update are routed through `RemoteMemoryStoreHelper`, which in turn invokes connectors via `MLExecuteConnectorAction`.

| Stage | Method | Connector Action | Underlying client call | Reference |
|-------|--------|------------------|------------------------|-----------|
| Fetch remote doc | `remoteMemoryStoreHelper.getDocument(remoteStore, index, docId, …)` | `get_doc` | `client.execute(MLExecuteConnectorAction.INSTANCE, request, …)` | `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:255-285` → `RemoteMemoryStoreHelper.java:778-837`, `:382-438` |
| Update remote doc | `remoteMemoryStoreHelper.updateDocument(remoteStore, index, docId, source, …)` | `update_doc` | `client.execute(MLExecuteConnectorAction.INSTANCE, request, …)` | `plugin/src/main/java/org/opensearch/ml/helper/MemoryContainerHelper.java:361-389` → `RemoteMemoryStoreHelper.java:688-759`, `:382-438` |

**Notes**
- Each connector call wraps the payload in a `RemoteInferenceInputDataSet` with `FunctionName.CONNECTOR`, so from the transport layer’s perspective these are standard `MLExecuteConnectorAction` requests.
- Inline connectors (embedded in the container) are decrypted at runtime inside `RemoteMemoryStoreHelper.runConnector`, but they still share the same execution path (`RemoteMemoryStoreHelper.java:405-438`).

---

## 5. Call Graph (simplified)

```mermaid
flowchart TD
    A[TransportUpdateMemoryAction.doExecute] --> B[sdkClient.getDataObjectAsync<br/>(getMemoryContainer)]
    B --> C[RestActionUtils.getUserContext(client)]
    C --> D{Storage Mode?}
    D -->|Local/System| E[client.get]
    D -->|Remote| F[executeConnectorAction(get_doc)]
    E --> G[Build updated doc]
    F --> G
    G --> H{Storage Mode?}
    H -->|Local/System| I[client.index]
    H -->|Remote| J[executeConnectorAction(update_doc)]
    I --> K[IndexResponse -> caller]
    J --> K
```

This mirrors the exact control flow in `TransportUpdateMemoryAction`: only two externally-visible operations occur (read + write), and each swaps between core OpenSearch actions and connector execution depending on the memory container’s storage backend.
