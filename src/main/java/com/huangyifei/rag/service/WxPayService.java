package com.huangyifei.rag.service;

import com.huangyifei.rag.config.WxPayConfig;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

@Slf4j
@Service
public class WxPayService {

    private static final int PAY_EXPIRE_TIME = 100 * 60 * 1000;
    public static final DateTimeFormatter WX_PAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+08:00'");

    private final WxPayConfig wxPayConfig;

    public WxPayService(WxPayConfig wxPayConfig) {
        this.wxPayConfig = wxPayConfig;
    }

    public PrePayInfoResBo createOrder(PayOrderReq payReq) {
        if (!wxPayConfig.isEnable()) {
            log.warn("WeChat Pay is disabled, returning mock prepay info for tradeNo={}", payReq.tradeNo());
        }
        return new PrePayInfoResBo()
                .setOutTradeNo(payReq.tradeNo())
                .setAppId(wxPayConfig.getAppId())
                .setNonceStr("")
                .setPrePackage("")
                .setPaySign("")
                .setTimeStamp(String.valueOf(System.currentTimeMillis() / 1000))
                .setSignType("RSA")
                .setPrePayId("")
                .setExpireTime(System.currentTimeMillis() + PAY_EXPIRE_TIME);
    }

    public PayCallbackBo queryOrder(String outTradeNo) {
        return new PayCallbackBo(outTradeNo, null, null, RechargeStatusEnum.NOT_PAY);
    }

    public PayCallbackBo payCallback(HttpServletRequest request) {
        String outTradeNo = request.getParameter("out_trade_no");
        return new PayCallbackBo(outTradeNo, System.currentTimeMillis(), request.getParameter("transaction_id"), RechargeStatusEnum.SUCCEED);
    }

    public <T> ResponseEntity<String> refundCallback(HttpServletRequest request, Function<T, Boolean> refundCallback) {
        return ResponseEntity.ok("success");
    }

    public static Long wxDayToTimestamp(String day) {
        LocalDateTime parse = LocalDateTime.parse(day, WX_PAY_FORMATTER);
        return parse.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Getter
    public enum RechargeStatusEnum {
        NOT_PAY(0, "待支付"),
        PAYING(1, "支付中"),
        SUCCEED(2, "支付成功"),
        FAIL(3, "支付失败"),
        CLOSED(4, "已关闭");

        private final Integer value;
        private final String desc;

        RechargeStatusEnum(Integer value, String desc) {
            this.value = value;
            this.desc = desc;
        }
    }

    public record PayOrderReq(String tradeNo, String description, int amount) {
    }

    public record PayCallbackBo(
            String outTradeNo,
            Long successTime,
            String thirdTransactionId,
            RechargeStatusEnum payStatus
    ) {
    }

    @Data
    @Accessors(chain = true)
    public static class PrePayInfoResBo {
        private String outTradeNo;
        private String appId;
        private String nonceStr;
        private String prePackage;
        private String paySign;
        private String timeStamp;
        private String signType;
        private String prePayId;
        private Long expireTime;
    }
}
