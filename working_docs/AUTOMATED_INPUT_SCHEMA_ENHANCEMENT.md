# Automated Input Schema Enhancement: System Prompt & Message Processing

## Document Status
**Status**: Design Checkpoint
**Created**: 2025-10-09
**Type**: Design Proposal
**Related**: AUTOMATED_LLM_RESULT_PATH_DESIGN.md (output schema automation)

---

## Executive Summary

**Problem**: Current system has hardcoded assumptions about how to inject system prompts and process message content, leading to incompatibility with different LLM providers.

**Solution**: Use input schemas (similar to output schema automation) to automatically detect model capabilities and adapt request formatting accordingly.

**Key Insight**: Input schemas already contain all the information needed to determine:
1. Whether a model uses `system_prompt` parameter (Claude) or system messages (OpenAI)
2. What content format is expected in the messages array

---

## Problem Statement

### Current Complexity

**Problem 1: System Prompt Placement**
Current code (lines 134-147 in MemoryProcessingService.java):
```java
stringParameters.put("system_prompt", defaultPrompt);
```

**Issue**: This works for Claude but breaks for OpenAI models.
- **Claude**: Uses `system_prompt` parameter in API call
- **OpenAI**: Requires system prompt as first message with `{"role": "system", "content": "..."}`

**Problem 2: Message Content Processing**
Current code (lines 159-160):
```java
for (MessageInput message : messages) {
    message.toXContent(messagesBuilder, ToXContent.EMPTY_PARAMS);
}
```

**Issue**: No validation or normalization of content objects.
- Some models expect `{"type": "text", "text": "..."}` format
- Users may provide custom objects without `type` field
- No handling for mixed or malformed content

### Real-World Failure Scenarios

**Scenario 1: OpenAI Model with System Prompt**
```
User creates memory container with GPT-4 model
↓
Adds memories with fact extraction enabled
↓
❌ FAILS: OpenAI API rejects system_prompt parameter (not in their API)
↓
Facts never extracted, memory doesn't work
```

**Scenario 2: User-Defined Content Objects**
```
User sends message with custom object: {"data": {"key": "value"}}
↓
Object passed as-is to LLM
↓
❌ LLM confused: expects {"type": "...", ...} format
↓
May fail to process or give poor results
```

---

## Current State Analysis

### Input Schemas Already Exist

**Claude Schema** (`bedrock_anthropic_claude_use_system_prompt_input.json`):
```json
{
  "properties": {
    "parameters": {
      "properties": {
        "system_prompt": {
          "type": "string",
          "description": "System prompt to guide the model's behavior"
        },
        "messages": {
          "type": "array",
          "items": {
            "properties": {
              "role": {"type": "string"},
              "content": {
                "oneOf": [
                  {"type": "string"},
                  {
                    "type": "array",
                    "items": {
                      "properties": {
                        "type": {"type": "string"}
                      },
                      "required": ["type"]
                    }
                  }
                ]
              }
            }
          }
        }
      }
    }
  }
}
```

**OpenAI Schema** (`openai_chat_completions_input.json`):
```json
{
  "properties": {
    "parameters": {
      "properties": {
        "messages": {
          "type": "array",
          "items": {
            "properties": {
              "role": {"type": "string"},
              "content": {
                "oneOf": [
                  {"type": "string"},
                  {
                    "type": "array",
                    "items": {
                      "properties": {
                        "type": {"type": "string"}
                      },
                      "required": ["type"]
                    }
                  }
                ]
              }
            }
          }
        }
      },
      "required": ["messages"]
    }
  }
}
```

**Key Difference**: Claude schema HAS `system_prompt`, OpenAI schema DOES NOT.

### MessageInput Structure

**Current Implementation** (`MessageInput.java`):
```java
@Getter
@Setter
@Builder
public class MessageInput implements ToXContentObject, Writeable {
    private String role;
    private List<Map<String, Object>> content;  // Generic content objects

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) {
        builder.startObject();
        if (role != null) {
            builder.field(ROLE_FIELD, role);
        }
        if (content != null) {
            builder.field(CONTENT_FIELD, content);  // No processing/validation
        }
        builder.endObject();
        return builder;
    }
}
```

