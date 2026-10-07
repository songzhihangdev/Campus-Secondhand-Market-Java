package com.campus.agent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.config.AgentSessionProperties;
import com.campus.agent.config.ContextCompressProperties;
import com.campus.agent.config.MallProperties;
import com.campus.agent.config.SkillRegistry;
import com.campus.agent.context.ContextCompressor;
import com.campus.agent.executor.MallApiExecutor;
import com.campus.agent.llmclient.client.LlmClient;
import com.campus.agent.llmclient.converter.SkillToToolConverter;
import com.campus.agent.llmclient.dto.LlmResult;
import com.campus.agent.session.FileSessionStore;
import com.campus.agent.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReActAgentScheduler 单元测试。
 * <p>
 * 用 Mockito 模拟 LlmClient 与 MallApiExecutor（不发起真实 LLM/商城请求），
 * 技能注册、转换器、会话管理使用真实组件（落盘到临时目录），完整跑通 ReAct 循环。
 * <p>
 * <b>适配说明</b>：run 签名现为 {@code run(sessionId, userId, query)}，
 * 身份来自 JWT；sessionId 必须是合法的 32 位 hex。
 */
class ReActAgentSchedulerTest {

    private static final Long USER_ID = 1001L;

    private LlmClient llmClient;
    private MallApiExecutor mallApiExecutor;
    private InMemoryAgentMemoryStore memoryStore;
    private SessionManager sessionManager;
    private ReActAgentScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        // 真实的注册中心 + 转换器（读取 classpath 的技能配置）
        SkillRegistry registry = new SkillRegistry(objectMapper, new MallProperties());
        registry.load();
        SkillToToolConverter converter = new SkillToToolConverter(objectMapper);

        // 落盘到临时目录，避免污染真实对话目录
        Path tempRoot = Files.createTempDirectory("agent-test-sessions");
        AgentSessionProperties sessionProperties = new AgentSessionProperties();
        sessionProperties.setRootDir(tempRoot.toString());

        FileSessionStore fileStore = new FileSessionStore(objectMapper, sessionProperties);

        // 被 mock 的外部依赖
        llmClient = mock(LlmClient.class);
        mallApiExecutor = mock(MallApiExecutor.class);
        memoryStore = new InMemoryAgentMemoryStore();

        SessionManager manager = new SessionManager(memoryStore, fileStore, sessionProperties);
        sessionManager = manager;

        // 真实压缩器（用默认配置），验证与调度链路的集成
        ContextCompressor compressor = new ContextCompressor(
                new ContextCompressProperties(), objectMapper);

