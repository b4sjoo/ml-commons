# Access Control Design on Agentic Memory

## Introduction

The new Agentic Memory feature gives OpenSearch the powerful ability to store and recall information from conversations, effectively giving a "memory" to AI agents. This memory can contain anything from user preferences and conversation history to **personally identifiable information (PII)** extracted by the LLM. As we enable users and automated agents to store and manage this potentially sensitive data, implementing robust access control is not just a feature enhancement—it is a fundamental requirement for security, privacy, and user trust.

Without proper access control, we face significant risks: 

1. Data Privacy Violations: A user's memories, potentially containing personal or confidential information, could be accessed by other unauthorized users or agents.
2. Data Integrity Issues: Malicious or poorly configured agents could intentionally or unintentionally corrupt or delete memory data, leading to a poor user experience and loss of critical information.
3. Security Vulnerabilities: Unrestricted access to memory APIs could be exploited to inject harmful data or overwhelm the system.

By implementing fine-grained access control, we ensure that only authorized users and agents can view, create, or modify memories. This is essential for building a secure, reliable, and enterprise-ready agentic memory system that our users can trust with their data.

However, good news is, in 3.3, security team will introduce a new resource-based access control framework which allowed current OpenSearch users to do a document-based sharing **[RFC: https://github.com/opensearch-project/security/issues/4500]**. The proposed Resource-Based Access Control framework would be a significant contribution to our agentic memory feature. It provides a standardized way to  implement the exact, fine-grained security controls that our memory data requires.

Instead of building a custom authorization layer for agentic memory, we could leverage this new framework. This would allow a user—the "owner" of a memory container—to explicitly control which other users or backend roles can access or modify their memories. This directly addresses the security and privacy risks of unauthorized data access and ensures that our agentic memory feature is built on a robust, centralized, and enterprise-grade security model from day one.

## Flow Diagram

[Image: image]

## Option 1: User + Model access control on memory container, self-configured access control on memory (current status)

### Introduction

Basically we only use the access of LLM model and embedding model to determine the access for the memory

### pros

* Easiest way with least tech efforts
* Still comply with AppSec
* AOSS migration friendly

### cons

* Currently agent side has no access control, if memory container also has no access control, it might cause security issue
* Complicated out-of-the-box experience for user to setup this, make user hard to adopt the feature
* If user does not provide any LLM or embedding models, it will have no access control
* All container are considered as private, which has very low level granularity and not robust at all

## Option 2: Resource-based control on memory container,  self-configured access control on memory [Via Security Plugin]

### Introduction

Security team provided a resource-based control framework in ML commons plugin, which we can utilize.

```
{
   "source_idx": ".plugins-ml-agentic-memory-container",
   "resource_id": "<doc_id_of_memory-container>",
   "created_by": {
      "user": "seasonsg1",
      "backend_role": "",
   },
   "share_with": { // If no share_with provided, this doc will be private
      "read_only": {
         "users": [
            "seasonsg2"
         ],
         "roles": [ml-memory-access],
         "backend_roles": [arn*]
      },
      "read_write": { // also support wildcard to make it as a public accessible resource
         "users": [
            *
         ],
         "roles": [*],
         "backend_roles": [*]
      }
   }
}
```

#### Memory container system index mapping

```
{
  ".plugins-ml-agentic-memory-container": {
    "aliases": {},
    "mappings": {
      "_meta": {
        "schema_version": 1
      },
      "properties": {
        "created_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "description": {
          "type": "text"
        },
        "last_updated_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "memory_storage_config": {
          "properties": {
            "dimension": {
              "type": "integer"
            },
            "embedding_model_id": {
              "type": "keyword"
            },
            "embedding_model_type": {
              "type": "keyword"
            },
            "llm_model_id": {
              "type": "keyword"
            },
            "max_infer_size": {
              "type": "integer"
            },
            "max_recent_messages": {
              "type": "integer"
            },
            "memory_index_name": {
              "type": "keyword"
            },
            "semantic_storage_enabled": {
              "type": "boolean"
            }
          }
        },
        "name": {
          "type": "text",
          "fields": {
            "keyword": {
              "type": "keyword",
              "ignore_above": 256
            }
          }
        },
        "owner": {
          "type": "nested",
          "properties": {
            "backend_roles": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "custom_attribute_names": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "name": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword",
                  "ignore_above": 256
                }
              }
            },
            "roles": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "user_requested_tenant_access": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword",
                  "ignore_above": 256
                }
              }
            }
          }
        },
        "tenant_id": {
          "type": "keyword"
        }
      }
    }
  }
}
```

#### pros

* This provided a high granularity doc-based access control option
* The security team has opened both the REST/Transport client for us, indicating we can do a pre-configured for user by coding

#### cons

* This is a new feature, reliability need to be carefully examined
* Mild level code changes
* Memory index is still managed by user, and not pre-configured, indicating a complicated out-of-the-box experience for user
* **AOSS does not have security plugin** onboarded, indicating potential migration issue

## Option 3.1: System-Index-Is-All-You-Need (Resource-based control on memory container,  resource-based control on memory)

### Introduction

Similar to Option 2, plus onboarding resource-based control feature to memory indices also. However, in order to decrease cluster pressure and simplify the maintaining effort, we need to minimize the system indices we create. Accordingly, we need to merge previous isolated memory indices (one memory index per container) into 3+ system indices according to the index type (static content only, sparse, and KNN enabled index)

#### Memory container system index mapping

```
{
  ".plugins-ml-agentic-memory-container": {
    "aliases": {},
    "mappings": {
      "_meta": {
        "schema_version": 1
      },
      "properties": {
        "created_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "description": {
          "type": "text"
        },
        "last_updated_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "memory_storage_config": {
          "properties": {
            "dimension": {
              "type": "integer"
            },
            "embedding_model_id": {
              "type": "keyword"
            },
            "embedding_model_type": {
              "type": "keyword"
            },
            "llm_model_id": {
              "type": "keyword"
            },
            "max_infer_size": {
              "type": "integer"
            },
            "max_recent_messages": {
              "type": "integer"
            },
            "memory_index_name": {
              "type": "keyword" // If we decide to use system index to store we need to mark this field hidden and unsearchable due to security concern
            },
            "semantic_storage_enabled": {
              "type": "boolean"
            }
          }
        },
        "name": {
          "type": "text",
          "fields": {
            "keyword": {
              "type": "keyword",
              "ignore_above": 256
            }
          }
        },
        "owner": {
          "type": "nested",
          "properties": {
            "backend_roles": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "custom_attribute_names": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "name": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword",
                  "ignore_above": 256
                }
              }
            },
            "roles": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "user_requested_tenant_access": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword",
                  "ignore_above": 256
                }
              }
            }
          }
        },
        "tenant_id": {
          "type": "keyword"
        }
      }
    }
  }
}
```

#### Static system index mapping

```
{
  ".plugin-ml-static-memory-storage": {
    "aliases": {},
    "mappings": {
      "properties": {
        "agent_id": {
          "type": "keyword"
        },
        "created_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "last_updated_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "memory": {
          "type": "text"
        },
        "memory_type": {
          "type": "keyword"
        },
        "role": {
          "type": "text"
        },
        "session_id": {
          "type": "keyword"
        },
        "tags": {
          "type": "flat_object"
        },
        "user_id": {
          "type": "keyword"
        }
      }
    }
  }
}
```

#### Sparse encoding system index mapping

```
{
  ".plugin-ml-sparse-memory-storage": {
    "aliases": {},
    "mappings": {
      "properties": {
        "agent_id": {
          "type": "keyword"
        },
        "created_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "last_updated_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "memory": {
          "type": "text"
        },
        "memory_embedding": {
          "type": "rank_features" // Comparing to the static index this is the only differnt field, so we can even merge these 2 indices
        },
        "memory_type": {
          "type": "keyword"
        },
        "role": {
          "type": "text"
        },
        "session_id": {
          "type": "keyword"
        },
        "tags": {
          "type": "flat_object"
        },
        "user_id": {
          "type": "keyword"
        }
      }
    }
  }
}
```

#### KNN system index mapping

```
{
  ".plugin-ml-knn-memory-storage-$DIMENSION": { // security plugin support wildcard system index, so we only need to declare .plugin-ml-knn-memory-storage-*  
    "aliases": {},
    "mappings": {
      "properties": {
        "agent_id": {
          "type": "keyword"
        },
        "created_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "last_updated_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "memory": {
          "type": "text"
        },
        "memory_embedding": {
          "type": "knn_vector",
          "dimension": 1024, // user might have specified different dimension due to their embedding model
          "method": {
            "engine": "lucene",
            "space_type": "cosinesimil",
            "name": "hnsw",
            "parameters": {
              "ef_construction": 100,
              "m": 16
            }
          }
        },
        "memory_type": {
          "type": "keyword"
        },
        "role": {
          "type": "text"
        },
        "session_id": {
          "type": "keyword"
        },
        "tags": {
          "type": "flat_object"
        },
        "user_id": {
          "type": "keyword"
        }
      }
    },
    "settings": {
      "index": {
        "knn.algo_param": {
          "ef_search": "100"
        },
        "knn": "true"
      }
    }
  }
}
```

#### pros

* This provided a high granularity doc-based access control option for both memory container itself and user’s memory
* The security team has opened both the REST/Transport client for us, indicating we can do a pre-configured for user by coding

#### cons

* More coding efforts
* This feature required the index to be per-registered system index, indicating:
    * Current user memory index need to be changed to system index
    * User still need to configured access control on self-provided memory index
    * We will have at least 6 more system indices (onboarding this framework will double the system index number)
* KNN will still have many system indices because vector length cannot be determined
    * Also the engine and other KNN hyper-parameters can have many different combinations, contributing the variety towards this index settings, but we can hard restrict those. However, it is still unreasonable to restric the vector length
* With this option we need to mark memory-index field hidden and unsearchable due to AppSec concern
* AOSS required all index containing customer data to provide KMS encryption option to customer, whereas all our system indices are stored in DynamoDB, indicating this option requires most migration effort

## Option 3.2: One-Index-to-Rule-Them-All (one memory container system index, one memory system index)

### Introduction

Same as option 3.1, but everytime user specifed a new memory container with different KNN dimension, we will update the system index mapping to add a new field called `memory_knn_embedding_$DIMENSION` . In the meantime, the XContent parser need to be carefully designed to enable the field with prefix `memory_knn_embedding_` can be successfully parsed whereas illegal field still get skipped. When user trying to create or query the memory we will use memory container to validate the dimension and embedding model info to assure it will get embedded to the right field. In the meantime since we won’t show the embedding field to user anyway (including search and get api) we can just get exclude those fields so that user won’t have a super long result.

#### Memory container system index mapping (same as 3.1)

```
{
  ".plugins-ml-agentic-memory-container": {
    "aliases": {},
    "mappings": {
      "_meta": {
        "schema_version": 1
      },
      "properties": {
        "created_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "description": {
          "type": "text"
        },
        "last_updated_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "memory_storage_config": {
          "properties": {
            "dimension": {
              "type": "integer"
            },
            "embedding_model_id": {
              "type": "keyword"
            },
            "embedding_model_type": {
              "type": "keyword"
            },
            "llm_model_id": {
              "type": "keyword"
            },
            "max_infer_size": {
              "type": "integer"
            },
            "max_recent_messages": {
              "type": "integer"
            },
            "memory_index_name": {
              "type": "keyword" // If we decide to use system index to store we need to mark this field hidden and unsearchable due to security concern
            },
            "semantic_storage_enabled": {
              "type": "boolean"
            }
          }
        },
        "name": {
          "type": "text",
          "fields": {
            "keyword": {
              "type": "keyword",
              "ignore_above": 256
            }
          }
        },
        "owner": {
          "type": "nested",
          "properties": {
            "backend_roles": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "custom_attribute_names": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "name": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword",
                  "ignore_above": 256
                }
              }
            },
            "roles": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword"
                }
              }
            },
            "user_requested_tenant_access": {
              "type": "text",
              "fields": {
                "keyword": {
                  "type": "keyword",
                  "ignore_above": 256
                }
              }
            }
          }
        },
        "tenant_id": {
          "type": "keyword"
        }
      }
    }
  }
}
```

#### Memory system index mapping

```
{
  ".plugin-ml-memory-storage": {
    "aliases": {},
    "mappings": {
      "properties": {
        "agent_id": {
          "type": "keyword"
        },
        "created_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "last_updated_time": {
          "type": "date",
          "format": "strict_date_time||epoch_millis"
        },
        "memory": {
          "type": "text"
        },
        "role": {
          "type": "text"
        },
        "session_id": {
          "type": "keyword"
        },
        "tags": {
          "type": "flat_object"
        },
        "user_id": {
          "type": "keyword"
        },
        "memory_sparse_embedding": {
          "type": "rank_features"
          "type": "keyword"
        },
        "memory_knn_embedding_$DIMENSION1": {
          "type": "knn_vector",
          "dimension": $DIMENSION1,
          "method": {
            "engine": "lucene",
            "space_type": "cosinesimil",
            "name": "hnsw",
            "parameters": {
              "ef_construction": 100,
              "m": 16
            }
          }
        },
        "memory_knn_embedding_$DIMENSION2": {
          "type": "knn_vector",
          "dimension": $DIMENSION2,
          "method": {
            "engine": "lucene",
            "space_type": "cosinesimil",
            "name": "hnsw",
            "parameters": {
              "ef_construction": 100,
              "m": 16
            }
          }
        },
        ...
      }
    },
    "settings": {
      "index": {
        "knn.algo_param": {
          "ef_search": "100"
        },
        "knn": "true"
      }
    }
  }
}
```

#### pros

* This provided a high granularity doc-based access control option for both memory container itself and user’s memory
* The security team has opened both the REST/Transport client for us, indicating we can do a pre-configured for user by coding
* Only 2*2=4 system indices will be added

#### cons

* More coding efforts even than 3.1, with complex creating, retrieving, and updating logic
* This feature required the index to be per-registered system index, indicating:
    * Current user memory index need to be changed to system index
    * User still need to configured access control on self-provided memory index
* With this option we need to mark memory-index field hidden and unsearchable due to AppSec concern
* AOSS required all index containing customer data to provide KMS encryption option to customer, whereas all our system indices are stored in DynamoDB, indicating this **option requires most migration effort**

## Meeting notes:

1. To avoid performance issue under multi-tenancy/large amount of user querying one KNN index, try score script filter (cons: Not working with neural search and neural sparse search)
Shard size could become an issue when document number scaling up, suggesting:
  * Let S3 to store the data, with S3 vector search plus metadata filtering
    * Pros: S3 will help manage the scale
    * Cons: additional setup for user, queries are limited, not support neural and neural sparse
  * Create a stand-alone serverless collection to store
    * Pros: serverless works in an auto-scaling way, can support all kinds of serverless query
    * Cons: Cx need to help manage the serverless collection, which could cause a pain in Cx
  * Find some way to strict the index scale like background summarization on long term memory,  shorter storing date (e.g. 7 days) for short-term memory or even control the maximal number of short term memory (like 50 per user)
    * Pros: Less technical efforts
    * Cons: Could potentially impact the accuracy
2. If parsing unknown incoming field could be a problem, we can try creating several different KNN index plus a metadata (static) index with term lookup query
3. To decrease memory pressure of the cluster, try memory optimized search and disk based compression (32x)
4. For best performance on KNN search, try FAISS + ANN (cons: current FAISS support have some issue in ml side)
5. We might only need to leverage the memory index to be system index but no need to onboard the resource-base access control, to cut the system index number into half


