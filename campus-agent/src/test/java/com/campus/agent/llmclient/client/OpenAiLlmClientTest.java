package com.campus.agent.llmclient.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.llmclient.dto.LlmResult;
import com.campus.agent.llmclient.dto.ToolDefinition;
import com.campus.agent.llmclient.exception.LlmInvokeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OpenAiLlmClient 单元测试。
 * <p>
 * 使用 MockRestServiceServer 模拟 LLM 服务，独立测试客户端，
 * 不依赖 Agent、商城接口，也不发起真实网络请求。
 */
class OpenAiLlmClientTest {

    private MockRestServiceServer server;
    private OpenAiLlmClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl("https://api.test");
        properties.setApiKey("sk-test");
        properties.setModel("test-model");
        properties.setTimeout(5000);

        objectMapper = new ObjectMapper();
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new OpenAiLlmClient(properties, objectMapper, restTemplate);
    }

    /** 类型B：最终文本回答 */
    @Test
    void chat_textAnswer_shouldReturnContent() {
        String responseBody = "{\"id\":\"chatcmpl-1\",\"choices\":["
                + "{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"你好，我是助手\"}}]}";

        server.expect(requestTo("https://api.test/chat/completions"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString("application/json")))
                .andExpect(content().string(containsString("\"model\":\"test-model\"")))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        LlmResult result = client.chat(List.of(LlmMessage.user("你好")));

        assertTrue(result.isText());
        assertEquals("你好，我是助手", result.getContent());
        server.verify();
    }

    /** 类型A：工具调用，需提取 skillName、arguments，且请求体应带 tools */
    @Test
    void chat_toolCalls_shouldParseSkillNameAndArguments() {
        String responseBody = "{\"id\":\"chatcmpl-2\",\"choices\":["
                + "{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                + "\"content\":null,\"tool_calls\":[{"
                + "\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"search_items\","
                + "\"arguments\":\"{\\\"key\\\":\\\"phone\\\",\\\"pageNo\\\":1}\"}}]}}]}";

        server.expect(requestTo("https://api.test/chat/completions"))
                .andExpect(content().string(containsString("\"tools\"")))
                .andExpect(content().string(containsString("\"name\":\"search_items\"")))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        // 传入一个工具定义，验证请求体会被序列化
        ToolDefinition tool = ToolDefinition.builder()
                .type("function")
                .function(ToolDefinition.Function.builder()
                        .name("search_items")
                        .description("搜索商品")
                        .parameters(objectMapper.createObjectNode().put("type", "object"))
                        .build())
                .build();

        LlmResult result = client.chat(List.of(LlmMessage.user("帮我找手机")), List.of(tool));

        assertTrue(result.isToolCalls());
        assertEquals(1, result.getToolCalls().size());
        LlmResult.ParsedToolCall call = result.getToolCalls().get(0);
        assertEquals("call_1", call.getToolCallId());
        assertEquals("search_items", call.getSkillName());
        assertEquals("phone", call.getArguments().get("key"));
        assertEquals(1, call.getArguments().get("pageNo"));
        server.verify();
    }

    /** 401：应包装为 LlmInvokeException 并保留状态码 */
    @Test
    void chat_unauthorized_shouldThrowLlmInvokeException() {
        server.expect(requestTo("https://api.test/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("invalid api key"));

        LlmInvokeException ex = assertThrows(LlmInvokeException.class,
                () -> client.chat(List.of(LlmMessage.user("hi"))));
        assertEquals(401, ex.getStatusCode());
        server.verify();
    }

    /** 500：应包装为 LlmInvokeException */
    @Test
    void chat_serverError_shouldThrowLlmInvokeException() {
        server.expect(requestTo("https://api.test/chat/completions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        LlmInvokeException ex = assertThrows(LlmInvokeException.class,
                () -> client.chat(List.of(LlmMessage.user("hi"))));
        assertEquals(500, ex.getStatusCode());
        server.verify();
    }

    /** choices 为空：应抛 LlmInvokeException */
    @Test
    void chat_emptyChoices_shouldThrow() {
        server.expect(requestTo("https://api.test/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(LlmInvokeException.class,
                () -> client.chat(List.of(LlmMessage.user("hi"))));
        server.verify();
    }

    /** arguments 非法 JSON：应抛 LlmInvokeException */
    @Test
    void chat_invalidArguments_shouldThrow() {
        String responseBody = "{\"choices\":[{\"index\":0,\"message\":{"
                + "\"role\":\"assistant\",\"tool_calls\":[{"
                + "\"id\":\"call_x\",\"type\":\"function\","
                + "\"function\":{\"name\":\"search_items\",\"arguments\":\"not-a-json\"}}]}}]}";

        server.expect(requestTo("https://api.test/chat/completions"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThrows(LlmInvokeException.class,
                () -> client.chat(List.of(LlmMessage.user("hi")),
                        Collections.singletonList(ToolDefinition.builder().build())));
        server.verify();
    }

    /** 空消息入参：应直接拒绝 */
    @Test
    void chat_emptyMessages_shouldThrow() {
        assertThrows(LlmInvokeException.class, () -> client.chat(Collections.emptyList()));
    }
}
