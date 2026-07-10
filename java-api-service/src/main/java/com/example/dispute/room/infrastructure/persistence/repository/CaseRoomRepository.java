package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseRoomRepository extends JpaRepository<CaseRoomEntity, String> {

    Optional<CaseRoomEntity> findByCaseIdAndRoomType(String caseId, RoomType roomType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select room
              from CaseRoomEntity room
             where room.caseId = :caseId
               and room.roomType = :roomType
            """)
    Optional<CaseRoomEntity> findByCaseIdAndRoomTypeForUpdate(
            @Param("caseId") String caseId, @Param("roomType") RoomType roomType);

    List<CaseRoomEntity> findAllByCaseId(String caseId);
}
