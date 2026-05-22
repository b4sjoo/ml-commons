# Working Memory Retention — Pilot (Stage 0) Task Breakdown

Tracks implementation progress for the Pilot stage of working memory retention.
Full plan: `.claude/plans/snappy-snacking-fountain.md`

---

## A. RFC Update
- [x] **A1** — Restructure RFC into 3 stages (Pilot / Stage 1 / Stage 2)
  - File: `working_docs/WORKING_MEMORY_RETENTION_RFC_GITHUB.md`
  - Split old "Stage 1" into Pilot (query-time filtering only) and Stage 1 (opportunistic cleanup)
  - Update Rollout Plan to 3 phases
  - Update "Why Staged Rollout" with pilot rationale

## B. Production Code
- [x] **B1** — Create `WorkingMemoryRetentionPolicy` model class
  - New file: `common/src/main/java/org/opensearch/ml/common/memorycontainer/WorkingMemoryRetentionPolicy.java`
  - Fields: `retentionDays` (Integer, nullable), constants (DEFAULT=90, MIN=1, MAX=365)
  - Methods: `parse()`, `toXContent()`, `writeTo()`, `StreamInput` ctor, `getEffectiveRetentionDays()`

- [x] **B2** — Add constants to `MemoryContainerConstants`
  - File: `common/.../memorycontainer/MemoryContainerConstants.java`
  - Add: `WORKING_MEMORY_RETENTION_POLICY_FIELD`, `RETENTION_DAYS_FIELD`, `RETENTION_DAYS_OUT_OF_RANGE_ERROR`

- [x] **B3** — Wire retention policy into `MemoryConfiguration`
  - File: `common/.../memorycontainer/MemoryConfiguration.java`
  - Add nullable field, constructor param, `writeTo`/`StreamInput`, `toXContent`, `parse()` case, builder field, `update()` support

- [x] **B4** — Update index mapping
  - File: `common/.../resources/index-mappings/ml_memory_container.json`
  - Add `working_memory_retention_policy` object with `retention_days` integer property

- [x] **B5** — Add `addRetentionFilter()` to `MemoryContainerHelper`
  - File: `plugin/.../helper/MemoryContainerHelper.java`
  - New method: computes cutoff, adds `RangeQueryBuilder` on `last_updated_time` via `applyFilterToSearchSource`

- [x] **B6** — Inject retention filter in `TransportSearchMemoriesAction`
  - File: `plugin/.../memory/TransportSearchMemoriesAction.java`
  - After existing filters, call `addRetentionFilter` when `memoryType == MemoryType.WORKING`

- [x] **B7** — Post-fetch expiry check in `TransportGetMemoryAction`
  - File: `plugin/.../memory/TransportGetMemoryAction.java`
  - After `getResponse.isExists()`, check `last_updated_time` against cutoff for WORKING memory; return NOT_FOUND if expired

## C. Unit Tests
- [x] **C1** — `WorkingMemoryRetentionPolicyTests` (new file)
  - File: `common/src/test/java/org/opensearch/ml/common/memorycontainer/WorkingMemoryRetentionPolicyTests.java`
  - Tests: parse valid/invalid, boundary values, round-trip serialization, effective default

- [x] **C2** — `MemoryConfigurationTests` additions
  - File: `common/.../memorycontainer/MemoryConfigurationTests.java`
  - Tests: parse with/without retention policy, toXContent, writeTo round-trip, update()

- [x] **C3** — `MemoryContainerHelperTests` additions
  - File: `plugin/.../helper/MemoryContainerHelperTests.java`
  - Tests: null policy no-op, 30-day filter, default 90-day filter

- [x] **C4** — `TransportSearchMemoriesActionTests` additions
  - File: `plugin/.../memory/TransportSearchMemoriesActionTests.java`
  - Tests: WORKING with policy → filter injected, WORKING without policy → no filter, LONG_TERM → no filter

- [x] **C5** — `TransportGetMemoryActionTests` additions
  - File: `plugin/.../memory/TransportGetMemoryActionTests.java`
  - Tests: within window → OK, outside window → NOT_FOUND, no policy → OK, non-WORKING → no check

## D. Verification
- [x] **D1** — `./gradlew build` passes
- [x] **D2** — Grep RFC for stale "Stage 1" references that should say "Pilot"
- [x] **D3** — Confirm `MemoryConfiguration.parse()` with absent field yields `null` (not default policy)
