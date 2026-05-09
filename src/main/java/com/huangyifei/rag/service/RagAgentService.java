package com.huangyifei.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.service.KnowledgeSearchToolService.KnowledgeSearchHit;
import com.huangyifei.rag.service.KnowledgeSearchToolService.KnowledgeSearchRequest;
import com.huangyifei.rag.service.KnowledgeSearchToolService.KnowledgeSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagAgentService {

    private static final Logger logger = LoggerFactory.getLogger(RagAgentService.class);
    private static final int DEFAULT_SEARCH_TOP_K = 5;
    private static final int MAX_TOOL_STEPS = 2;

    private final KnowledgeSearchToolService knowledgeSearchToolService;
    private final LlmProviderRouter llmProviderRouter;
    private final ObjectMapper objectMapper;

    public RagAgentService(KnowledgeSearchToolService knowledgeSearchToolService,
                           LlmProviderRouter llmProviderRouter,
                           ObjectMapper objectMapper) {
        this.knowledgeSearchToolService = knowledgeSearchToolService;
        this.llmProviderRouter = llmProviderRouter;
        this.objectMapper = objectMapper;
    }

    public AgentPreparation prepare(String userId, String userMessage) {
        String question = userMessage == null ? "" : userMessage.trim();
        SearchPlan plan = planSearch(userId, question);
        List<ToolInvocation> toolInvocations = new ArrayList<>();
        List<KnowledgeSearchHit> combinedHits = new ArrayList<>();

        if (!plan.shouldSearch()) {
            return new AgentPreparation(
                    toolInvocations,
                    new KnowledgeSearchResponse(KnowledgeSearchToolService.TOOL_NAME, "", DEFAULT_SEARCH_TOP_K, List.of()),
                    "",
                    plan.reason()
            );
        }

        KnowledgeSearchResponse firstSearch = executeSearch(userId, plan.query(), DEFAULT_SEARCH_TOP_K);
        toolInvocations.add(toInvocation(1, plan.query(), firstSearch.results().size(), plan.reason()));
        combinedHits.addAll(firstSearch.results());

        SearchPlan reflection = reflectSearch(userId, question, firstSearch.results());
        if (reflection.shouldSearch() && toolInvocations.size() < MAX_TOOL_STEPS) {
            KnowledgeSearchResponse secondSearch = executeSearch(userId, reflection.query(), DEFAULT_SEARCH_TOP_K);
            toolInvocations.add(toInvocation(2, reflection.query(), secondSearch.results().size(), reflection.reason()));
            combinedHits.addAll(secondSearch.results());
        }

        List<KnowledgeSearchHit> deduplicatedHits = renumberHits(deduplicateHits(combinedHits));
        KnowledgeSearchResponse mergedResponse = new KnowledgeSearchResponse(
                KnowledgeSearchToolService.TOOL_NAME,
                question,
                DEFAULT_SEARCH_TOP_K,
                deduplicatedHits
        );

        return new AgentPreparation(
                toolInvocations,
                mergedResponse,
                buildContext(deduplicatedHits),
                reflection.reason()
        );
    }

    private SearchPlan planSearch(String userId, String question) {
        if (question == null || question.isBlank()) {
            return new SearchPlan(false, "", "Empty question");
        }

        try {
            String content = llmProviderRouter.completeAgentDecision(userId, List.of(
                    Map.of("role", "system", "content", """
                            You are a RAG agent planner. Decide whether the user's message needs enterprise knowledge-base retrieval.
                            Return JSON only with this schema:
                            {"shouldSearch":true|false,"query":"optimized Chinese search query","reason":"short reason"}
                            Search for questions about documents, policies, product knowledge, project implementation, code behavior, or facts likely stored in the knowledge base.
                            Do not search for greetings, thanks, or pure chat with no knowledge need.
                            """),
                    Map.of("role", "user", "content", question)
            ), 256);
            return parsePlan(content, question, true);
        } catch (Exception exception) {
            logger.warn("Agent search planning failed, using fallback plan: {}", exception.getMessage());
            return fallbackPlan(question);
        }
    }

    private SearchPlan reflectSearch(String userId, String question, List<KnowledgeSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new SearchPlan(true, question, "First search returned no results, retry with original question");
        }

        try {
            String content = llmProviderRouter.completeAgentDecision(userId, List.of(
                    Map.of("role", "system", "content", """
                            You are a RAG retrieval reviewer. Decide whether one more knowledge_search call is needed.
                            Return JSON only with this schema:
                            {"shouldSearch":true|false,"query":"better search query if needed","reason":"short reason"}
                            Ask for another search only if the current snippets look off-topic, too narrow, or miss a key concept from the user question.
                            """),
                    Map.of("role", "user", "content", "Question:\n" + question + "\n\nCurrent snippets:\n" + summarizeHits(hits))
            ), 256);
            return parsePlan(content, question, false);
        } catch (Exception exception) {
            logger.warn("Agent search reflection failed, skipping second search: {}", exception.getMessage());
            return new SearchPlan(false, "", "Reflection failed; keep first search results");
        }
    }

    private SearchPlan parsePlan(String rawContent, String fallbackQuery, boolean defaultShouldSearch) {
        try {
            String json = extractJson(rawContent);
            JsonNode root = objectMapper.readTree(json);
            boolean shouldSearch = root.path("shouldSearch").asBoolean(defaultShouldSearch);
            String query = root.path("query").asText(fallbackQuery == null ? "" : fallbackQuery).trim();
            String reason = root.path("reason").asText("LLM decision");

            if (shouldSearch && query.isBlank()) {
                query = fallbackQuery == null ? "" : fallbackQuery;
            }
            return new SearchPlan(shouldSearch, query, reason);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid agent decision JSON: " + rawContent, exception);
        }
    }

    private String extractJson(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "{}";
        }

        String trimmed = rawContent.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private SearchPlan fallbackPlan(String question) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.length() <= 12 && normalized.matches("(?i).*(你好|您好|hello|hi|谢谢|多谢).*")) {
            return new SearchPlan(false, "", "Small talk does not need retrieval");
        }
        return new SearchPlan(true, normalized, "Fallback: use the user question as search query");
    }

    private KnowledgeSearchResponse executeSearch(String userId, String query, int topK) {
        return knowledgeSearchToolService.execute(new KnowledgeSearchRequest(query, userId, topK));
    }

    private ToolInvocation toInvocation(int step, String query, int resultCount, String reason) {
        return new ToolInvocation(
                step,
                KnowledgeSearchToolService.TOOL_NAME,
                query,
                resultCount,
                reason,
                LocalDateTime.now().toString()
        );
    }

    private List<KnowledgeSearchHit> deduplicateHits(List<KnowledgeSearchHit> hits) {
        Map<String, KnowledgeSearchHit> deduplicated = new LinkedHashMap<>();
        for (KnowledgeSearchHit hit : hits) {
            if (hit == null) {
                continue;
            }
            String key = hit.fileMd5() + ":" + hit.chunkId() + ":" + hit.contentType();
            deduplicated.putIfAbsent(key, hit);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private List<KnowledgeSearchHit> renumberHits(List<KnowledgeSearchHit> hits) {
        List<KnowledgeSearchHit> renumbered = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            KnowledgeSearchHit hit = hits.get(index);
            renumbered.add(new KnowledgeSearchHit(
                    index + 1,
                    hit.fileMd5(),
                    hit.fileName(),
                    hit.chunkId(),
                    hit.content(),
                    hit.pageNumber(),
                    hit.anchorText(),
                    hit.retrievalMode(),
                    hit.retrievalLabel(),
                    hit.matchedChunkText(),
                    hit.score(),
                    hit.contentType()
            ));
        }
        return renumbered;
    }

    private String summarizeHits(List<KnowledgeSearchHit> hits) {
        StringBuilder summary = new StringBuilder();
        int limit = Math.min(hits.size(), 5);
        for (int index = 0; index < limit; index++) {
            KnowledgeSearchHit hit = hits.get(index);
            summary.append("[").append(index + 1).append("] ");
            if (hit.fileName() != null) {
                summary.append(hit.fileName()).append(" ");
            }
            summary.append("score=").append(hit.score()).append("\n");
            String content = hit.content() == null ? "" : hit.content().replaceAll("\\s+", " ").trim();
            summary.append(content, 0, Math.min(content.length(), 260)).append("\n");
        }
        return summary.toString();
    }

    private String buildContext(List<KnowledgeSearchHit> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        for (KnowledgeSearchHit result : results) {
            context.append("[").append(result.referenceNumber()).append("] ")
                    .append("File: ").append(result.fileName() == null ? "Unknown file" : result.fileName());
            if (result.pageNumber() != null) {
                context.append(" | Page: ").append(result.pageNumber());
            }
            if (result.retrievalMode() != null) {
                context.append(" | Mode: ").append(result.retrievalMode());
            }
            context.append("\n")
                    .append(result.content() == null ? "" : result.content())
                    .append("\n");
        }
        return context.toString();
    }

    private record SearchPlan(
            boolean shouldSearch,
            String query,
            String reason
    ) {
    }

    public record AgentPreparation(
            List<ToolInvocation> toolInvocations,
            KnowledgeSearchResponse knowledgeSearch,
            String context,
            String finalReason
    ) {
    }

    public record ToolInvocation(
            int step,
            String toolName,
            String input,
            int resultCount,
            String reason,
            String executedAt
    ) {
    }
}
