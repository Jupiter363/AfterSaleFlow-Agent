package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingStateRepository extends JpaRepository<HearingStateEntity, String> {}
