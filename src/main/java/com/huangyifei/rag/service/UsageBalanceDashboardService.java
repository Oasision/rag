package com.huangyifei.rag.service;

import com.huangyifei.rag.repository.UserRepository;

public class UsageBalanceDashboardService extends UsageDashboardService {

    public UsageBalanceDashboardService(
            UserRepository userRepository,
            UsageQuotaService usageQuotaService,
            UserTokenService userTokenService
    ) {
        super(userRepository, usageQuotaService);
    }
}
