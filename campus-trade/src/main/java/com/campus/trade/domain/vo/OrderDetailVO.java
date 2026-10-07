package com.campus.trade.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 订单明细展示 VO。
 *
 * <p>订单 VO 里直接放实体不合适（会带出不该展示的字段），
 * 因此单独定义只含展示必需字段的 VO。
 */
@Data
@ApiModel(description = "订单明细VO")
public class OrderDetailVO {
    @ApiModelProperty("明细id")
    private Long id;
    @ApiModelProperty("订单id")
    private Long orderId;
    @ApiModelProperty("物品id")
    private Long itemId;
    @ApiModelProperty("物品名称")
    private String name;
    @ApiModelProperty("规格（含成色，形如「成色：9成新」）")
    private String spec;
    @ApiModelProperty("单价（分）")
    private Integer price;
    @ApiModelProperty("物品图片")
    private String image;

    @ApiModelProperty("数量")
    private Integer num;
}