        scheduler = new ReActAgentScheduler(
                llmClient, mallApiExecutor, registry, converter,
                manager, compressor, objectMapper, 10);
    }

    /** 构造一次 search_items 工具调用结果 */
    private LlmResult searchToolCall() {
        return LlmResult.toolCalls(List.of(
                LlmResult.ParsedToolCall.builder()
                        .toolCallId("call_1")
                        .skillName("search_items")
                        .arguments(Map.of("key", "手机", "brand", "华为"))
                        .build()));
    }

    /** 合法的 sessionId（32 位小写 hex），带随机后缀避免多次运行间文件残留干扰 */
    private String sid(String seed) {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        StringBuilder sb = new StringBuilder(seed);
        while (sb.length() < 24) {
            sb.append('0');
        }
        return (sb.substring(0, 24) + suffix).toLowerCase();
    }

    /** 正常闭环：第1轮工具调用 -> 第2轮最终回答 */
    @Test
    void run_toolCallThenText_shouldComplete() {
        when(llmClient.chat(anyList(), any()))
                .thenReturn(searchToolCall())
                .thenReturn(LlmResult.text("为你找到以下华为手机：…"));
        when(mallApiExecutor.execute(eq("search_items"), any()))
                .thenReturn("{\"total\":2,\"list\":[]}");

        AgentResult result = scheduler.run(sid("a1"), USER_ID, "帮我搜一下华为手机");

        assertTrue(result.isCompleted());
        assertEquals("为你找到以下华为手机：…", result.getFinalAnswer());
        assertEquals(2, result.getRounds());

        // 一次工具调用记录
        assertEquals(1, result.getCallRecords().size());
        AgentResult.AgentCallRecord record = result.getCallRecords().get(0);
        assertEquals("search_items", record.getSkillName());
        assertTrue(record.getObservation().contains("\"total\":2"));

        verify(mallApiExecutor, times(1)).execute(eq("search_items"), any());
    }

    /** 达到最大轮次仍未给最终回答：强制终止 */
    @Test
    void run_reachMaxRound_shouldTerminate() {
        scheduler.setMaxRound(3);
        when(llmClient.chat(anyList(), any())).thenReturn(searchToolCall());
        when(mallApiExecutor.execute(eq("search_items"), any()))
                .thenReturn("{\"total\":1}");

        AgentResult result = scheduler.run(sid("b2"), USER_ID, "一直搜");

        assertFalse(result.isCompleted());
        assertEquals("已到达最大执行轮次，任务终止", result.getFinalAnswer());
        assertEquals(3, result.getRounds());
        assertEquals(3, result.getCallRecords().size());
    }

    /** 工具执行抛异常：异常信息作为 observation，模型仍可继续给出最终回答 */
    @Test
    void run_toolThrows_observationShouldContainErrorAndContinue() {
        when(llmClient.chat(anyList(), any()))
                .thenReturn(searchToolCall())
                .thenReturn(LlmResult.text("搜索服务暂时不可用，请稍后再试。"));
        when(mallApiExecutor.execute(eq("search_items"), any()))
                .thenThrow(new RuntimeException("boom"));

        AgentResult result = scheduler.run(sid("c3"), USER_ID, "搜手机");

        assertTrue(result.isCompleted());
        AgentResult.AgentCallRecord record = result.getCallRecords().get(0);
        assertTrue(record.getObservation().contains("工具执行异常：boom"));
    }

    /** LLM 直接返回文本（无需工具）：一轮完成 */
    @Test
    void run_directText_shouldCompleteInOneRound() {
        when(llmClient.chat(anyList(), any()))
                .thenReturn(LlmResult.text("你好，我是商城助手。"));

        AgentResult result = scheduler.run(sid("d4"), USER_ID, "你好");

        assertTrue(result.isCompleted());
        assertEquals(1, result.getRounds());
        assertTrue(result.getCallRecords().isEmpty());
    }

    /** 多会话隔离：两个会话使用各自记忆，互不影响 */
    @Test
    void multipleSessions_shouldBeIsolated() {
        ObjectMapper objectMapper = new ObjectMapper();
        SkillRegistry registry = new SkillRegistry(objectMapper, new MallProperties());
        registry.load();
        SkillToToolConverter converter = new SkillToToolConverter(objectMapper);

        LlmClient llmB = mock(LlmClient.class);
        MallApiExecutor execB = mock(MallApiExecutor.class);
        // 复用 setUp 中同一个 SessionManager（同一份临时目录，避免测试间互相污染）
        ReActAgentScheduler schedulerB = new ReActAgentScheduler(
                llmB, execB, registry, converter, sessionManager,
                new ContextCompressor(new ContextCompressProperties(), objectMapper),
                objectMapper, 10);

        String sidA = sid("aaaa");
        String sidB = sid("bbbb");

        // 会话A：工具调用 + 回答
        when(llmClient.chat(anyList(), any()))
                .thenReturn(searchToolCall())
                .thenReturn(LlmResult.text("A的结果"));
        when(mallApiExecutor.execute(eq("search_items"), any())).thenReturn("{}");
        scheduler.run(sidA, USER_ID, "A的问题");

        // 会话B：直接回答
        when(llmB.chat(anyList(), any())).thenReturn(LlmResult.text("B的结果"));
        schedulerB.run(sidB, USER_ID, "B的问题");

        assertEquals(2, memoryStore.sessionCount());
        // A 消息构成：system + user + assistant(tool_calls) + tool + assistant = 5 条
        // B 消息构成：system + user + assistant = 3 条
        assertEquals(5, memoryStore.loadOrCreate(sidA, USER_ID).size());
        assertEquals(3, memoryStore.loadOrCreate(sidB, USER_ID).size());
    }

    /** LLM 调用本身抛异常：应返回未完成结果，而不是向上抛 */
    @Test
    void run_llmThrows_shouldReturnFailureResult() {
        when(llmClient.chat(anyList(), any()))
                .thenThrow(new RuntimeException("model down"));

        AgentResult result = scheduler.run(sid("e5"), USER_ID, "你好");

        assertFalse(result.isCompleted());
        assertTrue(result.getFinalAnswer().contains("模型调用失败"));
    }
}
