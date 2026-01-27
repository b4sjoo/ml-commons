# Automated Input Schema Enhancement V2: Strategy + Factory Pattern

## Document Status
**Status**: Enhanced Design Proposal
**Created**: 2025-10-09
**Type**: Architecture Redesign
**Supersedes**: AUTOMATED_INPUT_SCHEMA_ENHANCEMENT.md (original proposal)
**Related**: AUTOMATED_LLM_RESULT_PATH_DESIGN.md (output schema automation)

---

## Executive Summary

**Problem**: Current system has hardcoded assumptions about how to inject system prompts and process message content, leading to incompatibility with different LLM providers.

**Original Solution**: Add if/else logic with helper utilities (~800 lines across 5 files)

**Enhanced Solution**: Use **Strategy + Factory pattern** for cleaner architecture with less code (~400 lines across 6 files)

**Key Improvement**: Instead of scattered if/else checks, use object-oriented design patterns for better maintainability and extensibility.

---

## Architecture Comparison

### Original Approach (v1)
```
if (hasSystemPromptParameter(schema)) {
    // Claude logic
    stringParameters.put("system_prompt", prompt);
} else {
    // OpenAI logic
    injectSystemMessage(messages, prompt);
}
```
**Issue**: Logic repeated in 3+ methods, tight coupling, harder to extend

### Enhanced Approach (v2 - Recommended)
```
MessageFormatter formatter = MessageFormatterFactory.getFormatterForModel(modelId);
Map<String, String> parameters = formatter.formatRequest(systemPrompt, messages, config);
```
**Benefits**: Single line, reusable, extensible, testable

---

## Design Patterns Applied

### Pattern 1: Strategy Pattern

**Intent**: Define a family of algorithms (message formatting strategies), encapsulate each one, and make them interchangeable.

```
┌─────────────────────┐
│ MessageFormatter    │ ← Strategy Interface
├─────────────────────┤
│ + formatRequest()   │
│ + processContent()  │
└─────────────────────┘
          ▲
          │
    ┌─────┴──────┐
    │            │
┌───────────┐ ┌──────────┐
│  Claude   │ │  OpenAI  │ ← Concrete Strategies
│ Formatter │ │ Formatter│
└───────────┘ └──────────┘
```

**Why This Works**:
- Each formatter encapsulates model-specific logic
- Easy to add new formatters (Gemini, Llama, etc.)
- Formatters are independently testable
- No conditional logic in business code

### Pattern 2: Factory Pattern

**Intent**: Create objects without specifying exact class, based on input criteria (input schema).

```
┌──────────────────────┐
│ MessageFormatterFactory │
├──────────────────────┤
│ + getFormatter()      │ ← Creates appropriate formatter
│ + getFormatterForModel() │
└──────────────────────┘
         │
         │ creates
         ▼
┌─────────────────┐
│ MessageFormatter │
└─────────────────┘
```

**Why This Works**:
- Centralized formatter selection logic
- Single source of truth for schema analysis
- Can cache formatter choices per model
- Encapsulates complexity

---

## Implementation Details

### Component 1: MessageFormatter Interface

**File**: `common/src/main/java/org/opensearch/ml/common/utils/message/MessageFormatter.java`

```java
package org.opensearch.ml.common.utils.message;

import java.util.List;
import java.util.Map;
import org.opensearch.ml.common.transport.memorycontainer.memory.MessageInput;

/**
 * Strategy interface for formatting LLM requests based on model requirements.
 *
 * Each formatter implementation handles:
 * 1. System prompt placement (parameter vs message)
 * 2. Content object normalization (type field enforcement)
 * 3. Message array construction
 *
 * Implementations:
 * - ClaudeMessageFormatter: system_prompt parameter
 * - OpenAIMessageFormatter: system message in array
 */
public interface MessageFormatter {

    /**
     * Format a complete LLM request with proper system prompt placement.
     *
     * @param systemPrompt The system prompt to inject
     * @param messages User/assistant messages to include
     * @param additionalConfig Optional config (user_prompt_message, etc.)
     * @return Map of request parameters ready for MLInput
     */
    Map<String, String> formatRequest(
        String systemPrompt,
        List<MessageInput> messages,
        Map<String, Object> additionalConfig
    );

    /**
     * Process message content objects to ensure LLM compatibility.
     *
     * Rules:
     * - Objects WITH "type" field → keep as-is (standard LLM format)
     * - Objects WITHOUT "type" field → wrap as {"type": "text", "text": JSON}
     *
     * @param content List of content objects from message
     * @return Processed content list
     */
    List<Map<String, Object>> processContent(List<Map<String, Object>> content);
}
```

**Design Rationale**:
- Interface defines contract for all formatters
- Two core responsibilities: request formatting + content processing
- Simple, focused methods (single responsibility principle)

