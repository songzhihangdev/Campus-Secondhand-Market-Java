package com.campus.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.config.ContextCompressProperties;
import com.campus.agent.llmclient.dto.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩效果量化测试。
 * <p>
 * 目的：给出可复现的"单轮 token 节省比例"，用于评估该优化是否值得。
 * 这里的字符数粗略等比于 token 数（中文场景下 1 字符 ≈ 0.5~1 token），
 * 用于横向对比改造前后即可，无需精确。
 */
class ContextCompressorEfficiencyTest {

    private static final int ITEM_COUNT = 30;

    private ContextCompressProperties props = new ContextCompressProperties();

    private ContextCompressor compressor = new ContextCompressor(props, new ObjectMapper());

    /** 模拟 search_items 返回的 30 件完整商品 JSON */
    private String fullObservation() {
        StringBuilder sb = new StringBuilder("{\"total\":30,\"pages\":1,\"list\":[");
        for (int i = 1; i <= ITEM_COUNT; i++) {
            if (i > 1) sb.append(',');
            sb.append("{\"id\":").append(i)
              .append(",\"name\":\"华为 Mate ").append(i).append(" Pro 12+256 冰霜银\"")
              .append(",\"price\":").append(499900 + i)
              .append(",\"stock\":").append(10 + i)
              .append(",\"image\":\"http://img/item/").append(i).append(".jpg\"")
              .append(",\"brand\":\"华为\",\"category\":\"手机\",\"spec\":\"12+256\"")
              .append(",\"sold\":").append(100 + i)
              .append(",\"commentCount\":").append(i * 3)
              .append("}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("单条工具结果：保留 5 条可节省 80% 以上篇幅")
    void singleObservation_shouldSaveAtLeast80Percent() {
        String full = fullObservation();
        String compressed = compressor.compress(List.of(
                LlmMessage.builder().role("tool").name("search_items")
                        .toolCallId("c1").content(full).build()))
                .get(0).getContent();

        int savedPercent = (int) ((1 - (double) compressed.length() / full.length()) * 100);
        System.out.printf("[压缩效果] 单条 observation：%d -> %d 字符，节省 %d%%%n",
                full.length(), compressed.length(), savedPercent);

        assertTrue(full.length() > 3000, "原始数据应足够大，测试才有意义");
        assertTrue(savedPercent >= 80,
                "30 件商品裁到 5 件，节省应 ≥80%，实际 " + savedPercent + "%");
    }

    @Test
    @DisplayName("多轮会话累积效果：历史越长，节省越显著")
    void multiRound_shouldAccumulateSignificantSavings() {
        String full = fullObservation();
        String compressed = compressor.compress(List.of(
                LlmMessage.builder().role("tool").name("search_items")
                        .toolCallId("c1").content(full).build()))
                .get(0).getContent();

        // 模拟 10 轮会话，每轮一个 tool 结果 + 简短问答
        int rounds = 10;
        int fullChars = rounds * full.length();
        int compressedChars = rounds * compressed.length() + rounds * 200; // +问答约200字符
        int savedPercent = (int) ((1 - (double) compressedChars / fullChars) * 100);

        System.out.printf("[压缩效果] 10 轮会话累计：%d -> %d 字符，节省 %d%%%n",
                fullChars, compressedChars, savedPercent);
        assertTrue(savedPercent >= 60, "10 轮累计节省应 ≥60%，实际 " + savedPercent + "%");
    }
}
