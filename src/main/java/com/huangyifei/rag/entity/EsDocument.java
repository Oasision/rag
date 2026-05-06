package com.huangyifei.rag.entity;

import lombok.Data;

@Data
public class EsDocument {

    public static final String CONTENT_TYPE_TEXT = "TEXT";
    public static final String CONTENT_TYPE_IMAGE = "IMAGE";

    private String id;
    private String fileMd5;
    private Integer chunkId;
    private String textContent;
    private Integer pageNumber;
    private String anchorText;
    private float[] vector;
    private float[] visionVector;
    private String modelVersion;
    private String userId;
    private String orgTag;
    private boolean isPublic;
    private String contentType;

    public EsDocument() {
    }

    public EsDocument(String id, String fileMd5, int chunkId, String content,
                      Integer pageNumber, String anchorText,
                      float[] vector, String modelVersion,
                      String userId, String orgTag, boolean isPublic) {
        this(id, fileMd5, chunkId, content, pageNumber, anchorText, vector, null, modelVersion, userId, orgTag, isPublic, CONTENT_TYPE_TEXT);
    }

    public EsDocument(String id, String fileMd5, int chunkId, String content,
                      Integer pageNumber, String anchorText,
                      float[] vector, float[] visionVector, String modelVersion,
                      String userId, String orgTag, boolean isPublic, String contentType) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = content;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.vector = vector;
        this.visionVector = visionVector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.contentType = contentType != null ? contentType : CONTENT_TYPE_TEXT;
    }
}
