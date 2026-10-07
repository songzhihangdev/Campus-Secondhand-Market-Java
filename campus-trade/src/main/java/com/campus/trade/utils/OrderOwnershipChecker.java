package com.campus.trade.utils;

import com.campus.common.exception.ForbiddenException;
import com.campus.common.exception.UnauthorizedException;
import com.campus.common.utils.UserContext;
import com.campus.trade.domain.po.Order;

/**
 * 订单归属校验工具。
 *
 * <p><b>为什么必须校验</b>：订单是用户私有数据。若只凭 orderId 就能查详情、
 * 取消、标记支付，会出现典型的<b>水平越权（IDOR）</b>：
 * 用户 A 拿到用户 B 的 orderId 就能查看甚至取消 B 的订单。
 *
 * <p><b>身份来源</b>：userId 只从 {@link UserContext} 取（由网关解析 JWT 得到），
 * 绝不接受请求参数传入 —— 前端输入都不可信。
 *
 * <p>风格与 item 模块的 OwnershipChecker 保持一致，便于统一理解。
 */
public final class OrderOwnershipChecker {

    private OrderOwnershipChecker() {
        // 工具类，禁止实例化
    }

    /**
     * 取当前登录用户 id，未登录抛 401。
     */
    public static Long requireLogin() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }

    /**
     * 校验当前登录用户是否为该订单的归属人。
     *
     * <p>订单不存在时抛 403 而非 404：不暴露"该 id 是否存在"，
     * 避免被用来探测他人订单号。
     *
     * @param order 待操作的订单（可为空）
     */
    public static void checkOwner(Order order) {
        Long currentUserId = requireLogin();
        if (order == null) {
            throw new ForbiddenException("订单不存在或无权操作");
        }
        if (order.getUserId() == null || !order.getUserId().equals(currentUserId)) {
            throw new ForbiddenException("订单不存在或无权操作");
        }
    }
}
