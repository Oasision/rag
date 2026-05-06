package com.huangyifei.rag.repository;

import com.huangyifei.rag.model.RechargeOrder;
import com.huangyifei.rag.model.RechargeOrder.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;






@Repository
public interface RechargeOrderRepository extends JpaRepository<RechargeOrder, Long> {
    
    


    Optional<RechargeOrder> findByTradeNo(String tradeNo);
    
    


    List<RechargeOrder> findByUserIdOrderByCreatedAtDesc(String userId);
    
    

    List<RechargeOrder> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, OrderStatus status);
}
