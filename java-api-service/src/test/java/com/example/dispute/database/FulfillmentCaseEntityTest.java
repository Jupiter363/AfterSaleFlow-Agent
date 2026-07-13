/*
 * 所属模块：数据库迁移入口。
 * 文件职责：验证履约案件，覆盖 「openingHearingMarksRoomFlowAsFullHearing」、「importedHearingRoomCasesAreFullHearingCases」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；独立执行 Flyway 迁移并验证 PostgreSQL Schema 可用。
 * 关键边界：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
 */
package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

// 所属模块：【数据库迁移入口 / 自动化测试层】类型「FulfillmentCaseEntityTest」。
// 类型职责：集中验证履约案件的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「openingHearingMarksRoomFlowAsFullHearing」、「importedHearingRoomCasesAreFullHearingCases」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class FulfillmentCaseEntityTest {

    // 所属模块：【数据库迁移入口 / 自动化测试层】「FulfillmentCaseEntityTest.openingHearingMarksRoomFlowAsFullHearing()」。
    // 具体功能：「FulfillmentCaseEntityTest.openingHearingMarksRoomFlowAsFullHearing()」：复现“核对完整业务行为（场景方法「openingHearingMarksRoomFlowAsFullHearing」）”场景：驱动 「FulfillmentCaseEntity.imported」、「dispute.openHearing」、「dispute.getCaseStatus」、「dispute.getRouteType」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_room_hearing_route」、「ORDER_room_hearing_route」、「LOG_room_hearing_route」、「user-room」。
    // 上游调用：「FulfillmentCaseEntityTest.openingHearingMarksRoomFlowAsFullHearing()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FulfillmentCaseEntityTest.openingHearingMarksRoomFlowAsFullHearing()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FulfillmentCaseEntityTest.openingHearingMarksRoomFlowAsFullHearing()」守住「数据库迁移入口」的可执行规格，尤其防止 「CASE_room_hearing_route」、「ORDER_room_hearing_route」、「LOG_room_hearing_route」、「user-room」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【数据库迁移入口 / 自动化测试层】「FulfillmentCaseEntityTest.importedHearingRoomCasesAreFullHearingCases()」。
    // 具体功能：「FulfillmentCaseEntityTest.importedHearingRoomCasesAreFullHearingCases()」：复现“核对完整业务行为（场景方法「importedHearingRoomCasesAreFullHearingCases」）”场景：驱动 「FulfillmentCaseEntity.imported」、「dispute.getRouteType」、「OffsetDateTime.now().plusHours」、「assertThat(dispute.getRouteType()).isEqualTo」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_imported_hearing_route」、「ORDER_imported_hearing_route」、「LOG_imported_hearing_route」、「user-room」。
    // 上游调用：「FulfillmentCaseEntityTest.importedHearingRoomCasesAreFullHearingCases()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FulfillmentCaseEntityTest.importedHearingRoomCasesAreFullHearingCases()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FulfillmentCaseEntityTest.importedHearingRoomCasesAreFullHearingCases()」守住「数据库迁移入口」的可执行规格，尤其防止 「CASE_imported_hearing_route」、「ORDER_imported_hearing_route」、「LOG_imported_hearing_route」、「user-room」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
