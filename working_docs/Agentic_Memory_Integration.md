[FEATURE]RFC: Agentic Memory Integration in Opensearch

This RFC proposes a native memory system for intelligent agents in OpenSearch. It aims to unify short-term and long-term memory handling (episodic, semantic, procedural) under consistent and production-ready /memories APIs. By leveraging OpenSearch's vector store and security features, this system goes beyond transient chat history and supports memory-driven reasoning, self-evolution, and remote agent collaboration.


Problem Statement:

Today’s OpenSearch ML Commons plugin supports only lightweight memory for conversational search. While helpful for simple message recall, it falls short for enabling intelligent agents that need to reason over structured, persistent knowledge across time and context.
Although OpenSearch stores message-level interactions, the current approach lacks intelligent memory abstraction. As conversations grow, retrieving useful context becomes increasingly inefficient due to LLM context size limitations. Dumping large message histories into prompts leads to cost, latency, and model degradation issues.
Moreover, the stored messages lack structure—there is no differentiation between facts, plans, feedback, or procedures. Without metadata, summarization, or conflict resolution, agents cannot evolve their understanding or actions based on past experiences.


Core Challenges:

1. Context Window Constraints

Storing raw messages grows context size uncontrollably. Feeding entire history into LLMs is unsustainable as token costs grow and relevance drops.

1. Unstructured Memories

Current message APIs treat all content equally, offering no classification (e.g., facts vs. procedures), no temporal weighting, and no summarization.

1. Lack of Memory Evolution

New messages don't update or invalidate previous beliefs. Agents lack mechanisms to self-correct, enhance, or generalize memory over time.

1. No Procedural Knowledge

There's no ability to record workflows or learned SOPs that agents can reuse.

### Gaps in Existing Capabilities

OpenSearch’s current memory/message APIs (introduced in 2.12) offer basic message-level CRUD functionality. These APIs serve conversational search well but are limited in the following ways:

| Capability             | ML Commons 2.12              | Proposed Agentic Memory             |
|------------------------|-------------------------------|-------------------------------------|
| Purpose                | Conversation history only     | Structured, persistent memory       |
| Memory Types           | Messages only                 | Episodic / Semantic / Procedural    |
| Memory Evolution       | Manual only                   | LLM-assisted ADD / UPDATE / DELETE  |
| Procedural Abstraction | ❌                             | ✅                                   |
| Summarization          | ❌                             | ✅                                   |
| Agent Support          | Internal agents only          | Internal + external agents          |
| Multi-Tenant Isolation | Private memory only           | Full RBAC, security plugin control  |




<br><br>This proposal introduces memory as a <b>first-class citizen</b> in the OpenSearch agent ecosystem, enabling context-aware agents and memory-driven reasoning across tools.<br><br><h2><b>Requirements</b></h2><h3><b>Intelligent Memory Management</b></h3><ul><li>Automatic extraction of salient facts from conversation</li><li>Conflict resolution: update/delete outdated or contradicting entries</li><li>Summarization for compression</li><li>Configurable memory retention policies</li></ul><h3><b>Lifecycle Management</b></h3><ul><li>Memory history inspection (ADD/UPDATE/DELETE)</li><li>Pruning and aging policies</li><li>Version tracking</li></ul><h3><b>Security &amp; Access Control</b></h3><ul><li>Role-based access control (RBAC)</li><li>Audit logging of memory operations</li><li>Multi-tenant memory segregation</li></ul><h3><b>Developer Experience</b></h3><ul><li>Simple <code><code>/memories</code></code> REST APIs</li><li>Batch operations</li><li>Supports both agent-driven and user-driven memory ops</li><li>Observability/debug tools for inspecting memory state</li></ul>


Solution Overview

We propose a memory framework implemented within ML Commons plugin, consisting of:

