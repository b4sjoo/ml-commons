/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.utils;

import lombok.extern.log4j.Log4j2;
import org.opensearch.ml.common.MLModel;

import java.util.Map;

@Log4j2
public class ModelInterfaceUtils {

    private static final String GENERAL_CONVERSATIONAL_MODEL_INTERFACE_INPUT = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"parameters\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "                \"inputs\": {\n" +
            "                    \"type\": \"string\"\n" +
            "                }\n" +
            "            },\n" +
            "            \"required\": [\n" +
            "                \"inputs\"\n" +
            "            ]\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\n" +
            "        \"parameters\"\n" +
            "    ]\n" +
            "}";

    private static final String GENERAL_EMBEDDING_MODEL_INTERFACE_INPUT = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"parameters\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "                \"texts\": {\n" +
            "                    \"type\": \"array\",\n" +
            "                    \"items\": {\n" +
            "                        \"type\": \"string\"\n" +
            "                    }\n" +
            "                }\n" +
            "            },\n" +
            "            \"required\": [\n" +
            "                \"texts\"\n" +
            "            ]\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\n" +
            "        \"parameters\"\n" +
            "    ]\n" +
            "}";

    private static final String GENERAL_CONVERSATIONAL_MODEL_INTERFACE_OUTPUT = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"inference_results\": {\n" +
            "            \"type\": \"array\",\n" +
            "            \"items\": {\n" +
            "                \"type\": \"object\",\n" +
            "                \"properties\": {\n" +
            "                    \"output\": {\n" +
            "                        \"type\": \"array\",\n" +
            "                        \"items\": {\n" +
            "                            \"type\": \"object\",\n" +
            "                            \"properties\": {\n" +
            "                                \"name\": {\n" +
            "                                    \"type\": \"string\"\n" +
            "                                },\n" +
            "                                \"dataAsMap\": {\n" +
            "                                    \"type\": \"object\",\n" +
            "                                    \"properties\": {\n" +
            "                                        \"response\": {\n" +
            "                                            \"type\": \"string\"\n" +
            "                                        }\n" +
            "                                    },\n" +
            "                                    \"required\": [\n" +
            "                                        \"response\"\n" +
            "                                    ]\n" +
            "                                }\n" +
            "                            },\n" +
            "                            \"required\": [\n" +
            "                                \"name\",\n" +
            "                                \"dataAsMap\"\n" +
            "                            ]\n" +
            "                        }\n" +
            "                    },\n" +
            "                    \"status_code\": {\n" +
            "                        \"type\": \"integer\"\n" +
            "                    }\n" +
            "                },\n" +
            "                \"required\": [\n" +
            "                    \"output\",\n" +
            "                    \"status_code\"\n" +
            "                ]\n" +
            "            }\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\n" +
            "        \"inference_results\"\n" +
            "    ]\n" +
            "}";

    private static final String BEDROCK_ANTHROPIC_CLAUDE_V2_MODEL_INTERFACE_OUTPUT = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"inference_results\": {\n" +
            "            \"type\": \"array\",\n" +
            "            \"items\": {\n" +
            "                \"type\": \"object\",\n" +
            "                \"properties\": {\n" +
            "                    \"output\": {\n" +
            "                        \"type\": \"array\",\n" +
            "                        \"items\": {\n" +
            "                            \"type\": \"object\",\n" +
            "                            \"properties\": {\n" +
            "                                \"name\": {\n" +
            "                                    \"type\": \"string\"\n" +
            "                                },\n" +
            "                                \"dataAsMap\": {\n" +
            "                                    \"type\": \"object\",\n" +
            "                                    \"properties\": {\n" +
            "                                        \"type\": {\n" +
            "                                            \"type\": \"string\"\n" +
            "                                        },\n" +
            "                                        \"completion\": {\n" +
            "                                            \"type\": \"string\"\n" +
            "                                        },\n" +
            "                                        \"stop_reason\": {\n" +
            "                                            \"type\": \"string\"\n" +
            "                                        },\n" +
            "                                        \"stop\": {\n" +
            "                                            \"type\": \"string\"\n" +
            "                                        }\n" +
            "                                    },\n" +
            "                                    \"required\": [\n" +
            "                                        \"type\",\n" +
            "                                        \"completion\",\n" +
            "                                        \"stop_reason\",\n" +
            "                                        \"stop\"\n" +
            "                                    ]\n" +
            "                                }\n" +
            "                            },\n" +
            "                            \"required\": [\n" +
            "                                \"name\",\n" +
            "                                \"dataAsMap\"\n" +
            "                            ]\n" +
            "                        }\n" +
            "                    },\n" +
            "                    \"status_code\": {\n" +
            "                        \"type\": \"integer\"\n" +
            "                    }\n" +
            "                },\n" +
            "                \"required\": [\n" +
            "                    \"output\",\n" +
            "                    \"status_code\"\n" +
            "                ]\n" +
            "            }\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\n" +
            "        \"inference_results\"\n" +
            "    ]\n" +
            "}";

