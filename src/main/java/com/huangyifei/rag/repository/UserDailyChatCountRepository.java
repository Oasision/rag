package com.huangyifei.rag.repository;

import com.huangyifei.rag.model.DailyReqCountStat;
import com.huangyifei.rag.model.UserDailyChatCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;







@Repository
public interface UserDailyChatCountRepository extends JpaRepository<UserDailyChatCount, Long> {

    

    Optional<UserDailyChatCount> findByUserIdAndRecordDate(String userId, LocalDate recordDate);

    


    @Query("SELECT new com.huangyifei.rag.model.DailyReqCountStat( " +
            "r.recordDate, SUM(r.chatRequestCount)) " +
            "FROM UserDailyChatCount r " +
            "WHERE r.recordDate BETWEEN :startDate AND :endDate " +
            "GROUP BY r.recordDate ORDER BY r.recordDate ASC")
    List<DailyReqCountStat> findDailyChatCountStatsByDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );


}

