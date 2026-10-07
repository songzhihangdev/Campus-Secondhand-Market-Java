package com.campus.agent.config;

import com.campus.agent.llmclient.converter.SkillToToolConverter;
import com.campus.agent.model.LlmSkill;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能参数 JSON-Schema 格式校验。
 *
 * <p><b>为什么需要这个测试</b>：{@code parameters} 是<b>直接透传</b>给
 * OpenAI Function-Calling 的（见 {@code SkillToToolConverter}），
 * 格式写错不会在本地报错，而是<b>运行时被 LLM 服务返回 HTTP 400</b>：
 * <pre>
 *   Invalid schema for function 'xxx':
 *     is not of types "boolean", "object"
 * </pre>
 * 排查成本很高（要跑起来看日志才知道），所以在启动/测试阶段就拦住。
 *
 * <p><b>踩过的坑</b>：新增 {@code confirm_receipt} 时把 parameters 写成
 * {@code [{"name":"orderId","type":"integer","required":true}]}
 * 这种「数组+扁平字段」的形态 —— 看起来像，但 OpenAI 只接受
 * <b>JSON-Schema 对象</b>：{@code {"type":"object","properties":{...},"required":[...]}}。
 */
class SkillParameterSchemaTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry(new ObjectMapper(), new MallProperties());
        registry.load();
    }

    @Test
    void allSkillsParameters_shouldBeJsonSchemaObject() {
        ObjectMapper om = new ObjectMapper();
        for (String skillName : registry.getLlmSkillMap().keySet()) {
            LlmSkill skill = registry.getLlmSkill(skillName);
            JsonNode params = skill.getParameters();

            if (params == null || params.isNull()) {
                // 允许为空 —— 转换器会补一个最小 object schema
                continue;
            }

            assertFalse(params.isArray(),
                    "技能 " + skillName + " 的 parameters 不能是数组。"
                            + "OpenAI Function-Calling 要求 JSON-Schema 对象，"
                            + "正确写法：{\"type\":\"object\",\"properties\":{...},\"required\":[...]}");

            assertTrue(params.isObject(),
                    "技能 " + skillName + " 的 parameters 必须是 object，实际是 " + params.getNodeType());

            assertTrue(params.has("type"),
                    "技能 " + skillName + " 的 parameters 缺少 type 字段");
            assertEqualsQuietly("object", params.get("type").asText(),
                    "技能 " + skillName + " 的 parameters.type 必须是 object");

            JsonNode properties = params.get("properties");
            assertNotNull(properties,
                    "技能 " + skillName + " 的 parameters 缺少 properties 字段");

            JsonNode required = params.get("required");
            if (required != null) {
                assertTrue(required.isArray(),
                        "技能 " + skillName + " 的 required 必须是数组");
                // 每个 required 项都必须在 properties 里声明，否则模型传了也不认
                for (JsonNode r : required) {
                    String fieldName = r.asText();
                    assertTrue(properties.has(fieldName),
                            "技能 " + skillName + " 的 required 声明了 '" + fieldName
                                    + "'，但 properties 里没有它");
                }
            }
        }
    }

    @Test
    void skillParameterNames_shouldMatchPathParams() {
        // parameters 里声明的参数名，必须与 http_meta 的 pathParams 对得上，
        // 否则模型传对了名字却拼不出正确路径（或反过来）
        for (String skillName : registry.getLlmSkillMap().keySet()) {
            LlmSkill skill = registry.getLlmSkill(skillName);
            JsonNode params = skill.getParameters();
            var meta = registry.getHttpMeta(skillName);
            if (params == null || params.isNull() || meta == null
                    || meta.getPathParams() == null || meta.getPathParams().isEmpty()) {
                continue;
            }
            JsonNode properties = params.get("properties");
            if (properties == null) {
                continue;
            }
            properties.fieldNames().forEachRemaining(field -> {
                // path 形如 /orders/{id}/confirm
                if (meta.getPath().contains("{" + field + "}")) {
                    assertTrue(meta.getPathParams().containsKey(field),
                            "技能 " + skillName + " 的路径含 {" + field
                                    + "}，但 pathParams 里没声明它");
                }
            });
        }
    }

    @Test
    void confirmReceiptSchema_shouldBeValid() {
        LlmSkill skill = registry.getLlmSkill("confirm_receipt");
        assertNotNull(skill, "应存在 confirm_receipt 技能");

        JsonNode p = skill.getParameters();
        assertTrue(p.isObject(), "confirm_receipt 的 parameters 必须是 object");
        assertEqualsQuietly("object", p.get("type").asText(), "type 必须是 object");
        assertTrue(p.has("properties"), "必须有 properties");
        assertTrue(p.has("required"), "必须有 required");

        // 参数名与项目约定一致（其他技能都用 id）
        assertTrue(p.get("properties").has("id"),
                "confirm_receipt 的参数名应为 id，与 get_order_by_id 等保持一致");
        assertEqualsQuietly("int", p.get("properties").get("id").get("type").asText(),
                "参数类型用 int（项目约定），不是 JSON-Schema 标准的 integer");

        // 路径参数名一致
        assertEqualsQuietly("/orders/{id}/confirm",
                registry.getHttpMeta("confirm_receipt").getPath(),
                "路径参数名应统一用 {id}");
    }

    private static void assertEqualsQuietly(Object expected, Object actual, String msg) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, msg);
    }
}