    private static final String GENERAL_EMBEDDING_MODEL_INTERFACE_OUTPUT = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"inference_results\": {\n" +
            "            \"type\": \"array\",\n" +
            "            \"items\": {\n" +
            "                \"type\": \"object\",\n" +
            "                \"properties\": {\n" +
            "                    \"output\": {\n" +
            "                        \"type\": \"array\",\n" +
            "                        \"items\": {\n" +
            "                            \"type\": \"object\",\n" +
            "                            \"properties\": {\n" +
            "                                \"name\": {\n" +
            "                                    \"type\": \"string\"\n" +
            "                                },\n" +
            "                                \"data_type\": {\n" +
            "                                    \"type\": \"string\"\n" +
            "                                },\n" +
            "                                \"shape\": {\n" +
            "                                    \"type\": \"array\",\n" +
            "                                    \"items\": {\n" +
            "                                        \"type\": \"integer\"\n" +
            "                                    }\n" +
            "                                },\n" +
            "                                \"data\": {\n" +
            "                                    \"type\": \"array\",\n" +
            "                                    \"items\": {\n" +
            "                                        \"type\": \"number\"\n" +
            "                                    }\n" +
            "                                }\n" +
            "                            },\n" +
            "                            \"required\": [\n" +
            "                                \"name\",\n" +
            "                                \"data_type\",\n" +
            "                                \"shape\",\n" +
            "                                \"data\"\n" +
            "                            ]\n" +
            "                        }\n" +
            "                    },\n" +
            "                    \"status_code\": {\n" +
            "                        \"type\": \"integer\"\n" +
            "                    }\n" +
            "                },\n" +
            "                \"required\": [\n" +
            "                    \"output\",\n" +
            "                    \"status_code\"\n" +
            "                ]\n" +
            "            }\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\n" +
            "        \"inference_results\"\n" +
            "    ]\n" +
            "}";

    private static final String TITAN_TEXT_EMBEDDING_MODEL_INTERFACE_INPUT = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"parameters\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "                \"inputText\": {\n" +
            "                    \"type\": \"string\"\n" +
            "                }\n" +
            "            },\n" +
            "            \"required\": [\n" +
            "                \"inputText\"\n" +
            "            ]\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\n" +
            "        \"parameters\"\n" +
            "    ]\n" +
            "}";

