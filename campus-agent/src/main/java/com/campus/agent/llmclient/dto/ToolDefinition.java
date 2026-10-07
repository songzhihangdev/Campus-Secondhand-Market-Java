package com.campus.agent.llmclient.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送给 LLM 的单个工具定义（Function-Calling 标准 tools 元素）。
 * 序列化结构：
 * <pre>
 * { "type": "function",
 *   "function": { "name": "...", "description": "...", "parameters": { ...JSON Schema... } } }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

    /** 固定为 "function" */
    @JsonProperty("type")
    private String type;

    /** 函数定义体 */
    @JsonProperty("function")
    private Function function;

    /**
     * 函数元信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Function {

        /** 函数名（即技能 name） */
        @JsonProperty("name")
        private String name;

        /** 函数说明（面向大模型，告诉模型何时调用） */
        @JsonProperty("description")
        private String description;

        /** 入参 JSON Schema：{ type, properties, required ... } */
        @JsonProperty("parameters")
        private JsonNode parameters;
    }
}
