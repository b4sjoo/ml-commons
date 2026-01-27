### Memory Flow

1. **Adding Memory**:
   - Input → LLM processing → Extract facts/entities → Generate embeddings → Store in vector DB + graph DB + SQLite

2. **Retrieving Memory**:
   - Query → Generate embedding → Vector search + Graph search → Re-rank with BM25 → Return relevant memories

### Core Business Logic Implementation

1. **Multi-Store Memory Architecture**:
   - **Vector Store**: Semantic similarity search using embeddings
   - **Graph Store**: Entity relationships and traversal (Neo4j)
   - **SQLite**: History tracking and audit trail
   - All three stores operate in parallel for optimal performance

2. **Intelligent Memory Operations**:
   - **Fact Extraction**: LLM extracts relevant facts from conversations using `FACT_RETRIEVAL_PROMPT`
   - **Deduplication**: Compares new facts with existing memories (top 5 similar)
   - **Smart Updates**: LLM decides whether to ADD, UPDATE, DELETE, or NONE for each fact
   - **Conflict Resolution**: Handles contradictions and consolidates related information

3. **Multi-Level Memory Isolation**:
   - Every memory operation requires exactly one session identifier:
     - `user_id`: User-specific memories
     - `agent_id`: Agent-specific procedural memories
     - `run_id`: Run-specific temporary memories
   - Enforced through `_build_filters_and_metadata()` helper

4. **Hybrid Search with BM25 Re-ranking**:
   - **Vector Search**: Semantic similarity using embeddings
   - **Graph Search**: Entity relationship traversal
   - **BM25 Re-ranking**: Improves relevance of final results
   - Returns both memory results and graph relations

5. **Memory Update Rules**:
   - **ADD**: New information not present in memory
   - **UPDATE**: Consolidate related info (e.g., "likes pizza" → "loves cheese pizza")
   - **DELETE**: Remove contradictory information
   - **NONE**: Skip redundant information

6. **Performance Optimizations**:
   - **Concurrent Processing**: Parallel operations for vector and graph stores
   - **Embedding Cache**: Reuses embeddings to avoid recomputation
   - **Lazy Loading**: Providers loaded only when needed
   - **Batch Operations**: Process multiple facts together
   - **Filtered Searches**: Efficient queries with metadata filters

7. **Scalability Features**:
   - **Provider Abstraction**: Easy switching between 15+ vector databases
   - **Local SQLite**: Fast history tracking without network overhead
   - **UUID Mapping**: Handles potential UUID hallucinations from LLM
   - **Telemetry**: Built-in event tracking for monitoring

8. **Memory Extraction Implementation**:
   - **Conversation Summarization**:
     - **Procedural Memory**: For agent interactions, creates comprehensive summaries using `PROCEDURAL_MEMORY_SYSTEM_PROMPT`
     - **Fact Extraction**: For regular conversations, extracts key facts instead of full summaries
     - TODO: No dynamic conversation summarization for regular memories yet
   - **Recent Message Windowing**:
     - **Fixed Window**: Last 6 messages used in proxy layer (`_fetch_relevant_memories`)
     - **Not Configurable**: Window size is hardcoded, not a hyperparameter
     - **Simple Concatenation**: Messages joined with newlines, no sophisticated processing
   - **Current Limitations**:
     - No retrieval of historical conversation summaries from database
     - No progressive summarization of older messages
     - Windowing only exists in proxy layer, not in core memory operations

This architecture achieves the performance improvements mentioned in their paper:
- **+26% Accuracy**: Through intelligent fact extraction and deduplication
- **91% Faster**: By avoiding full context retrieval, using targeted search instead
- **90% Fewer Tokens**: By returning only relevant memories instead of full history (using 6-message window)

### Low-Level Core API Analysis

#### 1. **ADD API**
**Signature**: `add(messages, *, user_id=None, agent_id=None, run_id=None, metadata=None, infer=True, memory_type=None, prompt=None)`

**Internal Flow**:
1. **Session Validation**: Requires exactly one session ID via `_build_filters_and_metadata()`
2. **Message Preprocessing**: Converts strings to message format, handles vision messages
3. **Dual Processing Paths**:
   - **Procedural Memory**: Uses `PROCEDURAL_MEMORY_SYSTEM_PROMPT` for comprehensive summaries
   - **Regular Memory**: Extracts facts, searches similar memories, decides ADD/UPDATE/DELETE/NONE
4. **Parallel Storage**: Concurrent execution to vector store and graph store
5. **Vector Store Operations** (`_create_memory`):
   ```python
   memory_id = str(uuid.uuid4())
   metadata["hash"] = hashlib.md5(data.encode()).hexdigest()
   self.vector_store.insert(vectors=[embeddings], ids=[memory_id], payloads=[metadata])
   self.db.add_history(memory_id, None, data, "ADD", ...)
   ```
6. **Graph Store Operations**: Extracts entities, establishes relationships, merges nodes in Neo4j

#### 2. **UPDATE API**
**Signature**: `update(memory_id, data)`

**Internal Flow**:
1. **Direct Update**: No fact extraction, generates new embedding
2. **Update Process** (`_update_memory`):
   - Retrieves existing memory
   - Preserves original metadata (user_id, agent_id, created_at)
   - Updates hash and timestamp
   - Updates vector store with new embedding
   - Records full history in SQLite

#### 3. **DELETE API**
**Signature**: `delete(memory_id)`

**Internal Flow**:
1. **Single Deletion** (`_delete_memory`):
   - Retrieves memory for history
   - Deletes from vector store
   - Records in history with `is_deleted=1`
2. **Bulk Deletion**: `delete_all()` filters by session ID
3. **Graph Deletion**: Removes specific relationships between nodes

#### 4. **SEARCH API**
**Signature**: `search(query, *, user_id=None, agent_id=None, run_id=None, limit=100, filters=None)`

**Internal Flow**:
1. **Parallel Search**: Concurrent vector and graph searches
2. **Vector Search**: 
   - Generates query embedding
   - Searches with filters and limit
   - Returns with similarity scores
3. **Graph Search**:
   - Extracts entities from query
   - Searches similar nodes (cosine > 0.7)
   - **BM25 Re-ranking** for relevance:
   ```python
   bm25 = BM25Okapi(search_outputs_sequence)
   reranked_results = bm25.get_top_n(tokenized_query, search_outputs_sequence, n=5)
   ```
4. **Result Formatting**: Promotes key fields, includes scores, separates metadata

**Key Implementation Details**:
- **Embedding Cache**: Reuses embeddings in batch operations
- **UUID Mapping**: Prevents LLM hallucinations with temporary integer IDs
- **History Tracking**: Every operation recorded with timestamps and actor info
- **Error Handling**: Validates memory existence, handles provider exceptions
- **Telemetry**: All operations tracked with `capture_event()`
