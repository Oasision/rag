package com.huangyifei.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.huangyifei.rag.client.EmbeddingClient;
import com.huangyifei.rag.client.VisionEmbeddingClient;
import com.huangyifei.rag.entity.EsDocument;
import com.huangyifei.rag.entity.SearchResult;
import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.model.User;
import com.huangyifei.rag.repository.FileUploadRepository;
import com.huangyifei.rag.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RESULT_WINDOW_MULTIPLIER = 4;
    private static final int KNN_RECALL_MULTIPLIER = 40;
    private static final int MIN_KNN_RECALL = 40;
    private static final int MAX_CHUNKS_PER_FILE = 2;

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final VisionEmbeddingClient visionEmbeddingClient;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final FileUploadRepository fileUploadRepository;
    private final MultimodalRoutingService multimodalRoutingService;

    public HybridSearchService(ElasticsearchClient esClient,
                               EmbeddingClient embeddingClient,
                               VisionEmbeddingClient visionEmbeddingClient,
                               UserRepository userRepository,
                               OrgTagCacheService orgTagCacheService,
                               FileUploadRepository fileUploadRepository,
                               MultimodalRoutingService multimodalRoutingService) {
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
        this.visionEmbeddingClient = visionEmbeddingClient;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.fileUploadRepository = fileUploadRepository;
        this.multimodalRoutingService = multimodalRoutingService;
    }

    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("Start permission-aware search, query={}, userId={}", query, userId);
        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);

            List<SearchResult> mergedResults = new ArrayList<>();
            mergedResults.addAll(searchTextWithPermission(query, userId, userDbId, userEffectiveTags, topK));

            if (multimodalRoutingService.shouldRunVisionRecall(query)) {
                mergedResults.addAll(searchVisionWithPermission(query, userId, userDbId, userEffectiveTags, topK));
            }

            attachFileNames(mergedResults);
            return mergeAndLimitResults(mergedResults, topK, MAX_CHUNKS_PER_FILE);
        } catch (Exception exception) {
            logger.error("Permission-aware hybrid search failed", exception);
            return Collections.emptyList();
        }
    }

    public List<SearchResult> search(String query, int topK) {
        try {
            List<SearchResult> mergedResults = new ArrayList<>();
            mergedResults.addAll(searchTextWithoutPermission(query, "system", topK));
            if (multimodalRoutingService.shouldRunVisionRecall(query)) {
                mergedResults.addAll(searchVisionWithoutPermission(query, "system", topK));
            }
            attachFileNames(mergedResults);
            return mergeAndLimitResults(mergedResults, topK, MAX_CHUNKS_PER_FILE);
        } catch (Exception exception) {
            logger.error("Hybrid search failed", exception);
            throw new RuntimeException("Search failed", exception);
        }
    }

    private List<SearchResult> searchTextWithPermission(String query, String requesterId, String userDbId, List<String> userEffectiveTags, int topK) {
        List<Float> queryVector = embedToVectorList(query, requesterId);
        if (queryVector == null) {
            return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
        }

        try {
            String normalizedQuery = normalizeQueryForSearch(query);
            int recallK = Math.max(topK * KNN_RECALL_MULTIPLIER, MIN_KNN_RECALL);
            int resultWindow = Math.max(topK * RESULT_WINDOW_MULTIPLIER, topK);

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK)
                );
                s.query(q -> q.bool(b -> {
                    b.filter(f -> f.bool(bf -> applyPermissionShoulds(bf, userDbId, userEffectiveTags)));
                    b.filter(f -> f.term(t -> t.field("contentType").value(EsDocument.CONTENT_TYPE_TEXT)));
                    b.should(sh -> sh.match(m -> m.field("textContent").query(query).boost(1.0f)));
                    if (!normalizedQuery.equals(query)) {
                        b.should(sh -> sh.match(m -> m.field("textContent").query(normalizedQuery).boost(1.2f)));
                    }
                    b.minimumShouldMatch("0");
                    return b;
                }));
                s.size(resultWindow);
                return s;
            }, EsDocument.class);

            return mapHits(response, "HYBRID");
        } catch (Exception exception) {
            logger.warn("Text hybrid search failed, falling back to text-only search: {}", exception.getMessage());
            return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
        }
    }

    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId, List<String> userEffectiveTags, int topK) {
        try {
            String normalizedQuery = normalizeQueryForSearch(query);
            int resultWindow = Math.max(topK * RESULT_WINDOW_MULTIPLIER, topK);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.bool(inner -> {
                            inner.should(sh -> sh.match(ma -> ma.field("textContent").query(query)));
                            if (!normalizedQuery.equals(query)) {
                                inner.should(sh -> sh.match(ma -> ma.field("textContent").query(normalizedQuery).boost(1.2f)));
                            }
                            inner.minimumShouldMatch("1");
                            return inner;
                        }));
                        b.filter(f -> f.bool(bf -> applyPermissionShoulds(bf, userDbId, userEffectiveTags)));
                        b.filter(f -> f.term(t -> t.field("contentType").value(EsDocument.CONTENT_TYPE_TEXT)));
                        return b;
                    }))
                    .minScore(0.3d)
                    .size(resultWindow), EsDocument.class);

            return mapHits(response, "TEXT_ONLY");
        } catch (Exception exception) {
            logger.error("Text-only search with permission failed", exception);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> searchTextWithoutPermission(String query, String requesterId, int topK) {
        List<Float> queryVector = embedToVectorList(query, requesterId);
        if (queryVector == null) {
            return textOnlySearch(query, topK);
        }

        try {
            String normalizedQuery = normalizeQueryForSearch(query);
            int recallK = Math.max(topK * KNN_RECALL_MULTIPLIER, MIN_KNN_RECALL);
            int resultWindow = Math.max(topK * RESULT_WINDOW_MULTIPLIER, topK);

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK)
                );
                s.query(q -> q.bool(b -> {
                    b.filter(f -> f.term(t -> t.field("contentType").value(EsDocument.CONTENT_TYPE_TEXT)));
                    b.should(sh -> sh.match(m -> m.field("textContent").query(query).boost(1.0f)));
                    if (!normalizedQuery.equals(query)) {
                        b.should(sh -> sh.match(m -> m.field("textContent").query(normalizedQuery).boost(1.2f)));
                    }
                    b.minimumShouldMatch("0");
                    return b;
                }));
                s.size(resultWindow);
                return s;
            }, EsDocument.class);

            return mapHits(response, "HYBRID");
        } catch (Exception exception) {
            logger.warn("Text hybrid search without permission failed, fallback to text-only search: {}", exception.getMessage());
            return textOnlySearch(query, topK);
        }
    }

    private List<SearchResult> textOnlySearch(String query, int topK) {
        try {
            String normalizedQuery = normalizeQueryForSearch(query);
            int resultWindow = Math.max(topK * RESULT_WINDOW_MULTIPLIER, topK);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> {
                        b.filter(f -> f.term(t -> t.field("contentType").value(EsDocument.CONTENT_TYPE_TEXT)));
                        b.should(sh -> sh.match(m -> m.field("textContent").query(query)));
                        if (!normalizedQuery.equals(query)) {
                            b.should(sh -> sh.match(m -> m.field("textContent").query(normalizedQuery).boost(1.2f)));
                        }
                        b.minimumShouldMatch("1");
                        return b;
                    }))
                    .size(resultWindow), EsDocument.class);

            return mapHits(response, "TEXT_ONLY");
        } catch (Exception exception) {
            logger.error("Text-only search failed", exception);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> searchVisionWithPermission(String query, String requesterId, String userDbId, List<String> userEffectiveTags, int topK) {
        List<Float> queryVector = embedToVisionVectorList(query, requesterId);
        if (queryVector == null) {
            return Collections.emptyList();
        }

        try {
            int recallK = Math.max(topK * KNN_RECALL_MULTIPLIER, MIN_KNN_RECALL);
            int resultWindow = Math.max(topK * RESULT_WINDOW_MULTIPLIER, topK);

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(kn -> kn
                        .field("visionVector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK)
                );
                s.query(q -> q.bool(b -> {
                    b.filter(f -> f.bool(bf -> applyPermissionShoulds(bf, userDbId, userEffectiveTags)));
                    b.filter(f -> f.term(t -> t.field("contentType").value(EsDocument.CONTENT_TYPE_IMAGE)));
                    return b;
                }));
                s.size(resultWindow);
                return s;
            }, EsDocument.class);

            return mapHits(response, "IMAGE");
        } catch (Exception exception) {
            logger.error("Vision search with permission failed", exception);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> searchVisionWithoutPermission(String query, String requesterId, int topK) {
        List<Float> queryVector = embedToVisionVectorList(query, requesterId);
        if (queryVector == null) {
            return Collections.emptyList();
        }

        try {
            int recallK = Math.max(topK * KNN_RECALL_MULTIPLIER, MIN_KNN_RECALL);
            int resultWindow = Math.max(topK * RESULT_WINDOW_MULTIPLIER, topK);

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(kn -> kn
                        .field("visionVector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK)
                );
                s.query(q -> q.term(t -> t.field("contentType").value(EsDocument.CONTENT_TYPE_IMAGE)));
                s.size(resultWindow);
                return s;
            }, EsDocument.class);

            return mapHits(response, "IMAGE");
        } catch (Exception exception) {
            logger.error("Vision search failed", exception);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> mapHits(SearchResponse<EsDocument> response, String retrievalMode) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return Collections.emptyList();
        }

        return response.hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> new SearchResult(
                        hit.source().getFileMd5(),
                        hit.source().getChunkId(),
                        hit.source().getTextContent(),
                        hit.score(),
                        hit.source().getUserId(),
                        hit.source().getOrgTag(),
                        hit.source().isPublic(),
                        null,
                        hit.source().getPageNumber(),
                        hit.source().getAnchorText(),
                        retrievalMode,
                        hit.source().getTextContent(),
                        hit.source().getContentType()
                ))
                .toList();
    }

    private List<SearchResult> mergeAndLimitResults(List<SearchResult> results, int topK, int maxChunksPerFile) {
        Map<String, SearchResult> deduplicated = new LinkedHashMap<>();
        for (SearchResult result : results) {
            if (result == null) {
                continue;
            }
            String key = result.getFileMd5() + ":" + result.getChunkId() + ":" + result.getContentType();
            SearchResult existing = deduplicated.get(key);
            if (existing == null || safeScore(result.getScore()) > safeScore(existing.getScore())) {
                deduplicated.put(key, result);
            }
        }

        List<SearchResult> sorted = deduplicated.values().stream()
                .sorted(Comparator.comparingDouble((SearchResult item) -> safeScore(item.getScore())).reversed())
                .toList();

        Map<String, Integer> fileCounters = new LinkedHashMap<>();
        List<SearchResult> limited = new ArrayList<>();
        for (SearchResult item : sorted) {
            String fileKey = item.getFileMd5();
            int currentCount = fileCounters.getOrDefault(fileKey, 0);
            if (currentCount >= maxChunksPerFile) {
                continue;
            }
            limited.add(item);
            fileCounters.put(fileKey, currentCount + 1);
            if (limited.size() >= topK) {
                break;
            }
        }
        return limited;
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }

        List<String> md5List = results.stream()
                .map(SearchResult::getFileMd5)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (md5List.isEmpty()) {
            return;
        }

        Map<String, FileUpload> fileMap = fileUploadRepository.findByFileMd5In(md5List).stream()
                .collect(Collectors.toMap(FileUpload::getFileMd5, item -> item, (first, second) -> first));

        for (SearchResult result : results) {
            FileUpload fileUpload = fileMap.get(result.getFileMd5());
            if (fileUpload != null) {
                result.setFileName(fileUpload.getFileName());
            }
        }
    }

    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vectors = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vectors == null || vectors.isEmpty()) {
                return null;
            }
            return toFloatList(vectors.get(0));
        } catch (Exception exception) {
            logger.error("Failed to generate text embedding", exception);
            return null;
        }
    }

    private List<Float> embedToVisionVectorList(String text, String requesterId) {
        try {
            VisionEmbeddingClient.EmbeddingUsageResult result = visionEmbeddingClient.embedText(
                    text,
                    requesterId,
                    VisionEmbeddingClient.UsageType.QUERY
            );
            if (result.vectors() == null || result.vectors().isEmpty()) {
                return null;
            }
            return toFloatList(result.vectors().get(0));
        } catch (Exception exception) {
            logger.error("Failed to generate vision embedding", exception);
            return null;
        }
    }

    private List<Float> toFloatList(float[] raw) {
        List<Float> list = new ArrayList<>(raw.length);
        for (float value : raw) {
            list.add(value);
        }
        return list;
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = resolveUser(userId);
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception exception) {
            logger.error("Failed to resolve effective org tags, userId={}", userId, exception);
            return Collections.emptyList();
        }
    }

    private String getUserDbId(String userId) {
        return String.valueOf(resolveUser(userId).getId());
    }

    private User resolveUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException ignored) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder applyPermissionShoulds(
            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder boolBuilder,
            String userDbId,
            Collection<String> userEffectiveTags) {
        boolBuilder.should(s -> s.term(t -> t.field("isPublic").value(true)));
        if (userDbId != null && !userDbId.isBlank()) {
            boolBuilder.should(s -> s.term(t -> t.field("userId").value(userDbId)));
        }
        if (userEffectiveTags != null) {
            for (String tag : userEffectiveTags) {
                if (tag != null && !tag.isBlank()) {
                    boolBuilder.should(s -> s.term(t -> t.field("orgTag").value(tag)));
                }
            }
        }
        boolBuilder.minimumShouldMatch("1");
        return boolBuilder;
    }

    private String normalizeQueryForSearch(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return query.replaceAll("\\s+", " ").trim();
    }

    private double safeScore(Double score) {
        return score == null ? 0.0d : score;
    }
}
