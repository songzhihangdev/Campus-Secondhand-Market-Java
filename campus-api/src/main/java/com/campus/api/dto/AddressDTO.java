package com.campus.api.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 收货地址（跨服务传输）。
 *
 * <p><b>为什么在 campus-api 里再放一份</b>：
 * 原始 DTO 在 {@code campus-user} 模块内，而 campus-trade 依赖 campus-api
 * 而不是 campus-user（微服务之间不应有实现级依赖）。
 * 复制一份到共享模块，是微服务间共享契约的常规做法。
 *
 * <p>{@code campus-user} 里的那份保留不动 —— 它被 user 服务的 Controller/Service 使用，
 * 两者字段一致，但保持模块自治，避免大范围改动。
 */
@Data
@ApiModel(description = "收货地址")
public class AddressDTO {

    @ApiModelProperty("id")
    private Long id;

    @ApiModelProperty("省")
    private String province;

    @ApiModelProperty("市")
    private String city;

    @ApiModelProperty("县/区")
    private String town;

    @ApiModelProperty("手机")
    private String mobile;

    @ApiModelProperty("详细地址")
    private String street;

    @ApiModelProperty("联系人")
    private String contact;

    @ApiModelProperty("是否是默认 1默认 0否")
    private Integer isDefault;

    @ApiModelProperty("备注")
    private String notes;
}