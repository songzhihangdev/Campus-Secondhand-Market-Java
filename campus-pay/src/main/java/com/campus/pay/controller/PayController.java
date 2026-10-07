package com.campus.pay.controller;

import org.springframework.http.ResponseEntity;
import com.campus.api.dto.PayOrderDTO;
import com.campus.common.exception.BizIllegalException;
import com.campus.common.utils.BeanUtils;
import com.campus.pay.domain.dto.PayApplyDTO;
import com.campus.pay.domain.dto.PayOrderFormDTO;
import com.campus.pay.domain.po.PayOrder;
import com.campus.pay.domain.vo.PayOrderVO;
import com.campus.pay.enums.PayType;
import com.campus.pay.service.IPayOrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "支付相关接口")
@RestController
@RequestMapping("pay-orders")
@RequiredArgsConstructor
public class PayController {

    private final IPayOrderService payOrderService;

    @ApiOperation("支付单查询")
    @GetMapping
    public List<PayOrderVO> queryPayOrders(){
        return BeanUtils.copyList(payOrderService.list(), PayOrderVO.class);
    }

    @ApiOperation("生成支付单")
    @PostMapping
    public ResponseEntity<String> applyPayOrder(@RequestBody PayApplyDTO applyDTO){
        if(!PayType.BALANCE.equalsValue(applyDTO.getPayType())){
            // 目前只支持余额支付
            throw new BizIllegalException("抱歉，目前只支持余额支付");
        }
        String payOrderId = payOrderService.applyPayOrder(applyDTO);
        /*
         * 为什么要手动加引号：
         *
         * <p>支付单id 是雪花ID（19 位，如 2107491922128973827），
         * <b>超出 JS Number.MAX_SAFE_INTEGER（9007199254740991）</b>。
         * 若以 JSON 数字返回，前端 JSON.parse 会丢精度：
         * <pre>
         *   2107491922128973827 → JS 解析后 2107491922128973820（末位被抹）
         * </pre>
         * 丢掉的末位会让后续 {@code POST /pay-orders/{id}} 查不到支付单，
         * 后端 getById 返回 null → {@code NullPointerException}。
         *
         * <p>项目里的 {@code JsonConfig} 已把 {@code Long} 序列化成字符串，
         * 但那<b>只对 Long 生效</b>；本接口返回类型是 String，
         * Jackson 会原样输出字面量（不带引号），前端当成数字解析 → 精度丢失。
         *
         * <p>所以这里显式包一层引号，语义仍是"id 字符串"，
         * 但让 JSON 结构明确，前端 typeof === 'string'，不会丢精度。
         */
        return ResponseEntity.ok("\"" + payOrderId + "\"");
    }

    @ApiOperation("尝试基于用户余额支付")
    @ApiImplicitParam(value = "支付单id", name = "id")
    @PostMapping("{id}")
    public void tryPayOrderByBalance(@PathVariable("id") Long id, @RequestBody PayOrderFormDTO payOrderFormDTO){
        payOrderFormDTO.setId(id);
        payOrderService.tryPayOrderByBalance(payOrderFormDTO);
    }

    @ApiOperation("根据id查询支付单")
    @GetMapping("/biz/{id}")
    public PayOrderDTO queryPayOrderByBizOrderNo(@PathVariable("id") Long id){
        PayOrder payOrder = payOrderService.lambdaQuery().eq(PayOrder::getBizOrderNo, id).one();
        return BeanUtils.copyBean(payOrder, PayOrderDTO.class);
    }
}
