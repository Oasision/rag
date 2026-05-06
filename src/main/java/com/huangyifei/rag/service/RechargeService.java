package com.huangyifei.rag.service;

import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.RechargeOrder;
import com.huangyifei.rag.model.RechargeOrder.OrderStatus;
import com.huangyifei.rag.model.RechargePackage;
import com.huangyifei.rag.repository.RechargeOrderRepository;
import com.huangyifei.rag.repository.RechargePackageRepository;
import com.huangyifei.rag.utils.PriceUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeService {

    private final WxPayService wxPayService;
    private final RechargePackageRepository packageRepository;
    private final RechargeOrderRepository orderRepository;
    private final UserTokenService userTokenService;

    public List<RechargePackage> getAllPackages() {
        return packageRepository.findAllByEnabledTrueAndDeletedFalseAndPackagePriceGreaterThanOrderBySortOrderAsc(1L);
    }

    public RechargePackage getPackageById(Integer id) {
        return packageRepository.findById(id)
                .orElseThrow(() -> new CustomException("套餐不存在", HttpStatus.BAD_REQUEST));
    }

    @Transactional(rollbackFor = Exception.class)
    public WxPayService.PrePayInfoResBo createRechargeOrder(String userId, Integer packageId, Long customAmount) {
        RechargePackage rechargePackage;
        Long amount;
        Long llmToken;
        Long embeddingToken;
        String description;

        if (packageId != null && packageId > 0) {
            rechargePackage = getPackageById(packageId);
            amount = rechargePackage.getPackagePrice();
            llmToken = rechargePackage.getLlmToken();
            embeddingToken = rechargePackage.getEmbeddingToken();
            description = "RAG 充值套餐: " + rechargePackage.getPackageName();
        } else {
            if (customAmount == null || customAmount <= 0) {
                throw new CustomException("充值金额必须大于 0", HttpStatus.BAD_REQUEST);
            }
            amount = customAmount;
            rechargePackage = packageRepository.findByPackagePriceAndEnabledIsTrueAndDeletedFalse(1)
                    .orElseThrow(() -> new CustomException("自定义充值基准套餐不存在", HttpStatus.BAD_REQUEST));
            llmToken = amount * rechargePackage.getLlmToken();
            embeddingToken = amount * rechargePackage.getEmbeddingToken();
            description = "RAG 自定义充值: " + PriceUtil.toYuanPrice(amount) + " 元";
        }

        String tradeNo = generateTradeNo(userId);
        RechargeOrder order = new RechargeOrder();
        order.setTradeNo(tradeNo);
        order.setUserId(userId);
        order.setPackageId(packageId != null ? packageId : 0);
        order.setAmount(amount);
        order.setLlmToken(llmToken);
        order.setEmbeddingToken(embeddingToken);
        order.setStatus(OrderStatus.SUCCEED);
        order.setDescription(description);
        order.setWxTransactionId("LOCAL_SELF_TEST_" + tradeNo);
        order.setPayTime(LocalDateTime.now());
        order = orderRepository.save(order);

        paySuccessCallback(order);

        WxPayService.PrePayInfoResBo payInfo = new WxPayService.PrePayInfoResBo();
        payInfo.setOutTradeNo(tradeNo);
        payInfo.setAppId("LOCAL_SELF_TEST");
        payInfo.setPrePayId("LOCAL_SELF_TEST_PAID");
        payInfo.setExpireTime(System.currentTimeMillis());
        return payInfo;
    }

    @Transactional(rollbackFor = Exception.class)
    public void handlePayCallback(HttpServletRequest request) {
        WxPayService.PayCallbackBo callbackBo = wxPayService.payCallback(request);
        String tradeNo = callbackBo.outTradeNo();
        RechargeOrder order = orderRepository.findByTradeNo(tradeNo)
                .orElseThrow(() -> new CustomException("订单不存在", HttpStatus.BAD_REQUEST));

        if (callbackBo.payStatus() == WxPayService.RechargeStatusEnum.SUCCEED) {
            order.setStatus(OrderStatus.SUCCEED);
            order.setWxTransactionId(callbackBo.thirdTransactionId());
            order.setPayTime(LocalDateTime.now());
            orderRepository.save(order);
            paySuccessCallback(order);
        } else if (callbackBo.payStatus() == WxPayService.RechargeStatusEnum.FAIL) {
            order.setStatus(OrderStatus.FAIL);
            orderRepository.save(order);
        } else {
            order.setStatus(OrderStatus.PAYING);
            orderRepository.save(order);
        }
    }

    public List<RechargeOrder> getUserOrders(String userId, OrderStatus status) {
        if (status != null) {
            return orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        }
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public RechargeOrder getOrderDetail(String tradeNo) {
        return orderRepository.findByTradeNo(tradeNo)
                .orElseThrow(() -> new CustomException("订单不存在", HttpStatus.BAD_REQUEST));
    }

    @Transactional(rollbackFor = Exception.class)
    public RechargeOrder checkOrderPayStatus(String tradeNo) {
        return getOrderDetail(tradeNo);
    }

    private void paySuccessCallback(RechargeOrder rechargeOrder) {
        userTokenService.addLlmTokens(rechargeOrder.getUserId(), rechargeOrder.getLlmToken());
        userTokenService.addEmbeddingTokens(rechargeOrder.getUserId(), rechargeOrder.getEmbeddingToken());
    }

    private String generateTradeNo(String userId) {
        String date = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String time = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        String normalizedUser = String.format("%06d", Long.parseLong(userId)).substring(0, 6);
        String random = String.format("%04d", new Random().nextInt(10000));
        return "R" + date + time + normalizedUser + random;
    }
}
