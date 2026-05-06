package com.huangyifei.rag.service;

import com.huangyifei.rag.config.UsageQuotaProperties;
import com.huangyifei.rag.model.DailyReqCountStat;
import com.huangyifei.rag.model.DailyUsageStat;
import com.huangyifei.rag.model.UserTokenRecord;
import com.huangyifei.rag.repository.UserTokenRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class UserTokenService {

    private final UserTokenRecordRepository userTokenRecordRepository;
    private final UsageQuotaProperties usageQuotaProperties;

    public UserTokenService(UserTokenRecordRepository userTokenRecordRepository, UsageQuotaProperties usageQuotaProperties) {
        this.userTokenRecordRepository = userTokenRecordRepository;
        this.usageQuotaProperties = usageQuotaProperties;
    }

    @Transactional
    public void initializeTokenBalances(String userId, long llmTokens, long embeddingTokens, String reason) {
        if (llmTokens > 0) {
            addTokens(userId, UserTokenRecord.TokenType.LLM, llmTokens, reason);
        }
        if (embeddingTokens > 0) {
            addTokens(userId, UserTokenRecord.TokenType.EMBEDDING, embeddingTokens, reason);
        }
    }

    @Transactional
    public void addLlmTokens(String userId, Long amount) {
        addTokens(userId, UserTokenRecord.TokenType.LLM, amount == null ? 0 : amount, "充值增加 LLM Token");
    }

    @Transactional
    public void addEmbeddingTokens(String userId, Long amount) {
        addTokens(userId, UserTokenRecord.TokenType.EMBEDDING, amount == null ? 0 : amount, "充值增加 Embedding Token");
    }

    public boolean hasEnoughLlmTokens(String userId, long amount) {
        return getLlmTokenBalance(userId) >= amount;
    }

    public boolean hasEnoughEmbeddingTokens(String userId, long amount) {
        return getEmbeddingTokenBalance(userId) >= amount;
    }

    @Transactional
    public void consumeLlmTokens(String userId, long amount) {
        consumeTokens(userId, UserTokenRecord.TokenType.LLM, amount, "LLM 调用消耗");
    }

    @Transactional
    public void consumeEmbeddingTokens(String userId, long amount) {
        consumeTokens(userId, UserTokenRecord.TokenType.EMBEDDING, amount, "Embedding 调用消耗");
    }

    public Long getLlmTokenBalance(String userId) {
        return getBalance(userId, UserTokenRecord.TokenType.LLM);
    }

    public Long getEmbeddingTokenBalance(String userId) {
        return getBalance(userId, UserTokenRecord.TokenType.EMBEDDING);
    }

    public long getUserLlmTotalIncreaseTokens(String userId) {
        return userTokenRecordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                userId, UserTokenRecord.TokenType.LLM, UserTokenRecord.ChangeType.INCREASE);
    }

    public long getUserEmbeddingTotalIncreaseTokens(String userId) {
        return userTokenRecordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                userId, UserTokenRecord.TokenType.EMBEDDING, UserTokenRecord.ChangeType.INCREASE);
    }

    @Transactional
    public void incrementUserTotalRequestCount(String scope, String userId) {
        UserTokenRecord.TokenType tokenType = "embedding".equals(scope)
                ? UserTokenRecord.TokenType.EMBEDDING
                : UserTokenRecord.TokenType.LLM;
        UserTokenRecord record = findOrCreateDailyRecord(userId, tokenType, UserTokenRecord.ChangeType.CONSUME, LocalDate.now());
        record.setRequestCount(record.getRequestCount() + 1);
        userTokenRecordRepository.save(record);
    }

    public long getUserTotalRequestCount(String scope, String userId) {
        UserTokenRecord.TokenType tokenType = "embedding".equals(scope)
                ? UserTokenRecord.TokenType.EMBEDDING
                : UserTokenRecord.TokenType.LLM;
        return userTokenRecordRepository.sumRequestCountByUserIdAndTokenTypeAndChangeType(
                userId, tokenType, UserTokenRecord.ChangeType.CONSUME);
    }

    @Transactional
    public void updateUserDailyChatCount(String userId, LocalDate day) {
        UserTokenRecord record = findOrCreateDailyRecord(userId, UserTokenRecord.TokenType.LLM, UserTokenRecord.ChangeType.CONSUME, day);
        record.setRequestCount(record.getRequestCount() + 1);
        userTokenRecordRepository.save(record);
    }

    public long getUserDailyChatCount(String userId, LocalDate day) {
        return userTokenRecordRepository.sumRequestCountByUserIdAndRecordDateAndTokenTypeAndChangeType(
                userId, day, UserTokenRecord.TokenType.LLM, UserTokenRecord.ChangeType.CONSUME);
    }

    public List<DailyUsageStat> getDailyStatsByType(LocalDate startDate, LocalDate endDate, UserTokenRecord.TokenType tokenType) {
        return userTokenRecordRepository.findDailyUsageStatsByDateRangeAndTokenType(startDate, endDate, tokenType);
    }

    public List<DailyReqCountStat> getDailyReqCountStats(LocalDate startDate, LocalDate endDate) {
        return List.of();
    }

    public Page<UserTokenRecord> getUserTokenRecords(String userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.max(size, 1), Sort.by(Sort.Direction.DESC, "recordDate", "createdAt"));
        return userTokenRecordRepository.findByUserIdOrderByRecordDateDesc(userId, pageable);
    }

    private void addTokens(String userId, UserTokenRecord.TokenType tokenType, long amount, String reason) {
        if (amount <= 0) {
            return;
        }
        long before = getBalance(userId, tokenType);
        saveRecord(userId, tokenType, UserTokenRecord.ChangeType.INCREASE, amount, before, before + amount, reason);
    }

    private void consumeTokens(String userId, UserTokenRecord.TokenType tokenType, long amount, String reason) {
        if (amount <= 0) {
            return;
        }
        long before = getBalance(userId, tokenType);
        long after = Math.max(before - amount, 0);
        saveRecord(userId, tokenType, UserTokenRecord.ChangeType.CONSUME, amount, before, after, reason);
    }

    private long getBalance(String userId, UserTokenRecord.TokenType tokenType) {
        long increase = userTokenRecordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                userId, tokenType, UserTokenRecord.ChangeType.INCREASE);
        long consume = userTokenRecordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                userId, tokenType, UserTokenRecord.ChangeType.CONSUME);
        long configuredInitial = resolveInitToken(tokenType);
        return Math.max(configuredInitial + increase - consume, 0);
    }

    private long resolveInitToken(UserTokenRecord.TokenType tokenType) {
        UsageQuotaProperties.DailyTokenQuota quota = tokenType == UserTokenRecord.TokenType.LLM
                ? usageQuotaProperties.getLlm()
                : usageQuotaProperties.getEmbedding();
        return Math.max(quota.getInitTokens(), 0);
    }

    private void saveRecord(
            String userId,
            UserTokenRecord.TokenType tokenType,
            UserTokenRecord.ChangeType changeType,
            long amount,
            long balanceBefore,
            long balanceAfter,
            String reason
    ) {
        UserTokenRecord record = new UserTokenRecord();
        record.setUserId(userId);
        record.setRecordDate(LocalDate.now());
        record.setTokenType(tokenType);
        record.setChangeType(changeType);
        record.setAmount(amount);
        record.setBalanceBefore(balanceBefore);
        record.setBalanceAfter(balanceAfter);
        record.setReason(reason);
        record.setRequestCount(changeType == UserTokenRecord.ChangeType.CONSUME ? 1L : 0L);
        userTokenRecordRepository.save(record);
    }

    private UserTokenRecord findOrCreateDailyRecord(
            String userId,
            UserTokenRecord.TokenType tokenType,
            UserTokenRecord.ChangeType changeType,
            LocalDate day
    ) {
        return userTokenRecordRepository
                .findFirstByUserIdAndRecordDateAndTokenTypeAndChangeTypeOrderByIdAsc(userId, day, tokenType, changeType)
                .orElseGet(() -> {
                    UserTokenRecord record = new UserTokenRecord();
                    record.setUserId(userId);
                    record.setRecordDate(day);
                    record.setTokenType(tokenType);
                    record.setChangeType(changeType);
                    record.setAmount(0L);
                    record.setBalanceBefore(getBalance(userId, tokenType));
                    record.setBalanceAfter(getBalance(userId, tokenType));
                    record.setRequestCount(0L);
                    return record;
                });
    }
}
