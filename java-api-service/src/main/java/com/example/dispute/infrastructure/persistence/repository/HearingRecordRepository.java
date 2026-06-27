package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingRecordRepository extends JpaRepository<HearingRecordEntity, String> {}
