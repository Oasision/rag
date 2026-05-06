package com.huangyifei.rag.entity;

import lombok.Data;

@Data
public class SearchResult {

    public static final String CONTENT_TYPE_TEXT = "TEXT";
    public static final String CONTENT_TYPE_IMAGE = "IMAGE";

    private String fileMd5;
    private Integer chunkId;
    private String textContent;
    private Double score;
    private String fileName;
    private String userId;
    private String orgTag;
    private Boolean isPublic;
    private Integer pageNumber;
    private String anchorText;
    private String retrievalMode;
    private String matchedChunkText;
    private String contentType;

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score) {
        this(fileMd5, chunkId, textContent, score, null, null, false, null, null, null, null, null, CONTENT_TYPE_TEXT);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String fileName) {
        this(fileMd5, chunkId, textContent, score, null, null, false, fileName, null, null, null, null, CONTENT_TYPE_TEXT);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, null, null, null, null, null, CONTENT_TYPE_TEXT);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, null, null, null, null, CONTENT_TYPE_TEXT);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, pageNumber, anchorText, null, textContent, CONTENT_TYPE_TEXT);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, pageNumber, anchorText, retrievalMode, matchedChunkText, CONTENT_TYPE_TEXT);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText, String contentType) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.retrievalMode = retrievalMode;
        this.matchedChunkText = matchedChunkText != null ? matchedChunkText : textContent;
        this.contentType = contentType != null ? contentType : CONTENT_TYPE_TEXT;
    }
}
