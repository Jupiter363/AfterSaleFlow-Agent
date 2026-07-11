package com.example.dispute.casecore.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DemoCasePurgeServiceTest {

    private final FulfillmentCaseRepository caseRepository =
            mock(FulfillmentCaseRepository.class);
    private final DemoCasePurgeStore purgeStore = mock(DemoCasePurgeStore.class);
    private final DemoCasePurgeService service =
            new DemoCasePurgeService(caseRepository, purgeStore);

    @ParameterizedTest
    @MethodSource("nonReviewerActors")
    void rejectsEveryRoleExceptPlatformReviewer(AuthenticatedActor actor) {
        assertThatThrownBy(() -> service.purge("CASE_demo", actor))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("only the platform reviewer can delete simulated cases");

        verifyNoInteractions(caseRepository, purgeStore);
    }

    @Test
    void returnsNotFoundWhenCaseDoesNotExist() {
        when(caseRepository.findByIdForUpdate("CASE_missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.purge(
                                        "CASE_missing",
                                        new AuthenticatedActor(
                                                "reviewer-local",
                                                ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("case was not found");

        verifyNoInteractions(purgeStore);
    }

    @Test
    void rejectsARegularIntakeCase() {
        FulfillmentCaseEntity disputeCase = simulatedCase(CaseSourceType.INTAKE_CREATED, null);
        when(caseRepository.findByIdForUpdate("CASE_regular"))
                .thenReturn(Optional.of(disputeCase));

        assertThatThrownBy(
                        () ->
                                service.purge(
                                        "CASE_regular",
                                        new AuthenticatedActor(
                                                "reviewer-local",
                                                ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("only simulated imported cases can be deleted");

        verifyNoInteractions(purgeStore);
    }

    @Test
    void rejectsARealExternalImport() {
        FulfillmentCaseEntity disputeCase = simulatedCase(CaseSourceType.EXTERNAL_IMPORT, "OMS");
        when(caseRepository.findByIdForUpdate("CASE_external"))
                .thenReturn(Optional.of(disputeCase));

        assertThatThrownBy(
                        () ->
                                service.purge(
                                        "CASE_external",
                                        new AuthenticatedActor(
                                                "reviewer-local",
                                                ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("only simulated imported cases can be deleted");

        verifyNoInteractions(purgeStore);
    }

    @ParameterizedTest
    @MethodSource("simulatedSources")
    void purgesNewAndLegacySimulatedImports(String sourceSystem) {
        FulfillmentCaseEntity disputeCase =
                simulatedCase(CaseSourceType.EXTERNAL_IMPORT, sourceSystem);
        when(caseRepository.findByIdForUpdate("CASE_demo"))
                .thenReturn(Optional.of(disputeCase));

        service.purge(
                "CASE_demo",
                new AuthenticatedActor(
                        "reviewer-local", ActorRole.PLATFORM_REVIEWER));

        verify(purgeStore)
                .purge("CASE_demo", "reviewer-local", ActorRole.PLATFORM_REVIEWER.name());
    }

    private static FulfillmentCaseEntity simulatedCase(
            CaseSourceType sourceType, String sourceSystem) {
        FulfillmentCaseEntity disputeCase = mock(FulfillmentCaseEntity.class);
        when(disputeCase.getSourceType()).thenReturn(sourceType);
        when(disputeCase.getSourceSystem()).thenReturn(sourceSystem);
        return disputeCase;
    }

    private static Stream<AuthenticatedActor> nonReviewerActors() {
        return Stream.of(
                new AuthenticatedActor("user-local", ActorRole.USER),
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                new AuthenticatedActor("admin-local", ActorRole.ADMIN),
                new AuthenticatedActor("system", ActorRole.SYSTEM));
    }

    private static Stream<String> simulatedSources() {
        return Stream.of("TEMPLATE_SIMULATED_OMS", "LLM_SIMULATED_OMS");
    }
}
