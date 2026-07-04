package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class FulfillmentCaseEntityTest {

    @Test
    void openingHearingMarksRoomFlowAsFullHearing() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_room_hearing_route",
                        "ORDER_room_hearing_route",
                        null,
                        "LOG_room_hearing_route",
                        "user-room",
                        "merchant-room",
                        "IMPORT_room_hearing_route",
                        "DELIVERY_NOT_RECEIVED",
                        "Delivery dispute",
                        "Package says delivered but was not received.",
                        RiskLevel.HIGH,
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        OffsetDateTime.now().plusHours(2),
                        "external",
                        "EXT_room_hearing_route",
                        "system");

        dispute.openHearing(OffsetDateTime.now().plusHours(3), "system");

        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.HEARING_OPEN);
        assertThat(dispute.getRouteType()).isEqualTo(RouteType.FULL_HEARING);
    }

    @Test
    void importedHearingRoomCasesAreFullHearingCases() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_imported_hearing_route",
                        "ORDER_imported_hearing_route",
                        null,
                        "LOG_imported_hearing_route",
                        "user-room",
                        "merchant-room",
                        "IMPORT_imported_hearing_route",
                        "DELIVERY_NOT_RECEIVED",
                        "Delivery dispute",
                        "Imported case is already in the hearing room.",
                        RiskLevel.HIGH,
                        CaseStatus.HEARING,
                        "HEARING",
                        OffsetDateTime.now().plusHours(3),
                        "external",
                        "EXT_imported_hearing_route",
                        "system");

        assertThat(dispute.getRouteType()).isEqualTo(RouteType.FULL_HEARING);
    }
}
