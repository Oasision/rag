package com.huangyifei.rag.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "recharge_orders")
public class RechargeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; 

    @Column(nullable = false, name = "trade_no", unique = true)
    private String tradeNo; 
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId; 

    @Column(nullable = false, name = "package_id")
    private Integer packageId; 
    @Column(nullable = false)
    private Long amount; 

    @Column(nullable = false, name = "llm_token")
    private Long llmToken; 

    @Column(nullable = false, name = "embedding_token")
    private Long embeddingToken; 

    @Column(name = "wx_transaction_id")
    private String wxTransactionId; 
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status; 
    @Column
    private String description; 

    @Column(name = "pay_time")
    private LocalDateTime payTime; 

    @CreationTimestamp
    private LocalDateTime createdAt; 

    @UpdateTimestamp
    private LocalDateTime updatedAt; 

    

    public enum OrderStatus {
        NOT_PAY,      
        PAYING,
        SUCCEED,
        FAIL,         
        CANCELLED     
    }
}