* Memory Containers: logical groupings of memories (supporting vector stores and metadata)
* Memory API: CRUD operations, vector search, summarization, update hooks
* Embedding/Summarization Hooks: integration with LLMs for transforming memory
* Security: integrated with Security Plugin’s resource access control



Memory Types

Short-Term Memory (STM)

* Working memory, focus window, recent messages
* Stored in-memory, optionally persisted via checkpoint mechanism

Long-Term Memory (LTM)

Episodic Memory

* Time-based logs of past events
* Summarized user sessions, specific case outcomes

Semantic Memory

* Structured factual knowledge: preferences, domain info, facts
* Managed with embeddings, optionally connected to graph DBs

Procedural Memory

* "How-to" knowledge: SOPs, multi-step workflows
* Extracted from successful past interactions or scripted by users

Memory Ingestion & Transformation Flow:

```
           +----------------------+
           | Agent Input (Prompt, |
           | Tool, Notebook Block)|
           +----------+-----------+
                      |
                      v
           +----------------------+
           |  LLM Transformation  |
           | (Summarize, Classify,|
           |  Extract Tags)       |
           +----------+-----------+
                      |
                      v
            +---------------------+
            |  Memory Router      |
            |  - Search Memory    |
            |  - Conflict Check   |
            |  - Decide Action    |
            +----------+----------+
                      |
      +---------------+---------------+
      |               |               |
   [ADD]           [UPDATE]        [DELETE]
      |               |               |
      v               v               v
+----------------------------+        |
|  Ingest Entry to Vector DB |        |
|  - Store Raw Text          |        |
|  - Generate Embedding      |        |
|  - Assign Metadata & Tags  |        |
|  - Index in KNN-backed idx |        |
+----------------------------+        |
                                     ...

```

Key REST APIs

REST APIs

Create Memory container

Creates a memory container with specified configurations for LLM, vector store, and optional graph store. The system automatically generates a KNN index.

```
POST /_plugins/_ml/mem-containers
{
    "name": "AOS Ops Assistant memory",
    "description": "this is a test memory for AOS ops assistant",
    "config": {
        "llm": "<llm_model_id>",
        "vector_store": {
            "index_name": "aos_ops_memory_knn_index",
            "embedding_model": "<embedding_model_id>"
        },
        "graph_store": {# optional
            "connector_id": "<connector of GraphDB like Neo4J>",
            "llm": "<llm_model_id>" 
        }
    }
}

```

output

```
{
    "container_id": "TfnzAdiRpyWdaSfad"
}
```
Get container configuration

Retrieves the configuration details of an existing memory container.

```
GET /_plugins/_ml/mem-containers/TfnzAdiRpyWdaSfad
```

output

```
{
    "name": "AOS Ops Assistant memory",
    "description": "this is a test memory for AOS ops assistant",
    "config": {
        "llm": "<llm_model_id>",
        "vector_store": {
            "index_name": "aos_ops_memory_knn_index",
            "embedding_model": "<embedding_model_id>"
        },
        "graph_store": { # optional
            "connector_id": "<connector of GraphDB like Neo4J>",
            "llm": "<llm_model_id>" 
        }
    },
    "created_time": "2025-06-05T09:39:29.965868-07:00"
}
```

Add memory

Add new memories to the container. 

Workflow

1. Store the messages to existing conversation messages. 
2. Extraction Phase: extracts salient facts from messages using LLM. 
3. Update Phase: compares with existing memories and performs ADD/UPDATE/DELETE/NOOP. For ADD/UPDATE actions, will call embedding model to regenerate embedding.
    1. ADD: If the new fact is novel and doesn't overlap semantically with existing memories, the LLM instructs the system to add it as a new memory. When create a new memory, will write the request OpenSearch user info (roles) into memory for permission control through Security Plugin's resource access control.
    2. UPDATE: When the new fact complements or enhances an existing memory, the LLM updates the existing memory to incorporate the new information.
    3. DELETE: If the new fact contradicts an existing memory, indicating that the previous information is outdated or incorrect, the LLM deletes the conflicting memory.
    4. NOOP: If the new fact doesn't provide additional value or change to the existing memory, the LLM takes no action, leaving the memory unchanged.

