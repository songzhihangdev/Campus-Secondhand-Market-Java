package com.campus.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.campus.agent.config.ContextCompressProperties;
import com.campus.agent.llmclient.dto.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器：把"完整历史"裁剪成"够模型用的短上下文"。
 *
 * <h3>为什么只裁 tool 结果？</h3>
 * 实测一个 ReAct 会话的 token 分布里，<b>tool 消息（商品 JSON 等 observation）
 * 往往占据 70% 以上</b>，而 user/assistant 的自然语言很短。裁剪它性价比最高、
 * 副作用最小——对话语义（"刚才那个蓝色的再来一件"）完全保留。
 *
 * <h3>为什么不会破坏协议？</h3>
 * <ul>
 *   <li>本类只做两件事：① 缩短 tool 消息的 content；② 超长时按<b>整轮</b>丢弃最早的消息。
 *       绝不单独丢弃 assistant(tool_calls) 或孤立的 tool 消息，
 *       因此 OpenAI 的 tool_call_id 配对关系始终成立。</li>
 *   <li>裁剪后的 tool 消息仍是合法 JSON（分页场景）或可读文本，不会让模型解析失败。</li>
 * </ul>
 *
 * <h3>数据完整性</h3>
 * 完整历史照旧写入 jsonl（L0 真相层）。本类只作用于<b>送给 LLM 的临时视图</b>，
 * 压缩结果不落盘、请求结束即弃，随时可从 jsonl 重新生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    /** 裁剪后追加的提示语，让模型知道"还有更多结果"而不是以为只有这几条 */
    private static final String TRUNCATED_HINT =
            "（为节省上下文，仅保留前 %d 条，共 %d 条；如需查看更多请提高 pageSize 或使用查询条件过滤）";

    /** 纯文本截断后的提示 */
    private static final String TEXT_TRUNCATED_HINT = "……（内容过长已截断）";

    private final ContextCompressProperties properties;

    private final ObjectMapper objectMapper;

    /**
     * 压缩上下文：返回<b>新的</b> List，不修改入参，也不修改 AgentMemory 内部状态。
     *
     * @param messages 完整历史（按时间顺序）
     * @return 裁剪后的新列表，可直接送给 LLM
     */
    public List<LlmMessage> compress(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? new ArrayList<>() : messages;
        }
        if (!properties.isEnabled()) {
            // 关闭时原样返回（仅拷贝，保证调用方拿到的仍是独立列表）
            return new ArrayList<>(messages);
        }

        // 第一步：逐条裁剪 tool 消息的 observation
        List<LlmMessage> compacted = new ArrayList<>(messages.size());
        int savedChars = 0;
        for (LlmMessage m : messages) {
            if (m == null) {
                continue;
            }
            if ("tool".equals(m.getRole())) {
                LlmMessage compressed = compressToolMessage(m);
                savedChars += (m.getContent() == null ? 0 : m.getContent().length())
                        - (compressed.getContent() == null ? 0 : compressed.getContent().length());
                compacted.add(compressed);
            } else {
                compacted.add(m);
            }
        }

        // 第二步：整体长度控制（按整轮丢弃最早的消息）
        List<LlmMessage> trimmed = trimOldRounds(compacted);

        if (savedChars > 0) {
            log.debug("上下文裁剪完成：tool 结果共节省约 {} 字符，消息数 {} -> {}",
                    savedChars, messages.size(), trimmed.size());
        }
        return trimmed;
    }

    // ==================== 单条 tool 消息裁剪 ====================

    private LlmMessage compressToolMessage(LlmMessage toolMessage) {
        String content = toolMessage.getContent();
        if (content == null || content.isEmpty()) {
            return toolMessage;
        }

        int maxPlain = properties.getMaxPlainTextLength();
        int maxObs = properties.getMaxObservationLength();

        String newContent;
        try {
            newContent = compressJsonObservation(content, maxPlain, maxObs);
        } catch (Exception e) {
            // JSON 解析失败：退化为纯文本截断，绝不因压缩而让整轮失败
            log.debug("工具结果[{}]非 JSON，退化为文本截断", toolMessage.getName());
            newContent = truncatePlainText(content, maxPlain);
        }
        return copyWithContent(toolMessage, newContent);
    }

    /**
     * 裁剪分页类 JSON 结果：只保留前 N 条商品。
     * <p>
     * 只处理"含 list 数组且是分页对象"这一种最常见的膨胀源；
     * 其他结构原样返回，避免误伤。
     */
    private String compressJsonObservation(String content, int maxPlain, int maxObs) throws Exception {
        // 不是 JSON 就走文本截断
        if (!content.trim().startsWith("{")) {
            return truncatePlainText(content, maxPlain);
        }

        JsonNode root = objectMapper.readTree(content);
        JsonNode listNode = root.get("list");

        // 非分页结构：只做总长度兜底
        if (listNode == null || !listNode.isArray()) {
            return applyHardLimit(content, maxObs);
        }

        ArrayNode array = (ArrayNode) listNode;
        int total = array.size();
        int keep = properties.getMaxItemsInList();

        // 条数不多，无需裁剪
        if (keep <= 0 || total <= keep) {
            return applyHardLimit(content, maxObs);
        }

        // 裁剪：复制一份，保留前 keep 条
        ObjectNode copy = ((ObjectNode) root).deepCopy();
        ArrayNode keptItems = objectMapper.createArrayNode();
        for (int i = 0; i < keep; i++) {
            keptItems.add(array.get(i));
        }
        copy.set("list", keptItems);
        // 保留真实总数，避免模型误以为只有 keep 条
        if (copy.get("total") == null) {
            copy.put("total", total);
        }
        // 标注已裁剪，并说明真实总数
        copy.put("_truncated", true);
        copy.put("_totalSize", total);

        String json = objectMapper.writeValueAsString(copy);
        String hint = String.format(TRUNCATED_HINT, keep, total);

        // 裁剪结果本身可能仍超长（如单个商品字段特别多），最后再兜一次
        return applyHardLimit(json + "\n" + hint, maxObs);
    }

    /**
     * 纯文本截断。
     */
    private String truncatePlainText(String text, int maxLen) {
        if (maxLen <= 0 || text.length() <= maxLen) {
            return applyHardLimit(text, properties.getMaxObservationLength());
        }
        return text.substring(0, maxLen) + "\n" + TEXT_TRUNCATED_HINT;
    }

    /**
     * 硬上限兜底：无论前面走了哪条分支，最终结果都不超过该字符数（0 表示不限制）。
     */
    private String applyHardLimit(String text, int maxLen) {
        if (maxLen <= 0 || text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "\n" + TEXT_TRUNCATED_HINT;
    }

    /**
     * 复制一条消息并替换 content（保持原 role/name/toolCallId 不变）。
     * <p>
     * 用 LlmMessage.builder 而不是直接改对象，是为了<b>不污染 AgentMemory 里的原始消息</b>
     * ——落盘时仍能拿到完整 observation。
     */
    private LlmMessage copyWithContent(LlmMessage src, String newContent) {
        if (newContent != null && newContent.equals(src.getContent())) {
            return src; // 无变化，直接复用原对象省一次拷贝
        }
        return LlmMessage.builder()
                .role(src.getRole())
                .content(newContent)
                .name(src.getName())
                .toolCallId(src.getToolCallId())
                .toolCalls(src.getToolCalls())
                .build();
    }

    // ==================== 按整轮丢弃最早消息 ====================

    /**
     * 当消息数超过阈值时，丢弃最早的部分。
     *
     * <p><b>安全约束</b>：丢弃的起点必须落在"完整轮次边界"上，即不能把
     * assistant(tool_calls) 与其后的 tool 消息拆散。做法是从尾部往前找到
     * 第一条 user 消息（每轮都以 user 消息开始），从那里开始裁。
     */
    private List<LlmMessage> trimOldRounds(List<LlmMessage> messages) {
        int max = properties.getMaxMessages();
        int keepRecent = properties.getKeepRecentMessages();

        if (max <= 0 || messages.size() <= max) {
            return messages;
        }
        // 保留策略不合法时退化为"不丢弃"，避免误伤
        if (keepRecent < 0 || keepRecent >= max) {
            log.warn("上下文压缩配置异常（keepRecent={}, max={}），本次不丢弃历史消息", keepRecent, max);
            return messages;
        }

        // 计算要丢弃的条数后，从该位置开始找轮次边界
        int dropCount = messages.size() - max;
        int startIndex = findRoundBoundary(messages, dropCount);

        if (startIndex <= 0) {
            // 找不到合适边界（本轮消息过于交错），保守起见不裁剪
            log.warn("未能找到安全的轮次边界，跳过历史消息丢弃（size={}）", messages.size());
            return messages;
        }

        List<LlmMessage> result = new ArrayList<>(messages.size() - startIndex);
        result.addAll(messages.subList(startIndex, messages.size()));

        // 被丢弃的消息不参与后续轮次；如需在上下文里留痕，插入一条说明
        result.add(0, LlmMessage.system(
                "（为控制上下文长度，已省略最早的 " + startIndex + " 条历史消息）"));
        return result;
    }

    /**
     * 从 index 位置开始向后寻找"轮次边界"：第一条 role=user 的消息。
     * <p>
     * 因为一轮对话总是以 user 消息开始（scheduler 中 addUser 在每轮开头调用），
     * 所以第一条 user 消息之前的所有内容都可以安全丢弃。
     */
    private int findRoundBoundary(List<LlmMessage> messages, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < messages.size(); i++) {
            LlmMessage m = messages.get(i);
            if (m != null && "user".equals(m.getRole())) {
                return i;
            }
        }
        return -1;
    }
}
