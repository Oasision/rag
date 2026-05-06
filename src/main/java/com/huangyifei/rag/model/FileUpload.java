package com.huangyifei.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;



@Data
@Entity
@Table(name = "file_upload")
public class FileUpload {
    public static final int STATUS_UPLOADING = 0;
    public static final int STATUS_COMPLETED = 1;
    public static final int STATUS_MERGING = 2;
    public static final String VECTORIZATION_STATUS_PENDING = "PENDING";
    public static final String VECTORIZATION_STATUS_PROCESSING = "PROCESSING";
    public static final String VECTORIZATION_STATUS_COMPLETED = "COMPLETED";
    public static final String VECTORIZATION_STATUS_FAILED = "FAILED";

    

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; 

    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    


    private String fileName;

    

    private long totalSize;

    

    private int status; 
    

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;
    
    

    @Column(name = "org_tag")
    private String orgTag;

    


    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(name = "estimated_embedding_tokens")
    private Long estimatedEmbeddingTokens;

    @Column(name = "estimated_chunk_count")
    private Integer estimatedChunkCount;

    @Column(name = "actual_embedding_tokens")
    private Long actualEmbeddingTokens;

    @Column(name = "actual_chunk_count")
    private Integer actualChunkCount;

    @Column(name = "vectorization_status", length = 32)
    private String vectorizationStatus;

    @Column(name = "vectorization_error_message", length = 1000)
    private String vectorizationErrorMessage;

    


    @CreationTimestamp
    private LocalDateTime createdAt;

    


    @UpdateTimestamp
    private LocalDateTime mergedAt;
}
