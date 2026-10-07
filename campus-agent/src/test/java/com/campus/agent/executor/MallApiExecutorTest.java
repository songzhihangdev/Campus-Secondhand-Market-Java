package com.campus.agent.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.config.MallProperties;
import com.campus.agent.config.SkillRegistry;
import com.campus.agent.context.MallContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * MallApiExecutor 单元测试。
 * <p>
 * 使用 MockRestServiceServer 模拟商城 HTTP 响应，全程不依赖真实商城、也不涉及任何 LLM。
 */
class MallApiExecutorTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private MallApiExecutor executor;

    @BeforeEach
    void setUp() {
        MallProperties properties = new MallProperties();
        properties.setBaseUrl("http://mall.test");

        // 手动构建注册中心并触发加载（读取 classpath 中的两份 JSON）
        SkillRegistry registry = new SkillRegistry(new ObjectMapper(), properties);
        registry.load();

        restTemplate = new RestTemplate();
        // 将 MockRestServiceServer 绑定到该 RestTemplate（替换其请求工厂）
        server = MockRestServiceServer.createServer(restTemplate);

        // 工厂始终返回被 mock 绑定的 RestTemplate，忽略超时参数
        executor = new MallApiExecutor(registry, timeoutMs -> restTemplate, properties);

        MallContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        MallContextHolder.clear();
    }

    @Test
    void highRiskSkill_shouldBeBlocked() {
        // 用「发布闲置」验证高风险拦截（deduct_user_balance 已作为内部流程技能移除）
        Map<String, Object> params = new HashMap<>();
        params.put("name", "数据结构教材");
        params.put("price", 2500);

        String result = executor.execute("add_item", params);

        assertEquals("【高风险操作，需要人工确认，禁止自动执行】", result);
        // 高风险被拦截，不应发出任何请求
        server.verify();
    }

    /**
     * 交易类技能（create_order / apply_pay_order / pay_order_by_balance）
     * 在校园二手 C2C 场景下已升级为高风险，执行器必须拦截并要求人工确认。
     */
    @Test
    void tradeSkills_shouldBeBlockedInC2cScenario() {
        for (String skill : new String[]{"create_order", "apply_pay_order", "pay_order_by_balance"}) {
            String result = executor.execute(skill, new HashMap<>());
            assertEquals("【高风险操作，需要人工确认，禁止自动执行】", result,
                    "交易类技能必须被拦截：" + skill);
        }
        // 全部被拦截，不应发出任何请求
        server.verify();
    }

    @Test
    void unknownSkill_shouldReturnReadableError() {
        String result = executor.execute("not_exist_skill", new HashMap<>());
        assertTrue(result.contains("未找到技能配置"));
    }

    @Test
    void userLogin_removed_shouldReturnFailureText() {
        // 登录已改为由框架层 JWT 拦截器保证，user_login 技能已从配置中移除。
        // 即使仍有人用旧技能名调用，也不应发出任何请求，直接返回可读失败文本。
        Map<String, Object> params = new HashMap<>();
        params.put("username", "jack");
        params.put("password", "secret");

        String result = executor.execute("user_login", params);

        assertTrue(result.contains("未找到技能配置"));
        // 不应发起任何 HTTP 请求
        server.verify();
    }

    @Test
    void search_shouldAppendQueryParams() {
        server.expect(requestTo(startsWith("http://mall.test/search/list")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("key", "huawei"))
                .andExpect(queryParam("pageNo", "1"))
                .andRespond(withSuccess("{\"total\":0,\"list\":[]}", MediaType.APPLICATION_JSON));

        Map<String, Object> params = new HashMap<>();
        params.put("key", "huawei");
        params.put("pageNo", 1);

        String result = executor.execute("search_items", params);

        assertTrue(result.contains("\"total\":0"));
        server.verify();
    }

    @Test
    void getItem_shouldFillPathParam() {
        server.expect(requestTo("http://mall.test/items/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":7,\"name\":\"phone\"}", MediaType.APPLICATION_JSON));

        Map<String, Object> params = new HashMap<>();
        params.put("id", 7);

        String result = executor.execute("get_item_by_id", params);

        assertTrue(result.contains("\"name\":\"phone\""));
        server.verify();
    }

    @Test
    void listCarts_withToken_shouldSendAuthorizationHeader() {
        server.expect(requestTo("http://mall.test/carts"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok123"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        MallContextHolder.setToken("tok123");

        String result = executor.execute("list_my_carts", new HashMap<>());

        assertEquals("[]", result);
        server.verify();
    }

    @Test
    void listCarts_withoutToken_401_shouldReturnReadableMessage() {
        server.expect(requestTo("http://mall.test/carts"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        String result = executor.execute("list_my_carts", new HashMap<>());

        assertTrue(result.contains("401"));
        assertTrue(result.contains("重新登录"));
        server.verify();
    }

    @Test
    void serverError_500_shouldReturnReadableMessage() {
        server.expect(requestTo("http://mall.test/items/1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        Map<String, Object> params = new HashMap<>();
        params.put("id", 1);

        String result = executor.execute("get_item_by_id", params);

        assertTrue(result.contains("500"));
        server.verify();
    }
}