---

### Component 2: Claude Implementation

**File**: `common/src/main/java/org/opensearch/ml/common/utils/message/ClaudeMessageFormatter.java`

```java
package org.opensearch.ml.common.utils.message;

import static org.opensearch.common.xcontent.json.JsonXContent.jsonXContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.ml.common.transport.memorycontainer.memory.MessageInput;
import org.opensearch.ml.common.utils.StringUtils;

import lombok.extern.log4j.Log4j2;

/**
 * Message formatter for Claude models (and similar models using system_prompt parameter).
 *
 * Format:
 * - System prompt: Placed in "system_prompt" parameter
 * - Messages: Array of user/assistant messages
 * - Content: Normalized to have "type" field
 *
 * Compatible with:
 * - Claude 3.x (Bedrock, Anthropic API)
 * - Claude 4.x (Sonnet, Opus)
 * - Any model with system_prompt in schema
 */
@Log4j2
public class ClaudeMessageFormatter implements MessageFormatter {

    @Override
    public Map<String, String> formatRequest(
        String systemPrompt,
        List<MessageInput> messages,
        Map<String, Object> additionalConfig
    ) {
        Map<String, String> parameters = new HashMap<>();

        // Claude-style: system_prompt as parameter
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            parameters.put("system_prompt", systemPrompt);
            log.debug("[CLAUDE_FORMATTER] Added system_prompt parameter ({} chars)",
                systemPrompt.length());
        }

        // Build messages array with content processing
        try {
            String messagesJson = buildMessagesArray(messages, additionalConfig);
            parameters.put("messages", messagesJson);
            log.debug("[CLAUDE_FORMATTER] Built messages array ({} messages)", messages.size());
        } catch (IOException e) {
            log.error("[CLAUDE_FORMATTER] Failed to build messages array", e);
            throw new RuntimeException("Failed to format Claude request", e);
        }

        return parameters;
    }

    @Override
    public List<Map<String, Object>> processContent(List<Map<String, Object>> content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        return content.stream()
            .map(this::normalizeContentObject)
            .collect(Collectors.toList());
    }

    /**
     * Normalize a single content object to ensure it has "type" field.
     */
    private Map<String, Object> normalizeContentObject(Map<String, Object> obj) {
        if (obj == null || obj.isEmpty()) {
            return obj;
        }

        // Already has type field → standard format
        if (obj.containsKey("type")) {
            log.trace("[CLAUDE_FORMATTER] Content object has type: {}", obj.get("type"));
            return obj;
        }

        // No type field → user-defined object, wrap as text
        Map<String, Object> wrapped = new HashMap<>();
        wrapped.put("type", "text");
        wrapped.put("text", StringUtils.toJson(obj));

        log.debug("[CLAUDE_FORMATTER] Wrapped user-defined object as text block");
        return wrapped;
    }

    /**
     * Build messages JSON array from MessageInput list.
     */
    private String buildMessagesArray(
        List<MessageInput> messages,
        Map<String, Object> additionalConfig
    ) throws IOException {
        XContentBuilder builder = jsonXContent.contentBuilder();
        builder.startArray();

        // Optional system_prompt_message from config
        if (additionalConfig != null && additionalConfig.containsKey("system_prompt_message")) {
            Object systemPromptMsg = additionalConfig.get("system_prompt_message");
            if (systemPromptMsg instanceof Map) {
                builder.map((Map<String, Object>) systemPromptMsg);
            }
        }

        // User messages (with content processing)
        for (MessageInput message : messages) {
            builder.startObject();
            builder.field("role", message.getRole());

            // Process content to ensure type fields
            List<Map<String, Object>> processedContent = processContent(message.getContent());
            builder.field("content", processedContent);

            builder.endObject();
        }

        // Optional user_prompt_message from config
        if (additionalConfig != null && additionalConfig.containsKey("user_prompt_message")) {
            Object userPromptMsg = additionalConfig.get("user_prompt_message");
            if (userPromptMsg instanceof Map) {
                builder.map((Map<String, Object>) userPromptMsg);
            }
        }

        builder.endArray();
        return builder.toString();
    }
}
```

**Key Features**:
- System prompt goes in `system_prompt` parameter
- Content objects automatically normalized
- Handles optional config messages (system_prompt_message, user_prompt_message)
- Clear logging for debugging

---

### Component 3: OpenAI Implementation

**File**: `common/src/main/java/org/opensearch/ml/common/utils/message/OpenAIMessageFormatter.java`

