package com.campus.item.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel(description = "库存变更消息")
public class StockChangeDTO {
    @ApiModelProperty("商品id")
    private Long itemId;
    @ApiModelProperty("扣减数量")
    private Integer num;
}
