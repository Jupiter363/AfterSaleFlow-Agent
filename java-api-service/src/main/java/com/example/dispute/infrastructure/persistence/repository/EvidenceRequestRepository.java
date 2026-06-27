package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvidenceRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceRequestRepository extends JpaRepository<EvidenceRequestEntity, String> {}