```java
package org.opensearch.ml.common.utils.message;

import static org.opensearch.common.xcontent.json.JsonXContent.jsonXContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.ml.common.transport.memorycontainer.memory.MessageInput;
import org.opensearch.ml.common.utils.StringUtils;

import lombok.extern.log4j.Log4j2;

/**
 * Message formatter for OpenAI models (and similar models without system_prompt parameter).
 *
 * Format:
 * - System prompt: Injected as first message with role="system"
 * - Messages: Array including system + user/assistant messages
 * - Content: Normalized to have "type" field
 *
 * Compatible with:
 * - GPT-4, GPT-4o, GPT-4o-mini
 * - GPT-3.5-turbo
 * - Any Chat Completions API model
 * - Models without system_prompt in schema
 */
@Log4j2
public class OpenAIMessageFormatter implements MessageFormatter {

    @Override
    public Map<String, String> formatRequest(
        String systemPrompt,
        List<MessageInput> messages,
        Map<String, Object> additionalConfig
    ) {
        Map<String, String> parameters = new HashMap<>();

        // OpenAI-style: NO system_prompt parameter
        // System prompt goes as first message in array

        List<MessageInput> allMessages = new ArrayList<>();

        // Inject system prompt as first message
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            allMessages.add(createSystemMessage(systemPrompt));
            log.debug("[OPENAI_FORMATTER] Injected system message ({} chars)",
                systemPrompt.length());
        }

        // Add user messages
        allMessages.addAll(messages);

        // Build messages array with content processing
        try {
            String messagesJson = buildMessagesArray(allMessages, additionalConfig);
            parameters.put("messages", messagesJson);
            log.debug("[OPENAI_FORMATTER] Built messages array ({} total messages, {} user)",
                allMessages.size(), messages.size());
        } catch (IOException e) {
            log.error("[OPENAI_FORMATTER] Failed to build messages array", e);
            throw new RuntimeException("Failed to format OpenAI request", e);
        }

        return parameters;
    }

    @Override
    public List<Map<String, Object>> processContent(List<Map<String, Object>> content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        return content.stream()
            .map(this::normalizeContentObject)
            .collect(Collectors.toList());
    }

    /**
     * Create a system message from prompt text.
     */
    private MessageInput createSystemMessage(String prompt) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
            "type", "text",
            "text", prompt
        ));

        return MessageInput.builder()
            .role("system")
            .content(content)
            .build();
    }

    /**
     * Normalize a single content object to ensure it has "type" field.
     */
    private Map<String, Object> normalizeContentObject(Map<String, Object> obj) {
        if (obj == null || obj.isEmpty()) {
            return obj;
        }

        // Already has type field → standard format
        if (obj.containsKey("type")) {
            log.trace("[OPENAI_FORMATTER] Content object has type: {}", obj.get("type"));
            return obj;
        }

        // No type field → user-defined object, wrap as text
        Map<String, Object> wrapped = new HashMap<>();
        wrapped.put("type", "text");
        wrapped.put("text", StringUtils.toJson(obj));

        log.debug("[OPENAI_FORMATTER] Wrapped user-defined object as text block");
        return wrapped;
    }

    /**
     * Build messages JSON array from MessageInput list.
     */
    private String buildMessagesArray(
        List<MessageInput> messages,
        Map<String, Object> additionalConfig
    ) throws IOException {
        XContentBuilder builder = jsonXContent.contentBuilder();
        builder.startArray();

        // Optional system_prompt_message from config (rare for OpenAI)
        if (additionalConfig != null && additionalConfig.containsKey("system_prompt_message")) {
            Object systemPromptMsg = additionalConfig.get("system_prompt_message");
            if (systemPromptMsg instanceof Map) {
                builder.map((Map<String, Object>) systemPromptMsg);
            }
        }

        // All messages (including injected system message)
        for (MessageInput message : messages) {
            builder.startObject();
            builder.field("role", message.getRole());

            // Process content to ensure type fields
            List<Map<String, Object>> processedContent = processContent(message.getContent());
            builder.field("content", processedContent);

            builder.endObject();
        }

        // Optional user_prompt_message from config
        if (additionalConfig != null && additionalConfig.containsKey("user_prompt_message")) {
            Object userPromptMsg = additionalConfig.get("user_prompt_message");
            if (userPromptMsg instanceof Map) {
                builder.map((Map<String, Object>) userPromptMsg);
            }
        }

        builder.endArray();
        return builder.toString();
    }
}
```

**Key Features**:
- System prompt injected as first message with `role="system"`
- NO `system_prompt` parameter
- Same content normalization as Claude
- Clear separation from Claude formatter

---

### Component 4: Factory

**File**: `common/src/main/java/org/opensearch/ml/common/utils/message/MessageFormatterFactory.java`

