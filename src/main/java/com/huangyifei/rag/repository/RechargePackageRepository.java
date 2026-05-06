package com.huangyifei.rag.repository;

import com.huangyifei.rag.model.RechargePackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;






@Repository
public interface RechargePackageRepository extends JpaRepository<RechargePackage, Integer> {

    

    List<RechargePackage> findAllByEnabledTrueAndDeletedFalseOrderBySortOrderAsc();

    

    List<RechargePackage> findAllByEnabledTrueAndDeletedFalseAndPackagePriceGreaterThanOrderBySortOrderAsc(Long price);

    


    List<RechargePackage> findAllByDeletedFalseOrderBySortOrderAsc();

    


    Optional<RechargePackage> findByPackagePriceAndEnabledIsTrueAndDeletedFalse(Integer price);

    

    Optional<RechargePackage> findById(Integer id);
}
