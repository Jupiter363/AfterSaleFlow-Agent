package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRoomRepository extends JpaRepository<CaseRoomEntity, String> {

    Optional<CaseRoomEntity> findByCaseIdAndRoomType(String caseId, RoomType roomType);

    List<CaseRoomEntity> findAllByCaseId(String caseId);
}
