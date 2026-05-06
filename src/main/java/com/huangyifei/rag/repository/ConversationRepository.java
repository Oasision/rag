package com.huangyifei.rag.repository;

import com.huangyifei.rag.model.Conversation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    





    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdAndTimestampBetweenOrderByTimestampAsc(Long userId, LocalDateTime startDate, LocalDateTime endDate);

    



    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdOrderByTimestampAsc(Long userId);

    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdAndConversationIdOrderByTimestampAsc(Long userId, String conversationId);

    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdAndConversationIdAndTimestampBetweenOrderByTimestampAsc(
            Long userId,
            String conversationId,
            LocalDateTime startDate,
            LocalDateTime endDate
    );
    
    




    @EntityGraph(attributePaths = "user")
    List<Conversation> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime startDate, LocalDateTime endDate);

    @EntityGraph(attributePaths = "user")
    List<Conversation> findAllByOrderByTimestampAsc();
}
