package com.campus.agent.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.config.SkillRegistry;
import com.campus.agent.context.ContextCompressor;
import com.campus.agent.context.MallContextHolder;
import com.campus.agent.executor.MallApiExecutor;
import com.campus.agent.llmclient.client.LlmClient;
import com.campus.agent.llmclient.converter.SkillToToolConverter;
import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.llmclient.dto.LlmResponse.FunctionCall;
import com.campus.agent.llmclient.dto.LlmResponse.ToolCall;
import com.campus.agent.llmclient.dto.LlmResult;
import com.campus.agent.llmclient.dto.ToolDefinition;
import com.campus.agent.session.SessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct Agent 调度器（核心 Bean）。
 * <p>
 * 只负责“推理(Reason) -> 行动(Act) -> 观察(Observation)”的循环编排，
 * 直接复用已有的 {@link LlmClient}、{@link MallApiExecutor}、{@link SkillToToolConverter}，
 * 不重写它们，也不引入 LangChain。
 */
@Slf4j
@Component
public class ReActAgentScheduler {

    /** observation 日志中保留的最大长度，避免刷屏 */
    private static final int OBSERVATION_MAX_LEN = 800;

    /**
     * 系统提示：定义 Agent 角色与“如何解读工具返回（observation）”的硬规则。
     * 没有它，模型在拿到合法 JSON 分页结果时也可能误判为“接口不可用”。
     */
    private static final String SYSTEM_PROMPT = String.join("\n",
            "你是一个专业的校园二手集市导购助手，负责帮用户查找商品、管理意向单、下单与查询订单等。",
            "",
            "【术语约定】本平台的「意向单」就是「购物车」——用户把中意的闲置物品先存起来，之后再决定要不要下单。",
            "   无论用户说“意向单”“购物车”“我收藏的东西”“我加的东西”“我想要的”，",
            "   都应理解为同一个功能，对应工具 list_my_carts / add_cart_item 等，不要回答“我不知道什么是意向单”。",
            "",
            "你基于 ReAct（推理-行动-观察）工作：可调用提供的工具获取真实商城数据，再基于返回结果回答用户。",
            "",
            "关于工具返回结果（observation）的判定规则（务必遵守）：",
            "1. 若 observation 是合法 JSON（尤其包含 total / list 的分页对象），表示接口调用【成功】并返回了真实数据，",
            "   必须基于其中的 list 内容如实回答，提取商品名称、价格（单位：分）、库存等信息呈现给用户。",
            "2. 只有当 observation 以“【调用失败】”开头或明显是错误文案时，才向用户说明接口调用失败；",
            "   严禁凭空断言“商品查询接口不可用”。",
            "3. 若 observation 中的 list 为空（total 为 0 或 list 为空数组），说明没有符合条件的商品，",
            "   应如实告知“未找到相关商品”，并建议用户放宽筛选（更换关键字、品牌或价格区间），绝不要说接口不可用。",
            "   同理，询问意向单内容而返回空列表时，应回答“你的意向单还是空的”，而不是“功能不可用”。",
            "4. 搜索商品优先使用 search_items；当用户给出“华为手机”这类组合词时，建议把关键字拆开，",
            "   例如 key=\"华为\" 或 brand=\"华为\"，因为后端是按商品名称做模糊匹配，整词“华为手机”很可能匹配不到。",
            "5. 系统已自动完成登录鉴权，你没有 user_login 类工具，也不要引导用户登录；",
            "   若某个接口返回 401，只需告知用户会话已过期、需要重新登录后重试。",
            "6. 你是校园二手交易助手：闲置物品由同学们自己发布，你只能协助操作当前登录用户自己发布的物品。",
            "7. 涉及下单、支付的操作，你必须先向用户展示完整信息（物品/成色/金额/地址）并取得确认，",
            "   严禁在未确认的情况下调用；支付密码绝不要向用户索取。",
            "8. 回答面向用户、简洁清晰，使用中文。"
    );

    private final LlmClient llmClient;
    private final MallApiExecutor mallApiExecutor;
    private final SkillRegistry skillRegistry;
    private final SkillToToolConverter skillToToolConverter;
    private final SessionManager sessionManager;
    private final ContextCompressor contextCompressor;
    private final ObjectMapper objectMapper;

