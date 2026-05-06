package com.huangyifei.rag.service;

import com.huangyifei.rag.config.AppAuthProperties;
import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.RegistrationMode;
import com.huangyifei.rag.model.SystemSetting;
import com.huangyifei.rag.repository.SystemSettingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class RegistrationSettingsService {

    private static final String REGISTRATION_MODE_KEY = "auth.registration.mode";

    private final AppAuthProperties appAuthProperties;
    private final SystemSettingRepository systemSettingRepository;

    public RegistrationSettingsService(AppAuthProperties appAuthProperties,
                                       SystemSettingRepository systemSettingRepository) {
        this.appAuthProperties = appAuthProperties;
        this.systemSettingRepository = systemSettingRepository;
    }

    public RegistrationMode getEffectiveMode() {
        return systemSettingRepository.findById(REGISTRATION_MODE_KEY)
                .map(SystemSetting::getSettingValue)
                .map(this::parseMode)
                .orElse(appAuthProperties.getRegistration().getMode());
    }

    public boolean isInviteRequired() {
        return getEffectiveMode() == RegistrationMode.INVITE_ONLY;
    }

    public Map<String, Object> getSettings() {
        RegistrationMode mode = getEffectiveMode();
        return Map.of(
                "mode", mode.name(),
                "inviteRequired", mode == RegistrationMode.INVITE_ONLY,
                "inviteGiftLlmTokens", appAuthProperties.getRegistration().getInviteGiftLlmTokens(),
                "inviteGiftEmbeddingTokens", appAuthProperties.getRegistration().getInviteGiftEmbeddingTokens()
        );
    }

    @Transactional
    public Map<String, Object> updateInviteRequired(boolean inviteRequired, String adminUsername) {
        RegistrationMode mode = inviteRequired ? RegistrationMode.INVITE_ONLY : RegistrationMode.OPEN;

        SystemSetting setting = systemSettingRepository.findById(REGISTRATION_MODE_KEY)
                .orElseGet(SystemSetting::new);
        setting.setSettingKey(REGISTRATION_MODE_KEY);
        setting.setSettingValue(mode.name());
        setting.setUpdatedBy(adminUsername);
        systemSettingRepository.save(setting);

        return getSettings();
    }

    private RegistrationMode parseMode(String value) {
        try {
            return RegistrationMode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new CustomException("Invalid registration mode: " + value, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
