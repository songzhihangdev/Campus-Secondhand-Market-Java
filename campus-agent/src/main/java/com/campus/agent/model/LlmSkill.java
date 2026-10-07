package com.campus.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 面向大模型的技能定义（对应 resources/skills/llm_skill_def.json 中的一个元素）。
 * <p>
 * 本类只承载配置数据，不包含任何 LLM 调用逻辑。
 * 其中 {@link #parameters} 是标准 JSON-Schema 片段，结构灵活，
 * 因此使用 {@link JsonNode} 无损保留原始内容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmSkill {

    /** 技能英文名称，小写下划线，全局唯一 */
    @JsonProperty("name")
    private String name;

    /** 一句话简短概括接口能力 */
    @JsonProperty("summary")
    private String summary;

    /** 面向大模型的业务描述 */
    @JsonProperty("description")
    private String description;

    /** 入参 JSON-Schema：{ type, properties, required ... }，原样保留 */
    @JsonProperty("parameters")
    private JsonNode parameters;

    /** 接口返回业务含义 */
    @JsonProperty("return_desc")
    private String returnDesc;

    /** 错误处理说明 */
    @JsonProperty("error_desc")
    private String errorDesc;

    /** 风险标记，形如“【高风险】...”或“【低风险】...” */
    @JsonProperty("warning")
    private String warning;
}
