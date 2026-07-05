package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseIntakeDossierRepository
        extends JpaRepository<CaseIntakeDossierEntity, String> {

    Optional<CaseIntakeDossierEntity> findByCaseIdAndRoomType(
            String caseId, RoomType roomType);
}

