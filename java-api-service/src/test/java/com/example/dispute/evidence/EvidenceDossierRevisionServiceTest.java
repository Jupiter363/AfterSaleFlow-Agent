package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.EvidenceDossierRevisionService;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceDossierRevisionServiceTest {

    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CaseEventService eventService;

    @Test
    void secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable() {
        EvidenceDossierRevisionService service =
                new EvidenceDossierRevisionService(
                        dossierRepository,
                        roomRepository,
                        eventService,
                        new ObjectMapper());
        when(dossierRepository.findTopByCaseIdOrderByDossierVersionDesc("CASE_BAD_MATRIX"))
                .thenReturn(
                        Optional.of(
                                EvidenceDossierEntity.frozen(
                                        "EVIDENCE_DOSSIER_BAD_MATRIX_V1",
                                        "CASE_BAD_MATRIX",
                                        1,
                                        "evidence-clerk",
                                        "{\"evidence_count\":1}",
                                        "[]",
                                        "{not-json")));

        assertThatThrownBy(
                        () ->
                                service.reviseAfterRoundIfNeeded(
                                        "CASE_BAD_MATRIX",
                                        EvidenceDossierRevisionService.EVIDENCE_EXPLANATION_ROUND,
                                        List.of(),
                                        "evidence-clerk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid evidence dossier latest matrix summary");

        verify(dossierRepository, never()).save(any());
        verifyNoInteractions(roomRepository, eventService);
    }
}
