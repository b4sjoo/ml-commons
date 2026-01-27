## **Core Concepts of Mem0 and Mem0g from the Research Paper**

* **Problem:** LLMs have fixed context windows, limiting their ability to maintain long-term memory and coherence in extended conversations.
* **Solution (Mem0):** A scalable memory-centric architecture with two phases:
    * **Extraction Phase:**
        * Takes a new message pair, a conversation summary (from DB, updated asynchronously), and recent messages as input.
        *  Uses an LLM to extract salient memories (candidate facts) from the new exchange, aware of the broader context.
    * **Update Phase:**
        * For each candidate fact, retrieves top 's' semantically similar memories from a vector DB.
        * Uses an LLM (via 'tool call') to decide on an operation:
            * **ADD:** New memory.
            * **UPDATE:** Augment existing memory.
            * **DELETE:** Remove contradicted memory.
            * **NOOP:** No change.
* **Solution (Mem0g - Graph-based variant):** Extends Mem0 by representing memories as a directed labeled graph.
    * **Nodes (V):** Entities (e.g., Alice, San_Francisco) with type, embedding, and timestamp.
    * **Edges (E):** Relationships (e.g., LIVES_IN) as triplets (source_entity, relationship, destination_entity).
    * **Extraction Phase:**
        * **Entity Extractor (LLM):** Identifies entities and their types from text.
        * **Relationship Generator (LLM):** Derives connections between entities, forming triplets.
    * **Update Phase:**
        * Computes embeddings for new entities.
        * Searches for semantically similar existing nodes.
        * Creates/uses nodes and establishes relationships.
        * **Conflict Detection:** Identifies conflicting existing relationships.
        * **Update Resolver (LLM):** Marks obsolete relationships as invalid (not deleted, for temporal reasoning).
    * **Retrieval (Dual-approach):**
        * **Entity-centric:** Identifies key entities in a query, finds corresponding graph nodes, explores relationships to build a subgraph.
        * **Semantic Triplet:** Encodes the entire query, matches against textual encodings of all relationship triplets in the graph.
* **Key Benefits Claimed:**
    * Outperforms existing memory systems.
    * Significant reduction in latency and token cost compared to full-context approaches.
    * Mem0 excels at single-hop and multi-hop reasoning.
    * Mem0g excels at temporal and open-domain tasks due to structured relational memory.

## Key takeaways from the README

* **Mem0's Goal:** Enhance AI assistants with an intelligent memory layer for personalization.
* **Core Capabilities:** Multi-level memory (User, Session, Agent state).
* **Self-Hosted Option:** The `mem0ai` PyPI package is what I'll be looking into.
* **Default LLM:** `gpt-4o-mini` via OpenAI, but others are supported.
* **Basic Usage Example:**
    * `memory = Memory()`: Instantiates the memory object.
    * `memory.search(query=message, user_id=user_id, limit=3)`: Retrieves relevant memories. This aligns with the retrieval part of the paper's architecture.
    * `memory.add(messages, user_id=user_id)`: Adds new memories from the conversation. This corresponds to the combined "Extraction" and "Update" phases mentioned in the paper. The example shows adding a list of messages (user + assistant).

The basic usage example is quite high-level. It doesn't explicitly show the two-phase (Extraction and Update) process for adding memories, nor does it detail how `ADD/UPDATE/DELETE/NOOP` decisions are made. It also doesn't differentiate between the base `Mem0` and `Mem0g` (graph-based) functionalities in this simple example.

## Project's dependencies


Read file: tmp/mem0/pyproject.toml

* **Core Dependencies:**
    * `qdrant-client>=1.9.1`: This strongly suggests Qdrant is used as the vector database for semantic similarity search of memories. This aligns with the paper's description of retrieving semantically similar memories during the update phase.
    * `openai>=1.33.0`: Confirms the use of OpenAI's library, likely for interacting with models like `gpt-4o-mini` for extraction and update logic.
    * `pydantic>=2.7.3`: Used for data validation and settings management.
    * `sqlalchemy>=2.0.31`: Suggests a relational database is also used, perhaps for storing metadata, conversation summaries, or user information, though the paper mainly emphasizes the vector DB for memories and Neo4j for graphs.
* **Optional Dependencies (`graph` group):**
    * `langchain-neo4j>=0.4.0`
    * `neo4j>=5.23.1`

These confirm that Neo4j is indeed the graph database used for the `Mem0g` functionality, as stated in the paper. The `langchain-neo4j` library suggests an integration with LangChain for graph operations.

