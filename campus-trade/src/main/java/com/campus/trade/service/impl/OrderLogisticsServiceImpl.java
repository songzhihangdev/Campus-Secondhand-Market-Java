package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.api.dto.AddressDTO;
import com.campus.trade.domain.po.OrderLogistics;
import com.campus.trade.mapper.OrderLogisticsMapper;
import com.campus.trade.service.IOrderLogisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Slf4j
@Service
public class OrderLogisticsServiceImpl extends ServiceImpl<OrderLogisticsMapper, OrderLogistics> implements IOrderLogisticsService {

    @Override
    public boolean saveAddressSnapshot(Long orderId, AddressDTO address) {
        if (orderId == null || address == null) {
            return false;
        }
        OrderLogistics po = new OrderLogistics();
        po.setOrderId(orderId);
        po.setContact(address.getContact());
        po.setMobile(address.getMobile());
        po.setProvince(address.getProvince());
        po.setCity(address.getCity());
        po.setTown(address.getTown());
        po.setStreet(address.getStreet());
        // logistics_number / logistics_company 留空 —— 发货时才填
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());

        boolean ok = save(po);
        if (ok) {
            log.info("订单地址快照已保存 orderId={}, contact={}", orderId, address.getContact());
        }
        return ok;
    }

    @Override
    public OrderLogistics getByOrderId(Long orderId) {
        if (orderId == null) {
            return null;
        }
        return getById(orderId);
    }
}