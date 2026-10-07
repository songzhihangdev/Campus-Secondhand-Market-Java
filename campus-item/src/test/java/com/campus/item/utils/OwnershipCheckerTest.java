package com.campus.item.utils;

import com.campus.common.exception.ForbiddenException;
import com.campus.common.exception.UnauthorizedException;
import com.campus.common.utils.UserContext;
import com.campus.item.domain.po.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * C2C 归属校验单元测试。
 *
 * <p>覆盖三类安全问题：
 * <ol>
 *   <li><b>水平越权</b>：用户 A 操作用户 B 的闲置 → 必须 403</li>
 *   <li><b>未登录</b>：无身份访问写接口 → 必须 401</li>
 *   <li><b>历史数据兼容</b>：creater 为空的旧记录如何处理</li>
 * </ol>
 */
class OwnershipCheckerTest {

    private static final Long ALICE = 1001L;
    private static final Long BOB = 2002L;

    @BeforeEach
    void setUp() {
        UserContext.setUser(ALICE);
    }

    @AfterEach
    void tearDown() {
        // 必须清理 ThreadLocal，否则线程池复用会导致测试间串号
        UserContext.removeUser();
    }

    private Item item(Long creater) {
        Item item = new Item();
        item.setId(1L);
        item.setCreater(creater);
        return item;
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("本人操作自己的闲置：放行")
    void owner_shouldPass() {
        assertDoesNotThrow(() -> OwnershipChecker.checkOwner(item(ALICE)));
    }

    @Test
    @DisplayName("requireLogin 返回当前登录用户 id")
    void requireLogin_shouldReturnCurrentUser() {
        assertEquals(ALICE, OwnershipChecker.requireLogin());
    }

    // ==================== 水平越权防护 ====================

    @Test
    @DisplayName("用户 A 操作用户 B 的闲置：应抛 403")
    void otherUser_shouldBeForbidden() {
        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> OwnershipChecker.checkOwner(item(BOB)));
        assertEquals("只能操作自己发布的闲置物品", e.getMessage());
    }

    @Test
    @DisplayName("物品不存在时也拒绝，且不暴露'是否存在'的信息")
    void nullItem_shouldBeForbidden() {
        // 无论物品是否存在，对外都返回同一句提示，避免信息泄露
        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> OwnershipChecker.checkOwner(null));
        assertEquals("物品不存在或无权操作", e.getMessage());
    }

    // ==================== 未登录 ====================

    @Test
    @DisplayName("未登录（UserContext 为空）：应抛 401")
    void notLogin_shouldBeUnauthorized() {
        UserContext.removeUser();
        assertThrows(UnauthorizedException.class, () -> OwnershipChecker.checkOwner(item(ALICE)));
        assertThrows(UnauthorizedException.class, OwnershipChecker::requireLogin);
    }

    // ==================== 历史数据兼容 ====================

    @Test
    @DisplayName("记录无发布者（历史数据）：宽松版放行")
    void legacyItem_shouldPassLooseCheck() {
        // 改造前的商品由管理员维护，creater 可能为空，
        // 此时无法判定归属，宽松版不拦截（由业务逻辑决定后续处理）
        assertDoesNotThrow(() -> OwnershipChecker.checkOwner(item(null)));
    }

    @Test
    @DisplayName("记录无发布者：严格版（用于删除）应拒绝")
    void legacyItem_shouldBeRejectedByStrictCheck() {
        // 删除不可逆，宁可拦住也不能误删
        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> OwnershipChecker.checkOwnerOrLegacy(null));
        assertEquals("该记录无发布者信息，禁止操作", e.getMessage());
    }

    @Test
    @DisplayName("严格版：本人可删、他人不可删")
    void strictCheck_shouldBehaveAsExpected() {
        assertDoesNotThrow(() -> OwnershipChecker.checkOwnerOrLegacy(ALICE));
        assertThrows(ForbiddenException.class, () -> OwnershipChecker.checkOwnerOrLegacy(BOB));
    }
}
