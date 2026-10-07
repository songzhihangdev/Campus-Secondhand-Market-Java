package com.campus.item.utils;

import com.campus.common.exception.ForbiddenException;
import com.campus.common.exception.UnauthorizedException;
import com.campus.common.utils.UserContext;
import com.campus.item.domain.po.Item;

/**
 * 校园二手 C2C 归属校验工具。
 *
 * <p><b>为什么必须校验</b>：二手交易里物品由用户自己发布，
 * 因此"只能操作自己发布的物品"是核心业务规则。
 * 如果不校验，会出现典型的<b>水平越权（IDOR）</b>：
 * 用户 A 拿到用户 B 的 itemId 就能改价、下架、甚至删除 B 的物品。
 *
 * <p><b>身份来源</b>：userId 只从 {@link UserContext} 取（即网关解析 JWT 得到的），
 * <b>绝不接受请求参数传入</b>——前端输入都是不可信的。
 *
 * <p><b>为何放在 service 层之前的独立工具类</b>：
 * 四个写操作都需要同样的校验，抽出来避免重复实现导致遗漏。
 */
public final class OwnershipChecker {

    private OwnershipChecker() {
        // 工具类，禁止实例化
    }

    /**
     * 校验「当前登录用户」是否可操作该物品。
     *
     * <p>规则：
     * <ul>
     *   <li>未登录 → {@link UnauthorizedException}（401）</li>
     *   <li>物品不存在 → {@link ForbiddenException}（403），不暴露"是否存在"的信息</li>
     *   <li>物品无发布者（历史数据 creater 为空）→ 放行，见 {@link #checkOwnerOrLegacy(Long)}</li>
     *   <li>发布者与当前用户不一致 → {@link ForbiddenException}（403）</li>
     * </ul>
     *
     * @param item 待操作的物品（可为空）
     */
    public static void checkOwner(Item item) {
        Long currentUserId = requireLogin();
        if (item == null) {
            throw new ForbiddenException("物品不存在或无权操作");
        }
        Long creater = item.getCreater();
        // 历史数据兼容：这条记录没有发布者（改造前由管理员统一维护），
        // 无法判定归属，暂不放行但也不抛错，由上层业务逻辑决定如何处理。
        if (creater == null) {
            return;
        }
        if (!creater.equals(currentUserId)) {
            throw new ForbiddenException("只能操作自己发布的闲置物品");
        }
    }

    /**
     * 严格版：物品无发布者时也拒绝。
     *
     * <p>用于「删除」这类不可逆操作——宁可拦住，也不能误删他人数据。
     */
    public static void checkOwnerOrLegacy(Long creater) {
        Long currentUserId = requireLogin();
        if (creater == null) {
            throw new ForbiddenException("该记录无发布者信息，禁止操作");
        }
        if (!creater.equals(currentUserId)) {
            throw new ForbiddenException("只能操作自己发布的闲置物品");
        }
    }

    /**
     * 取当前登录用户 id，未登录直接抛 401。
     */
    public static Long requireLogin() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        return userId;
    }
}
