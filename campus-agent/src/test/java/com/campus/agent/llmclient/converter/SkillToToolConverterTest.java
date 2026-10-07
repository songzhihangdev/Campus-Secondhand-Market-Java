package com.campus.agent.llmclient.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.campus.agent.llmclient.dto.ToolDefinition;
import com.campus.agent.model.LlmSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillToToolConverter 单元测试：验证技能被正确转换为 Function-Calling tools 结构。
 */
class SkillToToolConverterTest {

    private ObjectMapper objectMapper;
    private SkillToToolConverter converter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        converter = new SkillToToolConverter(objectMapper);
    }

    @Test
    void convert_shouldBuildFunctionTool() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", objectMapper.createObjectNode());

        LlmSkill skill = LlmSkill.builder()
                .name("search_items")
                .summary("关键字搜索商品")
                .description("按关键字搜索在售商品")
                .parameters(parameters)
                .returnDesc("返回分页商品")
                .build();

        ToolDefinition tool = converter.convert(skill);

        assertEquals("function", tool.getType());
        assertNotNull(tool.getFunction());
        assertEquals("search_items", tool.getFunction().getName());
        // description 应包含业务描述
        assertTrue(tool.getFunction().getDescription().contains("按关键字搜索在售商品"));
        // 追加了返回说明
        assertTrue(tool.getFunction().getDescription().contains("返回分页商品"));
        // 参数 JSON Schema 原样保留
        assertEquals("object", tool.getFunction().getParameters().get("type").asText());
    }

    @Test
    void convert_nullParameters_shouldFallbackToObjectSchema() {
        LlmSkill skill = LlmSkill.builder()
                .name("list_my_addresses")
                .description("查询我的地址")
                .parameters(null)
                .build();

        ToolDefinition tool = converter.convert(skill);

        assertEquals("object", tool.getFunction().getParameters().get("type").asText());
        assertNotNull(tool.getFunction().getParameters().get("properties"));
    }

    @Test
    void convert_nonStandardTypeInt_shouldNormalizeToInteger() {
        // 模拟技能文件里的 "type":"int"
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode price = objectMapper.createObjectNode();
        price.put("type", "int");
        price.put("description", "价格");
        properties.set("price", price);
        parameters.set("properties", properties);

        LlmSkill skill = LlmSkill.builder()
                .name("add_item").description("新增商品").parameters(parameters).build();

        ToolDefinition tool = converter.convert(skill);

        JsonNode priceNode = tool.getFunction().getParameters()
                .get("properties").get("price");
        assertEquals("integer", priceNode.get("type").asText());
    }

    @Test
    void convert_arrayWithoutItems_shouldAddItems() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode details = objectMapper.createObjectNode();
        details.put("type", "array");
        details.put("description", "明细");
        properties.set("details", details);
        parameters.set("properties", properties);

        LlmSkill skill = LlmSkill.builder()
                .name("create_order").description("下单").parameters(parameters).build();

        ToolDefinition tool = converter.convert(skill);

        JsonNode detailsNode = tool.getFunction().getParameters()
                .get("properties").get("details");
        assertEquals("array", detailsNode.get("type").asText());
        assertNotNull(detailsNode.get("items"));
    }

    @Test
    void convert_nullSkill_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> converter.convert(null));
    }
}