##  `mem0` directory structure

* `embeddings/`: Likely handles the generation of vector embeddings for memories.
* `graphs/`: This should contain the implementation for `Mem0g`, including interactions with Neo4j.
* `llms/`: Manages interactions with Large Language Models (e.g., OpenAI GPT models). This would be central to the extraction and update phases.
* `memory/`: This is probably where the core memory object (`Memory` class from the README example) and its `add` and `search` methods are defined. It might orchestrate the calls to LLMs, vector stores, and graph stores.
* `vector_stores/`: Contains implementations for interacting with vector databases, specifically Qdrant, based on the dependencies.
* `configs/`: Likely holds configuration settings for LLMs, vector stores, etc.
* `client/` and `proxy/`: Might be related to the hosted platform or API interactions.
* `utils/`: Utility functions.

## `main.py`structure

The `mem0/memory/main.py` file defines two main classes: `Memory` (synchronous) and `AsyncMemory` (asynchronous). Let's focus on the synchronous `Memory` class for clarity, as the core logic should be similar.

### **Initialization (`_init_`):**

* Loads configuration (`MemoryConfig`).
* Initializes:
    * **Embedding Model:** Via `EmbedderFactory` (e.g., OpenAI embeddings).
    * **Vector Store:** Via `VectorStoreFactory` (e.g., Qdrant, using `self.config.vector_store.config.collection_name`).
    * **LLM:** Via `LlmFactory` (e.g., OpenAI GPT models).
    * **SQLiteManager (`self.db`):** For storing history of memory changes (ADD, UPDATE, DELETE operations). This wasn't explicitly detailed as a separate DB in the paper's high-level overview but makes sense for auditability.
    * **Graph Store (`self.graph`):** If `config.graph_store.config` is present, it initializes a `MemoryGraph` instance (either `mem0.memory.memgraph_memory.MemoryGraph` or `mem0.memory.graph_memory.MemoryGraph`). This enables `self.enable_graph`.

### **Core `add` Method (Simplified Flow):**

This method is responsible for adding new information to the memory system and corresponds to the **Extraction and Update phases** from the paper.

1.  **Input:** `messages` (string or list of dicts like `[{'role': 'user', 'content': '...'}, ...]`), session identifiers (`user_id`, `agent_id`, or `run_id`), `metadata`, `infer` flag.
2.  **`infer=False` (Raw Memory Addition):**
    1. If `infer` is `False`, messages are added directly to the vector store without LLM-based fact extraction or update logic.
    2. Each message content is embedded using `self.embedding_model.embed()`.
    3. A new memory ID is generated (`uuid.uuid4()`).
    4. The memory (embedding, content, metadata) is inserted into `self.vector_store.insert()`.
    5. An "ADD" event is logged in the SQLite history DB.
    6. This path bypasses the sophisticated extraction/update described for the core Mem0 functionality.
