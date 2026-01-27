# ML Commons Developer Reference for Claude

This document provides essential information for developing with the OpenSearch ML Commons plugin.

## Project Overview

ML Commons is a machine learning plugin for OpenSearch that provides:
- A unified interface for training and inference tasks
- Support for various ML algorithms (clustering, regression, classification, etc.)
- Model serving framework for hosting ML models
- Remote inference capabilities for external ML services
- Integration with AWS services (Bedrock, SageMaker) and other ML providers

## Build and Development Commands

### Essential Gradle Commands

```bash
# Build the project
./gradlew build

# Build with RPM and DEB packages
./gradlew build buildDeb buildRpm

# Run OpenSearch with ML Commons plugin installed (single node)
./gradlew run

# Run integration tests (single node)
./gradlew integTest

# Run integration tests with multiple nodes
./gradlew integTest -PnumNodes=3

# Run specific integration test class
./gradlew integTest --tests="org.opensearch.ml.rest.RestMLTrainAndPredictIT"

# Run specific integration test method
./gradlew integTest --tests="org.opensearch.ml.rest.RestMLTrainAndPredictIT.testTrainAndPredictKmeansWithEmptyParam"

# Alternative way to run specific tests
./gradlew integTest -Dtests.class="org.opensearch.ml.rest.RestMLTrainAndPredictIT"
./gradlew integTest -Dtests.method="testTrainAndPredictKmeans"

# Run tests against remote cluster with security
./gradlew integTest -Dtests.rest.cluster=localhost:9200 -Dtests.cluster=localhost:9200 -Dtests.clustername="docker-cluster" -Dhttps=true -Duser=admin -Dpassword=admin

# Format code
./gradlew spotlessApply

# Debug mode - starts cluster with debugger on port 8000
./gradlew :integTest --debug-jvm
./gradlew :run --debug-jvm

# Debug integration tests - debugger on port 5005
./gradlew -Dtest.debug :integTest
```

### Docker Development

```bash
# Build distributions first
./gradlew assemble

# Run OpenSearch using Docker Compose
docker-compose -f docs/docker/dev-docker-compose.yml up

# Access OpenSearch Dashboards at http://localhost:5601
# Credentials: admin / MyPassword123!
```

### Working with Remote OpenSearch

```bash
# Run integration tests against remote cluster
./gradlew integTestRemote -Dtests.rest.cluster=localhost:9200 -Dhttps=true -Duser=admin -Dpassword=admin
```

## Project Structure

```
ml-commons/
├── common/                 # Common classes and interfaces
├── ml-algorithms/         # ML algorithm implementations
├── plugin/               # Main plugin code
├── client/              # Java client for ML Commons
├── spi/                 # Service Provider Interface
├── memory/              # Memory management for conversational agents
├── search-processors/   # Search pipeline processors
└── docs/               # Documentation and tutorials
```

## Key Development Patterns

### Adding New ML Functions

1. **Define Function Name**: Add to `FunctionName.java` enum
2. **Create Input Class**: Implement `MLAlgoParams` with `@MLAlgoParameter` annotation
3. **Create Output Class**: Extend `MLOutput` with `@MLAlgoOutput` annotation
4. **Implement Algorithm**: Implement `Trainable`, `Predictable`, or `Executable` interfaces with `@Function` annotation
5. **Register Components**: Add to `MachineLearningPlugin.getNamedXContent()` and optionally register in `createComponents()`

### Code Style Requirements

- Use CamelCase for filenames
- Keep functions under ~25 lines
- Format code with `./gradlew spotlessApply` or import `.eclipseformat.xml` in IDE
- No commented-out code
- Avoid global definitions
- Java 21 compatibility required

## Architecture Decisions

### Plugin Architecture
- Uses OpenSearch plugin framework with job scheduler integration
- Supports both local execution and distributed ML tasks
- Resource isolation to prevent impact on core OpenSearch operations

### Model Management
- Models stored in OpenSearch indices
- Support for model versioning and access control
- Model groups for organizing related models

### Remote Inference
- Connector framework for integrating external ML services
- Built-in support for AWS Bedrock, SageMaker, OpenAI, Cohere, etc.
- Blueprints provided for common integration patterns

### Security
- Integration with OpenSearch security plugin
- Model access control with backend roles
- Secure credential storage for remote connectors

## Testing Guidelines

### Unit Tests
- Run with standard `test` task
- Located alongside source code
- Use mocking for external dependencies

### Integration Tests
- Test against running OpenSearch cluster
- Include both REST and transport layer tests
- Security tests require HTTPS enabled cluster
- BWC tests run only in `bwcsuite` task

### Test Data
- Use DataFrame format for ML input/output
- Support for both inline data and index-based data
- Column metadata required for type safety

## Important Configuration

### Default OpenSearch Setup
```yaml
opensearch.hosts: ["https://localhost:9200"]
opensearch.username: "admin"
opensearch.password: "admin"
```

### Build Properties
- OpenSearch version: Configured via `opensearch.version` system property
- Default: 3.2.0-SNAPSHOT
- Common utils version follows OpenSearch build version

### Memory Settings
- Integration tests use custom temp directory
- Configure JVM heap for large model operations
- Circuit breaker settings may need adjustment for ML workloads

## Debugging Tips

1. **Cluster Logs**: Check `/build/cluster/run node0/opensearch-<version>/logs`
2. **Remote Debugging**: Use `--debug-jvm` flag to enable debugger attachment
3. **Test Debugging**: IDE debugger for unit tests, special flags for integration tests
4. **Multi-node Debugging**: Each node gets sequential debug port starting from 5005

## CI/CD Considerations

- Maintainer approval required for workflow runs (as of Oct 2, 2024)
- Tests run against multiple OpenSearch versions
- Code coverage tracked via codecov
- Spotless formatting enforced in CI

## Key Resources

- [Model Serving Framework Docs](https://opensearch.org/docs/latest/ml-commons-plugin/model-serving-framework/)
- [Model Access Control](docs/model_access_control.md)
- [How to Add New Function](docs/how-to-add-new-function.md)
- [Remote Inference Blueprints](docs/remote_inference_blueprints/)
- [Contributing Guide](CONTRIBUTING.md)

## Common Development Tasks

### Running a Local ML Model
```bash
# Train a model
POST /_plugins/_ml/_train/kmeans
{
  "parameters": { "centroids": 3 },
  "input_data": { ... }
}

# Run prediction
POST /_plugins/_ml/_predict/kmeans/<model_id>
{
  "input_data": { ... }
}
```

### Setting Up Remote Inference
```bash
# Create connector
POST /_plugins/_ml/connectors/_create
{
  "name": "OpenAI Connector",
  "protocol": "http",
  "parameters": { ... }
}

# Deploy remote model
POST /_plugins/_ml/models/_register
{
  "name": "OpenAI Embeddings",
  "connector_id": "<connector_id>",
  "model_format": "REMOTE"
}
```

## Performance Considerations

- ML operations are compute-intensive
- Use dedicated ML nodes for production
- Monitor memory usage and circuit breakers
- Consider model caching for frequently used models
- Batch operations when possible

## Security Best Practices

- Always test with security enabled
- Use backend roles for model access control
- Encrypt credentials for remote connectors
- Follow principle of least privilege for API access
- Regular security audits of ML pipelines