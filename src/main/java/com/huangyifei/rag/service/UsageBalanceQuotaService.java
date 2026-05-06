package com.huangyifei.rag.service;

import com.huangyifei.rag.config.UsageQuotaProperties;
import com.huangyifei.rag.exception.RateLimitExceededException;
import com.huangyifei.rag.model.DailyReqCountStat;
import com.huangyifei.rag.model.DailyUsageStat;
import com.huangyifei.rag.model.UserTokenRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UsageBalanceQuotaService extends UsageQuotaService {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final UserTokenService userTokenService;

    public UsageBalanceQuotaService(
            StringRedisTemplate stringRedisTemplate,
            UsageQuotaProperties properties,
            UserTokenService userTokenService
    ) {
        super(stringRedisTemplate, properties);
        this.userTokenService = userTokenService;
    }

    @Override
    public TokenReservation reserveLlmTokens(String userId, int estimatedPromptTokens, int maxCompletionTokens) {
        if (!isQuotaManaged(userId)) {
            return TokenReservation.noop("llm", userId);
        }
        int reserveTokens = Math.max(estimatedPromptTokens, 0) + Math.max(maxCompletionTokens, 0);
        reserveTokens = Math.max(reserveTokens, 1);
        if (!userTokenService.hasEnoughLlmTokens(userId, reserveTokens)) {
            Long balance = userTokenService.getLlmTokenBalance(userId);
            throw new RateLimitExceededException("LLM Token 余额不足，需要: " + reserveTokens + "，当前余额: " + balance, 0);
        }
        return new TokenReservation("llm", userId, "", "", reserveTokens, reserveTokens, 0, false, true);
    }

    @Override
    public TokenReservation reserveEmbeddingTokens(String userId, List<String> texts) {
        if (!isQuotaManaged(userId)) {
            return TokenReservation.noop("embedding", userId);
        }
        int estimatedTokens = Math.max(estimateEmbeddingTokens(texts), 1);
        if (!userTokenService.hasEnoughEmbeddingTokens(userId, estimatedTokens)) {
            Long balance = userTokenService.getEmbeddingTokenBalance(userId);
            throw new RateLimitExceededException("Embedding Token 余额不足，需要: " + estimatedTokens + "，当前余额: " + balance, 0);
        }
        return new TokenReservation("embedding", userId, "", "", estimatedTokens, estimatedTokens, 0, false, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordChatRequest(String userId) {
        if (isQuotaManaged(userId)) {
            userTokenService.updateUserDailyChatCount(userId, LocalDate.now());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleReservation(TokenReservation reservation, int actualTokens) {
        if (reservation == null || reservation.noop() || actualTokens <= 0) {
            return;
        }
        if ("llm".equals(reservation.scope())) {
            userTokenService.consumeLlmTokens(reservation.userId(), actualTokens);
        } else if ("embedding".equals(reservation.scope())) {
            userTokenService.consumeEmbeddingTokens(reservation.userId(), actualTokens);
        } else {
            super.settleReservation(reservation, actualTokens);
        }
    }

    @Override
    public Map<String, UserUsageSnapshot> getSnapshots(List<String> userIds) {
        Map<String, UserUsageSnapshot> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        for (String userId : userIds) {
            result.put(userId, new UserUsageSnapshot(
                    currentDay(),
                    userTokenService.getUserDailyChatCount(userId, LocalDate.now()),
                    buildBalanceQuotaView("llm", userId, properties.getLlm()),
                    buildBalanceQuotaView("embedding", userId, properties.getEmbedding())));
        }
        return result;
    }

    @Override
    public List<DailyUsageAggregate> getDailyAggregates(List<String> userIds, int days) {
        int normalizedDays = Math.max(1, Math.min(days, properties.getRetentionDays()));
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate startDay = today.minusDays(normalizedDays - 1L);

        Map<LocalDate, DailyUsageStat> llmStats = userTokenService.getDailyStatsByType(startDay, today, UserTokenRecord.TokenType.LLM)
                .stream().collect(java.util.stream.Collectors.toMap(DailyUsageStat::recordDate, stat -> stat, (a, b) -> a));
        Map<LocalDate, DailyUsageStat> embeddingStats = userTokenService.getDailyStatsByType(startDay, today, UserTokenRecord.TokenType.EMBEDDING)
                .stream().collect(java.util.stream.Collectors.toMap(DailyUsageStat::recordDate, stat -> stat, (a, b) -> a));
        Map<LocalDate, DailyReqCountStat> requestStats = userTokenService.getDailyReqCountStats(startDay, today)
                .stream().collect(java.util.stream.Collectors.toMap(DailyReqCountStat::recordDate, stat -> stat, (a, b) -> a));

        java.util.ArrayList<DailyUsageAggregate> result = new java.util.ArrayList<>();
        for (LocalDate day = startDay; !day.isAfter(today); day = day.plusDays(1)) {
            DailyUsageStat llm = llmStats.get(day);
            DailyUsageStat embedding = embeddingStats.get(day);
            DailyReqCountStat request = requestStats.get(day);
            result.add(new DailyUsageAggregate(
                    day.format(DAY_FORMATTER),
                    request == null ? 0 : request.totalRequestCount(),
                    llm == null ? 0 : llm.totalAmount(),
                    llm == null ? 0 : llm.totalRequestCount(),
                    embedding == null ? 0 : embedding.totalAmount(),
                    embedding == null ? 0 : embedding.totalRequestCount()));
        }
        return result;
    }

    private QuotaView buildBalanceQuotaView(String scope, String userId, UsageQuotaProperties.DailyTokenQuota quota) {
        if (!isQuotaManaged(userId) || !quota.isEnabled()) {
            return new QuotaView(false, 0, 0, 0, 0);
        }
        long balance;
        long totalIncrease;
        if ("llm".equals(scope)) {
            balance = userTokenService.getLlmTokenBalance(userId);
            totalIncrease = userTokenService.getUserLlmTotalIncreaseTokens(userId);
        } else {
            balance = userTokenService.getEmbeddingTokenBalance(userId);
            totalIncrease = userTokenService.getUserEmbeddingTotalIncreaseTokens(userId);
        }
        long used = Math.max(totalIncrease - balance, 0);
        long requestCount = userTokenService.getUserTotalRequestCount(scope, userId);
        return new QuotaView(true, used, totalIncrease, balance, requestCount);
    }
}
