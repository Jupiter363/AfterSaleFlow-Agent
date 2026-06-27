package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecordEntity, String> {}
