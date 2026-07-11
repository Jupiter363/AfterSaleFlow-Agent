package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomTurnMemoryRepository
        extends JpaRepository<RoomTurnMemoryEntity, String> {

    Optional<RoomTurnMemoryEntity>
            findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                    String caseId, RoomType roomType);

    Optional<RoomTurnMemoryEntity>
            findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(
                    String agentSessionId);

    List<RoomTurnMemoryEntity> findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(
            String caseId, RoomType roomType);

    List<RoomTurnMemoryEntity> findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(
            String caseId, RoomType roomType);

    List<RoomTurnMemoryEntity> findTop10ByAgentSessionIdOrderByTurnNoDesc(
            String agentSessionId);

    List<RoomTurnMemoryEntity> findTop50ByAgentSessionIdOrderByTurnNoDesc(
            String agentSessionId);

    List<RoomTurnMemoryEntity>
            findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(
                    String agentSessionId);

    @Query(
            "select coalesce(max(memory.turnNo), 0) from RoomTurnMemoryEntity memory "
                    + "where memory.caseId = :caseId and memory.roomType = :roomType")
    int findMaxTurnNo(String caseId, RoomType roomType);

    @Query(
            "select coalesce(max(memory.turnNo), 0) from RoomTurnMemoryEntity memory "
                    + "where memory.agentSessionId = :agentSessionId")
    int findMaxTurnNoByAgentSessionId(String agentSessionId);
}
