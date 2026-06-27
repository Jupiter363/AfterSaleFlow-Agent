package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewTaskRepository extends JpaRepository<ReviewTaskEntity, String> {}