    private static final String TITAN_MULTI_MODAL_EMBEDDING_MODEL_INTERFACE_INPUT = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"parameters\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "                \"inputText\": {\n" +
            "                    \"type\": \"string\"\n" +
            "                },\n" +
            "                \"inputImage\": {\n" +
            "                    \"type\": \"string\"\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "    },\n" +
            "    \"required\": [\n" +
            "        \"parameters\"\n" +
            "    ]\n" +
            "}";

    public static final Map<String, String> BEDROCK_AI21_LABS_JURASSIC2_MID_V1_MODEL_INTERFACE = Map.of(
            "input",
            GENERAL_CONVERSATIONAL_MODEL_INTERFACE_INPUT,
            "output",
            GENERAL_CONVERSATIONAL_MODEL_INTERFACE_OUTPUT
            );

    public static final Map<String, String> BEDROCK_ANTHROPIC_CLAUDE_V3_SONNET_MODEL_INTERFACE = Map.of(
            "input",
            GENERAL_CONVERSATIONAL_MODEL_INTERFACE_INPUT,
            "output",
            GENERAL_CONVERSATIONAL_MODEL_INTERFACE_OUTPUT
    );

    public static final Map<String, String> BEDROCK_ANTHROPIC_CLAUDE_V2_MODEL_INTERFACE = Map.of(
            "input",
            GENERAL_CONVERSATIONAL_MODEL_INTERFACE_INPUT,
            "output",
            BEDROCK_ANTHROPIC_CLAUDE_V2_MODEL_INTERFACE_OUTPUT
    );

    public static final Map<String, String> BEDROCK_COHERE_EMBED_ENGLISH_V3_MODEL_INTERFACE = Map.of(
            "input",
            GENERAL_EMBEDDING_MODEL_INTERFACE_INPUT,
            "output",
            GENERAL_EMBEDDING_MODEL_INTERFACE_OUTPUT
    );

    public static final Map<String, String> BEDROCK_COHERE_EMBED_MULTILINGUAL_V3_MODEL_INTERFACE = Map.of(
            "input",
            GENERAL_EMBEDDING_MODEL_INTERFACE_INPUT,
            "output",
            GENERAL_EMBEDDING_MODEL_INTERFACE_OUTPUT
    );

    public static final Map<String, String> BEDROCK_TITAN_EMBED_TEXT_V1_MODEL_INTERFACE = Map.of(
            "input",
            TITAN_TEXT_EMBEDDING_MODEL_INTERFACE_INPUT,
            "output",
            GENERAL_EMBEDDING_MODEL_INTERFACE_OUTPUT
    );

    public static final Map<String, String> BEDROCK_TITAN_EMBED_MULTI_MODAL_V1_MODEL_INTERFACE = Map.of(
            "input",
            TITAN_MULTI_MODAL_EMBEDDING_MODEL_INTERFACE_INPUT,
            "output",
            GENERAL_EMBEDDING_MODEL_INTERFACE_OUTPUT
    );


    public static Map<String, Object> createPresetModelInterfaceByRemoteModel(MLModel mlModel) {
        if (mlModel.getConnector().getParameters() != null) {
            switch (mlModel.getConnector().getParameters().get("service_name")) {
                case "bedrock":
                log.warn("Creating preset model interface for remote model: {}", mlModel.getConnector().getParameters().get("model"));
                switch (mlModel.getConnector().getParameters().get("model")) {
                    case "ai21.j2-mid-v1":
                        return Map.of(MLModel.INTERFACE_FIELD, BEDROCK_AI21_LABS_JURASSIC2_MID_V1_MODEL_INTERFACE);
                    case "anthropic.claude-3-sonnet-20240229-v1:0":
                        return Map.of(MLModel.INTERFACE_FIELD, BEDROCK_ANTHROPIC_CLAUDE_V3_SONNET_MODEL_INTERFACE);
                    case "anthropic.claude-v2":
                        return Map.of(MLModel.INTERFACE_FIELD, BEDROCK_ANTHROPIC_CLAUDE_V2_MODEL_INTERFACE);
                    case "cohere.embed.english-v3":
                        return Map.of(MLModel.INTERFACE_FIELD, BEDROCK_COHERE_EMBED_ENGLISH_V3_MODEL_INTERFACE);
                    case "cohere.embed.multilingual-v3":
                        return Map.of(MLModel.INTERFACE_FIELD, BEDROCK_COHERE_EMBED_MULTILINGUAL_V3_MODEL_INTERFACE);
                    case "amazon.titan-embed-text-v1":
                        return Map.of(MLModel.INTERFACE_FIELD, BEDROCK_TITAN_EMBED_TEXT_V1_MODEL_INTERFACE);
                    case "amazon.titan-embed-image-v1":
                        return Map.of(MLModel.INTERFACE_FIELD, BEDROCK_TITAN_EMBED_MULTI_MODAL_V1_MODEL_INTERFACE);
                    default:
                        return null;
                }
                default:
                    return null;
            }
        }
        return null;
    }
}