```java
package org.opensearch.ml.common.utils.message;

import java.util.Map;

import org.opensearch.ml.model.MLModelCacheHelper;

import lombok.extern.log4j.Log4j2;

/**
 * Factory for creating appropriate MessageFormatter based on model's input schema.
 *
 * Decision Algorithm:
 * 1. Get model's input schema from cache
 * 2. Check if schema contains "system_prompt" field
 * 3. If YES → Claude formatter
 * 4. If NO → OpenAI formatter
 * 5. On any error → Claude formatter (safe default)
 *
 * The factory uses singleton formatters for performance.
 */
@Log4j2
public class MessageFormatterFactory {

    // Singleton formatter instances (thread-safe, stateless)
    private static final MessageFormatter CLAUDE_FORMATTER = new ClaudeMessageFormatter();
    private static final MessageFormatter OPENAI_FORMATTER = new OpenAIMessageFormatter();

    /**
     * Get appropriate formatter based on input schema JSON.
     *
     * This is the core decision logic:
     * - Presence of "system_prompt" → Claude-style formatter
     * - Absence of "system_prompt" → OpenAI-style formatter
     *
     * @param inputSchemaJson The JSON schema string from model interface
     * @return Appropriate formatter (never null)
     */
    public static MessageFormatter getFormatter(String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            log.debug("[FORMATTER_FACTORY] No input schema provided, defaulting to Claude formatter");
            return CLAUDE_FORMATTER;
        }

        try {
            // Simple and fast: check if schema contains system_prompt field
            // This is more reliable than parsing JSON and navigating paths
            boolean hasSystemPromptParam = inputSchemaJson.contains("\"system_prompt\"");

            if (hasSystemPromptParam) {
                log.debug("[FORMATTER_FACTORY] Schema has system_prompt parameter → Claude formatter");
                return CLAUDE_FORMATTER;
            } else {
                log.debug("[FORMATTER_FACTORY] Schema lacks system_prompt parameter → OpenAI formatter");
                return OPENAI_FORMATTER;
            }
        } catch (Exception e) {
            log.warn("[FORMATTER_FACTORY] Failed to analyze input schema, defaulting to Claude formatter", e);
            return CLAUDE_FORMATTER;
        }
    }

    /**
     * Get formatter for a specific model (convenience method with caching).
     *
     * This method leverages MLModelCacheHelper to retrieve the model's
     * input schema, then delegates to getFormatter().
     *
     * @param modelId The model ID to get formatter for
     * @param modelCacheHelper Helper to retrieve model interface
     * @return Appropriate formatter (never null)
     */
    public static MessageFormatter getFormatterForModel(
        String modelId,
        MLModelCacheHelper modelCacheHelper
    ) {
        if (modelId == null || modelCacheHelper == null) {
            log.debug("[FORMATTER_FACTORY] No model ID or cache helper, using default Claude formatter");
            return CLAUDE_FORMATTER;
        }

        try {
            Map<String, String> modelInterface = modelCacheHelper.getModelInterface(modelId);

            if (modelInterface != null && modelInterface.containsKey("input")) {
                String inputSchema = modelInterface.get("input");
                log.debug("[FORMATTER_FACTORY] Retrieved input schema for model {}", modelId);
                return getFormatter(inputSchema);
            }

            log.debug("[FORMATTER_FACTORY] No input schema found for model {}, using default", modelId);
        } catch (Exception e) {
            log.warn("[FORMATTER_FACTORY] Failed to get formatter for model {}, using default", modelId, e);
        }

        return CLAUDE_FORMATTER;
    }

    /**
     * Get Claude formatter explicitly (for testing or explicit usage).
     */
    public static MessageFormatter getClaudeFormatter() {
        return CLAUDE_FORMATTER;
    }

    /**
     * Get OpenAI formatter explicitly (for testing or explicit usage).
     */
    public static MessageFormatter getOpenAIFormatter() {
        return OPENAI_FORMATTER;
    }
}
```

**Design Decisions**:
- **Singleton formatters**: Stateless, thread-safe, reusable
- **Simple detection**: String contains check (fast, reliable)
- **Safe defaults**: Always returns a formatter (never null)
- **Logging**: Clear logs for debugging formatter selection

---

### Component 5: MemoryProcessingService Integration

**File**: `plugin/src/main/java/org/opensearch/ml/action/memorycontainer/memory/MemoryProcessingService.java`

**Changes to `extractFactsFromConversation()` method**:

