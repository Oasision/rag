package com.huangyifei.rag.config;

import com.huangyifei.rag.repository.UserRepository;
import com.huangyifei.rag.service.UsageBalanceDashboardService;
import com.huangyifei.rag.service.UsageBalanceQuotaService;
import com.huangyifei.rag.service.UsageDashboardService;
import com.huangyifei.rag.service.UsageQuotaService;
import com.huangyifei.rag.service.UserTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;






@Configuration
@RequiredArgsConstructor
public class QuotaConfiguration {

    private final UsageQuotaProperties usageQuotaProperties;

    




    @Bean
    public UsageQuotaService usageQuotaService(StringRedisTemplate stringRedisTemplate,
                                               UserTokenService userTokenService) {
        if (usageQuotaProperties.isUseUserTokenBalance()) {
            return new UsageBalanceQuotaService(stringRedisTemplate, usageQuotaProperties, userTokenService);
        } else {
            return new UsageQuotaService(stringRedisTemplate, usageQuotaProperties);
        }
    }

    






    @Bean
    public UsageDashboardService usageBalanceQuotaService(UserRepository userRepository,
                                                          UsageQuotaService usageQuotaService,
                                                          UserTokenService userTokenService) {
        if (usageQuotaProperties.isUseUserTokenBalance()) {
            return new UsageBalanceDashboardService(userRepository, usageQuotaService, userTokenService);
        } else {
            return new UsageDashboardService(userRepository, usageQuotaService);
        }
    }

}
