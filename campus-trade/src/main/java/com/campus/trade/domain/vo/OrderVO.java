package com.campus.trade.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@ApiModel(description = "订单页面VO")
public class OrderVO {
    @ApiModelProperty("订单id")
    private Long id;
    @ApiModelProperty("总金额，单位为分")
    private Integer totalFee;
    @ApiModelProperty("支付类型，1、支付宝，2、微信，3、扣减余额")
    private Integer paymentType;
    @ApiModelProperty("用户id")
    private Long userId;
    @ApiModelProperty("订单的状态，1、未付款 2、已付款,未发货 3、已发货,未确认 4、确认收货，交易成功 5、交易取消，订单关闭 6、交易结束，已评价")
    private Integer status;
    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
    @ApiModelProperty("支付时间")
    private LocalDateTime payTime;
    @ApiModelProperty("发货时间")
    private LocalDateTime consignTime;
    @ApiModelProperty("交易完成时间")
    private LocalDateTime endTime;
    @ApiModelProperty("交易关闭时间")
    private LocalDateTime closeTime;
    @ApiModelProperty("评价时间")
    private LocalDateTime commentTime;

    /**
     * 订单内的物品明细。
     *
     * <p>二手场景下单通常只买 1~2 件，订单列表必须能看到「买了什么」，
     * 否则用户只看到一个金额和状态，完全不知道订单内容。
     * 由 Controller 查 OrderDetail 填充，BeanUtils 不覆盖此字段。
     */
    @ApiModelProperty("订单明细")
    private List<OrderDetailVO> details;

    /** 收货信息快照（来自 order_logistics 表，下单时写入） */
    private OrderAddressVO address;

    /** 明细展示用：第一条物品名称拼接（最多 2 条，超出显示「等 N 件」） */
    @ApiModelProperty("物品名称摘要")
    private String itemSummary;
}