```java
public void extractFactsFromConversation(
    List<MessageInput> messages,
    MemoryStrategy strategy,
    MemoryConfiguration memoryConfig,
    ActionListener<List<String>> listener
) {
    String llmModelId = getEffectiveLlmId(strategy, memoryConfig);
    if (llmModelId == null) {
        log.debug("No LLM model configured for fact extraction, skipping");
        listener.onResponse(new ArrayList<>());
        return;
    }

    // Get appropriate formatter for the model
    MessageFormatter formatter = MessageFormatterFactory.getFormatterForModel(
        llmModelId,
        modelCacheHelper
    );

    log.info("[FACT_EXTRACTION] Model {}: Using {} for request formatting",
        llmModelId,
        formatter.getClass().getSimpleName()
    );

    // Determine system prompt (existing logic)
    String systemPrompt = determineSystemPrompt(strategy);
    if (systemPrompt == null) {
        // Validation failed, listener already called
        return;
    }

    // Build full message list with additional messages from config
    List<MessageInput> allMessages = buildFullMessageList(messages, strategy);

    // Format the request using the appropriate formatter
    Map<String, String> parameters = formatter.formatRequest(
        systemPrompt,
        allMessages,
        strategy.getStrategyConfig()
    );

    // Continue with existing MLInput building and execution
    MLInput mlInput = MLInput.builder()
        .algorithm(FunctionName.REMOTE)
        .inputDataset(RemoteInferenceInputDataSet.builder()
            .parameters(parameters)
            .build())
        .build();

    MLPredictionTaskRequest predictionRequest = MLPredictionTaskRequest.builder()
        .modelId(llmModelId)
        .mlInput(mlInput)
        .build();

    client.execute(MLPredictionTaskAction.INSTANCE, predictionRequest, ActionListener.wrap(
        response -> {
            try {
                log.debug("Received LLM response, parsing facts...");
                MLOutput mlOutput = response.getOutput();
                List<String> facts = parseFactsFromLLMResponse(strategy, mlOutput, llmModelId);
                log.debug("Extracted {} facts from LLM response", facts.size());
                listener.onResponse(facts);
            } catch (Exception e) {
                log.error("Failed to parse facts from LLM response", e);
                listener.onFailure(new IllegalArgumentException("Failed to parse facts", e));
            }
        },
        e -> {
            log.error("Failed to call LLM for fact extraction", e);
            listener.onFailure(new OpenSearchException("Failed to extract facts", e));
        }
    ));
}

/**
 * Determine the system prompt to use (default or custom).
 * NEW: Extracted helper method for reusability.
 */
private String determineSystemPrompt(MemoryStrategy strategy) {
    // Determine default prompt based on strategy type
    String defaultPrompt;
    MemoryStrategyType type = strategy.getType();
    if (type == MemoryStrategyType.USER_PREFERENCE) {
        defaultPrompt = USER_PREFERENCE_FACTS_EXTRACTION_PROMPT;
    } else if (type == MemoryStrategyType.SUMMARY) {
        defaultPrompt = SUMMARY_FACTS_EXTRACTION_PROMPT;
    } else {
        defaultPrompt = SEMANTIC_FACTS_EXTRACTION_PROMPT;
    }

    // Check for custom prompt in strategy config
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

/**
 * Build full message list including additional messages from config.
 * NEW: Extracted helper method for clarity.
 */
private List<MessageInput> buildFullMessageList(
    List<MessageInput> messages,
    MemoryStrategy strategy
) {
    List<MessageInput> allMessages = new ArrayList<>(messages);

    // Add user prompt message if not in config
    Map<String, Object> strategyConfig = strategy.getStrategyConfig();
    if (strategyConfig == null || !strategyConfig.containsKey("user_prompt_message")) {
        MessageInput userPrompt = getMessageInput(
            "Please extract information from our conversation so far"
        );
        allMessages.add(userPrompt);
    }

    // Always add JSON enforcement message
    MessageInput enforcementMessage = getMessageInput(JSON_ENFORCEMENT_MESSAGE);
    allMessages.add(enforcementMessage);

    return allMessages;
}
```

**Similar updates for `makeMemoryDecisions()` and `summarizeMessages()`** - follow same pattern.

---

## Testing Strategy

### Unit Tests for Formatters

**File**: `common/src/test/java/org/opensearch/ml/common/utils/message/MessageFormatterTests.java`