```
POST /_plugins/_ml/mem-containers/TfnzAdiRpyWdaSfad/memories
{
    "messages": [
        {"role": "user", "content": "I see my model not responding in AWS OpenSearch cluster"},
        {"role": "assistant", "content": "Have you checked the model status? Is it undeployed or deleted?"},
        {"role": "user", "content": "Thanks, the model not deployed, it works after reredployment."},
        {"role": "assistant", "content": "Cool, glad to know the issue solved."}
    ],
    "tags": { //flat object, optional
        "category": "opensearch_model_issue",
        "user_id": "alice", # logical user for application level, not OpenSearch user
        "agent_id": "abc123abc123"
    }
}
```

output

```
KNN index: 
{
  "memories": [
    {
        "memory_id": "QaZxSwEdCvFrTgBy",
        "container_id": "TfnzAdiRpyWdaSfad",
        
        // Embedding will be generated for memory field, but not return in response
        "memory": "Try to redeploy model first if model not responding",
        "tags": {
            "category": "opensearch_model_issue",
            "user_id": "alice"
        },
        "created_time": "2025-06-05T09:39:29.965868-07:00",
        "updated_time": None,
        
        
        // Access control: resource access control. via security plugin
        "access_role": [ "aos_ml" ]
    }
  ]
}
```

Search memories

Performs vector similarity search to retrieve relevant memories. Supports optional summarization.

URL parameters

* summarize: default false, if set as true will summarize the extracted memory. 
* only_return_summarization: default false, If set as true, will return memory summarization only, not returning the detail memories.

Workflow:

1. Find the vector store index and LLM configured in memory container.
2. Execute query
3. If summazie=true , use LLM to summarize detail memories; If only_return_summarization=true, remove detail memories from response
4. return response

```
GET /_plugins/ml/mem-containers/TfnzAdiRpyWdaSfad/_search?summazie=true&only_return_summarization=false
{
  "query": {
    "bool": {
      "must": [
        {
          "neural": {
            "memory": {
              "query_text": "My model suddenly can't work"
            }
          }
        }
      ],
      "filter": [
        {
          "term": {
            "user_id": "alice"
          }
        }
      ]
    }
  },
  "size": 20,
  "_source": {
    "includes": [
      "memory_id",
      "memory"
    ]
  },
  "sort": [
    "created_time"
  ]
}
```

output

```
{
  "memories": [// will not return detail memories if only_return_summarization=true
    {
        "memory_id": "QaZxSwEdCvFrTgBy",
        "memory": "Try to redeploy model first if model not responding"
    } 
  ],

  // will output LLM summarized memory if summazie=true
  "summarization": "Try to redeploy model first if model not responding"
}
```

Update memory

Updates existing memory . This feature is useful when users discover that the LLM-extracted memories are incorrect. Users can use the update memory API to directly fix these memories.

```
PUT /_plugins/ml/containers/TfnzAdiRpyWdaSfad/memories/QaZxSwEdCvFrTgBy
{
    "memory": "If model not responding, Check if model exists first. If yes, try to redeploy model first.",
    "metadata": {
        "category": "opensearch_model_issue/not_responding"
    },
   "user_id": "alice"
}
```

output

```
{
   "message": "updated successfully"
}
```

Batch update memory

Updates multiple memory entries in a single request. 

```
PUT /_plugins/ml/containers/TfnzAdiRpyWdaSfad/memories
{
  "memories": [
    {
        "memory_id": "QaZxSwEdCvFrTgBy",
        "memory": "Try to redeploy model first if model not responding",
        "user_id": "alice"
    },
    {
        "memory_id": "YtReWqLkJhGfDsAm",
        "memory": "Bring red cluster back to green: delete redex index and restore from snapshot",
        "metadata": {
            "category": "opensearch_model_issue/not_responding"
        },
        "user_id": "alice"
    }
  ]
}
```

