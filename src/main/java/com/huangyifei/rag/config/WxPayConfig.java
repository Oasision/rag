package com.huangyifei.rag.config;

import lombok.Data;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Data
@Component
@ConfigurationProperties(prefix = "wx.pay")
public class WxPayConfig {

    private boolean enable = false;

    private String appId;

    private String merchantId;

    private String privateKey;

    private String apiV3Key;

    private String payNotifyUrl;

    private String refundNotifyUrl;

    public String getPrivateKeyContent() {
        if (privateKey == null || privateKey.isBlank()) {
            return null;
        }

        if (privateKey.contains("-----BEGIN PRIVATE KEY")) {
            return privateKey;
        }

        if (privateKey.endsWith("=") || privateKey.length() > 200) {
            return new String(Base64.decodeBase64(privateKey), StandardCharsets.UTF_8);
        }

        try {
            return IOUtils.resourceToString(privateKey, StandardCharsets.UTF_8, this.getClass().getClassLoader());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
