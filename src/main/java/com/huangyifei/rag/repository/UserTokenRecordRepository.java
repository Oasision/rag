package com.huangyifei.rag.repository;

import com.huangyifei.rag.model.DailyUsageStat;
import com.huangyifei.rag.model.UserTokenRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;







@Repository
public interface UserTokenRecordRepository extends JpaRepository<UserTokenRecord, Long> {
    


    Optional<UserTokenRecord> findFirstByUserIdAndRecordDateAndTokenTypeAndChangeTypeOrderByIdAsc(
            String userId,
            LocalDate recordDate,
            UserTokenRecord.TokenType tokenType,
            UserTokenRecord.ChangeType changeType
    );

    @Query("SELECT COALESCE(SUM(r.requestCount), 0L) FROM UserTokenRecord r " +
            "WHERE r.userId = :userId AND r.recordDate = :recordDate " +
            "AND r.tokenType = :tokenType AND r.changeType = :changeType")
    long sumRequestCountByUserIdAndRecordDateAndTokenTypeAndChangeType(
            @Param("userId") String userId,
            @Param("recordDate") LocalDate recordDate,
            @Param("tokenType") UserTokenRecord.TokenType tokenType,
            @Param("changeType") UserTokenRecord.ChangeType changeType
    );

    @Query("SELECT COALESCE(SUM(r.requestCount), 0L) FROM UserTokenRecord r " +
            "WHERE r.userId = :userId AND r.tokenType = :tokenType AND r.changeType = :changeType")
    long sumRequestCountByUserIdAndTokenTypeAndChangeType(
            @Param("userId") String userId,
            @Param("tokenType") UserTokenRecord.TokenType tokenType,
            @Param("changeType") UserTokenRecord.ChangeType changeType
    );


    


    @Query("SELECT COALESCE(SUM(r.amount), 0L) FROM UserTokenRecord r WHERE r.userId = :userId AND r.tokenType = :tokenType AND r.changeType = :changeType")
    long sumAmountByUserIdAndTokenTypeAndChangeType(
            @Param("userId") String userId,
            @Param("tokenType") UserTokenRecord.TokenType tokenType,
            @Param("changeType") UserTokenRecord.ChangeType changeType
    );

    


    @Query("SELECT new com.huangyifei.rag.model.DailyUsageStat( " +
            "r.recordDate, SUM(r.amount), SUM(r.requestCount)) " +
            "FROM UserTokenRecord r " +
            "WHERE r.tokenType = :tokenType AND r.changeType = 'CONSUME' " +
            "AND r.recordDate BETWEEN :startDate AND :endDate " +
            "GROUP BY r.recordDate ORDER BY r.recordDate ASC")
    List<DailyUsageStat> findDailyUsageStatsByDateRangeAndTokenType(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("tokenType") UserTokenRecord.TokenType tokenType
    );

    


    @Query("SELECT r " +
            "FROM UserTokenRecord r " +
            "WHERE r.tokenType = :tokenType AND r.changeType = 'CONSUME' " +
            "AND r.recordDate = :today " +
            "ORDER BY r.amount DESC")
    List<UserTokenRecord> findTodayTopConsumersByTokenType(
            @Param("today") LocalDate today,
            @Param("tokenType") UserTokenRecord.TokenType tokenType,
            Pageable pageable
    );

    


    @Query("SELECT u " +
            "FROM UserTokenRecord u " +
            "WHERE u.tokenType = :tokenType " +
            "AND u.changeType = 'CONSUME' " +
            "AND u.balanceAfter <= :minBalance " +
            "AND u.recordDate = (" +
            "    SELECT MAX(ur.recordDate) " +
            "    FROM UserTokenRecord ur " +
            "    WHERE ur.userId = u.userId " +
            "    AND ur.tokenType = :tokenType " +
            "    AND ur.changeType = 'CONSUME'" +
            ")")
    List<UserTokenRecord> findUsersWithLowBalance(
            @Param("tokenType") UserTokenRecord.TokenType tokenType,
            @Param("minBalance") long minBalance
    );

    


    Page<UserTokenRecord> findByUserIdOrderByRecordDateDesc(String userId, Pageable pageable);
}