3.  **`infer=True` (LLM-based Extraction and Update - This is the core Mem0 logic):**

     This happens within `add_to_vector_store` when `infer` is true.
 **a. Fact Extraction (Corresponds to Paper's "Extraction Phase"):**
* Messages are parsed (`parse_messages`).
* A system prompt and user prompt are constructed using `get_fact_retrieval_messages(parsed_messages)` (or a custom prompt if provided in config). This prompt likely instructs the LLM to extract salient facts from the `parsed_messages`.
* `self.llm.generate_response()` is called with these prompts, expecting a JSON object containing a list of "facts".
* `new_retrieved_facts = json.loads(response)["facts"]`.
**b. Preparation for Update:**
* If no new facts are retrieved, the process stops for this part.
* For each `new_mem` (a new fact) in `new_retrieved_facts`:
* Its embedding is generated: `messages_embeddings = self.embedding_model.embed(new_mem, "add")`.
* `self.vector_store.search()` is called to find the top 5 semantically similar existing memories (`existing_memories`) using the new fact's content and embedding. This uses the `filters` derived from `user_id`/`agent_id`/`run_id`. This matches the paper's description of retrieving similar memories.
* These `existing_memories` are collected into `retrieved_old_memory`.
 **c. Update Decision (Corresponds to Paper's "Update Phase" - LLM Tool Call):**
* A "function calling" prompt is created using `get_update_memory_messages(retrieved_old_memory, new_retrieved_facts, self.config.custom_update_memory_prompt)`. This prompt presents the LLM with both the newly extracted facts and relevant existing memories and asks it to decide on actions.
* `self.llm.generate_response()` is called with this prompt, expecting a JSON object detailing actions (ADD, UPDATE, DELETE, NONE/NOOP) for each fact.
* `new_memories_with_actions = json.loads(response)`.
 **d. Executing Actions:**
* The code iterates through `new_memories_with_actions.get("memory", [])`.
* For each `resp` (action item):
* `event_type = resp.get("event")` (e.g., "ADD", "UPDATE", "DELETE").
* `action_text = resp.get("text")` (the content of the memory to be added/updated).
* If `event_type == "ADD"`:
* `self._create_memory()` is called, which embeds `action_text`, generates a new ID, inserts into `self.vector_store`, and logs to SQLite history.
* If `event_type == "UPDATE"`:
* `self._update_memory()` is called with the `memory_id` (from `temp_uuid_mapping` which maps an original ID to the one given to the LLM), `action_text`. This method updates the entry in `self.vector_store` (new embedding, new data, updated timestamp) and logs to SQLite.
* If `event_type == "DELETE"`:
* `self._delete_memory()` is called, which removes the memory from `self.vector_store` and logs to SQLite.
* If `event_type == "NONE"` (NOOP): Logs and does nothing.
4.**Graph Integration (`_add_to_graph` called concurrently):**

1. If `self.enable_graph` is true (meaning graph store is configured):
2. `self.graph.add(data, filters)` is called. The `data` is the concatenated content of the input messages.
3. The implementation of `self.graph.add` would be in `mem0/memory/graph_memory.py` (or `memgraph_memory.py`) and should handle the entity/relationship extraction and graph update logic described for Mem0g in the paper.

### **`search` Method (Simplified Flow):**

1. Input: `query`, session identifiers, `limit`, `filters`.
2. Embeds the `query` using `self.embedding_model.embed(query, "search")`.
3. Calls `self.vector_store.search()` with the query embedding, limit, and filters to get matching memories from Qdrant.
4. If graph is enabled (`self.enable_graph`), it also calls `self.graph.search(query, effective_filters, limit)` concurrently. The implementation of this would be in the graph memory module.
5. Formats and returns the results from both vector store and optionally the graph store.

### **Low-Level Implementation vs. Paper**

#### **Vector Store (Mem0 & Mem0g)**

* **Paper:** Dynamically extracts, consolidates, retrieves salient information. Uses vector embeddings for similarity.
* **Code:**
* Uses `Qdrant` as the vector store (`VectorStoreFactory`).
* Uses an `EmbedderFactory` for embeddings (e.g., OpenAI).
* The `add` method (when `infer=True` via `_add_to_vector_store`):
1.**Extraction:** LLM call (`get_fact_retrieval_messages`) extracts `new_retrieved_facts`.
2.**Retrieval for Update Context:** For each new fact, searches Qdrant for similar existing memories.
3.**Consolidation/Update Decision:** LLM call (`get_update_memory_messages`) decides ADD/UPDATE/DELETE/NOOP based on new facts and retrieved similar memories.
4.**Execution:** CRUD operations on Qdrant (`_create_memory`, `_update_memory`, `_delete_memory`) and logs in SQLite.
* The `search` method uses Qdrant for similarity search.
* This aligns well with the paper's description of the Mem0 pipeline. The "tool call" mentioned in the paper for the update phase is implemented as a structured prompt to the LLM which then returns a JSON defining the operations.

#### **Graph Store (Mem0g)**

* **Paper:** Represents memories as a directed labeled graph (Entities as nodes, Relationships as edges). Two-stage extraction (Entity Extractor, Relationship Generator, both LLM-based). Conflict detection and LLM-based update resolver. Dual retrieval (entity-centric, semantic triplet). Uses Neo4j.
* **Code:**
* Dependencies include `langchain-neo4j` and `neo4j`.
* The `Memory` class initializes `self.graph` (an instance of `MemoryGraph`) if graph store is configured.
* `Memory.add()` calls `self.graph.add(data, filters)` concurrently. The `data` passed is the raw concatenated message content.
* `Memory.search()` calls `self.graph.search(query, filters)` concurrently.
* The actual graph-specific logic (entity/relationship extraction, LLM calls for this, Neo4j interaction, conflict resolution) would be within the `MemoryGraph` class in `mem0/graphs/graph_memory.py` (or `memgraph_memory.py`). I need to inspect this file to see the low-level details of graph operations.

#### **Conversation Summary**

* **Paper:** Mentions "a conversation summary S retrieved from the database that encapsulates the semantic content of the entire conversation history" and "an asynchronous summary generation module".
* **Code (`Memory` class in `main.py`):** I don't see an explicit reference to this global conversation summary `S` being directly used in the `get_fact_retrieval_messages` or `get_update_memory_messages` calls within the `_add_to_vector_store` method. The prompts seem to focus on the current `parsed_messages` for fact extraction and `retrieved_old_memory` + `new_retrieved_facts` for the update decisions. The asynchronous summary generation module is not immediately obvious in this file; it might be part of a larger system or handled by a different component if this is the library part.

## `graph_memory.py`structure

### **Initialization (`__init_`****)**

* Connects to Neo4j using `langchain_neo4j.Neo4jGraph`.
* Initializes an embedding model (via `EmbedderFactory`) and an LLM (via `LlmFactory`).
* Sets up Neo4j indexes for entities (`**Entity**` node label) based on `user_id` and potentially `name, user_id` (composite index for Enterprise Neo4j).

### **Core `add` Method (Mem0g Extraction and Update)**

This method takes raw `data` (text from conversation) and `filters` (containing `user_id`) and adds information to the graph. This encapsulates the "Extraction" and "Update" phases for Mem0g.

1.  **Entity Extraction (`_retrieve_nodes_from_data`):**
    1. **Paper's "Entity Extractor (LLM)":** This function uses an LLM call to extract entities and their types from the input `data`.
    2. It constructs a prompt instructing the LLM to identify entities and types. If self-references like 'I', 'me' are found, it uses the `filters['user_id']` as the entity.
    3. It uses LLM "tools" (structured function calling) defined in `mem0.graphs.tools` (`EXTRACT_ENTITIES_TOOL` or `EXTRACT_ENTITIES_STRUCT_TOOL` for structured LLM providers like OpenAI).
    4. The LLM is expected to return a list of entities and their types (e.g., `{"entity": "San Francisco", "entity_type": "City"}`).
    5. The result is an `entity_type_map` (e.g., `{"san_francisco": "city"}`).
2.  **Relationship Extraction (`_establish_nodes_relations_from_data`):**
    1. **Paper's "Relationship Generator (LLM)":** This function uses another LLM call to establish relationships between the entities found in the previous step.
    2. It uses the `EXTRACT_RELATIONS_PROMPT` (or a custom one from config). This prompt likely asks the LLM to form triplets (source, relationship, destination) based on the input `data` and the `entity_type_map`.
    3. Again, it uses LLM tools (`RELATIONS_TOOL` or `RELATIONS_STRUCT_TOOL`).
    4. The LLM returns a list of relationship triplets `to_be_added` (e.g., `[{"source": "alice", "relationship": "lives_in", "destination": "san_francisco"}]`).
3.  **Search for Existing Related Information (`_search_graph_db`):**
    1. **Paper's "Conflict Detection" (Implicit Start):** Before adding/deleting, it searches the graph for existing nodes similar to the ones extracted (`entity_type_map.keys()`) and their relationships.
    2. For each node in `node_list` (from `entity_type_map`), it generates an embedding.
    3. It then queries Neo4j using Cypher to find nodes with cosine similarity to the input node's embedding above a `self.threshold`. It retrieves these similar nodes and their incoming/outgoing relationships.
    4. The result is `search_output`, a list of existing relevant relationships.
4.  **Determine Entities/Relationships to Delete (`_get_delete_entities_from_search_output`):**
    1. **Paper's "Update Resolver (LLM)" for Deletion:** This function takes the `search_output` (existing relationships) and the new input `data`, and uses an LLM call to decide which, if any, of the existing relationships should be deleted because they are contradicted or made obsolete by the new `data`.
    2. It uses `get_delete_messages` to create the prompt and `DELETE_MEMORY_TOOL_GRAPH` (or its structured version) for the LLM tool call.
    3. The LLM returns a list of relationships `to_be_deleted`.
5.  **Execute Deletions (`_delete_entities`):**
    1. Iterates through `to_be_deleted` and executes Cypher queries to `DELETE` the specific relationships in Neo4j.
    2. **Paper's "marking them as invalid rather than physically removing them to enable temporal reasoning":** The current code shows a direct `DELETE r` for relationships. It does not seem to implement the "mark as invalid" strategy mentioned in the paper. This might be a simplification in the open-source version or a feature planned for later.
6.  **Execute Additions/Updates (`_add_entities`):**
    1. **Paper's "Update Resolver (LLM)" for Add/Update & "Storage and Update Strategy":** This function takes the `to_be_added` relationships (from step 2) and adds them to the graph.
    2. For each relationship triplet (source, destination, relationship):
        1. It gets embeddings for the source and destination entities.
        2. It searches for existing source and destination nodes in Neo4j based on embedding similarity (`_search_source_node`, `_search_destination_node`). This is similar to the paper's "searches for existing nodes with semantic similarity above a defined threshold".
        3. Based on whether source/destination nodes already exist (found by similarity search), it constructs a Cypher `MERGE` query.
            1. `MERGE` creates the node if it doesn't exist or matches it if it does.
            2. It sets properties like `created` timestamp, `mentions` count, and the `embedding` (using `db.create.setNodeVectorProperty` which is a Neo4j APOC procedure or similar for setting vector properties).
            3. It then `MERGE`s the relationship between the source and destination nodes, also setting timestamps and mention counts on the relationship.
    3. This step effectively implements the "create both nodes, create only one node, or use existing nodes before establishing the relationship" logic from the paper. The conflict resolution for adding/updating new info seems to be handled by the `MERGE` logic and potentially by the prior deletion step. The paper's "LLM-based update resolver" for additions/updates isn't a separate explicit LLM call here for each triplet; rather, the LLM extracted the target state of relationships in step 2, and this step materializes it.

### **`search` Method (Mem0g Retrieval)**

* **Paper's "Dual-approach strategy": Entity-centric and Semantic Triplet.**
* **Code Implementation:**
    *  `_retrieve_nodes_from_data(query, filters)`: First, it extracts entities from the `query` using an LLM call (similar to step 1 in the `add` method). This can be seen as the start of the **entity-centric** part.
    *  `_search_graph_db(node_list=list(entity_type_map.keys()), filters=filters)`: It then searches the graph for these extracted entities and their relationships using embedding similarity (as described in step 3 of the `add` method). This retrieves a subgraph relevant to the query entities.
    *  **Reranking with BM25:** The results from `_search_graph_db` (which are relationship triplets) are then reranked using `BM25Okapi` against the original `query`. BM25 is a keyword-based ranking algorithm. This step seems to refine the results from the initial graph traversal.
* **Comparison to Paper's Retrieval:**
    * The **entity-centric** part is somewhat implemented by extracting entities from the query and then exploring the graph.
    * The **semantic triplet approach** (encoding the entire query and matching against textual encodings of all relationship triplets) is **not directly visible**. Instead of matching the whole query embedding against all triplets, it seems to first find relevant entities via query entity extraction and graph traversal by entity similarity, then reranks these localized triplets using BM25. This is a hybrid approach. The paper mentioned Neo4j as the backend, and performing dense vector search across all relationship triplets might be computationally intensive without specialized indexing on triplet text, which might be why this alternative is used.

### **Low-Level Implementation Details Matching/Diverging from Paper**

* **Neo4j Usage:** Confirmed. `langchain_neo4j` is used. Cypher queries are extensively used.
* **LLM for Extraction/Update:** Confirmed. `LlmFactory` is used, and specific prompts/tools (`EXTRACT_ENTITIES_TOOL`, `RELATIONS_TOOL`, `DELETE_MEMORY_TOOL_GRAPH`) are employed for structured output from the LLM, mimicking the "tool call" mechanism.
* **Embeddings for Graph Nodes:** Confirmed. Nodes have embeddings, and similarity search is used.
* **Conflict Detection/Resolution during Update:**
    * The `_get_delete_entities_from_search_output` uses an LLM to decide which existing relationships to delete based on new data, which acts as a conflict resolver for contradictions.
    * The `_add_entities` uses `MERGE` which handles creation or matching of nodes/relationships. If new information augments existing, the `MERGE` on relationships would update mention counts or timestamps, but the paper's "UPDATE for augmentation of existing memories with complementary information" via an LLM for the graph part isn't an explicit separate LLM call for each update beyond the initial relation extraction. The "update" is more about adding new relations that might implicitly update the overall understanding.
* **Marking Obsolete Relationships vs. Deleting:** The code currently uses `DELETE r` for relationships, not the "mark as invalid" strategy mentioned for temporal reasoning in the paper.
* **Asynchronous Summary for Context:** Not explicitly seen in `graph_memory.py`'s `add` method for providing global context to the LLM calls for entity/relationship extraction. The prompts seem to take the current `data` (message chunk) as primary input.
* **Retrieval - Dual Approach:** The entity-centric aspect is partially there. The semantic triplet match against all triplets isn't directly there; instead, it's a localized search followed by BM25 reranking.

## **Summary of How `mem0` Implements its Functions (Low-Level)**

### **For `Mem0` (Vector-based memory - primarily in `memory/main.py`)**

1.  **Initialization:** Sets up Qdrant client, OpenAI LLM client, embedding model, and an SQLite DB for history.
2.  **Adding Memory (`Memory.add` with `infer=True` → `_add_to_vector_store`):**
    1. **Extraction:** An LLM (`self.llm`) is prompted (`get_fact_retrieval_messages`) with the current conversation messages to extract a list of salient `new_retrieved_facts` (JSON output).
    2. **Context Gathering for Update:** For each `new_retrieved_fact`, its embedding is calculated, and Qdrant is searched for the top `s` (e.g., 5) semantically similar `existing_memories`.
    3. **Update Decision (LLM Tool Call):** Another LLM call (`get_update_memory_messages`) is made. This LLM is given the `new_retrieved_facts` and the `existing_memories` and is prompted to return a list of actions:
        1. `{"event": "ADD", "text": "..."}`
        2. `{"event": "UPDATE", "id": "...", "text": "...", "old_memory": "..."}`
        3. `{"event": "DELETE", "id": "...", "text": "..."}`
        4. `{"event": "NONE"}`
    4. **Execution:**
        1. `ADD`: Embed text, generate UUID, insert into Qdrant, log to SQLite.
        2. `UPDATE`: Embed new text, update existing record in Qdrant by ID, log to SQLite.
        3. `DELETE`: Delete record from Qdrant by ID, log to SQLite.
3.  **Searching Memory (`Memory.search` → `_search_vector_store`):**
    1. The query is embedded.
    2. Qdrant is searched using this embedding and provided filters to find relevant memories.

### **For `Mem0g` (Graph-based memory - primarily in `memory/graph_memory.py` orchestrated by `memory/main.py`)**

1.  **Initialization:** Sets up Neo4j client, LLM, and embedding model.
2.  **Adding Memory (`MemoryGraph.add` called from `Memory.add` → `_add_to_graph`):**
    1. **Entity Extraction (`_retrieve_nodes_from_data`):** An LLM is prompted (using `EXTRACT_ENTITIES_TOOL`) with the input text to identify entities and their types. Returns `entity_type_map`.
    2. **Relationship Extraction (`_establish_nodes_relations_from_data`):** Another LLM call (using `RELATIONS_TOOL` and `EXTRACT_RELATIONS_PROMPT`) takes the input text and `entity_type_map` to generate relationship triplets (`to_be_added`).
    3. **Find Existing Relevant Graph Data (`_search_graph_db`):** Embeds extracted entities and queries Neo4j for similar nodes and their relationships using vector similarity. This is `search_output`.
    4. **Conflict Resolution - Deletion (`_get_delete_entities_from_search_output`):** An LLM call (using `DELETE_MEMORY_TOOL_GRAPH` and `get_delete_messages`) takes `search_output` (existing graph context) and the new input text to decide which existing relationships (`to_be_deleted`) are contradicted and should be removed.
    5. **Execute Deletion (`_delete_entities`):** Issues `DELETE` Cypher queries to Neo4j for relationships in `to_be_deleted`.
    6. **Execute Addition/Update (`_add_entities`):**
        1. For each relationship in `to_be_added`:
            1. Embeds source and destination entities.
            2. Searches Neo4j for existing nodes matching these embeddings (`_search_source_node`, `_search_destination_node`).
            3. Uses Cypher `MERGE` statements to create/update nodes (setting embeddings, types, timestamps, mention counts) and `MERGE` the relationship between them (setting timestamps, mention counts). This handles adding new entities/relations or updating existing ones by incrementing mention counts.
3.  **Searching Memory (`MemoryGraph.search` called from `Memory.search`):**
    1. **Entity Extraction from Query (`_retrieve_nodes_from_data`):** Extracts entities from the search query using an LLM.
    2. **Graph Traversal/Search (`_search_graph_db`):** Finds these entities in Neo4j by embedding similarity and retrieves their relationships.
    3. **Reranking:** The retrieved relationship triplets are reranked using BM25 against the original query text to improve relevance.