    /** 最大循环轮次，从配置 agent.max-round 读取，默认 10 */
    private int maxRound;

    public ReActAgentScheduler(LlmClient llmClient,
                               MallApiExecutor mallApiExecutor,
                               SkillRegistry skillRegistry,
                               SkillToToolConverter skillToToolConverter,
                               SessionManager sessionManager,
                               ContextCompressor contextCompressor,
                               ObjectMapper objectMapper,
                               @Value("${agent.max-round:10}") int maxRound) {
        this.llmClient = llmClient;
        this.mallApiExecutor = mallApiExecutor;
        this.skillRegistry = skillRegistry;
        this.skillToToolConverter = skillToToolConverter;
        this.sessionManager = sessionManager;
        this.contextCompressor = contextCompressor;
        this.objectMapper = objectMapper;
        this.maxRound = maxRound;
    }

    /** 测试/特殊场景下调整最大轮次 */
    public void setMaxRound(int maxRound) {
        this.maxRound = maxRound;
    }

    /**
     * 运行一次会话任务。
     * <p>
     * <b>改造要点</b>：身份不再由模型调 user_login 获得，而是每轮从 JWT 透传：
     * {@code AuthInterceptor} 已把 JWT 写入 {@link MallContextHolder}，
     * 这里只负责把本轮新增消息落盘。
     *
     * @param sessionId 会话 id（多会话隔离）
     * @param userId    当前登录用户 id（来自 JWT，用于归属校验与文件分目录）
     * @param userQuery 用户本轮自然语言指令
     * @return 最终结果（含答复、是否完成、调用记录）
     */
    public AgentResult run(String sessionId, Long userId, String userQuery) {
        // a. 获取会话上下文（不存在则创建，必要时从文件回填历史）
        AgentMemory memory = sessionManager.openSession(sessionId, userId);
        // 记录本轮起始位置，便于结束后只落盘"新增"的消息
        int sizeBeforeRun = memory.size();

        memory.addSystemIfAbsent(SYSTEM_PROMPT);
        memory.addUser(userQuery);

        // 把全部技能转成 tools（每轮复用同一份工具定义）
        List<ToolDefinition> tools = skillToToolConverter.convertAll(
                new ArrayList<>(skillRegistry.allLlmSkills()));

        List<AgentResult.AgentCallRecord> callRecords = new ArrayList<>();

        // b. ReAct 循环（finally 中清理 ThreadLocal，避免线程池串号/泄漏）
        try {
        for (int round = 1; round <= maxRound; round++) {

            // i. 调用 LLM
            //    上下文压缩：把完整历史裁剪成"够用"的短上下文后再发送。
            //    注意裁剪只作用于送给模型的临时视图，AgentMemory 内部与落盘 jsonl
            //    始终保留完整 observation，因此用户查看历史时不会丢失任何信息。
            LlmResult llmResult;
            try {
                List<LlmMessage> contextForLlm = contextCompressor.compress(memory.snapshot());
                llmResult = llmClient.chat(contextForLlm, tools);
            } catch (Exception e) {
                // LLM 自身异常：无法再交给模型处理，直接终止并返回失败
                log.error("第{}轮调用 LLM 失败，任务终止", round, e);
                persistRound(sessionId, userId, memory, sizeBeforeRun);
                return AgentResult.builder()
                        .sessionId(sessionId)
                        .finalAnswer("【任务终止】模型调用失败：" + e.getMessage())
                        .completed(false)
                        .rounds(round)
                        .callRecords(callRecords)
                        .build();
            }

            // ii. 工具调用分支
            if (llmResult.isToolCalls()) {
                List<LlmResult.ParsedToolCall> parsedCalls = llmResult.getToolCalls();

                // 先为每个调用重建标准 ToolCall（保证 id 非空、arguments 为 JSON 字符串）
                List<ToolCall> rawToolCalls = new ArrayList<>();
                for (int i = 0; i < parsedCalls.size(); i++) {
                    LlmResult.ParsedToolCall parsed = parsedCalls.get(i);
                    String callId = parsed.getToolCallId() != null
                            ? parsed.getToolCallId()
                            : ("call_" + round + "_" + i);
                    rawToolCalls.add(rebuildToolCall(callId, parsed));
                }

                // 消息顺序：先写带 tool_calls 的 assistant 消息
                memory.addAssistantToolCalls(rawToolCalls);

                // 再逐个执行并写入对应的 tool 结果消息
                for (int i = 0; i < parsedCalls.size(); i++) {
                    LlmResult.ParsedToolCall parsed = parsedCalls.get(i);
                    String callId = rawToolCalls.get(i).getId();
                    String skillName = parsed.getSkillName();
                    Map<String, Object> arguments = parsed.getArguments();

                    // 执行工具；执行期异常（含高风险拦截文本）统一作为 observation，不抛出
                    String observation = executeSafely(skillName, arguments);

                    // 日志：轮次、技能名、参数、observation
                    log.info("第{}轮 调用技能[{}]，参数={}，observation={}",
                            round, skillName, arguments, truncate(observation));

                    memory.addToolResult(callId, skillName, observation);

                    callRecords.add(AgentResult.AgentCallRecord.builder()
                            .round(round)
                            .skillName(skillName)
                            .arguments(arguments)
                            .observation(observation)
                            .build());
                }
                // 本轮行动+观察完成，进入下一轮推理
                continue;
            }

            // iii. 最终文本回答分支：保存回答并退出
            String finalAnswer = llmResult.getContent();
            memory.addAssistantText(finalAnswer);
            persistRound(sessionId, userId, memory, sizeBeforeRun);
            log.info("第{}轮 模型给出最终回答，任务完成", round);
            return AgentResult.builder()
                    .sessionId(sessionId)
                    .finalAnswer(finalAnswer)
                    .completed(true)
                    .rounds(round)
                    .callRecords(callRecords)
                    .build();
        }
        } finally {
            // 请求结束清理线程级 token，防止 Tomcat 线程池复用导致会话间串号
            MallContextHolder.clear();
        }

        // d. 达到最大轮次，强制终止
        persistRound(sessionId, userId, memory, sizeBeforeRun);
        log.warn("会话[{}]已到达最大执行轮次{}，任务终止", sessionId, maxRound);
        return AgentResult.builder()
                .sessionId(sessionId)
                .finalAnswer("已到达最大执行轮次，任务终止")
                .completed(false)
                .rounds(maxRound)
                .callRecords(callRecords)
                .build();
    }

