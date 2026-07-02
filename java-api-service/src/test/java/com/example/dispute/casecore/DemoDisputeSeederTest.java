package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.domain.model.CaseStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

class DemoDisputeSeederTest {

    private final DisputeImportService importService = mock(DisputeImportService.class);

    @Test
    void seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled() {
        when(importService.importDispute(any(), any(), anyString()))
                .thenReturn(
                        new ImportedDisputeView(
                                "CASE_DEMO",
                                "EXTERNAL_IMPORT",
                                "DEMO",
                                "DEMO-1",
                                CaseStatus.INTAKE_PENDING,
                                "INTAKE",
                                null));

        new ApplicationContextRunner()
                .withUserConfiguration(DemoSeederScan.class)
                .withBean(DisputeImportService.class, () -> importService)
                .withBean(
                        Clock.class,
                        () ->
                                Clock.fixed(
                                        Instant.parse("2026-07-03T00:00:00Z"),
                                        ZoneOffset.UTC))
                .withPropertyValues("dispute.seed-demo-disputes=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ApplicationRunner.class);
                            context.getBean(ApplicationRunner.class).run(null);
                        });

        ArgumentCaptor<ImportDisputeCommand> commands =
                ArgumentCaptor.forClass(ImportDisputeCommand.class);
        verify(importService, org.mockito.Mockito.times(6))
                .importDispute(commands.capture(), any(), anyString());
        List<ImportDisputeCommand> seeded = commands.getAllValues();
        assertThat(seeded)
                .extracting(ImportDisputeCommand::externalCaseReference)
                .doesNotHaveDuplicates()
                .containsExactly(
                        "DEMO-DISPUTE-001",
                        "DEMO-DISPUTE-002",
                        "DEMO-DISPUTE-003",
                        "DEMO-DISPUTE-004",
                        "DEMO-DISPUTE-005",
                        "DEMO-DISPUTE-006");
        assertThat(seeded)
                .extracting(ImportDisputeCommand::caseStatus)
                .contains(
                        CaseStatus.INTAKE_PENDING,
                        CaseStatus.EVIDENCE_OPEN,
                        CaseStatus.HEARING_OPEN,
                        CaseStatus.REVIEW_PENDING,
                        CaseStatus.CLOSED);
    }

    @Test
    void doesNotRegisterTheSeederWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(DemoSeederScan.class)
                .withBean(DisputeImportService.class, () -> importService)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("dispute.seed-demo-disputes=false")
                .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = "com.example.dispute.casecore.application",
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern =
                                    "com\\.example\\.dispute\\.casecore\\.application\\.DemoDisputeSeeder"))
    static class DemoSeederScan {}
}
