# LLM Result Path Auto-Generation Logging Guide

## Log Prefix
All logs use the prefix `[LLM_RESULT_PATH_GEN]` for easy filtering.

## Log Levels

### INFO Level (Key Milestones)
These logs show the major steps of auto-generation:

1. **Generation Started**
   ```
   [LLM_RESULT_PATH_GEN] Starting llm_result_path auto-generation (schema size: 3245 bytes)
   ```

2. **DataAsMap Navigation Result**
   - **Found:**
     ```
     [LLM_RESULT_PATH_GEN] Found dataAsMap schema at standard path, searching within dataAsMap structure
     ```
   - **Not Found (Fallback to Root):**
     ```
     [LLM_RESULT_PATH_GEN] No dataAsMap schema found in standard ModelTensorOutput path, searching from schema root
     ```

3. **Marker Found**
   ```
   [LLM_RESULT_PATH_GEN] 🎯 Found x-llm-output marker at path: $.choices[0].message.content
   ```

4. **Success**
   ```
   [LLM_RESULT_PATH_GEN] ✅ Successfully generated llm_result_path: $.choices[0].message.content
   ```

### WARN Level (Issues/Fallbacks)

1. **Null/Empty Schema**
   ```
   [LLM_RESULT_PATH_GEN] Output schema is null or empty, cannot generate llm_result_path
   ```

2. **No Marker Found**
   ```
   [LLM_RESULT_PATH_GEN] ❌ No field with x-llm-output marker found in schema - will use fallback path
   ```

### ERROR Level (Failures)

1. **Schema Parsing Error**
   ```
   [LLM_RESULT_PATH_GEN] ❌ Failed to generate llm_result_path from schema
   ```

### DEBUG Level (Detailed Tracing)
Enable debug logging to see field-by-field traversal:

1. **DataAsMap Navigation**
   ```
   [LLM_RESULT_PATH_GEN] Navigating to dataAsMap using rigid path: properties.inference_results.items.properties.output.items.properties.dataAsMap
   [LLM_RESULT_PATH_GEN] Successfully navigated to dataAsMap schema
   ```

2. **Field Checking**
   ```
   [LLM_RESULT_PATH_GEN] Checking field: $.choices
   [LLM_RESULT_PATH_GEN] Navigating into array: $.choices[0]
   [LLM_RESULT_PATH_GEN] Checking field: $.choices[0].message
   [LLM_RESULT_PATH_GEN] Checking field: $.choices[0].message.content
   ```

## Example Log Flows

### Success Case 1: OpenAI Model
```
[LLM_RESULT_PATH_GEN] Starting llm_result_path auto-generation (schema size: 3245 bytes)
[LLM_RESULT_PATH_GEN] Found dataAsMap schema at standard path, searching within dataAsMap structure
[LLM_RESULT_PATH_GEN] 🎯 Found x-llm-output marker at path: $.choices[0].message.content
[LLM_RESULT_PATH_GEN] ✅ Successfully generated llm_result_path: $.choices[0].message.content
```

### Success Case 2: Claude Model
```
[LLM_RESULT_PATH_GEN] Starting llm_result_path auto-generation (schema size: 2156 bytes)
[LLM_RESULT_PATH_GEN] Found dataAsMap schema at standard path, searching within dataAsMap structure
[LLM_RESULT_PATH_GEN] 🎯 Found x-llm-output marker at path: $.content[0].text
[LLM_RESULT_PATH_GEN] ✅ Successfully generated llm_result_path: $.content[0].text
```

### Fallback Case: Custom Model Without Schema
```
[LLM_RESULT_PATH_GEN] Output schema is null or empty, cannot generate llm_result_path
```
→ MemoryProcessingService will use `CLAUDE_SYSTEM_PROMPT_PATH` fallback

### Fallback Case: Schema Without Marker
```
[LLM_RESULT_PATH_GEN] Starting llm_result_path auto-generation (schema size: 1890 bytes)
[LLM_RESULT_PATH_GEN] Found dataAsMap schema at standard path, searching within dataAsMap structure
[LLM_RESULT_PATH_GEN] ❌ No field with x-llm-output marker found in schema - will use fallback path
```
→ MemoryProcessingService will use `CLAUDE_SYSTEM_PROMPT_PATH` fallback

### Edge Case: Non-Standard Schema Structure
```
[LLM_RESULT_PATH_GEN] Starting llm_result_path auto-generation (schema size: 892 bytes)
[LLM_RESULT_PATH_GEN] No dataAsMap schema found in standard ModelTensorOutput path, searching from schema root
[LLM_RESULT_PATH_GEN] 🎯 Found x-llm-output marker at path: $.response
[LLM_RESULT_PATH_GEN] ✅ Successfully generated llm_result_path: $.response
```

## Grep Commands for Production Debugging

### View all auto-generation attempts
```bash
grep "LLM_RESULT_PATH_GEN" opensearch.log
```

### View only successful generations
```bash
grep "LLM_RESULT_PATH_GEN.*✅" opensearch.log
```

### View only failures/fallbacks
```bash
grep "LLM_RESULT_PATH_GEN.*❌" opensearch.log
```

### View what paths were generated
```bash
grep "Successfully generated llm_result_path" opensearch.log
```

### Enable debug logging
Add to `log4j2.properties`:
```properties
logger.llm_path_gen.name = org.opensearch.ml.common.utils.LlmResultPathGenerator
logger.llm_path_gen.level = debug
```

## Expected Production Behavior

### With Properly Annotated Schemas (GPT-4o, Claude 3.7+)
- ✅ Should see "Successfully generated" logs
- ✅ Paths should match model type (`$.choices[0].message.content` for OpenAI, `$.content[0].text` for Claude)
- ✅ No fallback warnings

### With Custom Models (No Schema)
- ⚠️ Should see "null or empty" warning
- ✅ Will use Claude fallback path
- ✅ Should still work if model response matches Claude structure

### With Legacy Models (Schema Without Marker)
- ⚠️ Should see "No field with x-llm-output marker" warning  
- ✅ Will use Claude fallback path
- ❗ May fail if model response doesn't match Claude structure

## Troubleshooting

### Issue: "No field with x-llm-output marker found"
**Cause:** Schema missing `x-llm-output: true` marker
**Solution:** Add marker to schema at LLM text field location
**Workaround:** System will use Claude fallback path

### Issue: "Failed to generate llm_result_path from schema"
**Cause:** Malformed JSON schema
**Solution:** Validate schema JSON syntax
**Workaround:** System will use Claude fallback path

### Issue: Generation succeeds but extraction fails
**Cause:** Schema doesn't match actual model response
**Solution:** Update schema to match actual response structure
**Debug:** Enable debug logging to see field-by-field traversal

## Integration with MemoryProcessingService

When integrated, you'll also see logs from `MemoryProcessingService`:

```
[MemoryProcessingService] Auto-generated llm_result_path for model gpt-4-model-id: $.choices[0].message.content
[MemoryProcessingService] Using fallback llm_result_path: $.content[0].text
```

These logs will appear together with the `LLM_RESULT_PATH_GEN` logs to show the complete flow.
