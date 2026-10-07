package com.campus.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 上下文压缩配置，前缀 {@code agent.compress}。
 * <p>
 * <b>设计原则：压缩只影响"送给大模型的视图"，不影响落盘的完整历史。</b>
 * 用户在前端查看历史时读到的永远是 jsonl 全量记录，因此这里的裁剪
 * 不会让用户丢失任何信息。
 */
@Data
@ConfigurationProperties(prefix = "agent.compress")
public class ContextCompressProperties {

    /**
     * 是否启用工具结果裁剪。
     * <p>
     * 关闭后行为与改造前一致：把完整上下文原样发给模型（仅用于对照排查）。
     */
    private boolean enabled = true;

    /**
     * 分页类工具结果（observation 是含 list 的 JSON）最多保留的商品条数。
     * <p>
     * 为什么优先裁这一类：search_items / query_items_by_ids 等接口会一次返回
     * 几十条完整商品 JSON，单条动辄数百 token，是上下文膨胀的主要来源；
     * 而模型做导购决策通常只需看前几条 + 总数。
     */
    private int maxItemsInList = 5;

    /**
     * 非 JSON 形态的工具结果，按字符数上限截断（0 表示不限制）。
     * <p>
     * 兜底保护：某些接口返回大段文本（地址列表等），也可能是不可解析的长字符串。
     */
    private int maxPlainTextLength = 1000;

    /**
     * 单条 observation 裁剪后的字符上限（0 表示不限制），最后一道防线。
     * <p>
     * 应对"JSON 解析失败或结构未知"导致裁剪没生效的情况。
     */
    private int maxObservationLength = 1500;

    /**
     * 超过该消息条数时，按整轮丢弃最早的轮次来控制上下文长度。
     * <p>
     * <b>为什么按"轮"而不是按"条"丢弃？</b>
     * OpenAI 协议要求 role=tool 消息必须紧跟对应的 assistant.tool_calls，
     * 若按单条裁剪会留下孤立的 tool 消息，API 会直接报错。
     * 因此必须以完整 ReAct 轮（assistant → tool → ... → assistant）为单位丢弃。
     * <p>
     * 注意这会丢失最早的对话内容，因此取值要足够大（默认 60 条 ≈ 20 轮）。
     */
    private int maxMessages = 60;

    /**
     * 保留最近多少条消息不参与丢弃（保护最新上下文）。
     */
    private int keepRecentMessages = 20;
}