Get memory

Retrieves a specific memory entry by ID.

```
GET /_plugins/ml/memories/TfnzAdiRpyWdaSfad//QaZxSwEdCvFrTgBy
```

output

```
{
    "memory_id": "QaZxSwEdCvFrTgBy",
    "container_id": "TfnzAdiRpyWdaSfad",
    "memory": "Try to redeploy model first if model not responding",
    "tags": {
        "category": "opensearch_model_issue",
        "user_id": "alice"
    },
    "created_time": "2025-06-05T09:39:29.965868-07:00",
    "updated_time": "2025-06-06T10:32:21.965868-07:00"
} 
```

Get memory history

Retrieves the history of changes for a specific memory entry, including ADD/UPDATE/DELETE operations.

This feature helps users track all actions performed on a memory entry. It's particularly useful for testing and debugging purposes, allowing users to identify any incorrect actions or extractions made by the LLM.

```
GET /_plugins/ml/containers/TfnzAdiRpyWdaSfad/memories/QaZxSwEdCvFrTgBy/history
```

output
```
{
  "history": [
     {
        "memory_id": "QaZxSwEdCvFrTgBy",
        "container_id": "TfnzAdiRpyWdaSfad",
        "memory": "Try to redeploy model first if model not responding",
        "tags": {
            "category": "opensearch_model_issue",
            "user_id": "alice"
        },
        "created_time": "2025-06-05T09:39:29.965868-07:00",
        "updated_time": None,
        "event": "ADD"
    },
    {
        "memory_id": "PqOwMnEbVcXzLkJh",
        "container_id": "TfnzAdiRpyWdaSfad",
        "memory": "If model not responding, Check if model exists first. If yes, try to redeploy model first.",
        "tags": {
            "category": "opensearch_model_issue/not_responding",
            "user_id": "alice",
        },
        "created_time": "2025-06-05T09:39:29.965868-07:00",
        "updated_time": "2025-06-06T10:32:21.965868-07:00",
        "event": "UPDATE"
    }
  ]
}
```

Delete memory

Removes a memory entry and its history.

```
DELETE /_plugins/ml/containers/TfnzAdiRpyWdaSfad/memories/QaZxSwEdCvFrTgBy
```

Batch delete memory

Removes multiple memory entries and their histories in a single request.

```
DELETE /_plugins/ml/containers/TfnzAdiRpyWdaSfad/memories
{
    "memories": [
        {
            "memory_id": "QaZxSwEdCvFrTgBy"
        },
        {
            "memory_id": "YtReWqLkJhGfDsAm"
        }
    ]
}
```

<h2><b>Agent Integration/b></h2>

* Internal agents: can interact with memory both directly (via transport calls to memory APIs) and indirectly via tool interfaces (e.g., MCP agent tools). This provides maximum flexibility—agents can choose the most appropriate integration style based on use case.
* Remote agents: interact via REST APIs with proper tenant headers and security roles
* Prompt engineering layer: wraps memory search, injecting retrieved context into LLM prompts to enhance reasoning


```
+-------------------------+
|        Agent            |
+-------------------------+
| Internal (Transport API)|
| External (REST + Auth)  |
+-------------------------+
           |
           v
+------------------+
|  /memories APIs  |
+------------------+
           |
           v
+--------------------------+
| Structured Memory Store  |
+--------------------------+

```


<h2><b>Conclusion</b></h2>This RFC proposes a scalable and production-ready memory module that complements OpenSearch’s emerging agent capabilities. By providing persistent and structured memory, OpenSearch agents can become smarter, more contextual, and more useful in real-world applications.<br>We recommend starting with core APIs: create, add, search, update. Later phases can introduce summarization, graph-based reasoning, and memory consolidation tools.<br>