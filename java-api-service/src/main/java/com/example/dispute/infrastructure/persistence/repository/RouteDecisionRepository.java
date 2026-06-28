package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.RouteDecisionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteDecisionRepository
        extends JpaRepository<RouteDecisionEntity, String> {

    Optional<RouteDecisionEntity> findByCaseId(String caseId);
}
