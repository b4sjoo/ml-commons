### Low-Level Feature Summary: Agentic Memory

The Agentic Memory feature is implemented as two distinct but related sets of APIs: the **Memory Container API** for managing metadata and the **Memories API** for CRUD operations on the memory data itself. The entire system is designed with security, multi-tenancy, and extensibility in mind, leveraging existing ML Commons patterns.

---

#### 1. Core Concepts & Data Storage

*   **Memory Container (`.plugins-ml-agentic-memory-container` index):** This is a system index that stores metadata documents (`MemoryContainer`). Each document defines a logical "memory". Its ID is the `memory_container_id`. It holds the configuration for how memories are stored, including the name of the actual data index and settings for semantic search.
*   **Memory Data (e.g., `ml-knn-memory-*` index):** These are the indices that store the actual memory entries (`MLMemory`). The index name is either user-defined or auto-generated based on the container ID and storage type (static, knn, or sparse). These indices contain the conversational text, facts, and optional vector embeddings.

---

#### 2. Request Flow and Key Classes

The implementation follows a standard OpenSearch plugin action pattern:
**REST Request -> `Rest...Action` -> `...Request` -> `...Action` -> `Transport...Action` -> `...Response`**

**A. Memory Container API (e.g., Create Memory Container)**

1.  **`RestMLCreateMemoryContainerAction`**:
    *   Defines the REST endpoint: `POST /_plugins/_ml/memory_containers/_create`.
    *   Parses the incoming JSON request into an `MLCreateMemoryContainerInput` object. This object represents the user's desired configuration.
    *   Wraps the input object into an `MLCreateMemoryContainerRequest`.

2.  **`MLCreateMemoryContainerAction`**:
    *   An `ActionType` that defines the cluster action name (`cluster:admin/opensearch/ml/memory_containers/create`).

3.  **`TransportCreateMemoryContainerAction`**: This is the core logic handler.
    *   **User/Security Context**: It first retrieves the `User` object from the thread context to handle security and tenancy.
    *   **Model Validation**: If semantic search is enabled (by providing an `embedding_model_id`), it calls `mlModelManager.getModels()` to validate that the specified LLM and embedding models exist and have the correct types (e.g., LLM must be `REMOTE`).
    *   **Build `MemoryContainer`**: It constructs the final `MemoryContainer` document, adding system-generated fields like `createdTime`, `lastUpdatedTime`, and the `owner`.
    *   **Create Data Index**: It calls `mlIndicesHandler` to create the underlying data index. The type of index created depends on the `MemoryStorageConfig`:
        *   `initKnnMemoryIndex` for `TEXT_EMBEDDING`.
        *   `initSparseMemoryIndex` for `SPARSE_ENCODING`.
        *   `initStaticMemoryIndex` for no semantic search.
    *   **Index Metadata**: After the data index is created, it indexes the final `MemoryContainer` document (with the actual data index name populated) into the `.plugins-ml-agentic-memory-container` system index.
    *   **Response**: It returns an `MLCreateMemoryContainerResponse` containing the auto-generated `memory_container_id`.

**B. Memories API (e.g., Create Events)**

1.  **`RestMLCreateEventAction`**:
    *   Defines the endpoint: `POST /_plugins/_ml/memory_containers/{memory_container_id}/events/_create`.
    *   Parses the request body into an `MLCreateEventInput`, which contains the list of messages and other parameters like `session_id` and `infer`.
    *   Wraps it in an `MLCreateEventRequest`.

2.  **`TransportCreateEventAction`**: This is the most complex transport action.
    *   **Get Container**: It first uses `MemoryContainerHelper.getMemoryContainer()` to retrieve the container's configuration, which includes security checks.
    *   **Logic Branch (`infer` flag)**:
        *   **If `infer` is `true`**:
            1.  **Fact Extraction**: It calls the configured LLM model via `MLPredictionTaskAction` to extract structured facts from the conversation.
            2.  **Similarity Search**: If a `session_id` is provided, it performs a search (neural, sparse, or match) against the data index to find existing facts in the same session that are similar to the newly extracted ones.
            3.  **Memory Decision**: It makes another LLM call, providing both the new facts and the similar old facts. The LLM acts as a "Memory Manager," deciding whether to `ADD` the new facts, `UPDATE` or `DELETE` old ones, or do `NONE`.
            4.  **Execute Operations**: It performs the decisions using a `BulkDataObjectRequest` with SDK client for efficiency.
        *   **If `infer` is `false`**:
            1.  It skips all LLM interactions and proceeds directly to storage.
    *   **Embedding Generation**: When semantic storage is enabled, embeddings are produced via the configured inference pipeline before documents are indexed.
    *   **Indexing**: Long-term decisions and short-term events are written through `MemoryOperationsService`, which manages the necessary bulk index, update, or delete operations.
    *   **Response**: It returns an `MLCreateEventResponse` with the generated event `_id` and session identifier.

---

#### 3. SDK Client Migration for Bulk Operations

The memory operations have been migrated from traditional OpenSearch client to the new SDK client pattern:

*   **Old Pattern**: Used `client.bulk()` with `BulkRequest`, `IndexRequest`, `UpdateRequest`, `DeleteRequest`
*   **New Pattern**: Uses `sdkClient.bulkDataObjectAsync()` with `BulkDataObjectRequest`, `PutDataObjectRequest`, `UpdateDataObjectRequest`, `DeleteDataObjectRequest`
*   **Benefits**: 
    - Better async handling with CompletableFuture
    - Consistent error handling with `SdkClientUtils.unwrapAndConvertToException()`
    - Follows OpenSearch's recommended patterns (per commit 190b2dfc)
*   **Implementation**: All bulk operations in `MemoryOperationsService` now use the SDK client for indexing, updating, and deleting memory documents

---

#### 4. Low-Level Design Highlights

*   **Helper Classes**: The logic is modularized into `MemoryContainerHelper` (for access control and validation) plus dedicated processing/search/operation services that keep the transport action lean.
*   **Decoupled Configuration**: The `semantic_storage_enabled` flag is not set by the user directly. It's a derived boolean, automatically set to `true` if an `embedding_model_id` and `embedding_model_type` are provided, simplifying the user experience.
*   **Dynamic Query Building**: `MemorySearchQueryBuilder` centralizes the logic for creating search queries. It dynamically constructs a `neural`, `neural_sparse`, or `match` query based on the `MemoryStorageConfig` of the container, avoiding a hard dependency on the neural-search plugin.
*   **ID Management**: The OpenSearch document `_id` is used as the canonical ID for both memory containers and individual memories, avoiding redundant fields in the document `_source`.
*   **Error Handling**: The system fails fast. For example, if `infer` is true, any failure during the LLM call or fact parsing results in an exception, preventing partial or inconsistent data from being saved.
*   **Data Models**: Strong typing is enforced through dedicated classes for every API layer: `...Input` (user request), `...Request` (transport), `...Response` (transport), and data entities like `MemoryContainer` and `MLMemory`.
