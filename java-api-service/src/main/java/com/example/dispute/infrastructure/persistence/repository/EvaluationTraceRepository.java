package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvaluationTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationTraceRepository extends JpaRepository<EvaluationTraceEntity, String> {}
