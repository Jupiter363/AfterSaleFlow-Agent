package com.example.dispute.casecore.infrastructure.persistence.repository;

import com.example.dispute.casecore.infrastructure.persistence.entity.SimulatedImportTemplateCursorEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SimulatedImportTemplateCursorRepository
        extends JpaRepository<SimulatedImportTemplateCursorEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select cursor from SimulatedImportTemplateCursorEntity cursor"
                    + " where cursor.id = :id")
    Optional<SimulatedImportTemplateCursorEntity> findByIdForUpdate(@Param("id") String id);
}
