package com.huangyifei.rag.config;

import com.huangyifei.rag.model.RegistrationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
public class AppAuthProperties {

    private final Registration registration = new Registration();

    public Registration getRegistration() {
        return registration;
    }

    public static class Registration {
        private RegistrationMode mode = RegistrationMode.INVITE_ONLY;
        private boolean inviteRequired = true;
        private long inviteGiftLlmTokens = 10_000;
        private long inviteGiftEmbeddingTokens = 10_000;

        public RegistrationMode getMode() {
            return mode;
        }

        public void setMode(RegistrationMode mode) {
            this.mode = mode;
        }

        public boolean isInviteRequired() {
            return inviteRequired;
        }

        public void setInviteRequired(boolean inviteRequired) {
            this.inviteRequired = inviteRequired;
        }

        public long getInviteGiftLlmTokens() {
            return inviteGiftLlmTokens;
        }

        public void setInviteGiftLlmTokens(long inviteGiftLlmTokens) {
            this.inviteGiftLlmTokens = inviteGiftLlmTokens;
        }

        public long getInviteGiftEmbeddingTokens() {
            return inviteGiftEmbeddingTokens;
        }

        public void setInviteGiftEmbeddingTokens(long inviteGiftEmbeddingTokens) {
            this.inviteGiftEmbeddingTokens = inviteGiftEmbeddingTokens;
        }
    }
}
