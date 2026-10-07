package com.campus.agent.llmclient.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.campus.agent.llmclient.dto.ToolDefinition;
import com.campus.agent.model.LlmSkill;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能 -> LLM 工具定义转换器。
 * <p>
 * 把内存中的 {@link LlmSkill} 转换成 OpenAI Function-Calling 标准 tools 结构
 * {@link ToolDefinition}。本类只做结构转换，不发起任何请求。
 */
@Component
public class SkillToToolConverter {

    private final ObjectMapper objectMapper;

    public SkillToToolConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 单个技能 -> ToolDefinition。
     */
    public ToolDefinition convert(LlmSkill skill) {
        if (skill == null) {
            throw new IllegalArgumentException("待转换的 LlmSkill 不能为 null");
        }

        // 函数名：技能英文名（小写下划线，唯一）
        String name = skill.getName();

        // 函数说明：业务描述为主，追加返回含义，帮助模型判断“何时调用、返回什么”
        String description = buildDescription(skill);

        // 参数 JSON Schema：复用技能 parameters；为空时给一个最小 object schema
        JsonNode parameters = skill.getParameters();
        if (parameters == null || parameters.isNull()) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("type", "object");
            fallback.set("properties", objectMapper.createObjectNode());
            parameters = fallback;
        }

        // 关键：技能文件里用了非标准类型（如 "int"），OpenAI/DeepSeek 只接受标准
        // JSON Schema 类型（integer/number/string/boolean/array/object），需递归归一化，
        // 否则 LLM 严格校验 tools 时会返回 400。deepCopy 避免污染注册中心里的原始节点。
        parameters = normalizeSchema(parameters.deepCopy());

        ToolDefinition.Function function = ToolDefinition.Function.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();

        return ToolDefinition.builder()
                .type("function")
                .function(function)
                .build();
    }

    /**
     * 批量转换。
     */
    public List<ToolDefinition> convertAll(List<LlmSkill> skills) {
        List<ToolDefinition> tools = new ArrayList<>();
        if (skills != null) {
            for (LlmSkill skill : skills) {
                tools.add(convert(skill));
            }
        }
        return tools;
    }

    /** 非标准/常见语言类型名 -> 标准 JSON Schema 类型名 */
    private static final Map<String, String> TYPE_MAPPING = new HashMap<>();

    static {
        // 整型族 -> integer（技能文件里的 "int" 就在这里被修正）
        TYPE_MAPPING.put("int", "integer");
        TYPE_MAPPING.put("integer", "integer");
        TYPE_MAPPING.put("long", "integer");
        // 浮点族 -> number
        TYPE_MAPPING.put("float", "number");
        TYPE_MAPPING.put("double", "number");
        TYPE_MAPPING.put("decimal", "number");
        TYPE_MAPPING.put("number", "number");
        // 布尔/字符串保持标准名
        TYPE_MAPPING.put("bool", "boolean");
        TYPE_MAPPING.put("boolean", "boolean");
        TYPE_MAPPING.put("str", "string");
        TYPE_MAPPING.put("string", "string");
    }

    /**
     * 递归规范化 JSON Schema：
     * 1) 把任意层级 "type":"int" 等非标准类型替换为标准类型；
     * 2) 给 type=array 但缺少 "items" 的节点补一个 {} （任意类型元素），提升兼容性。
     */
    private JsonNode normalizeSchema(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;

            // 处理当前节点的 type 字段
            JsonNode typeNode = objectNode.get("type");
            if (typeNode != null && typeNode.isTextual()) {
                String rawType = typeNode.asText();
                String standard = TYPE_MAPPING.get(rawType.toLowerCase());
                if (standard != null) {
                    objectNode.set("type", TextNode.valueOf(standard));
                }
            }

            // array 缺少 items 时补一个空 schema，避免严格校验报错
            JsonNode afterType = objectNode.get("type");
            if (afterType != null && "array".equals(afterType.asText())
                    && (objectNode.get("items") == null || objectNode.get("items").isNull())) {
                objectNode.set("items", objectMapper.createObjectNode());
            }

            // 递归处理所有子字段（properties、items、$defs 等）
            objectNode.fields().forEachRemaining(entry ->
                    objectNode.set(entry.getKey(), normalizeSchema(entry.getValue())));

            return objectNode;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                arrayNode.set(i, normalizeSchema(arrayNode.get(i)));
            }
            return arrayNode;
        }
        return node;
    }

    /**
     * 组合函数描述：description + 返回含义（error_desc/warning 属于内控信息，不下发给模型）。
     */
    private String buildDescription(LlmSkill skill) {
        StringBuilder sb = new StringBuilder();
        if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
            sb.append(skill.getDescription());
        } else if (skill.getSummary() != null) {
            // description 缺失时退回 summary
            sb.append(skill.getSummary());
        }
        if (skill.getReturnDesc() != null && !skill.getReturnDesc().isEmpty()) {
            sb.append("\n返回说明：").append(skill.getReturnDesc());
        }
        return sb.toString();
    }
}
