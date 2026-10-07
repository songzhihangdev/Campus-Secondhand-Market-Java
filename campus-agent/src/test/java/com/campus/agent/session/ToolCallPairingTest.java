package com.campus.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.config.AgentSessionProperties;
import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.llmclient.dto.LlmResponse.ToolCall;
import com.campus.agent.llmclient.dto.LlmResponse.FunctionCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tool_call_id 配对关系的落盘与重建测试。
 *
 * <p><b>背景（真实故障）</b>：早期实现认为「assistant 携带 tool_calls 是协议中间态、
 * 不给前端看」而直接跳过落盘，且 tool 消息没保存 toolCallId。
 * 结果 jsonl 里只剩孤立的 tool 消息，历史恢复后 DeepSeek 返回：
 * <pre>
 *   HTTP 422 - messages[2]: missing field `tool_call_id`
 * </pre>
 * 整个 ReAct 循环崩溃。本测试锁死该行为不再回退。
 */
class ToolCallPairingTest {

    @TempDir
    Path tempRoot;

    private FileSessionStore store;

    private static final Long USER = 1001L;

    private static final String SID = "aaaaaaaaaaaa1234bbbbbbbbbbbb5678";

    @BeforeEach
    void setUp() {
        AgentSessionProperties properties = new AgentSessionProperties();
        properties.setRootDir(tempRoot.toString());
        store = new FileSessionStore(new ObjectMapper(), properties);
    }

    private ToolCall sampleToolCall(String id, String name) {
        FunctionCall fn = new FunctionCall();
        fn.setName(name);
        fn.setArguments("{\"key\":\"书\"}");
        ToolCall tc = new ToolCall();
        tc.setId(id);
        tc.setType("function");
        tc.setFunction(fn);
        return tc;
    }

    @Test
    @DisplayName("正常场景：assistant(tool_calls) 与 tool 消息的配对能完整重建")
    void shouldRebuildToolCallPairing() {
        // ---- 落盘一轮真实消息 ----
        List<LlmMessage> round = new ArrayList<>();
        round.add(LlmMessage.user("帮我找书"));
        round.add(LlmMessage.builder()
                .role("assistant")
                .content(null)
                .toolCalls(List.of(sampleToolCall("call_1", "search_items")))
                .build());
        round.add(LlmMessage.builder()
                .role("tool")
                .name("search_items")
                .content("{\"total\":1,\"list\":[{\"id\":1,\"name\":\"数据结构\"}]}")
                .toolCallId("call_1")
                .build());
        round.add(LlmMessage.assistant("找到一本书"));

        List<ChatMessageRecord> records = toRecords(round);
        store.appendAll(USER, SID, records);

        // ---- 重建 ----
        List<LlmMessage> restored = store.readForContext(USER, SID);

        // 断言1：assistant 的 toolCalls 必须回来（否则 tool 消息失配）
        boolean hasAssistantWithToolCalls = restored.stream()
                .anyMatch(m -> "assistant".equals(m.getRole())
                        && m.getToolCalls() != null && !m.getToolCalls().isEmpty());
        assertTrue(hasAssistantWithToolCalls,
                "assistant 的 toolCalls 必须在落盘后保留，否则后续 tool 消息会 422");

        // 断言2：tool 消息必须带 toolCallId
        LlmMessage toolMsg = restored.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst()
                .orElse(null);
        assertNotNull(toolMsg, "tool 消息应被恢复");
        assertEquals("call_1", toolMsg.getToolCallId(), "tool 消息必须带 toolCallId");
        assertEquals("search_items", toolMsg.getName());

        // 断言3：配对关系成立 —— 每个 tool 消息的 id 都能在某个 assistant.tool_calls 中找到
        for (LlmMessage m : restored) {
            if (!"tool".equals(m.getRole())) continue;
            boolean matched = restored.stream()
                    .filter(a -> "assistant".equals(a.getRole()) && a.getToolCalls() != null)
                    .flatMap(a -> a.getToolCalls().stream())
                    .anyMatch(tc -> tc.getId().equals(m.getToolCallId()));
            assertTrue(matched,
                    "tool_call_id=" + m.getToolCallId() + " 找不到对应的 assistant.tool_calls");
        }
    }

