# Agentic Memory Threat Model

# Introduction

## Purpose

A [threat model](https://catalog.workshops.aws/threatmodel/en-US/introduction/what-is-threat-modeling) answers four questions: What are we working on? What can go wrong? What are we going to do about it? Did we do a good job? The purpose of a threat model document is to ensure your reader can answer these questions for the system being modeled. 

## Project background

Today’s OpenSearch ML Commons plugin supports only lightweight memory for conversational search. While helpful for simple message recall, it falls short for enabling intelligent agents that need to reason over structured, persistent knowledge across time and context. As conversations grow, retrieving useful context becomes increasingly inefficient due to LLM context size limitations. Dumping large message histories into prompts leads to cost, latency, and model degradation issues.

This project introduces the Agentic Memory feature, a native memory system for intelligent agents in OpenSearch. It aims to unify short-term and long-term memory handling (episodic and semantic) under a consistent, production-ready API. This allows agents to store and recall information from conversations, effectively giving them a "memory" to enable memory-driven reasoning, self-evolution, and better contextual understanding.

## Service Overview

The Agentic Memory System for OpenSearch ML Commons is a production-ready native memory management framework that enables intelligent agents to store, process, and retrieve conversational memories with state-of-the-art LLM automation. This feature unifies episodic and semantic memory handling in a scalable, enterprise-ready solution. It provides REST APIs for managing memory "containers" and the memories within them. The system can automatically extract key facts from conversations, store them, and use them to answer future questions with better context. It supports different storage backends, including static text, sparse, and dense (KNN) vector search for semantic retrieval.

## Security Tenets

- **Data Privacy and Isolation:** A user's memories, which may contain personal or confidential information, must be accessible only to that user and those they explicitly authorize. Multi-tenant environments must ensure strict data isolation.
- **Data Integrity:** Memories must be protected from unauthorized or unintentional modification or deletion. The integrity of conversational history and learned facts is critical for agent performance and user trust.
- **Least Privilege Access:** All components, including users and agents, must operate with the minimum permissions necessary. Access to memory APIs and underlying data stores must be strictly controlled.
- **Secure by Default:** The system should provide strong security out-of-the-box. Features like access control should be enabled by default, and users should not have to perform complex configurations to secure their data.

## Assumptions

*<You can make assumptions about design considerations, threats, and mitigations. Assumptions help you focus your effort and avoid wasting time on design considerations that might be irrelevant, threats that may be out of scope, or the effectiveness of common mitigations. If an assumption is invalidated later, you can always revisit those parts of the threat model.* *Optionally, include mitigations for your assumptions. >*

|ID	|Assumption	|Comments	|	|
|---	|---	|---	|---	|
|A-01	|AWS authentication (SigV4) and authorization (IAM) work correctly and are trusted methods to authenticate/authorize IAM principals	|	|	|
|A-02	|KMS is a secure cryptographic root for our system and can be trusted	|	|	|
|A-03	|Properly implemented TLS using cipher suites recommended by the [Crypto Bar Raiser](https://w.amazon.com/index.php/AWSCryptoBR) team is resistant to information disclosure and tampering to an acceptable level	|	|	|
|A-04	|The OpenSearch Security Plugin's resource-based access control framework is correctly implemented and can be trusted to enforce permissions.	|Our primary mitigation strategy depends on this.	|
|A-05	|LLM and embedding models are treated as trusted components and are not expected to be malicious, though they can be manipulated via prompt injection.	|We are not threat modeling the models themselves, but our interaction with them.	|

## Admin

* **AppSec Review link:** *<If you already have an* [*AppSec security review*](https://appsec.corp.amazon.com/) ** *link to it here.>*
* **Team Code Name:** *<List your team code names and aliases>*
* Design documentation: <*Link to your design documentation>*
- @Access_Control_Design_on_Agentic_Memory.md
- @Agentic_Memory_Integration.md
- @AgenticMemoryFeatureSummary.md
- @AgenticMemoryImplementationDeepDive.md
- @AgenticMemoryLowLevelSummary.md

# System Architecture

This section answers “What are we working on?” . The intent is to help the reader understand the system that is being modeled.

## High Level Design

The Agentic Memory service is composed of several layers: a REST API layer for user interaction, a transport layer for internal communication, helper services for business logic, and the underlying OpenSearch core for data storage.

```
┌─────────────────────────────────────────┐
│         REST API Layer                  │
│  ┌─────────────┐  ┌─────────────────┐  │
│  │ Container   │  │ Memory          │  │
│  │ Management  │  │ Operations      │  │
│  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│       Transport Actions Layer           │
│  ┌─────────────┐  ┌─────────────────┐  │
│  │ Container   │  │ Memory CRUD     │  │
│  │ CRUD        │  │ Operations      │  │
│  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│         Helper Services                 │
│  ┌─────────────┐  ┌─────────────────┐  │
│  │ Container   │  │ Embedding       │  │
│  │ Helper      │  │ Helper          │  │
│  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│         OpenSearch Core                 │
│  ┌─────────────┐  ┌─────────────────┐  │
│  │ System      │  │ Memory Data     │  │
│  │ Index       │  │ Indices         │  │
│  └─────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
```

Data is stored in two main locations:
1.  **System Index**: A system index (`.plugins-ml-agentic-memory-container`) stores the metadata for each memory container.
2.  **Memory Indices**: User-specific or system-managed indices store the actual memory data (conversations, facts). These can be static, sparse, or KNN-enabled based on the container's configuration.

## Low Level Design

*<If the system you are threat modeling is complex, go into more detail about each component and how it is designed.>*

### Authentication / Authorization

Authentication is handled by the standard OpenSearch security plugin. Authorization for the Agentic Memory feature is critical due to the potentially sensitive nature of the stored memories. Several designs were considered for access control:

**Option 1: User + Model Access Control (Legacy)**
- **Description:** Access to memories is determined by the user's access to the underlying LLM and embedding models configured in the memory container.
- **Limitations:** This provides a very coarse level of security. If a user has access to the model, they can access all memories in containers using that model. It offers no granular control and is not a robust solution for multi-user environments.

**Option 2: Resource-Based Control on Memory Container**
- **Description:** This approach leverages the new resource-based access control framework from the Security Plugin. Each memory container is a securable resource. The owner of the container can define fine-grained read-only or read-write permissions for other users or backend roles.
- **Implementation:** The `.plugins-ml-agentic-memory-container` system index stores the container metadata, including an `owner` field and a `share_with` field to define permissions.
- **Gap:** While this secures the container, the actual memory indices are still self-managed by the user, leading to a potentially complex setup and no built-in security for the memory data itself.

**Option 3.1: System-Index-Is-All-You-Need**
- **Description:** This extends Option 2 by also storing the memory data itself in system indices. Instead of one index per container, memories are consolidated into a few system indices based on type (static, sparse, KNN). This allows the resource-based access control to be applied at the individual memory (document) level.
- **Challenge:** This creates a large number of system indices, especially for KNN, where a new index might be needed for each vector dimension.

**Option 3.2: One-Index-to-Rule-Them-All (Chosen Approach)**
- **Description:** This is a refinement of 3.1. It uses a single system index for all memory data (`.plugin-ml-memory-storage`). To handle different KNN vector dimensions, the index mapping is dynamically updated to add new fields (e.g., `memory_knn_embedding_1024`, `memory_knn_embedding_1536`).
- **Benefit:** This approach provides high-granularity, document-level access control for both containers and memories while minimizing the number of system indices. It offers the best out-of-the-box experience and security posture.
- **Security Mechanism:** Access is controlled via the Security Plugin's resource-based access control framework, applied to both the container definition and the individual memory documents within the unified system index.

## Data Flow Diagrams

A typical data flow for creating and then searching for a memory is as follows:

**1. Create Memory Flow:**
- **User/Agent -> REST API Layer:** A user or agent sends a `POST /_plugins/_ml/memory_containers/{id}/memories` request containing conversational messages.
- **REST API -> Transport Layer:** The request is forwarded to the internal transport actions.
- **Transport Layer -> Helper Services:** The `MemoryContainerHelper` retrieves the container configuration (e.g., LLM model ID, embedding model ID). The raw messages are stored.
- **Helper Services -> LLM:** The helper service sends the messages to the configured LLM to extract key facts and make an ADD/UPDATE/DELETE decision.
- **Helper Services -> Embedding Model:** For new or updated facts, the service sends the text to an embedding model to generate a vector.
- **Helper Services -> OpenSearch Core:** The service creates/updates a document in the appropriate memory index. This document contains the raw text, the vector embedding, the owner, and other metadata.
- **OpenSearch Core -> User/Agent:** A confirmation is returned.

**2. Search Memory Flow:**
- **User/Agent -> REST API Layer:** A user or agent sends a `GET /_plugins/_ml/memory_containers/{id}/memories/_search` request with a query.
- **REST API -> Transport Layer:** The request is forwarded internally.
- **Transport Layer -> Helper Services:** The `MemoryContainerHelper` retrieves the container configuration to identify the correct memory index and embedding model.
- **Helper Services -> Embedding Model:** The search query text is sent to the embedding model to generate a query vector.
- **Helper Services -> OpenSearch Core:** The service executes a search query (typically a KNN vector search) against the memory index. The Security Plugin filters the results to ensure the user only sees memories they have permission to access.
- **OpenSearch Core -> User/Agent:** The search results are returned to the user/agent.

## APIs

|API	|Method	|Status	|Mutating/
Non-Mutating	|Functionality	|Callable from Internet	|Authorized Callers	|Comments	|
|---	|---	|---	|---	|---	|---	|---	|---	|
|`POST /_plugins/_ml/memory_containers/_create`	|POST	|New	|Mutating	|Creates a new memory container	|Yes	|Authorized users/roles	|	|
|`GET /_plugins/_ml/memory_containers/{id}`	|GET	|New	|Non-Mutating	|Reads a memory container's configuration	|Yes	|Authorized users/roles	|	|
|`DELETE /_plugins/_ml/memory_containers/{id}`	|DELETE	|New	|Mutating	|Deletes a memory container	|Yes	|Owner of the container	|	|
|`POST /_plugins/_ml/memory_containers/{id}/memories`	|POST	|New	|Mutating	|Adds one or more memories (messages/facts) to a container	|Yes	|Authorized users/roles	|	|
|`GET /_plugins/_ml/memory_containers/{id}/memories/_search`	|GET/POST	|New	|Non-Mutating	|Searches for memories within a container	|Yes	|Authorized users/roles	|	|
|`PUT /_plugins/_ml/memory_containers/{id}/memories/{memory_id}`	|PUT	|New	|Mutating	|Updates a specific memory	|Yes	|Authorized users/roles	|	|
|`DELETE /_plugins/_ml/memory_containers/{id}/memories/{memory_id}`	|DELETE	|New	|Mutating	|Deletes a specific memory	|Yes	|Authorized users/roles	|	|

## Assets

|Asset Name	|Asset Usage	|Data Type	|Comments	|
|---	|---	|---	|---	|
|Customer Conversational Data	|Stored in memory indices, used for context and fact extraction.	|Customer Content	|Can contain PII or other sensitive information. Stored in system indices (`.plugin-ml-memory-storage`).	|
|Memory Container Configuration	|Stores metadata about memory containers, including model IDs and index names.	|Service Configuration	|Stored in the `.plugins-ml-agentic-memory-container` system index.	|
|Extracted Facts & Embeddings	|Facts are extracted from conversations by an LLM and stored with their vector embeddings for semantic search.	|Customer Content	|Derived from customer data, stored in the memory index.	|
|LLM Models	|Used for fact extraction, summarization, and making memory decisions (ADD/UPDATE/DELETE).	|Service Configuration	|External or internal models accessed via model ID.	|
|Embedding Models	|Used to convert text (memories, queries) into vector embeddings.	|Service Configuration	|External or internal models accessed via model ID.	|
|Access Control Policies	|Resource-based policies attached to containers and memories to control access.	|Service Configuration	|Managed by the OpenSearch Security Plugin.	|

## Threat Actors

*<Optionally, describe the potential adversaries you think might attack your system. This section is included to ensure the people enumerating threats are thinking about the type of actors that may attempt to influence your system.>*

* A threat actor from the internet
* A threat actor acting with AWS customer permissions
* A threat actor with service development team permissions

## Security Anti-Patterns

*<Review the [Security Anti-Patterns](https://www.aristotle.a2z.com/recommendations/255) article and identify any that are relevant to the design of the system that is in-scope for this threat model. Try to identify any threats related to these Security Anti-Patterns. This will help you decide whether you should avoid the Security Anti-Pattern, or whether it is acceptable in the context of your system to proceed with it.  If it’s helpful, you can copy/paste the [Security Anti-Pattern helper table](https://quip-amazon.com/BqN8AP18Kacs/AWS-Security-Anti-Pattern-Template) to help you track your work.>*

# Threats

This section answers the question “[What can go wrong?](https://catalog.workshops.aws/threatmodel/en-US/what-can-go-wrong)”. The intent is to help the reader understand the threats relevant to the system. Unless you have a preferred way to write threats, use [AWS’ threat grammar](https://catalog.workshops.aws/threatmodel/en-US/what-can-go-wrong/threat-grammar). If using threat grammar feels difficult, use [Threat Composer](https://threat-composer.security.aws.dev/) ([wiki](https://w.amazon.com/bin/view/Threat-composer/)). 

*<Describe the threats to your system. It’s okay to have a lot of threats! Many find it easiest to create several tables for different parts of your system. You may also wish to a table for high level threats and/or threats that apply in many different places in your system. The priority column can help you sort your table. Focus on the threats that are the most impactful to your customers and their data.  Ensure you document threats that have no or limited mitigations and raise them with your AppSec engineer.>
*

## STRIDE
Spoofing - Violates authenticity

* Spoofing is pretending to be something or somebody other than who you actually are.
* By impersonating an identity already known to a system, a threat actor can take advantage of that trust.
* Mitigations include authentication

Tampering - Violates integrity

* Tampering is modifying something that is not intended to be modified, including data, executable code, configurations, and others.
* It may happen to data at rest, in memory, or in transit.
* Mitigations include minimizing human access to production data and cryptographic signature verification.

Repudiation - Violates non-repudiation

* Repudiation is disputing an action that you actually took.
* Conversely, it could involve claiming an action that you did not actually take.
* Mitigations include generating audit log entries that cannot be modified or delete

Information Disclosure - Violates confidentiality

* Information disclosure is obtaining information to which you should not have access.
* Mitigations include least-privilege access controls and encryption.

Denial of Service - Violates availability

* DoS prevents or inhibits legitimate users from using a system.
* This may include destroying data or consuming finite resources like network capacity, CPU cycles, memory, or disk space.
* The intent is to impact system availability or performance.
* Mitigations include throttling, resource quotas, and least privilege access controls.

Elevation of privilege - Violates authorization

* EoP uses existing access to perform actions that should not be allowed.
*  is an example of EoP. This is when an entity who lacks permission to perform an action coerces a more privileged entity to perform it. For example, a user tricks a service into modifying a file belonging to another user.
* Mitigation includes access controls and authorization checks.



|Threat Number	|Priority	|Threat	|[STRIDE](https://catalog.workshops.aws/threatmodel/en-US/what-can-go-wrong/stride)	|Affected Assets	|Mitigations	|Comments	|Status	|
|---	|---	|---	|---	|---	|---	|---	|---	|
|T-001	|High	|A threat actor from the internet attempts to modify API calls in transit, leading to the reduction in confidentiality or integrity of requests/responses in-transit	|Tampering	|Data-in-transit	|[M-001: AWS Threat Model Template](https://quip-amazon.com/U68gAawbeZbB#temp:s:temp:C:AEI48ddef015f7a6693932b7e50e;temp:C:AEI911e7a0fd1de8e6219fad6a31)	|Applies to all traffic between APIs and calling principals	|	|
|T-002	|Low	|A threat actor attempts to impersonate a valid caller to the public endpoint	|Spoofing	|All	|[M-002: AWS Threat Model Template](https://quip-amazon.com/U68gAawbeZbB#temp:s:temp:C:AEI2c7f7eca1504224c529a97674;temp:C:AEI911e7a0fd1de8e6219fad6a31)	|	|	|
|T-003	|High	|An unauthorized user accesses memories from another user/tenant.	|Information Disclosure	|Customer Conversational Data	|M-003: Resource-based access control	|This is the primary threat the security design aims to mitigate.	|Not Mitigated	|
|T-004	|High	|A user with access to a shared memory container deletes or corrupts memories belonging to another user.	|Tampering	|Customer Conversational Data	|M-003: Resource-based access control	|Fine-grained (read-only vs read-write) permissions are crucial.	|Not Mitigated	|
|T-005	|Medium	|A malicious user crafts input to the LLM (prompt injection) to either extract sensitive data from the context window (if it contains other users' data) or cause the agent to perform unintended actions.	|Tampering / Information Disclosure	|Customer Conversational Data, LLM Models	|M-004: Prompt Engineering, M-005: Contextual Scoping	|The system must ensure that the context provided to the LLM only contains data the user is authorized to see.	|Partially Mitigated	|
|T-006	|Medium	|A user creates a memory container with a very large KNN vector dimension, causing excessive resource consumption in the shared memory index.	|Denial of Service	|Memory System Indices	|M-006: Input Validation, M-007: Resource Quotas	|The dynamic mapping of the "One-Index-to-Rule-Them-All" approach could be an attack vector.	|Not Mitigated	|
|T-007	|Low	|The LLM incorrectly extracts facts or makes wrong ADD/UPDATE/DELETE decisions, leading to data integrity issues.	|Tampering	|Extracted Facts & Embeddings	|M-008: Manual Override APIs	|This is a functional correctness issue but has security implications for data integrity. The `update memory` API is a mitigation.	|Mitigated	|
|T-008	|High	|An attacker with access to the underlying memory index can bypass the API-level security checks and read/write data directly.	|Elevation of Privilege	|Customer Conversational Data	|M-009: System Index Protection	|Making the memory indices system indices helps, but cluster admin-level access is still a threat.	|Partially Mitigated	|
|T-009	|Medium	|A user with read-only access to a memory container is able to update or delete memories.	|Elevation of Privilege	|Customer Conversational Data	|M-003: Resource-based access control	|Requires robust testing of the security plugin's enforcement.	|Not Mitigated	|
|T-010	|Low	|The `owner` of a memory container is not correctly assigned on creation, leading to a confused deputy problem.	|Elevation of Privilege	|Memory Container Configuration	|M-010: Secure Creation Logic	|The logic for capturing the user context during container creation must be flawless.	|Not Mitigated	|
|T-011	|Low	|A user denies performing a destructive action (e.g., deleting a memory container) due to inadequate or insecure audit logging.	|Repudiation	|Audit Logs	|M-011: Secure Audit Logging	|Requires integration with OpenSearch audit logging capabilities.	|Not Mitigated	|
|T-012	|Medium	|Verbose error messages from the API leak internal system details or parts of other users' data.	|Information Disclosure	|API Responses	|M-012: Generic Error Messages	|Error handling code should catch exceptions and return standardized error messages.	|Not Mitigated	|
|T-013	|Medium	|A malicious user floods the memory creation or search APIs with a high volume of expensive requests, degrading service for others.	|Denial of Service	|API Endpoints, LLM/Embedding Models	|M-013: API Throttling/Rate Limiting	|Implement per-user rate limiting on expensive API calls.	|Not Mitigated	|
|T-014	|Medium	|A user with direct index write access to `.plugins-ml-agentic-memory-container` tampers with the configuration of another user's memory container.	|Tampering	|Memory Container Configuration	|M-009: System Index Protection	|Reinforces the importance of restricting direct access to system indices.	|Partially Mitigated	|
|T-015	|High	|A flaw in the logic that processes `share_with` grants allows a user to escalate their permissions on a memory container from read-only to read-write.	|Elevation of Privilege	|Customer Conversational Data	|M-003: Resource-based access control	|Requires rigorous testing of the permission model implementation.	|Not Mitigated	|
|T-016	|High	|A user deletes their memory container with `delete_all_memories=true` when multiple containers share the same index prefix, causing data loss for other users sharing those indices.	|Tampering	|Customer Conversational Data	|M-014: Shared Index Prefix Validation	|Multiple containers can share the same index prefix. Deleting indices without validation could delete other users' data.	|Mitigated	|
|T-017	|	|	|	|	|	|	|	|
|T-018	|	|	|	|	|	|	|	|
|T-019	|	|	|	|	|	|	|	|
|T-020	|	|	|	|	|	|	|	|
|T-021	|	|	|	|	|	|	|	|
|T-022	|	|	|	|	|	|	|	|
|T-023	|	|	|	|	|	|	|	|
|T-024	|	|	|	|	|	|	|	|
|T-025	|	|	|	|	|	|	|	|
|T-026	|	|	|	|	|	|	|	|
|T-027	|	|	|	|	|	|	|	|
|T-028	|	|	|	|	|	|	|	|
|T-029	|	|	|	|	|	|	|	|
|T-030	|	|	|	|	|	|	|	|

# Mitigations

This section answers “[What are we going to do about it?](https://catalog.workshops.aws/threatmodel/en-US/what-are-we-going-to-do-about-it)”.  The intent is to help the reader understand the security strategy. Mitigations reduce the likelihood and/or impact of the occurrence of threats.

<*The mapping of threats to mitigations is many-to-many. Consider using multiple mitigations for a threat for defense-in-depth.>
*

### Baseline Security Control Mitigations

The [Baseline Security Controls](https://w.amazon.com/bin/view/AWS_IT_Security/AppSec/ETSE/BaselineSecurityControls/) (BSCs) are a set of controls that, when applicable, must be implemented in AWS services. 
*<You can delete BSCs that aren’t applicable for you. You may optionally delete the whole table if it’s not helpful for you, as BSCs will be assigned to you via your Talos security review.*> 

|Mitigation Number	|Mitigation	|Threats Mitigating	|Status	|Ticket/Artifact/CR/Tests	|Comments	|
|---	|---	|---	|---	|---	|---	|
|BSC1	|Enable Content Security Policy	|	|	|	|	|
|BSC2	|Enable security HTTP headers	|	|	|	|	|
|BSC6	|Implement checks to ensure that only the resource owner can take action against the resource (protect against Confused Deputy)	|	|	|	|	|
|BSC7	|Implement CSRF protection	|	|	|	|	|
|BSC9	|Implement authorization for services	|	|	|	|	|
|BSC10	|Ensure a consistent authorization experience (CAE) using IAM	|	|	|	|	|
|BSC11	|Disable Instance Metadata Service v1 (IMDSv1) on EC2 instances by configuring the IMDS to require HTTP tokens (IMDSv2)	|	|	|	|	|
|BSC12	|Ensure that bucket and objects are not world readable or world writable	|	|	|	|	|
|BSC13	|Encrypt service data using a KMS Customer Managed Key owned by your service	|	|	|	|	|
|BSC14	|Encrypt customer content and support the use of a customer managed Customer Managed Key (CMK) managed in KMS.	|	|	|	|	|
|BSC15	|Disable Debug and Development Functionality	|	|	|	|	|
|BSC16	|Encrypt Data in Transit (HTTPS/TLS)	|	|	|	|	|
|BSC18	|Enable TLS for all ingress and egress connections	|	|	|	|	|
|BSC20	|Use Isengard for all AWS accounts and appropriately flag Production accounts	|	|	|	|	|
|BSC21	|Harden against denial of service	|	|	|	|	|
|BSC22	|Build all OSS packages from source code pulled from repositories owned and controlled by AWS	|	|	|	|	|
|BSC23	|Include standard security chapters in customer facing documentation	|	|	|	|	|
|BSC26	|Use appropriate mechanisms for accessing customer resources	|	|	|	|	|
|BSC28	|Apply least privilege principle to all processes, accounts, etc.	|	|	|	|	|
|BSC29	|Use IAM Roles and scoped-down IAM policy	|	|	|	|	|
|BSC30	|Use the minimum set of permissions in SLR or EZCRC roles required for the service to function	|	|	|	|	|
|BSC31	|Use resource policies to restrict access to resources to the minimum set required for the service to function	|	|	|	|	|
|BSC32	|Implement authentication	|	|	|	|	|
|BSC33	|Use sigv4 as the secure default authentication mechanism for APIS	|	|	|	|	|
|BSC34	|Implement strong secrets management	|	|	|	|	|
|BSC37	|Maintain third party dependencies of your code	|	|	|	|	|
|BSC38	|Monitor Vulnerabilities and Patch Compute Instances Promptly	|	|	|	|	|
|BSC39	|Ensure Correct Image Selection for EC2 and Containers	|	|	|	|	|
|BSC40	|Implement secure logging	|	|	|	|	|
|BSC43	|Enable S3 bucket access logging	|	|	|	|	|
|BSC44	|Integrate with CloudTrail and emit logs for all successful and unsuccessful actions	|	|	|	|	|
|BSC45	|Restrict inbound and outbound network access to least privilege	|	|	|	|	|
|BSC47	|Write security focused test cases	|	|	|	|	|
|BSC49	|Implement an out-of-band continuous auditing/verification mechanism (canary)	|	|	|	|	|
|BSC50	|Build new consoles on TangerineBox (new consoles only)	|	|	|	|	|
|BSC52	|Enable Taj for automated API security integration testing	|	|	|	|	|
|BSC54	|Onboard to PrivateLink (VPC endpoints) with support for endpoint policies	|	|	|	|	|
|BSC55	|Separate non-production and production environments	|	|	|	|	|
|BSC60	|Use Amazon recommended build and deployment tools to maintain a secure development environment	|	|	|	|	|

### System Specific Mitigations

These are mitigations that are specific to the system being modeled.

|Mitigation Number	|Mitigation	|Threats Mitigating	|Status	|[Related BSC](https://w.amazon.com/bin/view/AWS_IT_Security/AppSec/ETSE/BaselineSecurityControls/)	|**Ticket/Artifact/CR/Tests**	|Comments	|
|---	|---	|---	|---	|---	|---	|---	|
|M-001	|API Gateway Encryption-in-transit using TLS_1_2 policy	|[T-001](https://quip-amazon.com/iti8AaKJ6nbI#temp:s:temp:C:SNFc54ec0045a2d4b61b5ad705a1;temp:C:SNF0bce2346a17b45e89049c0f2a)	|	|	|	|	|
|M-002	|Authentication using SigV4	|[T-002](https://quip-amazon.com/iti8AaKJ6nbI#temp:s:temp:C:SNF1d8968dad81e4f908f8826e54;temp:C:SNF0bce2346a17b45e89049c0f2a)	|	|	|	|	|
|M-003	|Resource-based access control	|T-003, T-004, T-009	|Planned	|BSC6, BSC9, BSC31	|	|Leverage the OpenSearch Security Plugin to enforce document-level security on both memory containers and individual memories. The owner can grant read/write or read-only access to other principals.	|
|M-004	|Prompt Engineering	|T-005	|Planned	|	|	|System prompts will be carefully engineered to instruct the LLM to only use the provided context and not to reveal its instructions or perform actions outside its scope.	|
|M-005	|Contextual Scoping	|T-005	|Planned	|BSC28	|	|The application logic will ensure that the context (list of memories) passed to the LLM for any operation is pre-filtered by the user's permissions. The LLM will never see data the user is not authorized to see.	|
|M-006	|Input Validation	|T-006	|Planned	|	|	|The API layer will validate all inputs, including parameters like KNN vector dimension, to ensure they are within reasonable limits before processing.	|
|M-007	|Resource Quotas	|T-006	|Not Planned	|BSC21	|	|Future: Implement quotas on the number of memory containers, memories per container, or new KNN dimensions a user can create.	|
|M-008	|Manual Override APIs	|T-007	|Implemented	|	|	|The `PUT /.../memories/{id}` and `DELETE /.../memories/{id}` APIs allow users to manually correct or remove memories that were processed incorrectly by the LLM.	|
|M-009	|System Index Protection	|T-008	|Planned	|BSC12	|	|By using system indices (`.plugin-ml-*`), direct user access is restricted by default in OpenSearch. Only admin users can typically access them directly.	|
|M-010	|Secure Creation Logic	|T-010	|Planned	|BSC6	|	|The transport action for creating a memory container will correctly and securely identify the calling user principal and record it as the `owner` of the resource.	|
|M-011	|Secure Audit Logging	|T-011	|Not Planned	|BSC40, BSC44	|	|Integrate with OpenSearch audit logging to track all container and memory operations, ensuring non-repudiation.	|
|M-012	|Generic Error Messages	|T-012	|Implemented	|	|	|API error responses use generic messages that don't leak internal details or other users' data. For example, the shared prefix error doesn't reveal the exact count of containers.	|
|M-013	|API Throttling/Rate Limiting	|T-013	|Not Planned	|BSC21	|	|Implement per-user rate limiting on expensive operations like memory creation and LLM-based search.	|
|M-014	|Shared Index Prefix Validation	|T-016	|Implemented	|BSC28	|TransportDeleteMemoryContainerAction.java, MemoryContainerHelper.java	|Before deleting memory indices, count containers sharing the same index prefix. If count > 1, return 409 CONFLICT and refuse to delete indices. This prevents accidental data loss when multiple containers share indices.	|
|M-015	|	|	|	|	|	|	|
|M-016	|	|	|	|	|	|	|
|M-017	|	|	|	|	|	|	|
|M-018	|	|	|	|	|	|	|
|M-019	|	|	|	|	|	|	|
|M-020	|	|	|	|	|	|	|

## Security Tests

*<Testing and canaries are super important.  The longer we exist as an organization, the stronger our conviction is that having a robust set of tests will prevent the occurrence of regressions into production and a robust set of canaries will identify the presence of regressions, security or otherwise in your production environment.
*

*See the following guidance:* [*Write security focused test cases*](https://aristotle.corp.amazon.com/implementations/390)*,* [*build integration and unit tests for security*](https://www.aristotle.a2z.com/recommendations/95)*, [implement canaries as an out-of-band continuous auditing mechanism](https://www.aristotle.a2z.com/implementations/43). Identify mitigations that are testable and document your intended test coverage. Think about which mitigations are covered by* [*Taj*](https://w.amazon.com/bin/view/AWS_IT_Security/Secure/AppSec/ASAT/Taj/TajTests/) *or* [*Bliss*](https://w.amazon.com/bin/view/AWS_IT_Security/Secure/AppSec/Bliss/)*, and which are unique to your service.>*

|Test Number	|Mitigations Tested	|Test Case	|Description	|Test Type	|Status	|
|---	|---	|---	|---	|---	|---	|
|Test-001	|[M-001: AWS Threat Model Template](https://quip-amazon.com/U68gAawbeZbB#temp:s:temp:C:AEI48ddef015f7a6693932b7e50e;temp:C:AEI911e7a0fd1de8e6219fad6a31)	|Example - HTTP Denied	|Test endpoints X, Y, Z to ensure non-TLS traffic is rejected	|Canary	|Planned	|
|Test-002	|[M-001: AWS Threat Model Template](https://quip-amazon.com/U68gAawbeZbB#temp:s:temp:C:AEI48ddef015f7a6693932b7e50e;temp:C:AEI911e7a0fd1de8e6219fad6a31)	|Example - TLS 1.1 Denied	|Test endpoints X, Y, Z to ensure weak TLS traffic is rejected	|Canary	|Planned	|
|Test-003	|[M-001: AWS Threat Model Template](https://quip-amazon.com/U68gAawbeZbB#temp:s:temp:C:AEI48ddef015f7a6693932b7e50e;temp:C:AEI911e7a0fd1de8e6219fad6a31)	|Example - TLS 1.2 Allowed	|Test endpoints X, Y, Z to ensure only TLS 1.2 is allowed	|Canary	|Planned	|
|Test-004	|[M-001: AWS Threat Model Template](https://quip-amazon.com/U68gAawbeZbB#temp:s:temp:C:AEI48ddef015f7a6693932b7e50e;temp:C:AEI911e7a0fd1de8e6219fad6a31)	|Example - APIGW CDK uses TLS_1_2 policy	|Check that API Gateway configuration in CDK creates a custom domain and enforces a [minimum TLS version](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-custom-domain-tls-version.html)	|Unit	|Planned	|
|Test-005	|[M-002: AWS Threat Model Template](https://quip-amazon.com/U68gAawbeZbB#temp:s:temp:C:AEI2c7f7eca1504224c529a97674;temp:C:AEI911e7a0fd1de8e6219fad6a31)	|Example - Successful sigv4 calls should succeed	|Check that API Gateway configuration in CDK creates a custom domain and enforces a [minimum TLS version](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-custom-domain-tls-version.html)	|Unit, canary	|Planned	|
|Test-006	|[M-002: AWS Threat Model Template](https://quip-amazon.com/U68gAawbeZbB#temp:s:temp:C:AEI2c7f7eca1504224c529a97674;temp:C:AEI911e7a0fd1de8e6219fad6a31)	|Example - Requests without valid sigv4 should fail	|Check that API Gateway configuration in CDK creates a custom domain and enforces a [minimum TLS version](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-custom-domain-tls-version.html)	|Unit, canary	|Planned	|
|Test-007	|M-003	|Unauthorized Read	|Create a memory as User A. Verify that User B cannot read it via the search API.	|Integration	|Planned	|
|Test-008	|M-003	|Unauthorized Write	|Create a memory as User A. Grant User B read-only access. Verify User B cannot update or delete the memory.	|Integration	|Planned	|
|Test-009	|M-003	|Authorized Write	|Create a memory as User A. Grant User B read-write access. Verify User B can update the memory.	|Integration	|Planned	|
|Test-010	|M-005	|Context Isolation	|Create memories for User A and User B. When User A invokes an LLM operation (e.g., search with summarization), verify that User B's memories are not included in the context sent to the LLM.	|Integration	|Planned	|
|Test-011	|M-006	|Invalid Vector Dimension	|Attempt to create a memory container with an excessively large or negative vector dimension. Verify the API rejects the request.	|Unit	|Planned	|
|Test-012	|M-010	|Owner Assignment	|Create a memory container as User A. Inspect the underlying system index document to verify that `owner.name` is correctly set to User A.	|Integration	|Planned	|

## Appendix

### Glossary

|Term	|Definition	|Example	|
|---	|---	|---	|
|	|	|	|
|---	|---	|---	|
|	|	|	|
|	|	|	|
|	|	|	|
|	|	|	|

## References

The table below lists related threat models, design documents, PRFAQs, security reviews, pentests, or other documents.

|Reference	|Comments	|
|---	|---	|
|[SigV4 Public Documentation](https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html)	|Signature Version 4 (SigV4) is the process to add authentication information to AWS API requests sent by HTTP.	|
|---	|---	|
|	|	|
|	|	|
|	|	|
|	|	|
|	|	|

### Documentation

* *<If applicable, list as many design documents, security reviews, pentests, or other things that relate to this project.>*

### Managed policy requests

* *<As a useful record for future readers, list every managed policy request you’ve needed for this project.>*



