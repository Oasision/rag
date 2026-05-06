package com.huangyifei.rag.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;







@Data
@Entity
@Table(name = "user_daily_chat_count",
        indexes = {
                @Index(name = "idx_record_date", columnList = "recordDate")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_date", columnNames = {"userId", "recordDate"})
        })
public class UserDailyChatCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    


    @Column(nullable = false, length = 50, name = "user_id")
    private String userId;

    


    @Column(nullable = false, name = "record_date")
    private LocalDate recordDate;

    


    @Column(nullable = false, name = "chat_request_count")
    private Long chatRequestCount = 0L;

    @CreationTimestamp
    @Column(nullable = false, name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false, name = "updated_at")
    private LocalDateTime updatedAt;
}
