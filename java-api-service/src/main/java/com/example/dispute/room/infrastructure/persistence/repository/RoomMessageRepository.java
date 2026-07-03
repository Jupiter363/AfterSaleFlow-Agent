package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomMessageRepository extends JpaRepository<RoomMessageEntity, String> {
    Optional<RoomMessageEntity> findByCaseIdAndIdempotencyKey(String caseId, String idempotencyKey);
    List<RoomMessageEntity> findAllByRoomIdOrderBySequenceNoAsc(String roomId);

    @Query("select coalesce(max(message.sequenceNo), 0) from RoomMessageEntity message where message.roomId = :roomId")
    long findMaxSequenceByRoomId(@Param("roomId") String roomId);
}
