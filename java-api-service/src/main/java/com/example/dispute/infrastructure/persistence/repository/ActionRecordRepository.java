package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionRecordRepository extends JpaRepository<ActionRecordEntity, String> {}
