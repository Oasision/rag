package com.huangyifei.rag.controller;

import com.huangyifei.rag.entity.SearchResult;
import com.huangyifei.rag.service.HybridSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    @Autowired
    private HybridSearchService hybridSearchService;

    @GetMapping("/hybrid")
    public Map<String, Object> hybridSearch(@RequestParam String query,
                                            @RequestParam(defaultValue = "10") int topK,
                                            @RequestAttribute(value = "userId", required = false) String userId) {
        try {
            List<SearchResult> results = userId != null
                    ? hybridSearchService.searchWithPermission(query, userId, topK)
                    : hybridSearchService.search(query, topK);

            Map<String, Object> responseBody = new HashMap<>(4);
            responseBody.put("code", 200);
            responseBody.put("message", "success");
            responseBody.put("data", results);
            return responseBody;
        } catch (Exception e) {
            Map<String, Object> errorBody = new HashMap<>(4);
            errorBody.put("code", 500);
            errorBody.put("message", e.getMessage());
            errorBody.put("data", Collections.emptyList());
            return errorBody;
        }
    }
}
