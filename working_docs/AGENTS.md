# Repository Guidelines

## Project Structure & Module Organization
- Core plugin code sits in `plugin/src/main/java`; REST specs live in `plugin/src/yamlRestTest`, and cluster assets in `build/`.
- Shared primitives sit in `common/`, algorithms in `ml-algorithms/`, memory services in `memory/`, client wrappers in `client/`, with extensions under `search-processors/` and `spi/`.
- Docs, release assets, and scripts live in `docs/`, `release-notes/`, and `scripts/`; module tests sit in `src/test/java`.

## Build, Test, and Development Commands
- `./gradlew assemble` creates distributable zips in `plugin/build/distributions/` for packaging and Docker flows.
- `./gradlew build` compiles and runs unit checks; append `buildDeb buildRpm` when publishing native packages.
- `./gradlew run` launches a single-node cluster with ml-commons for manual verification.
- `./gradlew integTest [-PnumNodes=2 --tests org.opensearch.ml.rest.RestMLTrainAndPredictIT]` spins up an integration cluster and narrows execution as needed.
- `./gradlew spotlessApply` enforces shared formatting rules.

## Coding Style & Naming Conventions
- Target Java 17+, four-space indentation, and `CamelCase` filenames aligned with package structure.
- Run Spotless before pushing to honor import order (`java`, `javax`, `org`, `com`) and avoid CI failures; refactor long methods into helpers in `common/`.

## Testing Guidelines
- `./gradlew test` drives JUnit 5 unit suites per module.
- Place integration cases in `*/src/test/java/.../integ` or REST YAML suites in `plugin/src/yamlRestTest`, suffixing Java classes with `IT` to hook into `integTest`.
- Maintain inference coverage by updating YAML specs and referencing `build/cluster/run node0/opensearch-*/logs` during debugging.

## Agentic Memory Development
- Agentic Memory logic spans `memory/src/main/java/org/opensearch/ml/memory` and transport layers in `plugin/src/main/java/org/opensearch/ml/action/memorycontainer`; keep them aligned with shared models in `common/.../memory`.
- Review `AGENTIC_MEMORY.md` before adjusting APIs or namespace rules, and update REST specs under `plugin/src/yamlRestTest/memory` when behavior shifts.
- Test conversation and data flows via `./gradlew integTest --tests org.opensearch.ml.rest.RestMemoryCreateConversationActionIT` and `...RestMemoryCreateInteractionActionIT`, supplementing with YAML suites, and note index migrations or strategy changes in PRs.

## Commit & Pull Request Guidelines
- Use imperative commit subjects (`Fix build for testing`), optionally tagging scopes with `[FEATURE]` or `(#1234)` to match history.
- Keep commits testable, run `./gradlew build` before pushing, and include docs or schema updates alongside code.
- PRs need a concise summary, linked issues, and test evidence; maintainer approval gates CI, so respond quickly and re-request review after updates.

## Security & Configuration Tips
- Store secrets in environment variables; `docs/docker/dev-docker-compose.yml` seeds `OPENSEARCH_INITIAL_ADMIN_PASSWORD` for local stacks.
- Ignore generated artifacts instead of committing them, and flag dependency changes that touch crypto or network surfaces for review.