**Issue**: Content objects are serialized as-is with no validation or normalization.

---

## Proposed Solution

### Design Philosophy

**Principle**: Use input schemas as the single source of truth for request formatting.

**Similar to**: Output schema automation (already implemented for `llm_result_path`)
- Input schemas define API surface → Use them to generate correct requests
- Output schemas define response structure → Use them to extract results

### Solution Component 1: Schema-Driven System Prompt Injection

**Algorithm**:
```
1. Get model's input schema from cache
2. Check if schema has "system_prompt" field at parameters.properties.system_prompt
3. If YES:
   → Add system_prompt to parameters (Claude-style)
4. If NO:
   → Inject system message into messages array (OpenAI-style)
   → Format: {"role": "system", "content": [{"type": "text", "text": prompt}]}
5. Log which method was used for debugging
```

**Example Flow for OpenAI**:
```
determineEffectivePrompt() → "You are a helpful assistant..."
↓
hasSystemPromptParameter(inputSchema) → false (OpenAI has no system_prompt field)
↓
Inject as first message in array:
{
  "role": "system",
  "content": [{"type": "text", "text": "You are a helpful assistant..."}]
}
↓
Log: [MEMORY_PROMPT_INJECTION] Model gpt-4o: Injecting system prompt via message
```

### Solution Component 2: Smart Message Content Processing

**Content Object Categories**:

**Category 1: Simple String Content**
```json
{"role": "user", "content": "Hello"}
```
→ No processing needed (rare in our system)

**Category 2a: Standard LLM Format (has "type" field)**
```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "Hello"},
    {"type": "image_url", "image_url": {"url": "..."}}
  ]
}
```
→ Keep as-is, let LLM validate

**Category 2b: User-Defined Object (no "type" field)**
```json
{
  "role": "user",
  "content": [
    {"data": {"key": "value"}, "metadata": "custom"}
  ]
}
```
→ Wrap as: `{"type": "text", "text": "{\"data\":{\"key\":\"value\"},\"metadata\":\"custom\"}"}`

**Processing Algorithm**:
```
For each content object:
  If object has "type" field:
    → Keep as-is (standard format - Category 2a)
  Else:
    → Wrap as {"type": "text", "text": JSON.stringify(object)} (Category 2b)
```

**Why This Works**:
- Standard multi-modal content (text, images, audio) always has "type" field
- User-defined objects without "type" are automatically serialized as text
- LLMs can still parse the JSON string in text content
- No breaking changes to existing working content

---

## Implementation Plan

### Phase 1: Create Input Schema Utility

**New File**: `common/src/main/java/org/opensearch/ml/common/utils/LlmInputSchemaHelper.java`

**Purpose**: Detect model capabilities from input schema (similar to LlmResultPathGenerator for output)

**Key Methods**:
```java
public class LlmInputSchemaHelper {

    /**
     * Checks if model supports system_prompt parameter (Claude-style).
     *
     * Navigates to: parameters.properties.system_prompt
     *
     * @param inputSchemaJson The input schema JSON string
     * @return true if system_prompt parameter exists, false otherwise
     */
    public static boolean hasSystemPromptParameter(String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.trim().isEmpty()) {
            return false;
        }

        try {
            JsonNode schemaRoot = MAPPER.readTree(inputSchemaJson);
            JsonNode systemPrompt = schemaRoot
                .path("properties")
                .path("parameters")
                .path("properties")
                .path("system_prompt");

            return !systemPrompt.isMissingNode();
        } catch (Exception e) {
            log.error("Failed to check for system_prompt parameter in schema", e);
            return false;
        }
    }

    /**
     * Checks if messages array exists and is required in schema.
     *
     * @param inputSchemaJson The input schema JSON string
     * @return true if messages array exists
     */
    public static boolean hasMessagesArray(String inputSchemaJson) {
        // Similar navigation to parameters.properties.messages
    }
}
```

**Estimated Size**: ~120 lines (including logging and error handling)

---

### Phase 2: Update System Prompt Injection Logic

