package com.huangyifei.rag.repository;

import com.huangyifei.rag.model.ConversationSession;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {

    @EntityGraph(attributePaths = "user")
    List<ConversationSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    @EntityGraph(attributePaths = "user")
    List<ConversationSession> findByUserIdAndStatusOrderByUpdatedAtDesc(
            Long userId,
            ConversationSession.SessionStatus status
    );

    @EntityGraph(attributePaths = "user")
    Optional<ConversationSession> findByConversationId(String conversationId);

    boolean existsByConversationId(String conversationId);

    @Modifying
    @Query("update ConversationSession s set s.updatedAt = :updatedAt where s.conversationId = :conversationId")
    void touchUpdatedAt(String conversationId, LocalDateTime updatedAt);
}
