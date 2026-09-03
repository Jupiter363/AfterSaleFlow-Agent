package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.domain.EvidenceParseOutboxStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceParseOutboxEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface EvidenceParseOutboxRepository extends JpaRepository<EvidenceParseOutboxEntity, String> {
    Optional<EvidenceParseOutboxEntity> findByEvidenceIdAndFileSha256AndParserVersion(
            String evidenceId, String fileSha256, String parserVersion);

    boolean existsByEvidenceId(String evidenceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from EvidenceParseOutboxEntity o where o.requestKey = :requestKey")
    Optional<EvidenceParseOutboxEntity> lockByRequestKey(@Param("requestKey") String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from EvidenceParseOutboxEntity o where o.id = :id")
    Optional<EvidenceParseOutboxEntity> lockById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from EvidenceParseOutboxEntity o
             where (o.status = :pending and o.availableAt <= :now)
                or (o.status = :inFlight and o.leaseExpiresAt <= :now)
             order by o.availableAt asc, o.id asc
            """)
    List<EvidenceParseOutboxEntity> lockClaimable(
            @Param("now") OffsetDateTime now,
            @Param("pending") EvidenceParseOutboxStatus pending,
            @Param("inFlight") EvidenceParseOutboxStatus inFlight,
            Pageable pageable);
}
