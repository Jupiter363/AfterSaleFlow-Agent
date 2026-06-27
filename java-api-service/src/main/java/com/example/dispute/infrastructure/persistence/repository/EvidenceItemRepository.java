package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItemEntity, String> {}