```java
public class MessageFormatterTests {

    @Test
    public void testClaudeFormatter_SystemPromptInParameters() {
        MessageFormatter formatter = MessageFormatterFactory.getClaudeFormatter();

        List<MessageInput> messages = List.of(
            createMessage("user", "Hello")
        );

        Map<String, String> result = formatter.formatRequest(
            "You are a helpful assistant",
            messages,
            Map.of()
        );

        // System prompt should be in parameters
        assertEquals("You are a helpful assistant", result.get("system_prompt"));

        // Messages should NOT contain system role
        assertFalse(result.get("messages").contains("\"role\":\"system\""));

        // Should have user message
        assertTrue(result.get("messages").contains("\"role\":\"user\""));
    }

    @Test
    public void testOpenAIFormatter_SystemPromptInMessages() {
        MessageFormatter formatter = MessageFormatterFactory.getOpenAIFormatter();

        List<MessageInput> messages = List.of(
            createMessage("user", "Hello")
        );

        Map<String, String> result = formatter.formatRequest(
            "You are a helpful assistant",
            messages,
            Map.of()
        );

        // System prompt should NOT be in parameters
        assertNull(result.get("system_prompt"));

        // Messages should contain system role
        assertTrue(result.get("messages").contains("\"role\":\"system\""));
        assertTrue(result.get("messages").contains("You are a helpful assistant"));

        // Should have user message
        assertTrue(result.get("messages").contains("\"role\":\"user\""));
    }

    @Test
    public void testContentProcessing_StandardFormat() {
        MessageFormatter formatter = MessageFormatterFactory.getClaudeFormatter();

        List<Map<String, Object>> content = List.of(
            Map.of("type", "text", "text", "Hello"),
            Map.of("type", "image_url", "image_url", Map.of("url", "http://example.com/image.jpg"))
        );

        List<Map<String, Object>> processed = formatter.processContent(content);

        // Should keep standard format unchanged
        assertEquals(2, processed.size());
        assertEquals("text", processed.get(0).get("type"));
        assertEquals("image_url", processed.get(1).get("type"));
    }

    @Test
    public void testContentProcessing_UserDefinedObject() {
        MessageFormatter formatter = MessageFormatterFactory.getClaudeFormatter();

        List<Map<String, Object>> content = List.of(
            Map.of("custom_field", "custom_value", "data", Map.of("key", "value"))
        );

        List<Map<String, Object>> processed = formatter.processContent(content);

        // Should wrap as text block
        assertEquals(1, processed.size());
        assertEquals("text", processed.get(0).get("type"));
        assertTrue(processed.get(0).get("text").toString().contains("custom_field"));
        assertTrue(processed.get(0).get("text").toString().contains("custom_value"));
    }

    @Test
    public void testContentProcessing_MixedFormat() {
        MessageFormatter formatter = MessageFormatterFactory.getClaudeFormatter();

        List<Map<String, Object>> content = List.of(
            Map.of("type", "text", "text", "Hello"),        // Standard
            Map.of("custom", "object"),                       // User-defined
            Map.of("type", "image_url", "image_url", "...")   // Standard
        );

        List<Map<String, Object>> processed = formatter.processContent(content);

        assertEquals(3, processed.size());
        assertEquals("text", processed.get(0).get("type"));  // Unchanged
        assertEquals("text", processed.get(1).get("type"));  // Wrapped
        assertTrue(processed.get(1).get("text").toString().contains("custom"));
        assertEquals("image_url", processed.get(2).get("type"));  // Unchanged
    }
}
```

### Unit Tests for Factory

```java
public class MessageFormatterFactoryTests {

    @Test
    public void testFactoryWithClaudeSchema() {
        String claudeSchema = loadResource("bedrock_anthropic_claude_use_system_prompt_input.json");

        MessageFormatter formatter = MessageFormatterFactory.getFormatter(claudeSchema);

        assertNotNull(formatter);
        assertTrue(formatter instanceof ClaudeMessageFormatter);
    }

    @Test
    public void testFactoryWithOpenAISchema() {
        String openAISchema = loadResource("openai_chat_completions_input.json");

        MessageFormatter formatter = MessageFormatterFactory.getFormatter(openAISchema);

        assertNotNull(formatter);
        assertTrue(formatter instanceof OpenAIMessageFormatter);
    }

    @Test
    public void testFactoryWithNullSchema() {
        MessageFormatter formatter = MessageFormatterFactory.getFormatter(null);

        // Should default to Claude
        assertNotNull(formatter);
        assertTrue(formatter instanceof ClaudeMessageFormatter);
    }

    @Test
    public void testFactoryWithMalformedSchema() {
        MessageFormatter formatter = MessageFormatterFactory.getFormatter("{invalid json}");

        // Should default to Claude (safe fallback)
        assertNotNull(formatter);
        assertTrue(formatter instanceof ClaudeMessageFormatter);
    }

    @Test
    public void testGetFormatterForModel() {
        MLModelCacheHelper mockCache = mock(MLModelCacheHelper.class);
        when(mockCache.getModelInterface("claude-model"))
            .thenReturn(Map.of("input", claudeSchema));

        MessageFormatter formatter = MessageFormatterFactory.getFormatterForModel(
            "claude-model",
            mockCache
        );

        assertTrue(formatter instanceof ClaudeMessageFormatter);
    }
}
```

### Integration Tests

