package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loads local product-tour disputes through the same idempotent application boundary as a real
 * external adapter. Flyway remains schema-only and production stays empty unless explicitly
 * enabled.
 */
@Component
@ConditionalOnProperty(
        prefix = "dispute",
        name = "seed-demo-disputes",
        havingValue = "true")
public class DemoDisputeSeeder implements ApplicationRunner {

    private static final AuthenticatedActor DEMO_ADAPTER =
            new AuthenticatedActor("demo-dispute-adapter", ActorRole.SYSTEM);

    private final DisputeImportService importService;
    private final Clock clock;

    public DemoDisputeSeeder(DisputeImportService importService, Clock clock) {
        this.importService = importService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        demoCommands().forEach(
                command ->
                        importService.importDispute(
                                command,
                                DEMO_ADAPTER,
                                "seed-" + command.externalCaseReference().toLowerCase()));
    }

    private List<ImportDisputeCommand> demoCommands() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return List.of(
                command(
                        "DEMO-DISPUTE-001",
                        "ORDER-DEMO-001",
                        "user-local",
                        "merchant-demo-a",
                        "SIGNED_NOT_RECEIVED",
                        "包裹显示签收，但我没有收到",
                        "接待官正在梳理订单、物流和双方主张。",
                        RiskLevel.HIGH,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null),
                command(
                        "DEMO-DISPUTE-002",
                        "ORDER-DEMO-002",
                        "user-local",
                        "merchant-demo-b",
                        "DAMAGED_ON_ARRIVAL",
                        "开箱后发现商品破损",
                        "证据书记官正在等待双方提交图片、视频和物流凭证。",
                        RiskLevel.MEDIUM,
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        now.plus(Duration.ofHours(2))),
                command(
                        "DEMO-DISPUTE-003",
                        "ORDER-DEMO-003",
                        "user-local",
                        "merchant-demo-c",
                        "ITEM_MISMATCH",
                        "收到的商品与订单不一致",
                        "小法庭已经开放，双方可以陈述并补充证据。",
                        RiskLevel.MEDIUM,
                        CaseStatus.HEARING_OPEN,
                        "HEARING",
                        now.plus(Duration.ofHours(3))),
                command(
                        "DEMO-DISPUTE-004",
                        "ORDER-DEMO-004",
                        "user-demo-d",
                        "merchant-local",
                        "LATE_DELIVERY",
                        "履约超时引发损失争议",
                        "商家需要在证据时效内提交履约说明。",
                        RiskLevel.LOW,
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        now.plus(Duration.ofHours(2))),
                command(
                        "DEMO-DISPUTE-005",
                        "ORDER-DEMO-005",
                        "user-demo-e",
                        "merchant-local",
                        "SERVICE_NOT_FULFILLED",
                        "约定服务未按期履行",
                        "裁决草案已形成，等待平台审核员终审。",
                        RiskLevel.HIGH,
                        CaseStatus.REVIEW_PENDING,
                        "REVIEW",
                        null),
                command(
                        "DEMO-DISPUTE-006",
                        "ORDER-DEMO-006",
                        "user-local",
                        "merchant-local",
                        "REFUND_FULFILLMENT",
                        "退款履约争议已结案",
                        "平台终审完成，确定性执行记录可以查看。",
                        RiskLevel.LOW,
                        CaseStatus.CLOSED,
                        "REVIEW",
                        null));
    }

    private static ImportDisputeCommand command(
            String externalReference,
            String orderReference,
            String userId,
            String merchantId,
            String disputeType,
            String title,
            String description,
            RiskLevel riskLevel,
            CaseStatus status,
            String room,
            OffsetDateTime deadline) {
        return new ImportDisputeCommand(
                "DEMO",
                externalReference,
                orderReference,
                null,
                "LOG-" + externalReference,
                userId,
                merchantId,
                "USER",
                disputeType,
                title,
                description,
                riskLevel,
                status,
                room,
                deadline);
    }
}
