package com.campus.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.config.AgentSessionProperties;
import com.campus.agent.llmclient.dto.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话历史文件存储：把每条消息以 JSON Lines 格式追加写入
 * {@code {rootDir}/{userId}/{sessionId}.jsonl}。
 * <p>
 * <b>为什么用 JSONL（一行一条 JSON）而不是一个大 JSON 数组？</b>
 * <ul>
 *     <li><b>追加写</b>：新消息直接 append，无需"读全量→改→写全量"，并发友好、O(1) 写入；</li>
 *     <li><b>顺序天然保证</b>：行号即顺序，恢复历史时顺序读取即可；</li>
 *     <li><b>容错</b>：某一行损坏（如写入中断）只影响该行，不影响整个文件解析。</li>
 * </ul>
 *
 * <p><b>路径安全（三重防线）</b>：
 * <ol>
 *     <li>{@code userId} 只来自 JWT 解析结果（Long），不可能是攻击者构造的字符串；</li>
 *     <li>{@code sessionId} 先经 {@link SessionIds#requireValid} 白名单校验（32 位小写 hex），
 *         挡掉 {@code ../../etc/passwd} 这类输入；</li>
 *     <li>最终路径再做一次 {@link #normalizeAndVerify}，确认规范化后仍位于 rootDir 之内。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileSessionStore {

    private final ObjectMapper objectMapper;

    private final AgentSessionProperties properties;

    // ==================== 写 ====================

    /**
     * 追加一条消息到会话文件。
     *
     * @param userId    会话归属用户（来自 JWT）
     * @param sessionId 会话号
     * @param record    消息记录
     */
    public void append(Long userId, String sessionId, ChatMessageRecord record) {
        Path file = resolveSessionFile(userId, sessionId);
        try {
            Files.createDirectories(file.getParent());
            String line = objectMapper.writeValueAsString(record);
            // APPEND + CREATE：文件不存在则创建，存在则追加
            Files.writeString(file, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 落盘失败属于基础设施异常，向上抛（此时 Agent 已在内存里完成推理，
            // 由上层决定是否因"写历史失败"而中断整个请求）
            throw new UncheckedIOException("写入会话历史文件失败：" + file, e);
        }
    }

    /**
     * 批量追加（一次 run 结束后把新增的多条消息一次写完，减少 IO 次数）。
     */
    public void appendAll(Long userId, String sessionId, List<ChatMessageRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Path file = resolveSessionFile(userId, sessionId);
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (ChatMessageRecord r : records) {
                sb.append(objectMapper.writeValueAsString(r))
                        .append(System.lineSeparator());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("批量写入会话历史文件失败：" + file, e);
        }
    }

    // ==================== 读 ====================

    /**
     * 读取某会话的全部历史消息（按 seq 顺序）。
     */
    public List<ChatMessageRecord> readHistory(Long userId, String sessionId) {
        Path file = resolveSessionFile(userId, sessionId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        List<ChatMessageRecord> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    result.add(objectMapper.readValue(line, ChatMessageRecord.class));
                } catch (Exception e) {
                    // 单行损坏不应导致整个历史读取失败
                    log.warn("跳过损坏的历史记录行：sessionId={}, line={}", sessionId, line, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取会话历史文件失败：" + file, e);
        }
        return result;
    }

    /**
     * 读取历史并转换为 LLM 协议消息（用于回填 ReAct 上下文）。
     * <p>
     * 注意：assistant 的 tool_calls 结构在展示型 jsonl 中没有完整保存，
     * 因此回填时 assistant 消息只还原文本内容；这会丢失"当时调用了哪个工具"的信息，
     * 属于展示存储的固有权衡。如需完整可回放，应另存协议级原文。
     */
    public List<LlmMessage> readForContext(Long userId, String sessionId) {
        List<LlmMessage> messages = new ArrayList<>();
        for (ChatMessageRecord r : readHistory(userId, sessionId)) {
            if (r.getRole() == null) {
                continue;
            }
            String content = nullToEmpty(r.getContent());
            if ("user".equals(r.getRole())) {
                messages.add(LlmMessage.user(content));
            } else if ("assistant".equals(r.getRole())) {
                // 携带 toolCalls 的 assistant 是协议中间态：必须重建 toolCalls，
                // 否则后续 tool 消息因找不到对应 tool_call_id 而被 API 拒绝（422）。
                // 它的 content 通常为空，前端按 intermediate 标记过滤展示。
                if (r.getToolCalls() != null && !r.getToolCalls().isEmpty()) {
                    messages.add(LlmMessage.builder()
                            .role("assistant")
                            .content(content)
                            .toolCalls(r.getToolCalls())
                            .build());
                } else {
                    messages.add(LlmMessage.assistant(content));
                }
            } else if ("tool".equals(r.getRole())) {
                // 【防御】旧版本写入的 jsonl 里 tool 消息没有 toolCallId，
                // 且不存在配对的 assistant(tool_calls) —— 这类孤儿消息
                // 一并发送给 LLM 必然 422（missing field tool_call_id）。
                // 直接跳过：宁可少一段历史，也不能让整个请求失败。
                if (r.getToolCallId() == null || r.getToolCallId().isEmpty()) {
                    log.warn("[agent] 跳过无 toolCallId 的孤儿 tool 消息: sessionId={}, name={}",
                            sessionId, r.getName());
                    continue;
                }
                messages.add(LlmMessage.builder()
                        .role("tool")
                        .name(r.getName())
                        .content(content)
                        .toolCallId(r.getToolCallId())
                        .build());
            }
            // system 等角色不回填，由调度器重新注入
        }
        return messages;
    }

    /**
     * 列出某用户的全部会话摘要，按最后更新时间倒序。
     */
    public List<SessionSummary> listSessions(Long userId) {
        Path userDir = resolveUserDir(userId);
        if (!Files.isDirectory(userDir)) {
            return new ArrayList<>();
        }
        List<SessionSummary> summaries = new ArrayList<>();
        try (Stream<Path> files = Files.list(userDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(p -> {
                        String sid = p.getFileName().toString().replace(".jsonl", "");
                        if (!SessionIds.isValid(sid)) {
                            return;
                        }
                        summaries.add(summarize(userId, sid));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("列出会话目录失败：" + userDir, e);
        }
        summaries.sort(Comparator.comparingLong(SessionSummary::getUpdateTime).reversed());
        return summaries;
    }

    /**
     * 读取某会话并生成摘要（标题取首条 user 消息前 20 字）。
     */
    public SessionSummary summarize(Long userId, String sessionId) {
        List<ChatMessageRecord> history = readHistory(userId, sessionId);
        SessionSummary summary = new SessionSummary();
        summary.setSessionId(sessionId);
        summary.setMessageCount(history.size());
        summary.setCreateTime(history.isEmpty() ? System.currentTimeMillis()
                : orDefault(history.get(0).getTs()));
        summary.setUpdateTime(history.isEmpty() ? summary.getCreateTime()
                : orDefault(history.get(history.size() - 1).getTs()));
        summary.setTitle(buildTitle(history));
        return summary;
    }

    /**
     * 会话是否存在（文件维度）。
     */
    public boolean exists(Long userId, String sessionId) {
        return Files.exists(resolveSessionFile(userId, sessionId));
    }

    // ==================== 删 ====================

    /**
     * 删除某会话的历史文件。
     */
    public void delete(Long userId, String sessionId) {
        Path file = resolveSessionFile(userId, sessionId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("删除会话文件失败：" + file, e);
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 解析某用户的会话根目录。
     */
    public Path resolveUserDir(Long userId) {
        if (userId == null) {
            throw new SessionForbiddenException("未登录");
        }
        // userId 是 Long 的字符串形式，不可能包含路径分隔符或 ..，
        // 但仍做一次规范化+越界校验，形成纵深防御
        Path root = Paths.get(properties.getRootDir()).toAbsolutePath().normalize();
        Path userDir = root.resolve(String.valueOf(userId)).normalize();
        if (!userDir.startsWith(root)) {
            throw new SessionForbiddenException("非法的用户目录");
        }
        return userDir;
    }

    /**
     * 解析某会话文件路径，并做格式与越界双重校验。
     */
    public Path resolveSessionFile(Long userId, String sessionId) {
        // 第一重：格式白名单，挡掉 ../../etc/passdown 之类
        SessionIds.requireValid(sessionId);
        Path userDir = resolveUserDir(userId);
        // 第二重：规范化后必须仍在 userDir 之内
        Path file = userDir.resolve(sessionId + ".jsonl").normalize();
        if (!file.startsWith(userDir)) {
            throw new SessionForbiddenException("非法的会话路径");
        }
        return file;
    }

    private String buildTitle(List<ChatMessageRecord> history) {
        for (ChatMessageRecord r : history) {
            if ("user".equals(r.getRole()) && r.getContent() != null && !r.getContent().isBlank()) {
                String content = r.getContent().trim();
                return content.length() > 20 ? content.substring(0, 20) + "..." : content;
            }
        }
        return "新会话";
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private long orDefault(Long v) {
        return v == null ? System.currentTimeMillis() : v;
    }
}
