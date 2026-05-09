package com.huangyifei.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.service.KnowledgeSearchToolService.KnowledgeSearchHit;
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

    private final RagAgentService ragAgentService;
    private final LlmProviderRouter llmProviderRouter;
    private final ConversationService conversationService;
    private final ChatGenerationStateService chatGenerationStateService;
    private final ObjectMapper objectMapper;
    private final Map<String, ChatStreamHandle> activeStreams = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ReferenceInfo>> generationReferenceMappings = new ConcurrentHashMap<>();

    public ChatHandler(RagAgentService ragAgentService,
                       LlmProviderRouter llmProviderRouter,
                       ConversationService conversationService,
                       ChatGenerationStateService chatGenerationStateService,
                       ObjectMapper objectMapper) {
        this.ragAgentService = ragAgentService;
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

        RagAgentService.AgentPreparation agentPreparation = ragAgentService.prepare(userId, normalizedMessage);
        for (RagAgentService.ToolInvocation invocation : agentPreparation.toolInvocations()) {
            send(session, Map.of(
                    "type", "tool_result",
                    "generationId", generationId,
                    "conversationId", activeConversationId,
                    "toolName", invocation.toolName(),
                    "step", invocation.step(),
                    "input", invocation.input(),
                    "resultCount", invocation.resultCount(),
                    "reason", invocation.reason()
            ));
        }

        String context = agentPreparation.context();
        Map<Integer, ReferenceInfo> references = buildReferenceMappings(
                normalizedMessage,
                agentPreparation.knowledgeSearch().results()
        );
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
                                referencePayload,
                                agentPreparation.toolInvocations());
                        chatGenerationStateService.markCompleted(generationId, referencePayload);
                        send(session, Map.of(
                                "type", "completion",
                                "generationId", generationId,
                                "conversationId", activeConversationId,
                                "status", "finished",
                                "referenceMappings", referencePayload,
                                "toolInvocations", agentPreparation.toolInvocations()));
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

    private Map<Integer, ReferenceInfo> buildReferenceMappings(String query, List<KnowledgeSearchHit> results) {
        Map<Integer, ReferenceInfo> references = new HashMap<>();
        for (KnowledgeSearchHit result : results) {
            references.put(result.referenceNumber(), new ReferenceInfo(
                    result.fileMd5(),
                    result.fileName(),
                    result.pageNumber(),
                    result.anchorText(),
                    result.retrievalMode(),
                    result.retrievalLabel(),
                    query,
                    result.matchedChunkText() != null ? result.matchedChunkText() : result.content(),
                    result.anchorText(),
                    result.score(),
                    result.chunkId()
            ));
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
