package com.huangyifei.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.entity.SearchResult;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatHandler {

    private final HybridSearchService searchService;
    private final LlmProviderRouter llmProviderRouter;
    private final ConversationService conversationService;
    private final ChatGenerationStateService chatGenerationStateService;
    private final ObjectMapper objectMapper;
    private final Map<String, ChatStreamHandle> activeStreams = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ReferenceInfo>> generationReferenceMappings = new ConcurrentHashMap<>();

    public ChatHandler(HybridSearchService searchService,
                       LlmProviderRouter llmProviderRouter,
                       ConversationService conversationService,
                       ChatGenerationStateService chatGenerationStateService,
                       ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.llmProviderRouter = llmProviderRouter;
        this.conversationService = conversationService;
        this.chatGenerationStateService = chatGenerationStateService;
        this.objectMapper = objectMapper;
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        processMessage(userId, userMessage, null, session);
    }

    public void processMessage(String userId, String userMessage, String conversationId, WebSocketSession session) {
        Long parsedUserId = Long.parseLong(userId);
        String normalizedMessage = userMessage == null ? "" : userMessage.trim();
        var conversationSession = conversationService.ensureConversationSession(parsedUserId, conversationId, normalizedMessage);
        String activeConversationId = conversationSession.getConversationId();
        var generation = chatGenerationStateService.createGeneration(userId, activeConversationId, normalizedMessage);
        String generationId = generation.generationId();

        send(session, Map.of(
                "type", "start",
                "generationId", generationId,
                "conversationId", activeConversationId
        ));

        List<SearchResult> results = searchService.searchWithPermission(normalizedMessage, userId, 5);
        String context = buildContext(results);
        Map<Integer, ReferenceInfo> references = buildReferenceMappings(normalizedMessage, results);
        generationReferenceMappings.put(generationId, references);

        StringBuilder answer = new StringBuilder();
        AtomicBoolean finalized = new AtomicBoolean(false);
        ChatStreamHandle handle = llmProviderRouter.streamResponse(
                userId,
                normalizedMessage,
                context,
                List.of(),
                chunk -> {
                    answer.append(chunk);
                    chatGenerationStateService.appendChunk(generationId, chunk);
                    send(session, Map.of("generationId", generationId, "chunk", chunk));
                },
                error -> {
                    if (!finalized.compareAndSet(false, true)) {
                        return;
                    }
                    activeStreams.remove(generationId);
                    chatGenerationStateService.markFailed(generationId, error.getMessage());
                    send(session, Map.of(
                            "generationId", generationId,
                            "code", 500,
                            "error", true,
                            "message", error.getMessage() == null ? "Generation failed" : error.getMessage()));
                },
                () -> {
                    if (!finalized.compareAndSet(false, true)) {
                        return;
                    }

                    try {
                        Map<String, Map<String, Object>> referencePayload = toReferencePayload(references);
                        conversationService.recordConversation(
                                parsedUserId,
                                normalizedMessage,
                                answer.toString(),
                                activeConversationId,
                                referencePayload);
                        chatGenerationStateService.markCompleted(generationId, referencePayload);
                        send(session, Map.of(
                                "type", "completion",
                                "generationId", generationId,
                                "conversationId", activeConversationId,
                                "status", "finished",
                                "referenceMappings", referencePayload));
                    } catch (Exception exception) {
                        chatGenerationStateService.markFailed(generationId, exception.getMessage());
                        send(session, Map.of(
                                "generationId", generationId,
                                "code", 500,
                                "error", true,
                                "message", exception.getMessage() == null ? "Generation failed" : exception.getMessage()));
                    } finally {
                        activeStreams.remove(generationId);
                    }
                }
        );
        activeStreams.put(generationId, handle);
    }

    public void stopResponse(String userId, String generationId) {
        ChatStreamHandle handle = activeStreams.remove(generationId);
        if (handle != null) {
            handle.cancel();
            chatGenerationStateService.markCancelled(generationId);
        }
    }

    public String getReferenceMd5(String generationId, int referenceNumber) {
        ReferenceInfo detail = getReferenceDetail(generationId, referenceNumber);
        return detail == null ? null : detail.fileMd5();
    }

    public ReferenceInfo getReferenceDetail(String generationId, int referenceNumber) {
        Map<Integer, ReferenceInfo> references = generationReferenceMappings.get(generationId);
        return references == null ? null : references.get(referenceNumber);
    }

    private String buildContext(List<SearchResult> results) {
        StringBuilder context = new StringBuilder();
        int index = 1;
        for (SearchResult result : results) {
            context.append("[").append(index).append("] ")
                    .append("File: ").append(result.getFileName() == null ? "Unknown file" : result.getFileName());
            if (result.getPageNumber() != null) {
                context.append(" | Page: ").append(result.getPageNumber());
            }
            context.append("\n")
                    .append(result.getTextContent() == null ? "" : result.getTextContent())
                    .append("\n");
            index++;
        }
        return context.toString();
    }

    private Map<Integer, ReferenceInfo> buildReferenceMappings(String query, List<SearchResult> results) {
        Map<Integer, ReferenceInfo> references = new HashMap<>();
        int index = 1;
        for (SearchResult result : results) {
            references.put(index, new ReferenceInfo(
                    result.getFileMd5(),
                    result.getFileName(),
                    result.getPageNumber(),
                    result.getAnchorText(),
                    result.getRetrievalMode(),
                    "RAG retrieval",
                    query,
                    result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(),
                    result.getAnchorText(),
                    result.getScore(),
                    result.getChunkId()
            ));
            index++;
        }
        return references;
    }

    private Map<String, Map<String, Object>> toReferencePayload(Map<Integer, ReferenceInfo> references) {
        Map<String, Map<String, Object>> payload = new HashMap<>();
        references.forEach((number, info) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("fileMd5", info.fileMd5());
            item.put("fileName", info.fileName());
            item.put("pageNumber", info.pageNumber());
            item.put("anchorText", info.anchorText());
            item.put("retrievalMode", info.retrievalMode());
            item.put("retrievalLabel", info.retrievalLabel());
            item.put("retrievalQuery", info.retrievalQuery());
            item.put("matchedChunkText", info.matchedChunkText());
            item.put("evidenceSnippet", info.evidenceSnippet());
            item.put("score", info.score());
            item.put("chunkId", info.chunkId());
            payload.put(String.valueOf(number), item);
        });
        return payload;
    }

    private void send(WebSocketSession session, Map<String, Object> message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            }
        } catch (Exception ignored) {
        }
    }

    public record ReferenceInfo(
            String fileMd5,
            String fileName,
            Integer pageNumber,
            String anchorText,
            String retrievalMode,
            String retrievalLabel,
            String retrievalQuery,
            String matchedChunkText,
            String evidenceSnippet,
            Double score,
            Integer chunkId
    ) {
    }
}
