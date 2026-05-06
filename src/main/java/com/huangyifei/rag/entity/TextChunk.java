package com.huangyifei.rag.entity;

import lombok.Getter;

@Getter
public class TextChunk {

    private final int chunkId;
    private final String content;
    private final Integer pageNumber;
    private final String anchorText;

    public TextChunk(int chunkId, String content) {
        this(chunkId, content, null, null);
    }

    public TextChunk(int chunkId, String content, Integer pageNumber, String anchorText) {
        this.chunkId = chunkId;
        this.content = content;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
    }
}
