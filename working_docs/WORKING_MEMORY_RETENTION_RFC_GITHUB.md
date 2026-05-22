# RFC: Working Memory Retention for Agentic Memory

- **Status**: Draft
- **Authors**: TBD
- **Reviewers**: TBD
- **Created**: 2026-03-19
- **Tracking Issue**: TBD

## Summary

This RFC proposes a 3-stage retention and cleanup mechanism for working memory in agentic memory containers.

- **Pilot (Stage 0)** adds **per-container retention configuration** and **query-time expiry filtering** to hide expired documents from reads with zero deletion risk.
- **Stage 1** adds **opportunistic cleanup on the write path** to physically reclaim expired documents in active containers.
- **Stage 2** adds a **Job Scheduler periodic cleanup** to durably reclaim expired documents across all containers, including idle ones.

Together the three stages provide a complete retention lifecycle: expired working memory is hidden immediately, cleaned up best-effort on active containers, and guaranteed to be reclaimed by a background job.

## Problem Statement

Working memory in agentic memory containers currently grows without bound. There is no retention policy, no expiry filtering on reads, and no scheduled cleanup.

This causes three problems:

1. **Unbounded storage growth** — every working memory document persists indefinitely unless manually deleted.
2. **Stale data in query results** — expired working memory remains visible to read APIs, polluting context windows and degrading agent quality.
3. **Operational burden** — cleanup depends on ad hoc manual intervention with no centralized control or observability.

We need a retention mechanism that hides expired data from reads, eventually deletes it from storage, supports per-container configuration, and can be rolled out incrementally.

## Goals

- Add a per-container retention configuration for working memory.
- Make expired working memory invisible to all read APIs.
- Physically delete expired documents over time.
- Keep the initial implementation simple and low risk.
- Reuse existing infrastructure (delete-by-query, Job Scheduler) where practical.

## Non-Goals

- Retention for long-term memory, session memory, or history memory.
- Multi-tenancy isolation in this iteration.
- Re-architecting working memory into a different index layout or introducing index partitioning.
- Per-container cleanup cadence configuration.

## Current State

Today in the agentic memory system:

- Working memory documents carry `created_time` and `last_updated_time` fields. Retention is evaluated against `last_updated_time`.
- Each memory container stores working memory in a dedicated index (or shared index scoped by `memory_container_id`).
- Delete-by-query is already supported in the memory code path via `TransportDeleteMemoriesByQueryAction`.
- There is **no** retention-aware filtering in any memory read API.
- There is **no** scheduled cleanup for expired working memory.
- The only way to remove working memory is explicit manual deletion.

## Proposal: Retention Model

### Per-container configuration

Add a new field to memory container configuration:

| Field | Type | Default | Description |
|---|---|---|---|
| `working_memory_retention_policy` | object | `null` (not set) | Extensible retention policy object. Absent by default; when present, `retention_days` defaults to 90. |
| `working_memory_retention_policy.retention_days` | integer | 90 | Number of days to retain working memory (range: 1–365) |

**Example:**
```json
{
  "working_memory_retention_policy": {
    "retention_days": 30
  }
}
```

> **Extensibility note:** The `working_memory_retention_policy` object is designed to accommodate future retention controls (e.g., `max_memory_count`, `retention_strategy`) without breaking changes to the container schema.

### Defaults and validation

- If a container does not set `working_memory_retention_policy`, no retention is applied; all working memory is kept indefinitely.
- If `working_memory_retention_policy` is present but `retention_days` is omitted, the 90-day default is used.
- `retention_days` values outside the 1–365 range are rejected at creation and update time.
- Retention is evaluated against the **`last_updated_time`** field on each working memory document. This means a working memory entry that is actively updated will have its retention window reset on each update.

### Effective retention

All retention-aware code paths — read filtering, opportunistic cleanup, and scheduled cleanup — use the same effective retention:

```
if container.working_memory_retention_policy is null:
    # No retention — keep all working memory
    skip filtering / cleanup for this container

effective_retention_days = container.working_memory_retention_policy.retention_days ?? 90
cutoff = now - effective_retention_days
```

## Pilot (Stage 0): Query-Time Expiry Filtering

The Pilot stage delivers user-visible correctness with zero deletion risk. Expired documents remain in storage but are hidden from all read APIs.

### Query-time expiry filtering

All working memory read paths add a retention filter so that expired documents are never returned, even if they still exist in the index.

**Behavior:**
1. When a read request arrives, resolve the container's retention policy.
2. If the container has no `working_memory_retention_policy`, skip filtering entirely — return all documents.
3. Compute the cutoff: `cutoff = now - effective_retention_days`.
4. Inject a filter into the underlying query: `last_updated_time >= cutoff AND memory_container_id == <id>`.
5. Return only non-expired documents.

This filter is applied centrally in the read path so that all endpoints — get, search, and any future read APIs — behave consistently.

### Data flow

```
                              READ PATH
                              ---------

                  Client reads working memory
                              |
                              v
                  Load container retention config
                              |
                  Has retention policy?
                     /              \
                   No                Yes
                   |                  |
                   v                  v
         Return ALL documents   Compute cutoff from retention
                                      |
                                      v
                               Add filter: last_updated_time >= cutoff
                                      |
                                      v
                               Return only non-expired documents
```

### Advantages

- **Immediate correctness** — expired documents are hidden from reads as soon as retention is configured, with no delay.
- **Zero deletion risk** — nothing is physically deleted; expired documents are only filtered at read time.
- **Low implementation risk** — no new scheduler, no new index, no new transport action, no async work.

### Limitations

- **No storage reclamation** — expired documents remain in the index indefinitely until a later stage cleans them up.
- **Index size continues to grow** — without physical deletion, storage is not freed.

## Stage 1: Opportunistic Cleanup on Write

Stage 1 adds best-effort physical cleanup of expired documents on the write path of active containers.

### Opportunistic cleanup on write

When a new working memory entry is added to a container, the system piggybacks a best-effort physical cleanup.

**Behavior:**
1. Index the new working memory document.
2. Return success to the caller.
3. If the container has no `working_memory_retention_policy`, skip cleanup — no async work is triggered.
4. Otherwise, asynchronously fire a delete-by-query: `last_updated_time < cutoff AND memory_container_id == <id>`.

Important properties:
- The cleanup runs **after** the write response is sent — it does not block the caller.
- Cleanup failures are logged but do **not** fail the write request.
- The delete-by-query is always scoped to the specific container.

### Data flow

```
                        WRITE PATH
                        ----------

             Client writes working memory
                        |
                        v
              Index new document
                        |
                        v
              Return success to caller
                        |
                        v
              Has retention policy?
                 /           \
               No             Yes
               |               |
               v               v
           (no cleanup)  Compute retention cutoff
                               |
                               v
                  Async delete-by-query for
                  expired docs in this container
```

### Advantages

- **Storage reclamation on active containers** — expired documents are physically deleted as write traffic flows.
- **Naturally throttled** — cleanup work is proportional to write activity; quiet containers generate no cleanup load.

### Limitations

- **Idle containers are not cleaned up** — if a container stops receiving writes, expired documents remain in storage (but are hidden from reads by the Pilot stage).
- **Async overhead on writes** — each write triggers a background delete-by-query, adding minor load to active containers.
- **Best-effort reclamation** — storage cleanup is not guaranteed or predictable.

## Stage 2: Job Scheduler Periodic Cleanup

Stage 2 adds durable, cluster-wide storage reclamation through a dedicated Job Scheduler job.

### Behavior

A new Job Scheduler job runs on a fixed daily cadence and systematically cleans up expired working memory across all containers.