**File**: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryProcessingService.java`

**Location**: Lines 134-179 (extractFactsFromConversation method)

**Current Code**:
```java
// Determine default prompt
String defaultPrompt;
MemoryStrategyType type = strategy.getType();
if (type == MemoryStrategyType.USER_PREFERENCE) {
    defaultPrompt = USER_PREFERENCE_FACTS_EXTRACTION_PROMPT;
} else if (type == MemoryStrategyType.SUMMARY) {
    defaultPrompt = SUMMARY_FACTS_EXTRACTION_PROMPT;
} else {
    defaultPrompt = SEMANTIC_FACTS_EXTRACTION_PROMPT;
}

// Always put in parameters
stringParameters.put("system_prompt", defaultPrompt);
```

**New Code**:
```java
// Determine default prompt (unchanged)
String defaultPrompt;
MemoryStrategyType type = strategy.getType();
if (type == MemoryStrategyType.USER_PREFERENCE) {
    defaultPrompt = USER_PREFERENCE_FACTS_EXTRACTION_PROMPT;
} else if (type == MemoryStrategyType.SUMMARY) {
    defaultPrompt = SUMMARY_FACTS_EXTRACTION_PROMPT;
} else {
    defaultPrompt = SEMANTIC_FACTS_EXTRACTION_PROMPT;
}

// Determine custom prompt if provided (extract existing logic)
String effectiveSystemPrompt = determineEffectivePrompt(strategy, defaultPrompt, listener);
if (effectiveSystemPrompt == null) {
    return; // Validation failed, listener already called
}

// NEW: Determine where to inject system prompt based on input schema
String systemPromptToInject = null;
String systemPromptInjectionMethod = null;

try {
    Map<String, String> modelInterface = modelCacheHelper.getModelInterface(llmModelId);
    if (modelInterface != null && modelInterface.containsKey("input")) {
        String inputSchema = modelInterface.get("input");
        boolean hasSystemPromptParam = LlmInputSchemaHelper.hasSystemPromptParameter(inputSchema);

        if (hasSystemPromptParam) {
            // Claude-style: system_prompt parameter
            stringParameters.put("system_prompt", effectiveSystemPrompt);
            systemPromptInjectionMethod = "parameter";
            log.info("[MEMORY_PROMPT_INJECTION] Model {}: Using system_prompt parameter (prompt length: {} chars)",
                llmModelId, effectiveSystemPrompt.length());
        } else {
            // OpenAI-style: inject as first message
            systemPromptToInject = effectiveSystemPrompt;
            systemPromptInjectionMethod = "message";
            log.info("[MEMORY_PROMPT_INJECTION] Model {}: Injecting system message (prompt length: {} chars)",
                llmModelId, effectiveSystemPrompt.length());
        }
    } else {
        // No schema: fallback to Claude-style (legacy behavior)
        log.debug("No input schema found for model {}, using legacy system_prompt parameter", llmModelId);
        stringParameters.put("system_prompt", effectiveSystemPrompt);
        systemPromptInjectionMethod = "parameter";
    }
} catch (Exception e) {
    log.warn("Failed to detect system prompt method for model {}: {}. Using legacy parameter method.",
        llmModelId, e.getMessage());
    stringParameters.put("system_prompt", effectiveSystemPrompt);
    systemPromptInjectionMethod = "parameter";
}
```

**New Helper Method**:
```java
/**
 * Determines the effective system prompt to use, with validation.
 *
 * @param strategy Memory strategy that may contain custom prompt
 * @param defaultPrompt Default prompt for the strategy type
 * @param listener Listener to call on validation failure
 * @return Effective prompt to use, or null if validation failed
 */