    /**
     * 落盘本轮新增的消息（只落 {@code sizeBeforeRun} 之后追加的部分，避免重复写历史）。
     * <p>
     * 落盘失败只告警不抛异常：对话已经在内存里跑完并返回给用户，
     * 不应因为"写历史文件失败"而让整个请求失败。
     */
    private void persistRound(String sessionId, Long userId, AgentMemory memory, int sizeBeforeRun) {
        try {
            List<LlmMessage> all = memory.snapshot();
            int from = Math.max(0, Math.min(sizeBeforeRun, all.size()));
            List<LlmMessage> fresh = all.subList(from, all.size());
            sessionManager.persistRound(sessionId, userId, fresh);
        } catch (Exception e) {
            log.warn("本轮对话落盘失败（不影响本次响应）：sessionId={}", sessionId, e);
        }
    }

    /**
     * 安全执行商城接口：任何异常都转成可读 observation，不向上抛，交给下一轮 LLM 处理。
     */
    private String executeSafely(String skillName, Map<String, Object> arguments) {
        try {
            return mallApiExecutor.execute(skillName, arguments);
        } catch (Exception e) {
            log.error("执行技能[{}]发生异常，作为 observation 交回模型", skillName, e);
            return "工具执行异常：" + e.getMessage();
        }
    }

    /**
     * 由解析结果重建可写入记忆的 ToolCall（把参数 Map 重新序列化为 arguments JSON 字符串）。
     */
    private ToolCall rebuildToolCall(String callId, LlmResult.ParsedToolCall parsed) {
        String argumentsJson;
        try {
            argumentsJson = objectMapper.writeValueAsString(parsed.getArguments());
        } catch (JsonProcessingException e) {
            argumentsJson = "{}";
        }
        FunctionCall function = FunctionCall.builder()
                .name(parsed.getSkillName())
                .arguments(argumentsJson)
                .build();
        return ToolCall.builder()
                .id(callId)
                .type("function")
                .function(function)
                .build();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > OBSERVATION_MAX_LEN
                ? text.substring(0, OBSERVATION_MAX_LEN) + "..." : text;
    }
}
