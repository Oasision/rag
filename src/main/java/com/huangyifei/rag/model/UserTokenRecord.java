package com.huangyifei.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;







@Data
@Entity
@Table(name = "user_token_record", 
       indexes = {
           @Index(name = "idx_user_date", columnList = "userId, recordDate")
       })
public class UserTokenRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    


    @Column(nullable = false)
    private String userId;

    


    @Column(nullable = false)
    private LocalDate recordDate;

    


    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TokenType tokenType;

    


    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ChangeType changeType;

    


    @Column(nullable = false)
    private Long amount;

    


    private Long balanceBefore;

    


    private Long balanceAfter;

    


    @Column(length = 500)
    private String reason;

    


    @Column(length = 500)
    private String remark;

    

    @Column(nullable = false)
    private Long requestCount = 0L;

    


    @CreationTimestamp
    private LocalDateTime createdAt;

    


    public enum TokenType {
        LLM,          
        EMBEDDING     
    }

    


    public enum ChangeType {
        INCREASE,
        CONSUME
    }
}
