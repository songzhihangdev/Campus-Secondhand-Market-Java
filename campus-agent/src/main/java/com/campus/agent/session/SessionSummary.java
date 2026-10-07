package com.campus.agent.session;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 会话摘要信息，用于前端"历史会话列表"展示。
 * <p>
 * 列表项不包含完整消息体，避免一次拉取全部会话时数据量过大；
 * 点击某条会话时再调用 history 接口加载明细。
 */
@Data
@NoArgsConstructor
public class SessionSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话号 */
    private String sessionId;

    /** 会话标题（取首条 user 消息前若干字） */
    private String title;

    /** 消息总条数 */
    private int messageCount;

    /** 创建时间（毫秒时间戳） */
    private long createTime;

    /** 最后一条消息时间（毫秒时间戳） */
    private long updateTime;
}
