package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.api.dto.AddressDTO;
import com.campus.trade.domain.po.OrderLogistics;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface IOrderLogisticsService extends IService<OrderLogistics> {

    /**
     * 保存订单的收货地址快照（下单时调用）。
     *
     * <p><b>为什么用快照而不是实时查</b>：订单是法律凭证。
     * 用户随时可能改动或删除收货地址，若展示时才实时查询，
     * 会出现「订单里的地址突然变了 / 查不到了」。
     * 下单那一刻把地址<b>拷贝一份</b>存下，之后不受源数据变动影响。
     *
     * <p>写入 {@code order_logistics} 表 —— 该表原为物流设计，
     * 但字段已含 contact/mobile/province/city/town/street，
     * 正好覆盖收货地址快照需求，因此复用而不另建表。
     * 物流单号/公司留空，等发货时再补。
     *
     * @param orderId 订单号
     * @param address 收货地址
     * @return 是否写入成功
     */
    boolean saveAddressSnapshot(Long orderId, AddressDTO address);

    /**
     * 查询订单的收货地址快照。
     *
     * @param orderId 订单号
     * @return 快照；未保存过返回 {@code null}
     */
    OrderLogistics getByOrderId(Long orderId);
}