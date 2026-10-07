package com.campus.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.config.AgentSessionProperties;
import com.campus.agent.llmclient.dto.LlmMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话历史文件存储测试：验证 jsonl 追加写、顺序保持、路径安全、用户隔离。
 */
class FileSessionStoreTest {

    @TempDir
    Path tempRoot;

    private FileSessionStore store;

    private final Long userA = 1001L;

    private final Long userB = 2002L;

    @BeforeEach
    void setUp() {
        AgentSessionProperties properties = new AgentSessionProperties();
        properties.setRootDir(tempRoot.toString());
        store = new FileSessionStore(new ObjectMapper(), properties);
    }

    private ChatMessageRecord record(long seq, String role, String content) {
        return ChatMessageRecord.builder()
                .seq(seq)
                .ts(System.currentTimeMillis())
                .role(role)
                .content(content)
                .build();
    }

    @Test
    @DisplayName("追加写后能按顺序读回，且 jsonl 一行一条")
    void appendAndRead_shouldKeepOrder() throws IOException {
        String sid = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";

        store.append(userA, sid, record(1, "user", "帮我搜手机"));
        store.append(userA, sid, record(2, "assistant", "找到 3 款"));
        store.append(userA, sid, record(3, "user", "再看看华为的"));

        List<ChatMessageRecord> history = store.readHistory(userA, sid);
        assertEquals(3, history.size());
        assertEquals("帮我搜手机", history.get(0).getContent());
        assertEquals("找到 3 款", history.get(1).getContent());
        assertEquals("再看看华为的", history.get(2).getContent());

        // 验证确实是一行一条 JSON
        Path file = store.resolveSessionFile(userA, sid);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("{") && lines.get(0).endsWith("}"));
    }

    @Test
    @DisplayName("批量追加一次写完多条")
    void appendAll_shouldWriteAll() {
        String sid = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2";
        store.appendAll(userA, sid, List.of(
                record(1, "user", "你好"),
                record(2, "assistant", "在的"),
                record(3, "user", "查一下订单")));
        assertEquals(3, store.readHistory(userA, sid).size());
    }

    @Test
    @DisplayName("用户隔离：同一 sid 在不同用户目录下互不可见")
    void differentUsers_shouldNotSeeEachOther() {
        String sid = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3";
        store.append(userA, sid, record(1, "user", "A 的私密消息"));

        // A 能读到
        assertEquals(1, store.readHistory(userA, sid).size());
        // B 读同一个 sid 读不到（因为路径里是 B 自己的目录）
        assertTrue(store.readHistory(userB, sid).isEmpty());
        // B 的列表里也没有
        assertTrue(store.listSessions(userB).isEmpty());
        // A 的列表里有
        assertEquals(1, store.listSessions(userA).size());
    }

    @Test
    @DisplayName("路径穿越输入被拒绝，不会写到 rootDir 之外")
    void pathTraversal_shouldBeRejected() {
        assertThrows(SessionForbiddenException.class,
                () -> store.append(userA, "../../evil", record(1, "user", "x")));
        assertThrows(SessionForbiddenException.class,
                () -> store.readHistory(userA, "..%2F..%2Fevil"));
    }

    @Test
    @DisplayName("会话列表按更新时间倒序，标题取首条 user 消息")
    void listSessions_shouldSortByUpdateTimeDesc() {
        String sidOld = "1111111111111111111111111111111a";
        String sidNew = "2222222222222222222222222222222b";

        store.append(userA, sidOld, record(1, "user", "第一个会话的问题"));
        store.append(userA, sidNew, record(1, "user", "第二个会话的问题"));

        List<SessionSummary> list = store.listSessions(userA);
        assertEquals(2, list.size());
        // 后写入的 updateTime 更大，应排在前
        assertEquals(sidNew, list.get(0).getSessionId());
        assertEquals("第二个会话的问题", list.get(0).getTitle());
        assertEquals("第一个会话的问题", list.get(1).getTitle());
    }

    @Test
    @DisplayName("标题超过 20 字会截断")
    void title_shouldTruncate() {
        String sid = "3333333333333333333333333333333c";
        store.append(userA, sid, record(1, "user", "这是一个非常非常长的用户问题需要被截断处理"));

        SessionSummary summary = store.summarize(userA, sid);
        assertTrue(summary.getTitle().length() <= 23);
        assertTrue(summary.getTitle().endsWith("..."));
    }

    @Test
    @DisplayName("删除会话后文件消失")
    void delete_shouldRemoveFile() {
        String sid = "4444444444444444444444444444444d";
        store.append(userA, sid, record(1, "user", "待删除"));
        assertTrue(store.exists(userA, sid));

        store.delete(userA, sid);
        assertFalse(store.exists(userA, sid));
        assertTrue(store.readHistory(userA, sid).isEmpty());
    }

    @Test
    @DisplayName("损坏的行被跳过，不影响其余历史")
    void corruptedLine_shouldBeSkipped() throws IOException {
        String sid = "5555555555555555555555555555555e";
        store.append(userA, sid, record(1, "user", "正常消息"));

        // 人为追加一行坏 JSON
        Files.writeString(store.resolveSessionFile(userA, sid),
                "{ this is not valid json\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        store.append(userA, sid, record(3, "user", "另一条正常消息"));

        List<ChatMessageRecord> history = store.readHistory(userA, sid);
        assertEquals(2, history.size());
        assertEquals("正常消息", history.get(0).getContent());
        assertEquals("另一条正常消息", history.get(1).getContent());
    }

    @Test
    @DisplayName("readForContext 能把历史还原为 LLM 协议消息（含合规的 tool 消息）")
    void readForContext_shouldRebuildLlmMessages() {
        String sid = "6666666666666666666666666666666f";
        store.append(userA, sid, record(1, "user", "查订单"));
        store.append(userA, sid, record(2, "assistant", "你的订单在配送中"));
        // tool 消息必须带 toolCallId：OpenAI/DeepSeek 要求每条 tool 消息
        // 关联到 assistant.tool_calls 中的某个调用，缺失会返回 422。
        store.append(userA, sid, ChatMessageRecord.builder()
                .seq(3L).ts(System.currentTimeMillis())
                .role("tool").name("get_order_by_id").content("{\"id\":1}")
                .toolCallId("call_order_1")
                .build());

        List<LlmMessage> messages = store.readForContext(userA, sid);
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("tool", messages.get(2).getRole());
        assertEquals("get_order_by_id", messages.get(2).getName());
        assertEquals("call_order_1", messages.get(2).getToolCallId());
    }
}
