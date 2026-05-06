package com.huangyifei.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.Conversation;
import com.huangyifei.rag.model.ConversationSession;
import com.huangyifei.rag.model.User;
import com.huangyifei.rag.repository.ConversationRepository;
import com.huangyifei.rag.repository.ConversationSessionRepository;
import com.huangyifei.rag.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationSessionRepository conversationSessionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationSessionRepository conversationSessionRepository,
                               UserRepository userRepository,
                               ObjectMapper objectMapper,
                               StringRedisTemplate stringRedisTemplate) {
        this.conversationRepository = conversationRepository;
        this.conversationSessionRepository = conversationSessionRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void recordConversation(String username, String question, String answer) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        saveConversation(user, question, answer, null, null);
    }

    public void recordConversation(Long userId, String question, String answer, String conversationId,
                                   Map<String, Map<String, Object>> referenceMappings) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        ConversationSession session = ensureConversationSession(userId, conversationId, question);
        saveConversation(user, question, answer, session.getConversationId(), referenceMappings);
        updateSessionTitleIfDefault(session, question);
        touchSessionUpdatedAt(session);
    }

    private void saveConversation(User user, String question, String answer, String conversationId,
                                  Map<String, Map<String, Object>> referenceMappings) {
        Conversation conversation = new Conversation();
        conversation.setUser(user);
        conversation.setQuestion(question == null ? "" : question);
        conversation.setAnswer(answer == null ? "" : answer);
        conversation.setConversationId(conversationId);
        try {
            conversation.setReferenceMappingsJson(referenceMappings == null ? null : objectMapper.writeValueAsString(referenceMappings));
        } catch (Exception ignored) {
        }
        conversationRepository.save(conversation);
    }

    public List<Conversation> getConversations(String username, LocalDateTime startDate, LocalDateTime endDate) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        if (startDate != null && endDate != null) {
            return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc(user.getId(), startDate, endDate);
        }
        return conversationRepository.findByUserIdOrderByTimestampAsc(user.getId());
    }

    public List<Conversation> getConversations(String username, String conversationId,
                                               LocalDateTime startDate, LocalDateTime endDate) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        if (conversationId == null || conversationId.isBlank()) {
            return getConversations(username, startDate, endDate);
        }

        ConversationSession session = getOwnedSession(user.getId(), conversationId);
        if (startDate != null && endDate != null) {
            return conversationRepository.findByUserIdAndConversationIdAndTimestampBetweenOrderByTimestampAsc(
                    user.getId(),
                    session.getConversationId(),
                    startDate,
                    endDate
            );
        }
        return conversationRepository.findByUserIdAndConversationIdOrderByTimestampAsc(user.getId(), session.getConversationId());
    }

    public List<Conversation> getAllConversations(String adminUsername, String targetUsername,
                                                  LocalDateTime startDate, LocalDateTime endDate) {
        if (targetUsername != null) {
            return getConversations(targetUsername, startDate, endDate);
        }
        if (startDate != null && endDate != null) {
            return conversationRepository.findByTimestampBetweenOrderByTimestampAsc(startDate, endDate);
        }
        return conversationRepository.findAllByOrderByTimestampAsc();
    }

    public List<Map<String, Object>> toMessageHistory(List<Conversation> conversations, boolean includeUsername) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Conversation conversation : conversations) {
            messages.add(buildMessage("user", conversation.getQuestion(), conversation, includeUsername, null));
            messages.add(buildMessage("assistant", conversation.getAnswer(), conversation, includeUsername, parseReferences(conversation)));
        }
        return messages;
    }

    public List<Map<String, Object>> getConversationSessions(Long userId) {
        return conversationSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toSessionMap)
                .collect(Collectors.toList());
    }

    public Map<String, Object> createConversationSession(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(newConversationId());
        session.setTitle("新对话");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        ConversationSession saved = conversationSessionRepository.save(session);
        setCurrentConversation(userId, saved.getConversationId());
        return toSessionMap(saved);
    }

    public ConversationSession ensureConversationSession(Long userId, String conversationId, String firstQuestion) {
        if (conversationId != null && !conversationId.isBlank()) {
            ConversationSession session = getOwnedSession(userId, conversationId);
            if (session.getStatus() == ConversationSession.SessionStatus.ARCHIVED) {
                session.setStatus(ConversationSession.SessionStatus.ACTIVE);
                session = conversationSessionRepository.save(session);
            }
            setCurrentConversation(userId, session.getConversationId());
            return session;
        }

        String currentConversationId = stringRedisTemplate.opsForValue().get(currentConversationKey(userId));
        if (currentConversationId != null && !currentConversationId.isBlank()) {
            try {
                ConversationSession current = getOwnedSession(userId, currentConversationId);
                if (current.getStatus() == ConversationSession.SessionStatus.ACTIVE) {
                    return current;
                }
            } catch (CustomException ignored) {
            }
        }

        Map<String, Object> created = createConversationSession(userId);
        return getOwnedSession(userId, String.valueOf(created.get("conversationId")));
    }

    public void switchCurrentConversation(Long userId, String conversationId) {
        ConversationSession session = getOwnedSession(userId, conversationId);
        if (session.getStatus() == ConversationSession.SessionStatus.ARCHIVED) {
            throw new CustomException("Conversation session is archived", HttpStatus.BAD_REQUEST);
        }
        setCurrentConversation(userId, conversationId);
    }

    public void archiveConversationSession(Long userId, String conversationId) {
        ConversationSession session = getOwnedSession(userId, conversationId);
        session.setStatus(ConversationSession.SessionStatus.ARCHIVED);
        conversationSessionRepository.save(session);
        String current = stringRedisTemplate.opsForValue().get(currentConversationKey(userId));
        if (conversationId.equals(current)) {
            stringRedisTemplate.delete(currentConversationKey(userId));
        }
    }

    public void unarchiveConversationSession(Long userId, String conversationId) {
        ConversationSession session = getOwnedSession(userId, conversationId);
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        conversationSessionRepository.save(session);
    }

    public void touchSessionUpdatedAt(ConversationSession session) {
        if (session == null) {
            return;
        }
        conversationSessionRepository.touchUpdatedAt(session.getConversationId(), LocalDateTime.now());
    }

    public void updateSessionTitleIfDefault(ConversationSession session, String question) {
        if (session == null || question == null || question.isBlank()) {
            return;
        }
        String title = session.getTitle();
        if (title != null && !title.isBlank() && !"New chat".equals(title) && !"新对话".equals(title)) {
            return;
        }
        session.setTitle(buildSessionTitle(question));
        conversationSessionRepository.save(session);
    }

    private ConversationSession getOwnedSession(Long userId, String conversationId) {
        ConversationSession session = conversationSessionRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("Conversation session not found", HttpStatus.NOT_FOUND));
        if (session.getUser() == null || !userId.equals(session.getUser().getId())) {
            throw new CustomException("Conversation session not found", HttpStatus.NOT_FOUND);
        }
        return session;
    }

    private Map<String, Object> toSessionMap(ConversationSession session) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", session.getId());
        item.put("conversationId", session.getConversationId());
        item.put("title", session.getTitle());
        item.put("status", session.getStatus().name());
        item.put("createdAt", session.getCreatedAt());
        item.put("updatedAt", session.getUpdatedAt());
        return item;
    }

    private String newConversationId() {
        String conversationId;
        do {
            conversationId = UUID.randomUUID().toString();
        } while (conversationSessionRepository.existsByConversationId(conversationId));
        return conversationId;
    }

    private String buildSessionTitle(String question) {
        String normalized = question.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 30) {
            return normalized;
        }
        return normalized.substring(0, 30);
    }

    private void setCurrentConversation(Long userId, String conversationId) {
        stringRedisTemplate.opsForValue().set(currentConversationKey(userId), conversationId);
    }

    private String currentConversationKey(Long userId) {
        return "chat:user:" + userId + ":current_conversation";
    }

    private Map<String, Object> buildMessage(String role, String content, Conversation conversation,
                                             boolean includeUsername, Map<String, Map<String, Object>> references) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);
        message.put("conversationId", conversation.getConversationId());
        message.put("timestamp", conversation.getTimestamp());
        if (includeUsername && conversation.getUser() != null) {
            message.put("username", conversation.getUser().getUsername());
            message.put("userId", conversation.getUser().getId());
        }
        if (references != null && !references.isEmpty()) {
            message.put("referenceMappings", references);
        }
        return message;
    }

    private Map<String, Map<String, Object>> parseReferences(Conversation conversation) {
        String raw = conversation.getReferenceMappingsJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return null;
        }
    }
}
