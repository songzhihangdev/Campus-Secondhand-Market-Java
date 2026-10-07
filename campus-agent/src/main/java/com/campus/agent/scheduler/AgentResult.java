package com.campus.agent.scheduler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent 一次 run 的返回结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {

    /** 会话 id */
    private String sessionId;

    /** 最终回答文本（成功时为模型答复；超轮次/失败时为对应提示） */
    private String finalAnswer;

    /** 任务是否正常完成（拿到最终回答） */
    private boolean completed;

    /** 实际执行的轮次数 */
    private int rounds;

    /** 工具调用记录（用于日志/前端展示完整链路） */
    private List<AgentCallRecord> callRecords;

    /**
     * 单次工具调用记录。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentCallRecord {

        /** 第几轮 */
        private int round;

        /** 调用的技能名 */
        private String skillName;

        /** 调用参数 */
        private Map<String, Object> arguments;

        /** 执行器返回的观察文本 */
        private String observation;
    }
}
