package com.campus.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.model.LlmSkill;
import com.campus.agent.model.HttpSkillMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillRegistry 单元测试：验证两份 JSON 能正常加载、skillName 集合一致、风险标记可读。
 */
class SkillRegistryTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry(new ObjectMapper(), new MallProperties());
        registry.load();
    }

    @Test
    void shouldLoadAllSkills() {
        // 断言「两份配置的技能集合完全一致」而不是硬编码数量 ——
        // 数量会随业务增删技能变化（本次新增 confirm_receipt 就从 20 变 21），
        // 写死数字会导致每次加技能都要改测试，真正的风险（两份配置对不上）反而测不出来。
        //
        // 实际数量只做「不为空」的下限断言。
        assertTrue(registry.size() > 0, "技能列表不应为空");
        assertEquals(registry.size(), registry.getHttpMetaMap().size(),
                "llm_skill_def.json 与 http_meta.json 的技能数量必须一致");

        // 逐个核对技能名，两个 Map 的 key 集合必须完全相同
        assertEquals(registry.getLlmSkillMap().keySet(), registry.getHttpMetaMap().keySet(),
                "llm_skill_def.json 与 http_meta.json 的技能名集合必须完全匹配");
    }

    @Test
    void confirmReceiptSkill_shouldExist() {
        // 确认收货是二手交易闭环的最后一环，AI 需要能处理"我收到了"
        assertNotNull(registry.getLlmSkill("confirm_receipt"),
                "应提供 confirm_receipt 技能供 AI 确认收货");
        HttpSkillMeta meta = registry.getHttpMeta("confirm_receipt");
        assertNotNull(meta, "confirm_receipt 缺少 http 元数据");
        assertEquals("PUT", meta.getHttpMethod());
        assertTrue(meta.getPath().contains("{id}"), "路径应含 {id} 路径参数（与项目其他技能统一）");
    }

    @Test
    void userLoginSkill_shouldBeRemoved() {
        // 登录改由框架层 JWT 拦截器保证，不再作为工具暴露给模型
        assertNull(registry.getLlmSkill("user_login"));
        assertNull(registry.getHttpMeta("user_login"));
    }

    @Test
    void internalFlowSkills_shouldBeRemoved() {
        // 这三个属于「只应由系统内部触发」的流程，不应作为工具暴露给模型：
        //   mark_order_paid     —— 支付成功后由 MQ 回写状态
        //   deduct_item_stock   —— 库存扣减应在 create_order 事务内完成
        //   deduct_user_balance —— 支付必须走 campus-pay 的幂等与风控流程
        assertNull(registry.getLlmSkill("mark_order_paid"));
        assertNull(registry.getLlmSkill("deduct_item_stock"));
        assertNull(registry.getLlmSkill("deduct_user_balance"));
        assertNull(registry.getHttpMeta("mark_order_paid"));
        assertNull(registry.getHttpMeta("deduct_item_stock"));
        assertNull(registry.getHttpMeta("deduct_user_balance"));
    }

    @Test
    void tradeSkills_shouldBeMarkedHighRisk() {
        // 校园二手 C2C 场景下，Agent 代用户下单/支付属于资金相关行为，
        // 必须升级为高风险，由执行器拦截并要求人工确认
        assertTrue(registry.getLlmSkill("create_order").getWarning().contains("高风险"));
        assertTrue(registry.getLlmSkill("apply_pay_order").getWarning().contains("高风险"));
        assertTrue(registry.getLlmSkill("pay_order_by_balance").getWarning().contains("高风险"));
    }

    @Test
    void skillNameSets_shouldMatchExactly() {
        assertEquals(registry.getLlmSkillMap().keySet(), registry.getHttpMetaMap().keySet());
    }

    @Test
    void highRiskSkill_shouldBeMarked() {
        // 写操作类技能（发布/编辑/下架闲置）均应为高风险
        assertTrue(registry.getLlmSkill("add_item").getWarning().contains("高风险"));
        assertTrue(registry.getLlmSkill("update_item").getWarning().contains("高风险"));
        assertTrue(registry.getLlmSkill("delete_item").getWarning().contains("高风险"));
        assertTrue(registry.getLlmSkill("update_item_status").getWarning().contains("高风险"));
    }

    @Test
    void lowRiskSkill_shouldBeMarked() {
        LlmSkill skill = registry.getLlmSkill("search_items");
        assertNotNull(skill);
        assertTrue(skill.getWarning().contains("低风险"));
    }
}
