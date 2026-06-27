package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdjudicationDraftRepository
        extends JpaRepository<AdjudicationDraftEntity, String> {}
