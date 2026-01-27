# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

ML Commons is an OpenSearch plugin that provides machine learning capabilities including model management, inference, and agent framework. It's a Java-based multi-module Gradle project.

## Agentic Memory Feature

The Agentic Memory system provides sophisticated memory management with a four-index architecture, strategy-based processing, and namespace organization.

**For complete documentation, see:**
- [AGENTIC_MEMORY.md](./AGENTIC_MEMORY.md) - Complete agentic memory documentation
- [AGENTIC_MEMORY_UPDATES.md](./AGENTIC_MEMORY_UPDATES.md) - Recent updates and changes
- [AGENTIC_MEMORY_TESTING.md](./AGENTIC_MEMORY_TESTING.md) - Testing and troubleshooting

### Quick Reference
- **APIs**: 3 API groups (~15 REST endpoints)
  - Group 1: Container Management (5 endpoints: Create, Get, Update, Delete, Search)
  - Group 2: Add Memory (1 endpoint: POST /memories)
  - Group 3: Unified Memory APIs (4 endpoint patterns × 4 memory types = 16 combinations)
- **Architecture**: Four-index hierarchy (session, working memory, long-term, history)
- **Key Features**:
  - Unified API pattern for all memory types
  - Type-safe memory handling with `MemoryType` enum
  - Namespace system (flexible key-value organization, replaces fixed sessionId/agentId)
  - Strategy-based processing with validation
  - LLM integration for fact extraction
- **Implementation**: Uses existing ML Commons modules (common, plugin)

## Build and Development Commands

```bash
# Build entire project
./gradlew build

# Build without tests
./gradlew build -x test

# Build specific module
./gradlew :opensearch-ml-plugin:build

# Clean build
./gradlew clean build

# Generate code coverage report
./gradlew codeCoverageReport
```

### Test Commands
```bash
# Run all tests
./gradlew test

# Run unit tests only
./gradlew test -Dtests.class="*Test"

# Run integration tests
./gradlew integTest

# Run specific test class
./gradlew test -Dtests.class="org.opensearch.ml.common.MLTaskTest"

# Run specific test method
./gradlew test -Dtests.method="test_MLTaskNullFields"

# Run YAML REST tests
./gradlew yamlRestTest

# Run tests with custom seed (for reproducibility)
./gradlew test -Dtests.seed=2AEBDBBAE75AC5E7

# Generate test coverage report
./gradlew codeCoverageReportAggregate
```

### Running OpenSearch Locally
```bash
# Run single-node cluster with ML plugin
./gradlew run

# Run with security disabled (faster for development)
./gradlew run -Dtests.cluster=opensearch-ml-cluster -Dtests.clustername="opensearch-ml-cluster" -Dhttps=false

# Run with specific OpenSearch version
./gradlew run -Dopensearch.version=3.0.0

# Run multi-node cluster
./gradlew run -PnumNodes=3
```

### Docker Setup
```bash
# Start OpenSearch with ML Commons in Docker
docker run -p 9200:9200 -p 9600:9600 -e "discovery.type=single-node" -e "plugins.security.disabled=true" opensearchproject/opensearch:latest -E "bootstrap.ml_commons.enabled=true"
```

### Code Quality
```bash
# Format code
./gradlew spotlessApply

# Check code formatting
./gradlew spotlessCheck

# Run SpotBugs analysis
./gradlew spotbugsMain
```

### Debugging
```bash
# Debug OpenSearch cluster (port 5005)
./gradlew run --debug-jvm

# Debug tests
./gradlew test -Dtest.debug=true

# Debug specific test
./gradlew :opensearch-ml-plugin:test -Dtests.class="org.opensearch.ml.common.MLTaskTest" -Dtest.debug=true
```

## Project Structure