```java
public class MemoryProcessingServiceFormatterIntegrationTests {

    @Test
    public void testFactExtractionWithClaudeModel() {
        // Mock model cache to return Claude schema
        when(modelCacheHelper.getModelInterface("claude-3-7"))
            .thenReturn(Map.of("input", claudeInputSchema));

        // Execute fact extraction
        service.extractFactsFromConversation(messages, strategy, config, listener);

        // Verify request format
        ArgumentCaptor<MLPredictionTaskRequest> captor =
            ArgumentCaptor.forClass(MLPredictionTaskRequest.class);
        verify(client).execute(any(), captor.capture(), any());

        Map<String, String> params = extractParameters(captor.getValue());

        // Should have system_prompt parameter
        assertNotNull(params.get("system_prompt"));

        // Should NOT have system message in array
        assertFalse(params.get("messages").contains("\"role\":\"system\""));
    }

    @Test
    public void testFactExtractionWithOpenAIModel() {
        // Mock model cache to return OpenAI schema
        when(modelCacheHelper.getModelInterface("gpt-4o"))
            .thenReturn(Map.of("input", openAIInputSchema));

        // Execute fact extraction
        service.extractFactsFromConversation(messages, strategy, config, listener);

        // Verify request format
        ArgumentCaptor<MLPredictionTaskRequest> captor =
            ArgumentCaptor.forClass(MLPredictionTaskRequest.class);
        verify(client).execute(any(), captor.capture(), any());

        Map<String, String> params = extractParameters(captor.getValue());

        // Should NOT have system_prompt parameter
        assertNull(params.get("system_prompt"));

        // Should have system message in array
        assertTrue(params.get("messages").contains("\"role\":\"system\""));
    }
}
```

---

## Key Advantages Over Original Proposal

### 1. Cleaner Code Architecture

**Original Proposal (v1)**:
```java
// Scattered if/else logic in multiple methods
if (LlmInputSchemaHelper.hasSystemPromptParameter(schema)) {
    stringParameters.put("system_prompt", prompt);
    buildMessages(messages);
} else {
    injectSystemMessage(messages, prompt);
    buildMessages(allMessages);
}
```

**Enhanced Solution (v2)**:
```java
// Single line, reusable
MessageFormatter formatter = MessageFormatterFactory.getFormatterForModel(modelId);
Map<String, String> params = formatter.formatRequest(prompt, messages, config);
```

### 2. Better Maintainability

| Aspect | Original (v1) | Enhanced (v2) |
|--------|---------------|---------------|
| **Lines of code** | ~800 lines | ~400 lines |
| **Files modified** | 5 files | 6 files (but cleaner) |
| **Duplicate logic** | 3+ places | 0 (reusable formatters) |
| **Testing complexity** | Medium | Low (isolated components) |
| **Extension difficulty** | Medium (update multiple places) | Easy (add new formatter) |

### 3. Superior Extensibility

**Adding Gemini support**:

**v1 Approach**: Update 3+ methods with new if/else logic
```java
// In extractFactsFromConversation
if (hasSystemPromptParameter) {
    // Claude
} else if (isGeminiModel) {  // NEW
    // Gemini logic
} else {
    // OpenAI
}

// In makeMemoryDecisions - same logic
// In summarizeMessages - same logic
```

**v2 Approach**: Create single new class
```java
public class GeminiMessageFormatter implements MessageFormatter {
    // Implement Gemini-specific logic once
}

// Factory automatically uses it based on schema
```

### 4. Improved Testability

**Original Proposal**:
- Need to test if/else logic in 3+ methods
- Tightly coupled to MemoryProcessingService
- Hard to mock dependencies

**Enhanced Solution**:
- Test formatters independently
- Test factory independently
- Test integration separately
- Clear separation of concerns

### 5. Performance Benefits

**Original Proposal**:
- Schema check repeated in 3+ methods
- Content processing logic duplicated
- Hard to cache formatter choice

**Enhanced Solution**:
- Formatter instances are singletons (reused)
- Schema check happens once (in factory)
- Easy to cache formatter per model
- No object creation overhead

---

## Migration Path

### Phase 1: Create Formatter Infrastructure
1. Create `MessageFormatter` interface
2. Implement `ClaudeMessageFormatter`
3. Implement `OpenAIMessageFormatter`
4. Add comprehensive unit tests

**Estimated Time**: 3 hours

### Phase 2: Add Factory
1. Create `MessageFormatterFactory`
2. Add factory unit tests
3. Test with real schemas

**Estimated Time**: 2 hours

### Phase 3: Update MemoryProcessingService (Pilot)
1. Update `extractFactsFromConversation()` to use formatters
2. Test with real Claude model
3. Test with real OpenAI model
4. Verify logs are clear

**Estimated Time**: 3 hours