private String determineEffectivePrompt(
    MemoryStrategy strategy,
    String defaultPrompt,
    ActionListener<List<String>> listener
) {
    if (strategy.getStrategyConfig() == null || strategy.getStrategyConfig().isEmpty()) {
        return defaultPrompt;
    }

    Object customPrompt = strategy.getStrategyConfig().get("system_prompt");
    if (customPrompt == null || customPrompt.toString().isBlank()) {
        return defaultPrompt;
    }

    // Validate custom prompt format
    if (!validatePromptFormat(customPrompt.toString())) {
        log.error("Invalid custom prompt format - must specify JSON response format with 'facts' array");
        listener.onFailure(
            new IllegalArgumentException("Custom prompt must specify JSON response format with 'facts' array")
        );
        return null;
    }

    return customPrompt.toString();
}
```

**Update Message Building** (lines 150-178):
```java
try {
    XContentBuilder messagesBuilder = jsonXContent.contentBuilder();
    messagesBuilder.startArray();

    // NEW: Inject system prompt as message if needed (OpenAI-style)
    if (systemPromptToInject != null) {
        messagesBuilder.startObject();
        messagesBuilder.field("role", "system");
        messagesBuilder.startArray("content");
        messagesBuilder.startObject();
        messagesBuilder.field("type", "text");
        messagesBuilder.field("text", systemPromptToInject);
        messagesBuilder.endObject();
        messagesBuilder.endArray();
        messagesBuilder.endObject();
    }

    // EXISTING: Optional system_prompt_message from strategy config
    Map<String, Object> strategyConfig = strategy.getStrategyConfig();
    if (strategyConfig != null && strategyConfig.containsKey("system_prompt_message")) {
        Object systemPromptMsg = strategyConfig.get("system_prompt_message");
        if (systemPromptMsg != null && systemPromptMsg instanceof Map) {
            messagesBuilder.map((Map) systemPromptMsg);
        }
    }

    // EXISTING: User messages
    for (MessageInput message : messages) {
        message.toXContent(messagesBuilder, ToXContent.EMPTY_PARAMS);
    }

    // EXISTING: Optional user_prompt_message and enforcement message
    // ... rest unchanged ...
```

**Changes**: ~60 lines modified, ~50 lines added

---

### Phase 3: Smart Message Content Processing

**New Method in MemoryProcessingService.java**:
```java
/**
 * Processes message content objects to ensure LLM compatibility.
 *
 * Rules:
 * - Objects with "type" field → keep as-is (standard LLM format - Category 2a)
 * - Objects without "type" field → wrap as text block (user-defined - Category 2b)
 *
 * Examples:
 * Input:  [{"type": "text", "text": "Hello"}]
 * Output: [{"type": "text", "text": "Hello"}]  (unchanged)
 *
 * Input:  [{"data": {"key": "value"}}]
 * Output: [{"type": "text", "text": "{\"data\":{\"key\":\"value\"}}"}]  (wrapped)
 *
 * @param content List of content objects from message
 * @return Processed list ready for LLM
 */
private List<Map<String, Object>> processMessageContent(List<Map<String, Object>> content) {
    if (content == null || content.isEmpty()) {
        return content;
    }

    List<Map<String, Object>> processedContent = new ArrayList<>();
    int objectIndex = 0;

    for (Map<String, Object> contentObj : content) {
        if (contentObj == null || contentObj.isEmpty()) {
            log.debug("[MESSAGE_CONTENT_PROCESSING] Skipping null/empty content object at index {}", objectIndex);
            objectIndex++;
            continue;
        }

        if (contentObj.containsKey("type")) {
            // Category 2a: Has "type" field - standard LLM format
            // Keep as-is, let LLM validate structure
            processedContent.add(contentObj);
            log.debug("[MESSAGE_CONTENT_PROCESSING] Standard content block at index {} with type: {}",
                objectIndex, contentObj.get("type"));
        } else {
            // Category 2b: No "type" field - user-defined object
            // Wrap as text block with stringified JSON
            Map<String, Object> wrappedContent = new HashMap<>();
            wrappedContent.put("type", "text");
            wrappedContent.put("text", StringUtils.toJson(contentObj));
            processedContent.add(wrappedContent);
            log.debug("[MESSAGE_CONTENT_PROCESSING] Wrapped user-defined object at index {} as text block",
                objectIndex);
        }

        objectIndex++;
    }

    return processedContent;
}
```

**Update Message Building to Use Processing**:
```java
// EXISTING: User messages
for (MessageInput message : messages) {
    // NEW: Process content objects
    List<Map<String, Object>> originalContent = message.getContent();
    List<Map<String, Object>> processedContent = processMessageContent(originalContent);

    // Build message with processed content
    messagesBuilder.startObject();
    messagesBuilder.field("role", message.getRole());
    messagesBuilder.field("content", processedContent);
    messagesBuilder.endObject();
}
```

**Alternative Approach**: Modify `MessageInput.toXContent()` to do processing internally
- Pro: Centralized logic, automatic for all uses
- Con: Changes MessageInput behavior globally
- **Decision**: Add processing in MemoryProcessingService first, can refactor to MessageInput later

**Lines**: ~60 lines new method, ~15 lines integration

---

### Phase 4: Apply to Other Methods

**Similar Updates Needed**:

1. **`makeMemoryDecisions()` method** (lines 89-179)
   - Currently hardcodes system_prompt parameter
   - Needs same schema-driven injection logic
   - Message content is already simple (just JSON text)

2. **`summarizeMessages()` method** (lines 304-357)
   - Currently hardcodes system_prompt parameter
   - Needs same schema-driven injection logic
   - User messages need content processing

**Approach**: Extract common logic into helper methods
```java
private void injectSystemPrompt(
    String llmModelId,
    String systemPrompt,
    Map<String, String> stringParameters,
    ActionListener<?> listener
) {
    // Reusable system prompt injection logic
}

private void buildMessagesArray(
    XContentBuilder messagesBuilder,
    String systemPromptToInject,
    List<MessageInput> messages,
    Map<String, Object> additionalMessages
) throws IOException {
    // Reusable message array building logic
}
```

**Lines**: ~40 lines helper methods, ~30 lines integration

---

## Testing Strategy

### Unit Tests for LlmInputSchemaHelper

**File**: `common/src/test/java/org/opensearch/ml/common/utils/LlmInputSchemaHelperTest.java` (NEW)

```java
public class LlmInputSchemaHelperTest {

    @Test
    public void testHasSystemPromptParameter_Claude() {
        String claudeSchema = loadSchema("bedrock_anthropic_claude_use_system_prompt_input.json");
        boolean result = LlmInputSchemaHelper.hasSystemPromptParameter(claudeSchema);
        assertTrue("Claude schema should have system_prompt parameter", result);
    }

    @Test
    public void testHasSystemPromptParameter_OpenAI() {
        String openAISchema = loadSchema("openai_chat_completions_input.json");
        boolean result = LlmInputSchemaHelper.hasSystemPromptParameter(openAISchema);
        assertFalse("OpenAI schema should NOT have system_prompt parameter", result);
    }

    @Test
    public void testHasSystemPromptParameter_NullSchema() {
        boolean result = LlmInputSchemaHelper.hasSystemPromptParameter(null);
        assertFalse("Null schema should return false", result);
    }

    @Test
    public void testHasSystemPromptParameter_MalformedJson() {
        boolean result = LlmInputSchemaHelper.hasSystemPromptParameter("{invalid json}");
        assertFalse("Malformed schema should return false", result);
    }

    @Test
    public void testHasMessagesArray_BothModels() {
        String claudeSchema = loadSchema("bedrock_anthropic_claude_use_system_prompt_input.json");
        String openAISchema = loadSchema("openai_chat_completions_input.json");

        assertTrue("Claude schema should have messages array",
            LlmInputSchemaHelper.hasMessagesArray(claudeSchema));
        assertTrue("OpenAI schema should have messages array",
            LlmInputSchemaHelper.hasMessagesArray(openAISchema));
    }
}
```

**Expected Tests**: ~15 tests

---

### Unit Tests for Message Content Processing

**File**: `plugin/src/test/java/org/opensearch/ml/action/memorycontainer/memory/MemoryProcessingServiceTest.java`

```java
public class MemoryProcessingServiceTest {

    @Test
    public void testProcessMessageContent_StandardFormat() {
        // Standard LLM content with "type" field
        List<Map<String, Object>> content = List.of(
            Map.of("type", "text", "text", "Hello"),
            Map.of("type", "image_url", "image_url", Map.of("url", "http://..."))
        );

        List<Map<String, Object>> result = service.processMessageContent(content);

        assertEquals(2, result.size());
        assertEquals("text", result.get(0).get("type"));
        assertEquals("image_url", result.get(1).get("type"));
    }

    @Test
    public void testProcessMessageContent_UserDefinedObject() {
        // User-defined object without "type" field
        List<Map<String, Object>> content = List.of(
            Map.of("data", Map.of("key", "value"), "metadata", "custom")
        );

        List<Map<String, Object>> result = service.processMessageContent(content);

        assertEquals(1, result.size());
        assertEquals("text", result.get(0).get("type"));
        assertTrue(result.get(0).get("text").toString().contains("\"data\""));
        assertTrue(result.get(0).get("text").toString().contains("\"metadata\""));
    }

    @Test
    public void testProcessMessageContent_MixedContent() {
        // Mix of standard and user-defined
        List<Map<String, Object>> content = List.of(
            Map.of("type", "text", "text", "Hello"),
            Map.of("custom_field", "custom_value")
        );

        List<Map<String, Object>> result = service.processMessageContent(content);

        assertEquals(2, result.size());
        assertEquals("text", result.get(0).get("type")); // Unchanged
        assertEquals("text", result.get(1).get("type")); // Wrapped
        assertTrue(result.get(1).get("text").toString().contains("custom_field"));
    }

    @Test
    public void testProcessMessageContent_EmptyContent() {
        List<Map<String, Object>> result = service.processMessageContent(new ArrayList<>());
        assertTrue(result.isEmpty());
    }
}
```

**Expected Tests**: ~10 tests for content processing

---

### Integration Tests for System Prompt Injection

```java
public class MemoryProcessingServiceIntegrationTest {

    @Test
    public void testSystemPromptInjection_ClaudeModel() {
        // Mock model cache to return Claude input schema
        when(modelCacheHelper.getModelInterface("claude-3-7"))
            .thenReturn(Map.of("input", claudeInputSchema));

        // Build request
        service.extractFactsFromConversation(messages, strategy, config, listener);

        // Verify system_prompt in parameters
        ArgumentCaptor<MLPredictionTaskRequest> captor = ArgumentCaptor.forClass(MLPredictionTaskRequest.class);
        verify(client).execute(any(), captor.capture(), any());

        Map<String, String> params = getParameters(captor.getValue());
        assertNotNull("system_prompt should be in parameters", params.get("system_prompt"));

        // Verify NOT in messages array
        String messagesJson = params.get("messages");
        assertFalse("system message should NOT be in messages", messagesJson.contains("\"role\":\"system\""));
    }

    @Test
    public void testSystemPromptInjection_OpenAIModel() {
        // Mock model cache to return OpenAI input schema
        when(modelCacheHelper.getModelInterface("gpt-4o"))
            .thenReturn(Map.of("input", openAIInputSchema));

        // Build request
        service.extractFactsFromConversation(messages, strategy, config, listener);

        // Verify system message in messages array
        ArgumentCaptor<MLPredictionTaskRequest> captor = ArgumentCaptor.forClass(MLPredictionTaskRequest.class);
        verify(client).execute(any(), captor.capture(), any());

        Map<String, String> params = getParameters(captor.getValue());
        String messagesJson = params.get("messages");

        assertTrue("system message should be in messages", messagesJson.contains("\"role\":\"system\""));
        assertTrue("system content should be text type", messagesJson.contains("\"type\":\"text\""));

        // Verify NOT in parameters
        assertNull("system_prompt should NOT be in parameters", params.get("system_prompt"));
    }

    @Test
    public void testSystemPromptInjection_NoSchema() {
        // Mock model cache to return null (no schema)
        when(modelCacheHelper.getModelInterface("custom-model"))
            .thenReturn(null);

        // Should fall back to Claude-style (legacy behavior)
        service.extractFactsFromConversation(messages, strategy, config, listener);

        ArgumentCaptor<MLPredictionTaskRequest> captor = ArgumentCaptor.forClass(MLPredictionTaskRequest.class);
        verify(client).execute(any(), captor.capture(), any());

        Map<String, String> params = getParameters(captor.getValue());
        assertNotNull("system_prompt should be in parameters (fallback)", params.get("system_prompt"));
    }
}
```

**Expected Tests**: ~8 integration tests

---

## Expected Log Output

### Success Case: Claude Model
```
[MEMORY_PROMPT_INJECTION] Model claude-3-7: Using system_prompt parameter (prompt length: 342 chars)
[LLM_REQUEST] Processing 5 messages
```

### Success Case: OpenAI Model
```
[MEMORY_PROMPT_INJECTION] Model gpt-4o: Injecting system message (prompt length: 342 chars)
[MESSAGE_CONTENT_PROCESSING] Standard content block at index 0 with type: text
[MESSAGE_CONTENT_PROCESSING] Standard content block at index 1 with type: text
[LLM_REQUEST] Processing 6 messages (includes injected system message)
```

### Content Processing Examples
```
[MESSAGE_CONTENT_PROCESSING] Standard content block at index 0 with type: text
[MESSAGE_CONTENT_PROCESSING] Standard content block at index 1 with type: image_url
[MESSAGE_CONTENT_PROCESSING] Wrapped user-defined object at index 2 as text block
```

### Fallback Case: No Schema
```
[MEMORY_PROMPT_INJECTION] No input schema found for model custom-model-id, using legacy system_prompt parameter
```

---

## Files Modified Summary

| File | Type | Lines | Purpose |
|------|------|-------|---------|
| `LlmInputSchemaHelper.java` | NEW | +120 | Detect input schema capabilities |
| `LlmInputSchemaHelperTest.java` | NEW | +200 | Test schema detection |
| `MemoryProcessingService.java` | MODIFY | +150, ~80 modified | Smart prompt injection & content processing |
| `MemoryProcessingServiceTest.java` | MODIFY | +180 | Test injection & processing |
| `MemoryProcessingServiceIntegrationTest.java` | NEW | +150 | Integration tests |
| **Total** | **5 files** | **~800 lines** | Schema-driven request formatting |

---

## Benefits

### 1. **Automatic Model Compatibility**
- ✅ OpenAI models work correctly (system message in array)
- ✅ Claude models work correctly (system_prompt parameter)
- ✅ No manual configuration per model
- ✅ Future models auto-detected from schema

### 2. **Flexible Content Handling**
- ✅ Standard multi-modal content (text, images, audio) preserved
- ✅ User-defined objects automatically serialized
- ✅ Graceful handling of mixed content
- ✅ No breaking changes to existing working content

### 3. **Single Source of Truth**
- ✅ Input schemas define API surface
- ✅ Output schemas define response structure
- ✅ No hardcoded model assumptions in code
- ✅ Schema updates propagate automatically

### 4. **Better Debugging**
- ✅ Clear logs show which injection method used
- ✅ Content processing is transparent
- ✅ Easy to trace issues to schema vs code
- ✅ Grep-friendly log prefixes

### 5. **Future-Proof**
- ✅ New models: just add schema, no code changes
- ✅ New content types: LLM handles via "type" field
- ✅ API changes: update schema, not code
- ✅ Easy to add model-specific customizations

---

## Risk Analysis

### Risk 1: Schema Missing or Malformed
**Probability**: Low (schemas are validated at model registration)
**Impact**: Medium (falls back to legacy behavior)
**Mitigation**:
- Graceful fallback to Claude-style (current behavior)
- Clear error logging
- Schema validation at registration time

### Risk 2: Breaking Changes for Existing Users
**Probability**: Very Low
**Impact**: Low
**Analysis**:
- Only changes behavior for OpenAI models (which currently don't work with system prompts)
- Claude models continue to work exactly as before
- Content processing only wraps non-standard objects
**Mitigation**: Extensive testing with real models

### Risk 3: Content Wrapping Changes LLM Behavior
**Probability**: Low
**Impact**: Low
**Analysis**:
- Only wraps objects without "type" field (non-standard usage)
- LLMs can parse JSON in text content
- Doesn't affect standard multi-modal content
**Mitigation**: Log when wrapping occurs, easy to debug

### Risk 4: Performance Impact
**Probability**: Very Low
**Impact**: Negligible
**Analysis**:
- Schema lookup cached (already done for output)
- Content processing is O(n) where n = number of content objects (usually 1-3)
- String operations are fast
**Mitigation**: Use efficient JSON parsing, reuse objects

---

## Comparison with Current State

### Current Approach: Hardcoded Assumptions

**Code**: `stringParameters.put("system_prompt", defaultPrompt);`

**Problems**:
- ❌ Doesn't work with OpenAI models
- ❌ Requires code changes for new models
- ❌ No validation of model capabilities
- ❌ Silent failures (wrong format sent to LLM)

**Maintenance Cost**: High (code changes per model)

---

### Proposed Approach: Schema-Driven

**Code**: `if (LlmInputSchemaHelper.hasSystemPromptParameter(schema)) { ... }`

**Benefits**:
- ✅ Works with OpenAI and Claude
- ✅ Zero code changes for new models
- ✅ Schema defines capabilities
- ✅ Clear logging of decision path

**Maintenance Cost**: Low (schema updates only)

---

## Design Principles Applied

### 1. **Sophisticated Yet Simple**
- Complex logic hidden in utility class
- Simple boolean check in main code
- Clear decision tree: schema says yes → do A, schema says no → do B

### 2. **High Readability**
```java
boolean hasSystemPromptParam = LlmInputSchemaHelper.hasSystemPromptParameter(inputSchema);
if (hasSystemPromptParam) {
    // Claude-style
} else {
    // OpenAI-style
}
```
Self-documenting code with clear intent

### 3. **Better Future Maintenance**
- Adding new model: Create schema file → Done
- Changing injection logic: Update helper method → All usages fixed
- Debugging: Log prefix `[MEMORY_PROMPT_INJECTION]` → Easy grep

### 4. **Consistent with Output Schema Pattern**
- Input schemas → Request formatting (this design)
- Output schemas → Response extraction (already implemented)
- Same pattern, same mental model

---

## Next Steps (When Implementing)

### Prerequisites
- ✅ Output schema automation completed (already done)
- ✅ Input schemas exist for OpenAI and Claude (already exist)
- ✅ MLModelCacheHelper can retrieve schemas (already works)

### Implementation Order
1. **Create LlmInputSchemaHelper** (~2 hours)
   - Write core detection methods
   - Add comprehensive unit tests
   - Verify with actual schema files

2. **Update System Prompt Injection** (~4 hours)
   - Extract prompt determination logic
   - Add schema-driven injection
   - Update all three methods (facts, decisions, summary)
   - Add integration tests

3. **Add Content Processing** (~3 hours)
   - Implement processing method
   - Integrate into message building
   - Add unit tests for all cases

4. **Manual Testing** (~2 hours)
   - Test with real OpenAI model
   - Test with real Claude model
   - Test with custom content objects
   - Verify logs are clear

5. **Documentation** (~1 hour)
   - Update API docs
   - Add logging guide
   - Update troubleshooting section

**Total Estimated Time**: ~12 hours

---

## Open Questions (To Resolve Before Implementation)

### Question 1: Should content processing be in MessageInput or MemoryProcessingService?
**Option A**: Add to `MessageInput.toXContent()`
- Pro: Automatic for all uses, centralized
- Con: Changes MessageInput globally, may affect other use cases

**Option B**: Add to `MemoryProcessingService` (proposed)
- Pro: Isolated to memory feature, easy to test
- Con: Must remember to call in all places

**Recommendation**: Start with Option B, can refactor to Option A later if needed

### Question 2: How to handle models with neither system_prompt nor messages?
**Current Proposal**: Fall back to system_prompt parameter (legacy)
**Alternative**: Throw error (fail fast)

**Recommendation**: Graceful fallback with warning log

### Question 3: Should we validate content after processing?
**Current Proposal**: No validation, let LLM reject if invalid
**Alternative**: Validate that all objects have "type" field after processing

**Recommendation**: No validation, trust LLM validation (simpler)

---

## Conclusion

This design leverages input schemas (already created for other purposes) to solve two current pain points:
1. System prompt placement incompatibility between OpenAI and Claude
2. User-defined content object handling

The solution follows the same pattern as output schema automation (already implemented and working), maintains backwards compatibility, and requires no code changes for future models.

**Design Status**: ✅ Ready for implementation (pending approval)

**Implementation Complexity**: Medium (similar to output schema work)

**Expected Impact**: High (enables OpenAI model support, cleaner architecture)

**Risk Level**: Low (graceful fallbacks, extensive testing)

---

**End of Design Checkpoint**
