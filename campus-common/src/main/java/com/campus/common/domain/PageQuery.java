package com.campus.common.domain;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import lombok.experimental.Accessors;

import javax.validation.constraints.Min;
import java.util.Set;

@Slf4j
@Data
@ApiModel(description = "分页查询条件")
@Accessors(chain = true)
public class PageQuery {
    public static final Integer DEFAULT_PAGE_SIZE = 20;
    public static final Integer DEFAULT_PAGE_NUM = 1;
    @ApiModelProperty("页码")
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNo = DEFAULT_PAGE_NUM;
    @ApiModelProperty("页码")
    @Min(value = 1, message = "每页查询数量不能小于1")
    private Integer pageSize = DEFAULT_PAGE_SIZE;
    @ApiModelProperty("是否升序")
    private Boolean isAsc = true;
    @ApiModelProperty("排序方式")
    private String sortBy;

    /**
     * 允许排序的列白名单。
     *
     * <p><b>为什么必须有这个白名单</b>：
     * {@code sortBy} 来自 HTTP 请求参数，早期实现直接
     * {@code orderItem.setColumn(sortBy)} 拼进 ORDER BY，
     * 带来两个问题：
     * <ol>
     *   <li><b>SQL 注入</b>：攻击者传 {@code sortBy=id; DROP TABLE item;}，
     *       MyBatis-Plus 不做转义</li>
     *   <li><b>列名不存在</b>：前端传驼峰 {@code createTime}，
     *       而库里是下划线 {@code create_time} →
     *       {@code Unknown column 'createTime' in 'order clause'}（500）</li>
     * </ol>
     * 白名单同时解决这两点：只放行**已确认存在**的下划线列名，
     * 驼峰写法在下方统一转换。
     */
    private static final Set<String> ALLOWED_SORT_COLUMNS = Set.of(
            "id", "name", "price", "stock", "sold",
            "comment_count", "status", "create_time", "update_time"
    );

    /**
     * 驼峰 → 下划线。
     *
     * <p>前端习惯传 Java 字段名（createTime），
     * 而数据库列名是下划线形式（create_time），需要转换。
     * 手写正则而非引库，是为了不引入额外依赖。
     *
     * <p>注意：连续大写（如 {@code URL}）不转换，
     * 但因为还有白名单兜底，不在白名单里的最终会被拒绝。
     */
    private static String toColumnName(String field) {
        StringBuilder sb = new StringBuilder(field.length() + 4);
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public int from(){
        return (pageNo - 1) * pageSize;
    }

    /**
     * 解析出最终使用的排序列。
     *
     * <p>处理顺序：驼峰转下划线 → 白名单校验 → 非法则回退默认值。
     * <b>绝不把非法值放进 SQL</b>。
     *
     * @param defaultSortBy 兜底排序列（必须是下划线形式）
     * @return 合法可用的排序列名
     */
    private String resolveSortColumn(String defaultSortBy) {
        if (StrUtil.isBlank(sortBy)) {
            return defaultSortBy;
        }
        // 前端可能传驼峰（createTime），先统一转下划线
        String column = toColumnName(sortBy.trim().toLowerCase());
        if (ALLOWED_SORT_COLUMNS.contains(column)) {
            return column;
        }
        // 非法值：不抛异常打断请求，退回默认排序更友好
        log.warn("非法的排序字段已被忽略: sortBy={}, 允许的字段={}, 使用默认={}",
                sortBy, ALLOWED_SORT_COLUMNS, defaultSortBy);
        return defaultSortBy;
    }

    public <T> Page<T> toMpPage(OrderItem... orderItems) {
        Page<T> page = new Page<>(pageNo, pageSize);
        // 是否手动指定排序方式
        if (orderItems != null && orderItems.length > 0) {
            for (OrderItem orderItem : orderItems) {
                page.addOrder(orderItem);
            }
            return page;
        }
        // 前端是否有排序字段：经驼峰转换 + 白名单校验，绝不直接拼进 SQL
        if (StrUtil.isNotEmpty(sortBy)){
            OrderItem orderItem = new OrderItem();
            orderItem.setAsc(isAsc);
            orderItem.setColumn(resolveSortColumn(""));
            // 白名单被全部拒绝时 column 会是空串，此时不排序
            if (StrUtil.isNotEmpty(orderItem.getColumn())) {
                page.addOrder(orderItem);
            }
        }
        return page;
    }

    /**
     * 带默认排序的分页。
     *
     * <p>注意：这里<b>不能</b>用 {@code sortBy = defaultSortBy} 直接赋值，
     * 因为 sortBy 可能来自前端（驼峰/非法值）。统一走
     * {@link #resolveSortColumn} 完成转换与白名单校验。
     */
    public <T> Page<T> toMpPage(String defaultSortBy, boolean defaultAsc) {
        // 前端没传时用默认值；传了则用其 isAsc（保持原语义）
        boolean asc = StringUtils.isBlank(sortBy) ? defaultAsc : this.isAsc;

        Page<T> page = new Page<>(pageNo, pageSize);
        String column = resolveSortColumn(defaultSortBy);
        if (StrUtil.isNotEmpty(column)) {
            OrderItem orderItem = new OrderItem();
            orderItem.setAsc(asc);
            orderItem.setColumn(column);
            page.addOrder(orderItem);
        }
        return page;
    }
    public <T> Page<T> toMpPageDefaultSortByCreateTimeDesc() {
        return toMpPage("create_time", false);
    }
}
