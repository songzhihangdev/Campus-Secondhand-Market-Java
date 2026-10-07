package com.campus.trade.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 订单收货信息（展示用）。
 *
 * <p>数据来源是 {@code order_logistics} 表里下单时保存的<b>地址快照</b>，
 * 不是实时查user 服务 —— 地址随时可能被用户改动或删除，
 * 订单要能永久展示当时约定的收货信息。
 */
@Data
@ApiModel(description = "订单收货信息")
public class OrderAddressVO {

    @ApiModelProperty("收件人")
    private String contact;

    @ApiModelProperty("收件电话")
    private String mobile;

    @ApiModelProperty("省")
    private String province;

    @ApiModelProperty("市")
    private String city;

    @ApiModelProperty("区/县")
    private String town;

    @ApiModelProperty("详细地址")
    private String street;

    @ApiModelProperty("物流单号（发货后才有）")
    private String logisticsNumber;

    @ApiModelProperty("物流公司（发货后才有）")
    private String logisticsCompany;

    /**
     * 拼好的完整地址，前端直接展示，省去自己拼字符串。
     */
    public String fullAddress() {
        StringBuilder sb = new StringBuilder();
        if (province != null) sb.append(province);
        if (city != null) sb.append(city);
        if (town != null) sb.append(town);
        if (street != null) sb.append(street);
        return sb.toString();
    }
}