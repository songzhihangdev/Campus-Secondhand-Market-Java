package com.campus.api.constant;

/**
 * 商品状态。
 *
 * <p><b>为什么集中定义</b>：原先商品状态是裸数字，散落在
 * {@code item.setStatus(1)}、{@code status == 1} 等处。
 * 二手场景里「1=在售 0=已售」这个约定很容易被误当成"启用/禁用"，
 * 用常量能避免歧义。
 *
 * <p><b>注意</b>：{@code item.status} 与 {@code order.status} 是
 * 两套<b>互不相同</b>的枚举，数值含义不同，不要混用。
 * <pre>
 *   商品 status：0=已售/下架，1=在售
 *   订单 status：1=待支付 2=已支付 3=交易中 4=已完成 5=已关闭
 * </pre>
 */
public interface ItemStatus {

    /** 已售出 / 已下架 —— 不可再被购买 */
    int SOLD = 0;

    /** 在售 —— 可被搜索、可被下单 */
    int ON_SALE = 1;

    /**
     * 把数值转成可读文案。
     *
     * @param status 库里的值
     * @return 中文描述；未知值返回「未知状态(N)」，不抛异常
     */
    static String textOf(Integer status) {
        if (status == null) {
            return "未知";
        }
        if (status == SOLD) {
            return "已售";
        }
        if (status == ON_SALE) {
            return "在售";
        }
        return "未知状态(" + status + ")";
    }
}