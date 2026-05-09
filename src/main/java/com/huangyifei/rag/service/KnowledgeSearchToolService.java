package com.huangyifei.rag.service;

import com.huangyifei.rag.entity.SearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeSearchToolService {

    public static final String TOOL_NAME = "knowledge_search";
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final HybridSearchService hybridSearchService;

    public KnowledgeSearchToolService(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    public KnowledgeSearchResponse execute(KnowledgeSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return new KnowledgeSearchResponse(TOOL_NAME, "", normalizeTopK(null), List.of());
        }

        String query = request.query().trim();
        int topK = normalizeTopK(request.topK());
        List<SearchResult> searchResults = request.userId() == null || request.userId().isBlank()
                ? hybridSearchService.search(query, topK)
                : hybridSearchService.searchWithPermission(query, request.userId(), topK);

        List<KnowledgeSearchHit> hits = toHits(searchResults);
        return new KnowledgeSearchResponse(TOOL_NAME, query, topK, hits);
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<KnowledgeSearchHit> toHits(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return java.util.stream.IntStream.range(0, results.size())
                .mapToObj(index -> toHit(index + 1, results.get(index)))
                .toList();
    }

    private KnowledgeSearchHit toHit(int referenceNumber, SearchResult result) {
        return new KnowledgeSearchHit(
                referenceNumber,
                result.getFileMd5(),
                result.getFileName(),
                result.getChunkId(),
                result.getTextContent(),
                result.getPageNumber(),
                result.getAnchorText(),
                result.getRetrievalMode(),
                retrievalLabel(result.getRetrievalMode()),
                result.getMatchedChunkText(),
                result.getScore(),
                result.getContentType()
        );
    }

    private String retrievalLabel(String retrievalMode) {
        if ("IMAGE".equalsIgnoreCase(retrievalMode)) {
            return "Image retrieval";
        }
        if ("TEXT_ONLY".equalsIgnoreCase(retrievalMode)) {
            return "Text retrieval";
        }
        return "RAG retrieval";
    }

    public record KnowledgeSearchRequest(
            String query,
            String userId,
            Integer topK
    ) {
    }

    public record KnowledgeSearchResponse(
            String toolName,
            String query,
            int topK,
            List<KnowledgeSearchHit> results
    ) {
    }

    public record KnowledgeSearchHit(
            int referenceNumber,
            String fileMd5,
            String fileName,
            Integer chunkId,
            String content,
            Integer pageNumber,
            String anchorText,
            String retrievalMode,
            String retrievalLabel,
            String matchedChunkText,
            Double score,
            String contentType
    ) {
    }
}