**Job execution flow:**
1. Scan all memory containers.
2. For each container, check whether `working_memory_retention_policy` is set. If not, skip the container — no retention means no cleanup.
3. Resolve effective retention and compute the cutoff.
4. Execute delete-by-query for expired documents in that container.
5. Pause between containers (e.g., 5 seconds) to avoid delete spikes.
6. Log results per container: documents deleted, failures, skipped (no policy), duration.

### Job lifecycle

The cleanup job is created **lazily** — it is registered with Job Scheduler when the first memory container is successfully created. This avoids scheduling overhead in clusters that do not use agentic memory.

- Job creation is **idempotent** — concurrent container creation does not produce duplicate jobs.
- The job runs every **24 hours** once registered.
- The job processes all containers regardless of whether they have been active recently.

### Data flow

```
        First memory container created
                    |
                    v
        Register cleanup job with Job Scheduler (idempotent)

        -------  every 24 hours  -------

        Job Scheduler triggers cleanup job
                    |
                    v
        Scan all memory containers
                    |
                    v
        For each container:
            Compute cutoff from retention
            Delete expired docs by query
            Pause 5 seconds
                    |
                    v
        Log summary: containers scanned, docs deleted, failures
```

### Advantages

- **Cleans idle containers** — containers that stopped receiving writes are still reclaimed.
- **Predictable cadence** — storage reclamation happens daily, not tied to traffic patterns.
- **Centralized control** — a single job handles all containers, simplifying operational management.
- **Reuses existing infrastructure** — Job Scheduler is already available in the OpenSearch ecosystem.

### Limitations

- **Periodic, not immediate** — up to 24 hours can pass before expired documents are physically deleted (they are already hidden from reads by the Pilot stage filtering).
- **Container scan overhead** — each run scans all containers, though this cost is modest for the expected scale.
- **Additional plumbing** — requires new job type registration and processor implementation.

## Why Staged Rollout

We do not need to wait for a full scheduled cleanup framework to ship value.

**The Pilot (Stage 0) alone delivers correctness.** Query-time filtering ensures users never see expired data. This is the safest first step because nothing is deleted — expired documents are only filtered at read time. The Pilot is shippable independently and can soak in production before any cleanup stage lands.

**Stage 1 adds best-effort storage reclamation on active containers.** Opportunistic write-path cleanup physically removes expired documents as write traffic flows. This keeps active containers reasonably clean without introducing any new scheduling infrastructure.

**Stage 2 adds durable cluster-wide cleanup.** The Job Scheduler job guarantees that storage is reclaimed even for idle containers, provides predictable daily cleanup, and gives operators a single place to monitor retention behavior.

Shipping the Pilot first **reduces risk**: no data is deleted in the Pilot, so there is zero blast radius. Stage 1 introduces deletion scoped to active containers at write time. Stage 2 expands scope to all containers only after the core retention and cleanup logic has been validated in production.

## API and Data Model Changes

### New field on memory container

The `MemoryConfiguration` model gains one new optional field:

- **`working_memory_retention_policy`** — optional object containing `retention_days` (integer, default 90 when policy is present, range 1–365). Absent by default on existing containers; when absent, no retention is applied.

This field is persisted in the memory container index and read on every retention-aware operation. The object structure allows future extension with additional retention controls without schema-breaking changes.

### Read-path changes

All working memory read APIs (get, search, and any future read endpoints) automatically apply retention filtering. No API signature changes are needed — the filtering is transparent to callers.

### Write-path changes

The add-working-memory path gains an asynchronous post-write cleanup step. The write API contract does not change — callers still receive the same response. The cleanup is an internal implementation detail.

### No schema migration required

Existing working memory documents already carry `last_updated_time`. No reindexing or mapping changes are needed to support retention filtering and cleanup.

## Backward Compatibility

This proposal is backward compatible:

- **Existing containers** without `working_memory_retention_policy` have **no retention applied** — all working memory persists indefinitely. Upgrading the plugin does not silently start expiring data. To opt in to retention, update the container with a `working_memory_retention_policy`.
- **Existing working memory documents** do not need reindexing — `last_updated_time` is already present.
- **Read behavior is unchanged for existing containers** — because no retention policy is set, queries continue to return all documents. Only containers that are explicitly updated with a retention policy will begin filtering expired documents.
- **Write behavior is unchanged** from the caller's perspective. The async cleanup is transparent and only triggers when a retention policy is present.
- **Stage 2** only physically deletes documents in containers that have a retention policy configured.

## Operational Considerations

### Observability

**Logging** — each cleanup operation (opportunistic or scheduled) should log:
- Container ID
- Effective retention days and computed cutoff
- Number of documents deleted
- Failures and error details

**Metrics** — where practical, expose:
- Cleanup invocation count (opportunistic vs. scheduled)
- Containers scanned per scheduled run
- Documents deleted per run
- Cleanup failures
- Cleanup duration

### Failure handling

- **Stage 1**: Opportunistic cleanup failures must not fail writes. Log the error and continue.
- **Stage 2**: A failure on one container must not stop the entire scheduled run. Log, skip, and proceed to the next container.
- **Missing indices or containers**: Log a warning and skip — do not treat as a fatal error.

### Performance

- **Stage 1** adds a small asynchronous overhead to write operations on active containers. The cost is proportional to write volume and bounded by the delete-by-query execution.
- **Stage 2** adds a predictable daily scan-and-delete workload. The 5-second pause between containers intentionally trades throughput for cluster stability.
- Neither stage introduces significant read-path overhead — the retention filter is a simple range check on an indexed timestamp field.

### Security

- All cleanup queries must scope deletes to a specific `memory_container_id` — never perform unscoped deletes.
- The scheduled cleanup job runs with internal system-level access, not end-user credentials.
- This iteration does not address multi-tenancy isolation.

## Rollout Plan

### Phase 0 (Pilot)

Ship:
- `working_memory_retention_policy` object on memory container configuration with `retention_days` validation (1–365, default 90 when policy is present). No policy is applied by default — existing containers are unaffected until explicitly updated.
- Query-time expiry filtering on all working memory read paths.

This phase is self-contained and can be released independently. Nothing is physically deleted.

### Phase 1 (Stage 1)

Ship:
- Opportunistic async cleanup on the add-working-memory write path.

This phase builds on the Pilot and adds best-effort storage reclamation for active containers.

### Phase 2 (Stage 2)

Ship:
- Job Scheduler cleanup job type and processor.
- Lazy job registration on first container creation.
- 24-hour schedule with 5-second inter-container pause.
- Operational logging and metrics for scheduled runs.

## Testing Strategy

### Unit tests

- Retention field parsing, validation, and default behavior.
- Cutoff computation from retention configuration.
- Read-path filter injection — verify the retention filter is present in queries.
- Write-path async cleanup trigger — verify cleanup is dispatched without blocking the response.
- Job processor dispatch and per-container iteration logic.

### Integration tests

- Expired documents are hidden from read APIs (get and search) after retention is applied.
- Active containers physically reclaim expired documents on write.
- Idle containers are cleaned up by the scheduled job in Stage 2.
- First container creation registers the cleanup job (idempotent).
- Cleanup respects container-scoped `memory_container_id` — never deletes across containers.
- The scheduled job completes a full run even if individual containers fail.

## Open Questions

1. ~~Should retention evaluate `created_time` only, or should we support `last_updated_time` as an option in the future?~~ **Resolved:** Retention evaluates `last_updated_time`. This means actively updated working memory entries have their retention window reset on each update, which aligns with the intent that frequently used memory should persist longer.
2. Should query-time filtering be independently toggleable via a feature flag, separate from physical cleanup?
3. What is the right upper bound for `retention_days` — is 365 sufficient, or should we allow longer retention for compliance use cases?
4. Should the scheduled cleanup job frequency be configurable via a cluster setting, or is a fixed 24-hour cadence sufficient?
5. What additional fields might be added to `working_memory_retention_policy` in the future (e.g., `max_memory_count`, `retention_strategy`)?