- **client/** - ML client library for external applications
- **common/** - Shared data models and utilities
- **plugin/** - Core plugin implementation with REST/Transport actions
- **ml-algorithms/** - ML algorithm implementations
- **memory/** - Conversational memory for agents
- **search-processors/** - Search pipeline ML processors
- **spi/** - Service Provider Interface for extensions

## Architecture Patterns

### Adding New ML Functions
When adding a new ML function, follow this pattern:

1. **Define the Function** in `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/`:
```java
@Function(FunctionName.KMEANS)
public class Kmeans implements Trainable, Predictable {
    @MLAlgoParameter(value = "centroids", required = false, defaultValue = "2", description = "...")
    private Integer centroids = 2;

    @Override
    @MLAlgoOutput(MLOutputType.TRAINING_STATE)
    public MLOutput train(MLInput mlInput) {
        // Implementation
    }
}
```

2. **Register in Plugin** at `plugin/src/main/java/org/opensearch/ml/plugin/MachineLearningPlugin.java`

3. **Add Tests** in corresponding test directories

### Key Abstractions
- **MLInput/MLOutput** - Standard interfaces for ML operations
- **MLModel** - Model metadata and configuration
- **MLTask** - Async task tracking
- **Connector** - External model integration
- **Agent** - Conversational AI capabilities

## Testing Guidelines

### Test Types
- **Unit Tests**: Fast, isolated tests using mocks
- **Integration Tests**: Test plugin functionality with real OpenSearch cluster
- **YAML REST Tests**: End-to-end API testing

### Test Utilities
- Use `MLTestHelper` for creating test data
- Use `TestHelper` for common test operations
- Mock OpenSearch components with `org.opensearch.test.*`

## Important Configurations

### Default Settings (plugin/src/main/java/org/opensearch/ml/settings/MLCommonsSettings.java)
- Model access control enabled by default
- Native memory circuit breaker: 90% threshold
- Max model tasks per node: 10
- Request timeout: 10 seconds

### Build Properties
- Java 21 required
- OpenSearch 2.18.0 (check gradle.properties for current version)
- Uses Lombok for reducing boilerplate

## Common Development Tasks

### Training a Model
```java
MLInput mlInput = MLInput.builder()
    .algorithm(FunctionName.KMEANS)
    .parameters(MLAlgoParams.builder().build())
    .inputDataset(DataFrameInputDataset.builder().dataFrame(dataFrame).build())
    .build();
```

### Remote Inference Setup
1. Create connector with endpoint details
2. Register remote model with connector ID
3. Deploy model
4. Use predict API with model ID

### Invoking ML Prediction Requests
```java
// Create MLInput with your data
MLInput mlInput = MLInput.builder()
    .algorithm(FunctionName.TEXT_EMBEDDING)
    .inputDataset(TextDocsInputDataSet.builder()
        .docs(Arrays.asList("Your text to embed"))
        .build())
    .build();

// Create and execute prediction request
MLPredictionTaskRequest predictionRequest = MLPredictionTaskRequest.builder()
    .modelId("your-model-id")
    .mlInput(mlInput)
    .build();

client.execute(MLPredictionTaskAction.INSTANCE, predictionRequest, ActionListener.wrap(
    response -> {
        MLOutput mlOutput = response.getOutput();
        if (mlOutput instanceof ModelTensorOutput) {
            ModelTensorOutput tensorOutput = (ModelTensorOutput) mlOutput;
            // Extract embeddings based on type
        }
    },
    error -> { /* handle error */ }
));
```

## Performance Considerations
- Use circuit breakers to prevent OOM
- Implement proper resource cleanup in algorithms
- Consider async operations for long-running tasks
- Use appropriate thread pools for ML operations

## Security
- Model access control via backend roles
- Connector credentials stored encrypted
- Transport actions require appropriate permissions
- Never expose sensitive model endpoints

## Code Style
- Spotless for automatic formatting
- CamelCase for filenames
- Functions ≤ 25 lines recommended
- No commented-out code
- Clear variable names
