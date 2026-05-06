package com.huangyifei.rag.controller;

import com.huangyifei.rag.model.RechargeOrder;
import com.huangyifei.rag.model.RechargeOrder.OrderStatus;
import com.huangyifei.rag.service.RechargeService;
import com.huangyifei.rag.service.WxPayService;
import com.huangyifei.rag.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(path = "/api/v1/recharge")
@RequiredArgsConstructor
public class RechargeController {

    private final RechargeService rechargeService;
    private final JwtUtils jwtUtils;

    @GetMapping("/packages")
    public ResponseEntity<?> getPackages() {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", rechargeService.getAllPackages()));
    }

    @PostMapping("/create-order")
    public ResponseEntity<?> createRechargeOrder(
            @RequestHeader("Authorization") String token,
            @RequestBody CreateRechargeOrderRequest request) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (userId == null || userId.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "无效 token"));
        }

        WxPayService.PrePayInfoResBo payInfo = rechargeService.createRechargeOrder(
                userId,
                request.packageId(),
                request.customAmount()
        );
        return ResponseEntity.ok(Map.of("code", 200, "message", "订单创建成功", "data", payInfo));
    }

    @PostMapping("/pay-callback")
    public ResponseEntity<?> payCallback(HttpServletRequest request) {
        try {
            rechargeService.handlePayCallback(request);
            return ResponseEntity.ok(Map.of("code", 200, "message", "success"));
        } catch (Exception e) {
            log.error("Pay callback failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/orders")
    public ResponseEntity<?> getUserOrders(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String status) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (userId == null || userId.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "无效 token"));
        }

        OrderStatus orderStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                orderStatus = OrderStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "订单状态不正确"));
            }
        }

        List<RechargeOrder> orders = rechargeService.getUserOrders(userId, orderStatus);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", orders));
    }

    @GetMapping("/orders/{tradeNo}")
    public ResponseEntity<?> getOrderDetail(
            @RequestHeader("Authorization") String token,
            @PathVariable String tradeNo) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (userId == null || userId.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "无效 token"));
        }

        RechargeOrder order = rechargeService.getOrderDetail(tradeNo);
        if (!order.getUserId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", 403, "message", "无权查看该订单"));
        }

        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", order));
    }

    public record CreateRechargeOrderRequest(Integer packageId, Long customAmount) {
    }
}