    @Test
    @DisplayName("兼容旧数据：孤儿 tool 消息被跳过，不让整个请求崩掉")
    void shouldSkipOrphanToolMessages() {
        // 模拟旧版本写出的 jsonl：tool 消息没有 toolCallId（用手写 JSON 规避 builder）
        List<ChatMessageRecord> legacy = new ArrayList<>();
        legacy.add(record("user", "帮我找书", null));
        legacy.add(record("tool", "{\"total\":0,\"list\":[]}", "search_items")); // 无 toolCallId
        legacy.add(record("assistant", "没有找到", null));
        store.appendAll(USER, SID, legacy);

        List<LlmMessage> restored = store.readForContext(USER, SID);

        // 孤儿 tool 消息必须被跳过
        assertFalse(restored.stream().anyMatch(m -> "tool".equals(m.getRole())),
                "无 toolCallId 的孤儿 tool 消息必须跳过，否则 API 必然 422");
        // 其余消息正常保留
        assertTrue(restored.stream().anyMatch(m -> "user".equals(m.getRole())));
        assertTrue(restored.stream().anyMatch(m -> "assistant".equals(m.getRole())));
    }

    @Test
    @DisplayName("中间态标记：assistant(tool_calls) 被标为 intermediate 供前端过滤")
    void shouldMarkIntermediate() {
        List<LlmMessage> round = new ArrayList<>();
        round.add(LlmMessage.user("q"));
        round.add(LlmMessage.builder()
                .role("assistant")
                .toolCalls(List.of(sampleToolCall("c1", "search_items")))
                .build());
        round.add(LlmMessage.builder()
                .role("tool").name("search_items").content("{}").toolCallId("c1").build());
        round.add(LlmMessage.assistant("a"));
        store.appendAll(USER, SID, toRecords(round));

        List<ChatMessageRecord> history = store.readHistory(USER, SID);

        List<ChatMessageRecord> mid = history.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIntermediate()))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(1, mid.size(), "应恰好有 1 条 intermediate 记录");
        assertEquals("assistant", mid.get(0).getRole());

        // 普通消息不应被标为 intermediate（否则前端会把真实回答也过滤掉）
        long assistantReal = history.stream()
                .filter(r -> "assistant".equals(r.getRole())
                        && !Boolean.TRUE.equals(r.getIntermediate()))
                .count();
        assertEquals(1, assistantReal, "最终回答不应被标记为 intermediate");
    }

    @Test
    @DisplayName("多轮多工具：配对在多轮后仍成立")
    void shouldKeepPairingAcrossRounds() {
        List<LlmMessage> all = new ArrayList<>();
        all.add(LlmMessage.user("r1"));
        all.add(LlmMessage.builder().role("assistant")
                .toolCalls(List.of(sampleToolCall("c1", "search_items"),
                        sampleToolCall("c2", "get_item_by_id"))).build());
        all.add(LlmMessage.builder().role("tool").name("search_items")
                .content("{\"total\":1,\"list\":[]}").toolCallId("c1").build());
        all.add(LlmMessage.builder().role("tool").name("get_item_by_id")
                .content("{}").toolCallId("c2").build());
        all.add(LlmMessage.assistant("r1 done"));
        all.add(LlmMessage.user("r2"));
        all.add(LlmMessage.builder().role("assistant")
                .toolCalls(List.of(sampleToolCall("c3", "list_my_carts"))).build());
        all.add(LlmMessage.builder().role("tool").name("list_my_carts")
                .content("[]").toolCallId("c3").build());
        all.add(LlmMessage.assistant("r2 done"));

        store.appendAll(USER, SID, toRecords(all));
        List<LlmMessage> restored = store.readForContext(USER, SID);

        // 3 个 toolCallId 都必须有配对的 assistant.tool_calls
        for (String id : List.of("c1", "c2", "c3")) {
            boolean matched = restored.stream()
                    .filter(a -> "assistant".equals(a.getRole()) && a.getToolCalls() != null)
                    .flatMap(a -> a.getToolCalls().stream())
                    .anyMatch(tc -> id.equals(tc.getId()));
            assertTrue(matched, "tool_call_id=" + id + " 应有配对的 assistant");
        }
        assertEquals(3, restored.stream().filter(m -> "tool".equals(m.getRole())).count());
    }

    // ---------------- 辅助方法 ----------------

    private List<ChatMessageRecord> toRecords(List<LlmMessage> messages) {
        List<ChatMessageRecord> out = new ArrayList<>();
        long seq = 0;
        for (LlmMessage m : messages) {
            boolean intermediate = m.getToolCalls() != null && !m.getToolCalls().isEmpty();
            out.add(ChatMessageRecord.builder()
                    .seq(++seq)
                    .ts(System.currentTimeMillis())
                    .role(m.getRole())
                    .content(m.getContent())
                    .name(m.getName())
                    .toolCallId(m.getToolCallId())
                    .toolCalls(m.getToolCalls())
                    .intermediate(intermediate ? Boolean.TRUE : null)
                    .sessionId(SID)
                    .build());
        }
        return out;
    }

    private ChatMessageRecord record(String role, String content, String name) {
        return ChatMessageRecord.builder()
                .seq(System.nanoTime())
                .ts(System.currentTimeMillis())
                .role(role)
                .content(content)
                .name(name)
                .sessionId(SID)
                .build();
    }
}
