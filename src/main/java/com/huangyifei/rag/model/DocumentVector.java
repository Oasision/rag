package com.huangyifei.rag.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Blob;




@Data
@Entity
@Table(name = "document_vectors")
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(nullable = false, length = 32)
    private String fileMd5;

    @Column(nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Column(length = 32)
    private String modelVersion;
    
    


    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;
    
    

    @Column(name = "org_tag", length = 50)
    private String orgTag;
    
    


    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;
}
