package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewPacketRepository extends JpaRepository<ReviewPacketEntity, String> {
    Optional<ReviewPacketEntity> findFirstByCaseIdAndPlanIdOrderByPacketVersionDesc(String caseId,String planId);
}
