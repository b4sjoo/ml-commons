# Working Memory Cleanup - Job Scheduler LLD

## Problem statement
Working memory in agentic memory containers grows without a retention policy. We need a built-in cleanup mechanism that honors a per-container retention value (days) without adding any third-party services. The design must integrate with the existing Job Scheduler framework in ml-commons.

## Current status
- Working memory documents are stored in per-container working memory indices and already include `created_time` and `last_updated_time` fields.
- Container configuration is stored in `.plugins-ml-am-memory-container` under `MemoryConfiguration`.
- Delete-by-query is already supported and handled correctly for system indices through `MemoryContainerHelper.deleteDataByQuery(...)`.
- The Job Scheduler framework is integrated through `MachineLearningPlugin` and uses `.plugins-ml-jobs` to store job definitions.
- Existing jobs are started by indexing `MLJobParameter` documents (e.g., stats collector and batch task polling).

## Functional requirements
- Add a per-container config field: `working_memory_retention_days` (positive integer).
- Default retention is 90 days when the field is not explicitly set.
- Cleanup must only delete working memory documents belonging to the target container.
- Cleanup must work for both system indices and non-system indices.
- Cleanup must be asynchronous and must not block request flows.
- Must not introduce any new third-party services.

## Non-functional requirements
- Minimal overhead on large clusters; avoid frequent full scans.
- Safe execution under distributed locks to prevent duplicate work.
- Clear logging and metrics for observability.

## Design overview
Use the Job Scheduler framework to run a periodic cleanup job. The job enumerates memory containers with retention configured and performs a delete-by-query on each container's working memory index with a container ID filter and a time cutoff based on `created_time`.

## Components and responsibilities

### New configuration field
`MemoryConfiguration`
- Add `Integer workingMemoryRetentionDays`.
- Parse and serialize to JSON under `configuration.working_memory_retention_days`.
- Validate: `null` or `-1` means disabled; otherwise must be `> 0`.

### Job type
`MLJobType`
- Add `WORKING_MEMORY_CLEANUP`.

### Job runner
`MLJobRunner`
- Dispatch `WORKING_MEMORY_CLEANUP` to a new processor: `MLWorkingMemoryCleanupJobProcessor`.

### Job processor
`MLWorkingMemoryCleanupJobProcessor` (new)
- Extends `MLJobProcessor`.
- Responsibilities:
  - Search `.plugins-ml-am-memory-container` for containers with retention configured.
  - For each container:
    - Resolve working memory index from `MemoryConfiguration`.
    - Build delete-by-query with `memory_container_id` filter and `created_time < cutoff`.
    - Execute via `MemoryContainerHelper.deleteDataByQuery(...)`.

### Job creation
`MLTaskManager`
- Add a method to index a cleanup job document in `.plugins-ml-jobs`.
- Schedule interval is global (setting-driven) and should not be tied to container settings.

### Plugin integration
`MachineLearningPlugin`
- Job scheduler integration already present; no changes needed beyond job type and runner wiring.

## Data flow
1. Job scheduler triggers `MLJobRunner.runJob(...)` with `jobType=WORKING_MEMORY_CLEANUP`.
2. `MLWorkingMemoryCleanupJobProcessor.run()` executes on a single node (lock acquired by job scheduler).
3. Processor searches `.plugins-ml-am-memory-container` for containers with `working_memory_retention_days > 0`.
4. For each container:
   - Compute `cutoff = now - retention_days`.
   - Execute delete-by-query against the working memory index with:
     - `memory_container_id == container_id`
     - `created_time < cutoff`
   - Sleep 5 seconds before moving to the next container to avoid usage spikes.
5. Log results and continue to the next container.

## Scheduling and configuration
- New setting: `ml_commons.working_memory_cleanup_interval_in_hours`, default 24.
- Job definition stored in `.plugins-ml-jobs` using `MLJobParameter` with `IntervalSchedule`.
- Cleanup job can be enabled/disabled by indexing or updating the job document.
- Start the job lazily on the first memory container creation:
  - After `indexMemoryContainer(...)` succeeds, attempt to create the cleanup job with `opType CREATE`.
  - Ignore `ResourceAlreadyExistsException` to make it idempotent under concurrent container creation.

## Multi-tenancy handling
- Not in scope for this iteration because the expiration policy is coupled to the container itself.

## Security and access control
- Cleanup job runs as an internal system job and must apply `memory_container_id` filters to prevent cross-container deletes.
- The job should not rely on end-user permissions because it is maintenance logic.

## Error handling
- For each container, failures should be logged and the job should continue to the next container.
- Delete-by-query failures should include the container ID and index name in logs.
- If the container or working index is missing, log and continue.

## Performance considerations
- Use batch search with a reasonable page size when scanning containers (e.g., 1k).
- Avoid running cleanup too frequently; prefer hourly or daily intervals.
- Consider a maximum containers-per-run guard to avoid long-running jobs on large clusters.
- Sleep 5 seconds between containers to reduce load spikes.

## Testing strategy
- Unit tests for:
  - `MemoryConfiguration` parse/validate/serialize for the new field.
  - `MLWorkingMemoryCleanupJobProcessor` query building and cutoff logic.
  - `MLJobRunner` dispatch to the new job type.
- Integration tests:
  - Create a container with retention, add working memory, advance time, run job, and verify cleanup.

## Rollout plan
- Ship enabled by default with a 24-hour interval and 90-day retention unless explicitly overridden.
- Provide guidance for enabling via a settings toggle or an internal API path.
- Document the new config field and job interval setting.

## Backward compatibility
- Existing containers without `working_memory_retention_days` will automatically use the default 90-day retention.
- Existing documents are unaffected until the cleanup job runs; no index migrations are required.
- The new field is additive and safely ignored by older nodes during rolling upgrades.

## Open questions
- Cleanup cadence is set to 24 hours.
- Should cleanup use `created_time` or `last_updated_time`?
- Maximum retention is capped at 365 days.
