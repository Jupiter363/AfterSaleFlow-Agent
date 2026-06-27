package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.PartySubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartySubmissionRepository extends JpaRepository<PartySubmissionEntity, String> {}
