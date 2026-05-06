package com.huangyifei.rag.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;





@Data
@Entity
@Table(name = "recharge_packages")
public class RechargePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id; 

    @Column(nullable = false, length = 128, name = "package_name")
    private String packageName; 

    @Column(nullable = false, name = "package_price")
    private Long packagePrice; 

    @Column(columnDefinition = "TEXT", name = "package_desc")
    private String packageDesc; 

    @Column(columnDefinition = "TEXT", name = "package_benefit")
    private String packageBenefit; 

    @Column(nullable = false, name = "llm_token")
    private Long llmToken; 

    @Column(nullable = false, name = "embedding_token")
    private Long embeddingToken; 

    @Column(nullable = false, name = "enabled")
    private Boolean enabled = true; 

    @Column(nullable = false, name = "deleted")
    private Boolean deleted = false; 
    @Column(nullable = false, name = "sort_order")
    private Integer sortOrder = 0; 
    @CreationTimestamp
    private LocalDateTime createdAt; 

    @UpdateTimestamp
    private LocalDateTime updatedAt; 
}