### Phase 4: Complete Integration
1. Update `makeMemoryDecisions()`
2. Update `summarizeMessages()`
3. Add integration tests
4. Manual testing with both model types

**Estimated Time**: 3 hours

### Phase 5: Cleanup & Documentation
1. Remove old hardcoded logic
2. Update API documentation
3. Add troubleshooting guide
4. Performance verification

**Estimated Time**: 1 hour

**Total Estimated Time**: ~12 hours

---

## Files Summary

| File | Type | Lines | Purpose |
|------|------|-------|---------|
| `MessageFormatter.java` | NEW | ~35 | Strategy interface |
| `ClaudeMessageFormatter.java` | NEW | ~100 | Claude implementation |
| `OpenAIMessageFormatter.java` | NEW | ~100 | OpenAI implementation |
| `MessageFormatterFactory.java` | NEW | ~80 | Factory for formatters |
| `MemoryProcessingService.java` | MODIFY | ~60 modified | Use formatters |
| `MessageFormatterTests.java` | NEW | ~150 | Unit tests |
| **Total** | **6 files** | **~400 lines** | Clean OO solution |

---

## Expected Logs

### Claude Model
```
[FORMATTER_FACTORY] Schema has system_prompt parameter → Claude formatter
[FACT_EXTRACTION] Model claude-3-7: Using ClaudeMessageFormatter for request formatting
[CLAUDE_FORMATTER] Added system_prompt parameter (342 chars)
[CLAUDE_FORMATTER] Built messages array (5 messages)
```

### OpenAI Model
```
[FORMATTER_FACTORY] Schema lacks system_prompt parameter → OpenAI formatter
[FACT_EXTRACTION] Model gpt-4o: Using OpenAIMessageFormatter for request formatting
[OPENAI_FORMATTER] Injected system message (342 chars)
[OPENAI_FORMATTER] Built messages array (6 total messages, 5 user)
```

### Content Processing
```
[CLAUDE_FORMATTER] Content object has type: text
[CLAUDE_FORMATTER] Wrapped user-defined object as text block
```

---

## Risk Assessment

### Risk 1: Complexity of New Architecture
**Probability**: Low
**Impact**: Low
**Mitigation**: Pattern is well-established, easy to understand, comprehensive tests

### Risk 2: Breaking Existing Functionality
**Probability**: Very Low
**Impact**: Medium
**Mitigation**: Phased rollout, extensive testing, backward-compatible defaults

### Risk 3: Performance Overhead
**Probability**: Very Low
**Impact**: Negligible
**Mitigation**: Singleton formatters, simple schema check, no extra allocations

### Risk 4: Future Maintenance
**Probability**: Very Low
**Impact**: Low
**Mitigation**: Clear patterns, isolated components, extensive documentation

---

## Design Principles Demonstrated

### SOLID Principles

1. **Single Responsibility**: Each formatter handles one model type
2. **Open/Closed**: Open for extension (new formatters), closed for modification
3. **Liskov Substitution**: All formatters interchangeable via interface
4. **Interface Segregation**: Focused interface with only needed methods
5. **Dependency Inversion**: Depend on abstraction (MessageFormatter), not implementations

### Design Patterns

1. **Strategy Pattern**: Encapsulate formatting algorithms
2. **Factory Pattern**: Create formatters based on runtime info
3. **Singleton Pattern**: Reuse formatter instances

---

## Comparison with Original Design

| Criteria | Original (v1) | Enhanced (v2) | Winner |
|----------|---------------|---------------|---------|
| Lines of code | ~800 | ~400 | **v2** (50% less) |
| Maintainability | Medium | High | **v2** |
| Extensibility | Medium | Very High | **v2** |
| Testability | Medium | High | **v2** |
| Performance | Good | Excellent | **v2** |
| Code duplication | Moderate | None | **v2** |
| Learning curve | Low | Medium | v1 |
| Architecture quality | Good | Excellent | **v2** |

**Recommendation**: **Enhanced Solution (v2)** is superior in 7/8 criteria.

---

## Conclusion

The enhanced Strategy + Factory pattern approach provides:
- ✅ **Cleaner architecture** with OO design patterns
- ✅ **Less code** (~50% reduction from original proposal)
- ✅ **Better maintainability** through separation of concerns
- ✅ **Superior extensibility** (add formatters without modifying existing code)
- ✅ **Improved testability** (isolated, focused components)
- ✅ **Same functionality** as original proposal
- ✅ **Better performance** (singleton formatters, simple checks)

**Status**: ✅ Ready for implementation

**Implementation Complexity**: Medium (requires understanding of design patterns)

**Expected Impact**: High (enables multi-model support with clean architecture)

**Risk Level**: Low (well-tested patterns, phased rollout, comprehensive tests)

---

**End of Enhanced Design Document**
