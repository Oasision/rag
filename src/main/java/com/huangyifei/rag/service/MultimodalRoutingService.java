package com.huangyifei.rag.service;

import com.huangyifei.rag.entity.SearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class MultimodalRoutingService {

    private static final Set<String> VISION_KEYWORDS = Set.of(
            "图片", "图像", "照片", "截图", "看图", "视觉", "画面", "颜色", "形状", "瑕疵",
            "image", "photo", "picture", "vision", "visual", "screenshot"
    );

    public boolean shouldRunVisionRecall(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return VISION_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    public boolean shouldUseMultimodalGeneration(String query, List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return false;
        }
        boolean hasImageEvidence = searchResults.stream()
                .anyMatch(item -> SearchResult.CONTENT_TYPE_IMAGE.equalsIgnoreCase(item.getContentType()));
        return hasImageEvidence && shouldRunVisionRecall(query);
    }
}
