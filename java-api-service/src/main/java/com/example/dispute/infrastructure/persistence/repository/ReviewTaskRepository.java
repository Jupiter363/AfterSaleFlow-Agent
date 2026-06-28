package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.domain.model.ReviewTaskStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ReviewTaskRepository extends JpaRepository<ReviewTaskEntity, String> {
    List<ReviewTaskEntity> findAllByTaskStatusOrderByCreatedAtAsc(ReviewTaskStatus status);
    Optional<ReviewTaskEntity> findFirstByCaseIdOrderByCreatedAtDesc(String caseId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from ReviewTaskEntity task where task.id = :id")
    Optional<ReviewTaskEntity> findByIdForUpdate(@Param("id") String id);
}
