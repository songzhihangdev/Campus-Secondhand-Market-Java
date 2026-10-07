package com.campus.cart.utils;

import com.campus.common.exception.ForbiddenException;
import com.campus.common.exception.UnauthorizedException;
import com.campus.common.utils.UserContext;
import com.campus.cart.domain.po.Cart;

/**
 * 意向单归属校验工具。
 *
 * <p><b>为什么必须校验</b>：意向单是<b>按 userId 隔离</b>的私有数据。
 * 实测发现原实现存在<b>水平越权（IDOR）</b>：
 * <pre>
 *   DELETE /carts/{id}  →  cartService.removeById(id)
 * </pre>
 * 只凭条目 id 删除，不校验归属 —— 用户 A 只要拿到 B 的条目 id
 * 就能删掉 B 的意向单条目。实测已复现（Rose 成功删除 Jack 的条目）。
 *
 * <p><b>身份来源</b>：userId 只从 {@link UserContext} 取（由网关解析 JWT 得到），
 * 绝不接受请求参数传入。
 *
 * <p>风格与 trade 模块的 OrderOwnershipChecker、item 模块的 OwnershipChecker 保持一致。
 */
public final class CartOwnershipChecker {

    private CartOwnershipChecker() {
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
     * 校验当前登录用户是否为该意向单条目的归属人。
     *
     * <p>条目不存在时抛 403 而非 404：不暴露"该 id 是否存在"，
     * 避免被用来探测他人条目号。
     *
     * @param entry 待操作的条目（可为空）
     */
    public static void checkOwner(Cart entry) {
        Long currentUserId = requireLogin();
        if (entry == null) {
            throw new ForbiddenException("意向单条目不存在或无权操作");
        }
        if (entry.getUserId() == null || !entry.getUserId().equals(currentUserId)) {
            throw new ForbiddenException("意向单条目不存在或无权操作");
        }
    }
}
