package com.campus.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀接口返回结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否成功 */
    private Boolean success;

    /** 提示信息（失败原因或成功说明） */
    private String message;

    public static SeckillResult ok(String message) {
        return new SeckillResult(true, message);
    }

    public static SeckillResult fail(String message) {
        return new SeckillResult(false, message);
    }
}
