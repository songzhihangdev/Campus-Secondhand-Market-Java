package com.campus.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.config.ContextCompressProperties;
import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.llmclient.dto.LlmResponse.FunctionCall;
import com.campus.agent.llmclient.dto.LlmResponse.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文压缩器单元测试。
 *
 * <p>重点验证三类性质：
 * <ol>
 *   <li><b>有效性</b>：分页结果与长文本确实被裁短；</li>
 *   <li><b>安全性</b>：裁剪后 OpenAI 的 tool_call 配对关系不被破坏；</li>
 *   <li><b>无副作用</b>：不修改传入的原始消息（保证落盘 jsonl 仍是完整数据）。</li>
 * </ol>
 */
class ContextCompressorTest {

    private ObjectMapper objectMapper;

    private ContextCompressProperties props;

    private ContextCompressor compressor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        props = new ContextCompressProperties();
        compressor = new ContextCompressor(props, objectMapper);
    }

    /** 构造一个含 n 件商品的分页 JSON（模拟 search_items 的返回） */
    private String pageJson(int n) {
        StringBuilder sb = new StringBuilder("{\"total\":").append(n).append(",\"pages\":1,\"list\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append("{\"id\":").append(i)
              .append(",\"name\":\"商品").append(i)
              .append("\",\"price\":").append(1000 + i)
              .append(",\"stock\":10,\"image\":\"http://img/").append(i).append(".png\"}");
        }
        return sb.append("]}").toString();
    }

    private LlmMessage toolMessage(String content) {
        return LlmMessage.builder()
                .role("tool").name("search_items")
                .toolCallId("call_1")
                .content(content)
                .build();
    }

    // ==================== 有效性 ====================

    @Test
    @DisplayName("分页结果只保留前 N 件，并保留真实总数")
    void compress_shouldTrimListAndKeepTotal() throws Exception {
        props.setMaxItemsInList(3);
        String bigJson = pageJson(20);

        LlmMessage result = compressor.compress(List.of(toolMessage(bigJson))).get(0);

        JsonNode node = objectMapper.readTree(result.getContent());
        assertEquals(3, node.get("list").size(), "应只保留 3 件商品");
        assertEquals(20, node.get("total").intValue(), "total 应保留真实总数");
        assertTrue(node.get("_truncated").asBoolean());
        assertEquals(20, node.get("_totalSize").asInt());
        // 前 3 件的 id 应保持原样
        assertEquals(1, node.get("list").get(0).get("id").asInt());
        assertEquals(3, node.get("list").get(2).get("id").asInt());
    }

    @Test
    @DisplayName("商品条数不足上限时不裁剪")
    void compress_shouldNotTrimWhenListIsSmall() throws Exception {
        props.setMaxItemsInList(10);
        String json = pageJson(3);

        LlmMessage result = compressor.compress(List.of(toolMessage(json))).get(0);

        assertEquals(3, objectMapper.readTree(result.getContent()).get("list").size());
    }

    @Test
    @DisplayName("裁剪后内容显著变短（token 节省可观测）")
    void compress_shouldActuallyReduceLength() {
        props.setMaxItemsInList(5);
        String bigJson = pageJson(50);

        LlmMessage result = compressor.compress(List.of(toolMessage(bigJson))).get(0);

        assertTrue(result.getContent().length() < bigJson.length() / 4,
                "裁剪后长度应显著下降，原始=" + bigJson.length() + " 裁剪后=" + result.getContent().length());
    }

    @Test
    @DisplayName("非 JSON 的长文本按字符截断，且不抛异常")
    void compress_shouldTruncatePlainText() {
        props.setMaxPlainTextLength(100);
        String longText = "很长的地址列表内容".repeat(50);

        LlmMessage result = compressor.compress(List.of(toolMessage(longText))).get(0);

        assertTrue(result.getContent().length() <= 130, "应被截断到约 100 字符");
        assertTrue(result.getContent().contains("已截断"));
    }

    @Test
    @DisplayName("短 observation 原样保留，不做无谓改写")
    void compress_shouldKeepShortContentIntact() {
        props.setMaxItemsInList(5);
        String shortJson = "{\"total\":0,\"list\":[]}";

        LlmMessage result = compressor.compress(List.of(toolMessage(shortJson))).get(0);

        assertEquals(shortJson, result.getContent());
    }

    @Test
    @DisplayName("user / assistant 消息不被裁剪")
    void compress_shouldNotTouchUserAndAssistant() {
        props.setMaxItemsInList(1);
        LlmMessage user = LlmMessage.user("帮我搜华为手机");
        LlmMessage assistant = LlmMessage.assistant("好的，为你找到……");

        List<LlmMessage> out = compressor.compress(List.of(user, assistant));

        assertSame(user, out.get(0));
        assertSame(assistant, out.get(1));
    }

    @Test
    @DisplayName("关闭开关后原样透传（便于对照排查）")
    void compress_shouldPassThroughWhenDisabled() {
        props.setEnabled(false);
        String bigJson = pageJson(30);

        List<LlmMessage> out = compressor.compress(List.of(toolMessage(bigJson)));

        assertEquals(1, out.size());
        assertEquals(bigJson, out.get(0).getContent());
    }

    // ==================== 安全性 ====================

    @Test
    @DisplayName("裁剪不修改传入的原始消息（落盘数据保持完整）")
    void compress_shouldNotMutateInput() {
        props.setMaxItemsInList(2);
        LlmMessage original = toolMessage(pageJson(20));
        String originalContent = original.getContent();

        compressor.compress(List.of(original));

        assertEquals(originalContent, original.getContent(), "原始消息绝不能被裁剪改写");
    }

    @Test
    @DisplayName("历史过长时按整轮丢弃，tool 与其 tool_calls 成对保留")
    void compress_shouldDropWholeRoundsKeepingToolCallPairing() {
        props.setMaxItemsInList(2);
        props.setMaxMessages(4);
        props.setKeepRecentMessages(2);

        // 构造 3 个完整轮次：每轮 = user + assistant(tool_calls) + tool + assistant
        List<LlmMessage> history = new ArrayList<>();
        for (int r = 1; r <= 3; r++) {
            history.add(LlmMessage.user("问题" + r));
            history.add(LlmMessage.builder()
                    .role("assistant")
                    .toolCalls(List.of(ToolCall.builder()
                            .id("call_" + r).type("function")
                            .function(FunctionCall.builder()
                                    .name("search_items").arguments("{}").build())
                            .build()))
                    .build());
            history.add(LlmMessage.builder()
                    .role("tool").name("search_items")
                    .toolCallId("call_" + r).content(pageJson(10)).build());
            history.add(LlmMessage.assistant("回答" + r));
        }
        assertEquals(12, history.size());

        List<LlmMessage> out = compressor.compress(history);

        assertTrue(out.size() < history.size(), "应丢弃了一部分历史");

        // 关键断言：裁剪后不能存在"孤立"的 tool 消息
        // 规则：每条 tool 消息的 toolCallId，必须能在前面找到同 id 的 assistant.tool_calls
        java.util.Set<String> declaredIds = new java.util.HashSet<>();
        for (LlmMessage m : out) {
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null) {
                for (ToolCall tc : m.getToolCalls()) {
                    declaredIds.add(tc.getId());
                }
            } else if ("tool".equals(m.getRole())) {
                assertTrue(declaredIds.contains(m.getToolCallId()),
                        "发现孤立的 tool 消息：toolCallId=" + m.getToolCallId()
                                + " 在其之前没有对应的 assistant.tool_calls，这会让 OpenAI API 报错");
            }
        }
    }

    @Test
    @DisplayName("丢弃后以 system 消息留痕，说明省略了多少")
    void compress_shouldLeaveTraceAfterDropping() {
        props.setMaxMessages(3);
        props.setKeepRecentMessages(1);

        List<LlmMessage> history = new ArrayList<>();
        for (int r = 1; r <= 4; r++) {
            history.add(LlmMessage.user("问题" + r));
            history.add(LlmMessage.assistant("回答" + r));
        }

        List<LlmMessage> out = compressor.compress(history);

        assertEquals("system", out.get(0).getRole());
        assertTrue(out.get(0).getContent().contains("已省略最早的"));
    }

    @Test
    @DisplayName("配置异常时保守不丢弃，避免误伤上下文")
    void compress_shouldSkipDroppingWhenConfigInvalid() {
        props.setMaxMessages(10);
        props.setKeepRecentMessages(20); // keep >= max，非法

        List<LlmMessage> history = new ArrayList<>();
        for (int r = 1; r <= 6; r++) {
            history.add(LlmMessage.user("问题" + r));
            history.add(LlmMessage.assistant("回答" + r));
        }
        int before = history.size();

        List<LlmMessage> out = compressor.compress(history);

        assertEquals(before, out.size(), "非法配置下应原样保留，不做丢弃");
    }

    @Test
    @DisplayName("空输入与 null 输入不报错")
    void compress_shouldHandleEmptySafely() {
        assertNotNull(compressor.compress(null));
        assertTrue(compressor.compress(null).isEmpty());
        assertTrue(compressor.compress(new ArrayList<>()).isEmpty());
    }

    @Test
    @DisplayName("损坏的 JSON 不导致抛异常，退化为文本截断")
    void compress_shouldDegradeOnBrokenJson() {
        props.setMaxPlainTextLength(80);
        String broken = "{\"total\":5,\"list\":[{\"id\":1,";

        LlmMessage result = compressor.compress(List.of(toolMessage(broken))).get(0);

        assertNotNull(result.getContent());
        assertFalse(result.getContent().isEmpty());
    }
}
