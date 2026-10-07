package com.campus.trade.enums;

/**
 * 订单状态。
 *
 * <p><b>为什么用枚举而不是裸 Integer</b>：
 * 原代码里状态判断散落在各处且全是魔法数字
 * （{@code status != 1 && status != 2}），极易出错 ——
 * 实测就出现了「已支付(2) 被当成可取消」的资损 bug：
 * <pre>
 *   if (status != null && status != 1 && status != 2) { // ← 放行了 status=2
 *       throw ...
 *   }
 * </pre>
 * 集中到枚举后，状态含义与中文文案一一对应，
 * 新增状态时编译器会强制处理所有判断点。
 *
 * <p><b>数值必须与库里 {@code order.status} 一致</b>，不可随意调整。
 */
public enum OrderStatus {

    /** 待支付：下单成功但未付款，会被延迟消息超时关闭 */
    UNPAID(1, "待支付"),

    /** 已支付：付款成功，等待卖家发货 / 买家收货 */
    PAID(2, "已支付"),

    /** 交易中：卖家已发货，运输途中 */
    IN_TRANSIT(3, "交易中"),

    /** 已完成：买家确认收货，交易结束 */
    FINISHED(4, "已完成"),

    /** 已关闭：超时未支付或用户主动取消 */
    CLOSED(5, "已关闭");

    private final int value;
    private final String text;

    OrderStatus(int value, String text) {
        this.value = value;
        this.text = text;
    }

    public int getValue() {
        return value;
    }

    public String getText() {
        return text;
    }

    /**
     * 把库里的数值转成可读文案。
     *
     * <p>遇到未知数值<b>不抛异常</b>：脏数据不该让整个接口失败，
     * 返回 {@code 未知状态(数字)} 便于排查即可。
     */
    public static String textOf(Integer value) {
        if (value == null) {
            return "未知";
        }
        for (OrderStatus s : values()) {
            if (s.value == value) {
                return s.text;
            }
        }
        return "未知状态(" + value + ")";
    }
}