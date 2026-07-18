# Phase 1 MIG-001 SHADOW 证据归档与切流手册

Phase 2-8 开始前应先阅读
[`phase-1-retrospective.md`](./phase-1-retrospective.md)，复核 Phase 1 已出现的问题、
故障分类规则和防复发检查项。本手册负责正式证据和切流边界，复盘负责开发过程改进，二者不能互相替代。

## 1. 门禁边界

本手册生成 `temporal-first-phase-evidence.v1` 的 MIG-001 技术证据。Phase 1 只验证
Case/Room Workflow 控制面、bootstrap/command outbox、projection fencing、reconciliation、
独立 Task Queue 和故障恢复，不允许 Temporal 接管正式房间推进。

现场演练必须使用合成、非 PII 的 SHADOW case：

- Java 继续持有正式业务真相和宏观推进权。
- SHADOW epoch 的 lifecycle 始终为 `ACTIVE`。
- bootstrap 前后状态为 `ACTIVE + PENDING/PROVISIONING -> ACTIVE + READY`。
- command 最终状态只能是 `SHADOW_COMPLETED`，不能是 `APPLIED`。
- MIG-001 通过时，真实业务 epoch 可以且默认全部保持 LEGACY。

归档器只产生 `technical_result=PASS` 和 `promotion_status=PENDING_APPROVAL`。它不是切流
批准；Architecture、PhaseOwner 和 Release 的签名 promotion attestation 是独立后置门禁。

固定控制队列：

| Workflow | Task Queue |
|---|---|
| `CaseProcessWorkflow` | `case-control` |
| `RoomControlWorkflow` | `room-control` |

`TEMPORAL_TASK_QUEUE` 仅属于历史 `EvidenceWindowWorkflow` 兼容队列。

## 2. 安全与环境前置条件

1. 使用独立、干净的 release worktree，所有待验收代码已提交。
2. canary 使用专用 synthetic tenant、synthetic actor 和无业务含义的 payload reference；禁止
   使用生产 case ID、订单号、用户/商家 ID、证据内容、聊天文本或真实对象存储 URI。
3. 固定 Java 镜像 digest、Control Worker build ID、Temporal namespace、Temporal server、
   PostgreSQL major/minor 和 Flyway schema。
4. evidence artifact store 必须使用 KMS 加密、private ACL、访问审计和不可变保留策略。
5. KMS key ID 可以进入 metadata，密钥、数据库 URL、Token、证书和解密材料禁止进入 metadata。
6. 操作者只有 Temporal History 读取权和业务数据库只读权，不得直接修改 ledger/outbox/epoch。

归档器 metadata 仅允许以下键，并要求除 `skip_approval` 外全部存在：

```text
environment_id, temporal_namespace, control_build_id, java_image_digest,
postgresql_version, temporal_server_version, kms_key_id,
artifact_retention_days, skip_approval
```

设置演练标识。`CASE_ID/EPOCH_ID/BOOTSTRAP_UPDATE_ID/COMMAND_ID` 必须在场景驱动前生成并保持
不变；Workflow ID 必须来自 allocator 的持久化选择，Run ID 在 bootstrap receipt 后填写：

```bash
export RELEASE_ID="phase1-$(date -u +%Y%m%dT%H%M%SZ)"
export EVALUATED_HEAD="$(git rev-parse HEAD)"
export ENVIRONMENT_ID="synthetic-compose-mig001"
export TEMPORAL_NAMESPACE="default"
export CONTROL_BUILD_ID="<immutable-build-id>"
export JAVA_IMAGE_DIGEST="sha256:<64-lowercase-hex>"
export POSTGRESQL_VERSION="<major.minor>"
export TEMPORAL_SERVER_VERSION="<version>"
export EVIDENCE_KMS_KEY_ID="<non-secret-key-id>"
export ARTIFACT_RETENTION_DAYS="30"

export CASE_ID="CASE_MIG001_<synthetic-id>"
export EPOCH_ID="EPOCH_MIG001_<synthetic-id>"
export BOOTSTRAP_UPDATE_ID="bootstrap-mig001-<synthetic-id>"
export COMMAND_ID="command-mig001-<synthetic-id>"
export CASE_WORKFLOW_ID="<persisted-case-workflow-id>"
export ROOM_WORKFLOW_ID="<persisted-room-workflow-id>"
export CASE_RUN_ID=""
export ROOM_RUN_ID=""

export RAW_DIR="java-api-service/target/mig-001/${RELEASE_ID}/raw"
export ARTIFACT_ROOT="${MIG001_ARTIFACT_ROOT:-../phase-evidence-artifacts}"
export BUNDLE_DIR="${ARTIFACT_ROOT}/test-reports/temporal-first/${RELEASE_ID}/checks/MIG-001"
mkdir -p "$RAW_DIR" "$BUNDLE_DIR"
test -z "$(git status --porcelain=v1 --untracked-files=all)"
test "$(git rev-parse HEAD)" = "$EVALUATED_HEAD"
```

## 3. 必需测试与 JUnit XML

先 `clean`，避免归档旧 report。Surefire 和 Failsafe 必须分开执行；归档器会从每个 testcase
的 `classname` 校验以下全部必需类实际运行，缺少任一类即失败：

```bash
cd java-api-service
./mvnw clean \
  -Dtest=CaseProcessWorkflowReplayTest,RoomControlWorkflowReplayTest,TemporalWorkerRecoveryTest,CaseDomainEventRecoveryRelayTest,ProcessProjectionReconciliationSchedulerTest \
  test
./mvnw -Pintegration-test \
  -Dit.test=TemporalControlPlaneMigrationIntegrationTest,RoomEpochAllocatorIntegrationTest,RoomEpochBootstrapStoreIntegrationTest,CaseCommandOutboxStoreIntegrationTest,CommandOutboxKillWindowIntegrationTest,ActivityCompletionLossIntegrationTest,ProcessProjectionFencingIntegrationTest,ProcessProjectionReconcilerIntegrationTest \
  verify
cd ..
```

`*IntegrationTest` 被 Surefire 排除，只能由 Failsafe profile 执行。默认 `max-skips=0`。任何
非零 skip 额度必须有带到期时间的变更单，并通过 allowlisted `skip_approval` metadata 归档。

## 4. SHADOW bootstrap 故障演练

确认新 epoch selector 为 SHADOW，并确认没有任何真实业务 case 进入 canary。停止 Control Worker：

```bash
docker compose stop java-control-worker
docker compose ps java-control-worker temporal-server postgresql java-api-service
```

通过批准的 synthetic MIG-001 场景驱动器创建 `CASE_ID/EPOCH_ID`。不要提交业务 command。
事务提交后必须同时看到：

- 唯一 writer slot 为该 SHADOW epoch，`lifecycle_status=ACTIVE`。
- `provisioning_status=PENDING|PROVISIONING`。
- projection 的 `writer_activation_status=PREPARING|PROVISIONING`。
- 同一 `EPOCH_ID + BOOTSTRAP_UPDATE_ID` 的 bootstrap outbox 可恢复。

此时执行第 5 节 before 查询。`sql-before.json` 落盘后再启动 worker：

```bash
docker compose up -d --no-deps java-control-worker
docker compose exec -T temporal-server tctl --ns "$TEMPORAL_NAMESPACE" taskqueue describe \
  --taskqueue case-control --taskqueuetype workflow
docker compose exec -T temporal-server tctl --ns "$TEMPORAL_NAMESPACE" taskqueue describe \
  --taskqueue room-control --taskqueuetype workflow
```

等待同一 bootstrap outbox 到达 `DELIVERED`。从 receipt 和 epoch 读取并交叉确认 Run ID：

```bash
export CASE_RUN_ID="<bootstrap-receipt-case-run-id>"
export ROOM_RUN_ID="<bootstrap-receipt-room-run-id>"
```

确认该 SHADOW epoch 为 `ACTIVE + READY`、projection 为 `READY` 后，用预先生成的
`COMMAND_ID` 提交合成 command。等待 command 变为 `SHADOW_COMPLETED` 且其 command outbox
终止。SHADOW 输出不得正式推进 case、发送用户消息、创建正式审批或执行外部工具。

## 5. Tuple-scoped SQL before/after

使用只读 `MIG001_DATABASE_URL`。把以下查询保存为 `$RAW_DIR/mig-001-snapshot.sql`。每项断言
都限定本次 case/epoch/bootstrap update/command/workflow/run tuple：

```sql
\set ON_ERROR_STOP on
select jsonb_pretty(jsonb_build_object(
  'schema_version', 'mig-001-sql-snapshot.v1',
  'snapshot_phase', :'snapshot_phase',
  'captured_at_utc', to_char(clock_timestamp() at time zone 'UTC',
      'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  'writer_mode', 'SHADOW',
  'identity', jsonb_build_object(
    'case_id', :'case_id',
    'epoch_id', :'epoch_id',
    'bootstrap_update_id', :'bootstrap_update_id',
    'command_id', :'command_id',
    'case_workflow_id', :'case_workflow_id',
    'case_run_id', nullif(:'case_run_id', ''),
    'room_workflow_id', :'room_workflow_id',
    'room_run_id', nullif(:'room_run_id', '')
  ),
  'assertions', case :'snapshot_phase'
    when 'before' then jsonb_build_object(
      'single_writer_slot', (
        select count(*) = 1 from case_room_epoch
         where case_id = :'case_id'
           and lifecycle_status in ('PREPARING', 'PROVISIONING', 'ACTIVE')
      ),
      'shadow_epoch_active_pending', exists (
        select 1 from case_room_epoch
         where id = :'epoch_id' and case_id = :'case_id'
           and writer_mode = 'SHADOW' and lifecycle_status = 'ACTIVE'
           and provisioning_status in ('PENDING', 'PROVISIONING')
           and temporal_workflow_id = :'case_workflow_id'
           and room_temporal_workflow_id = :'room_workflow_id'
      ),
      'projection_pre_activation_matches_epoch', exists (
        select 1 from case_process_projection p
        join case_room_epoch e on e.id = :'epoch_id' and e.case_id = p.case_id
         where p.case_id = :'case_id' and e.writer_mode = 'SHADOW'
           and e.lifecycle_status = 'ACTIVE'
           and e.provisioning_status in ('PENDING', 'PROVISIONING')
           and e.temporal_workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and p.temporal_workflow_id = :'case_workflow_id'
           and p.writer_activation_status in ('PREPARING', 'PROVISIONING')
           and p.room_epoch = e.room_epoch and p.fencing_token = e.fencing_token
      ),
      'bootstrap_recoverable', exists (
        select 1 from room_epoch_bootstrap_outbox
         where epoch_id = :'epoch_id' and case_id = :'case_id'
           and update_id = :'bootstrap_update_id' and writer_mode = 'SHADOW'
           and case_workflow_id = :'case_workflow_id'
           and room_workflow_id = :'room_workflow_id'
           and outbox_status in ('PENDING', 'CLAIMED', 'RETRY')
      ),
      'bootstrap_not_dead_letter', not exists (
        select 1 from room_epoch_bootstrap_outbox
         where epoch_id = :'epoch_id' and update_id = :'bootstrap_update_id'
           and case_id = :'case_id' and writer_mode = 'SHADOW'
           and case_workflow_id = :'case_workflow_id'
           and room_workflow_id = :'room_workflow_id'
           and outbox_status = 'DEAD_LETTER'
       ),
       'command_outbox_not_dead_letter', not exists (
         select 1 from case_command_outbox o
         join case_command c on c.id = o.case_command_id
         join case_room_epoch e on e.id = :'epoch_id' and e.case_id = c.case_id
          and e.room_type = c.room_type and e.room_epoch = c.room_epoch
          where c.case_id = :'case_id' and c.command_id = :'command_id'
           and o.update_id = :'command_id'
           and o.workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and o.outbox_status = 'DEAD_LETTER'
       ),
      'no_open_critical_reconciliation', not exists (
        select 1 from process_reconciliation_issue i
         join case_room_epoch e on e.id = :'epoch_id' and e.room_epoch = i.room_epoch
          where i.case_id = :'case_id' and i.severity = 'CRITICAL'
           and i.fencing_token = e.fencing_token
           and e.temporal_workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and i.issue_status <> 'RESOLVED'
      )
    )
    when 'after' then jsonb_build_object(
      'single_writer_slot', (
        select count(*) = 1 from case_room_epoch
         where case_id = :'case_id'
           and lifecycle_status in ('PREPARING', 'PROVISIONING', 'ACTIVE')
      ),
      'shadow_epoch_active_ready', exists (
        select 1 from case_room_epoch
         where id = :'epoch_id' and case_id = :'case_id'
           and writer_mode = 'SHADOW' and lifecycle_status = 'ACTIVE'
           and provisioning_status = 'READY'
           and temporal_workflow_id = :'case_workflow_id'
           and temporal_run_id = :'case_run_id'
           and room_temporal_workflow_id = :'room_workflow_id'
           and room_temporal_run_id = :'room_run_id'
      ),
      'projection_ready_matches_epoch', exists (
        select 1 from case_process_projection p
        join case_room_epoch e on e.id = :'epoch_id' and e.case_id = p.case_id
         where p.case_id = :'case_id' and p.writer_activation_status = 'READY'
           and p.writer_mode = 'SHADOW' and p.room_epoch = e.room_epoch
           and p.fencing_token = e.fencing_token
           and p.temporal_workflow_id = :'case_workflow_id'
           and p.temporal_run_id = :'case_run_id'
      ),
      'bootstrap_delivered', exists (
        select 1 from room_epoch_bootstrap_outbox
         where epoch_id = :'epoch_id' and update_id = :'bootstrap_update_id'
           and case_id = :'case_id' and writer_mode = 'SHADOW'
           and case_workflow_id = :'case_workflow_id'
           and room_workflow_id = :'room_workflow_id'
           and outbox_status = 'DELIVERED' and delivered_at is not null
      ),
      'bootstrap_receipt_matches_epoch', exists (
        select 1 from room_epoch_bootstrap_outbox b
         join case_room_epoch e on e.id = b.epoch_id
          where b.epoch_id = :'epoch_id' and b.update_id = :'bootstrap_update_id'
           and b.case_id = :'case_id' and b.writer_mode = 'SHADOW'
           and b.case_workflow_id = :'case_workflow_id'
           and b.room_workflow_id = :'room_workflow_id'
           and e.case_id = :'case_id'
           and e.temporal_workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and b.case_temporal_run_id = :'case_run_id'
           and b.room_temporal_run_id = :'room_run_id'
           and e.temporal_run_id = b.case_temporal_run_id
           and e.room_temporal_run_id = b.room_temporal_run_id
      ),
      'bootstrap_no_recoverable', not exists (
        select 1 from room_epoch_bootstrap_outbox
         where epoch_id = :'epoch_id' and update_id = :'bootstrap_update_id'
           and case_id = :'case_id' and writer_mode = 'SHADOW'
           and case_workflow_id = :'case_workflow_id'
           and room_workflow_id = :'room_workflow_id'
           and outbox_status in ('PENDING', 'CLAIMED', 'RETRY')
      ),
      'bootstrap_not_dead_letter', not exists (
        select 1 from room_epoch_bootstrap_outbox
         where epoch_id = :'epoch_id' and update_id = :'bootstrap_update_id'
           and case_id = :'case_id' and writer_mode = 'SHADOW'
           and case_workflow_id = :'case_workflow_id'
           and room_workflow_id = :'room_workflow_id'
           and outbox_status = 'DEAD_LETTER'
       ),
       'command_shadow_completed', exists (
         select 1 from case_command c
         join case_room_epoch e on e.id = :'epoch_id' and e.case_id = c.case_id
          and e.room_type = c.room_type and e.room_epoch = c.room_epoch
          where c.case_id = :'case_id' and c.command_id = :'command_id'
           and e.temporal_workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and command_status = 'SHADOW_COMPLETED'
       ),
       'command_outbox_terminal', exists (
         select 1 from case_command_outbox o
         join case_command c on c.id = o.case_command_id
         join case_room_epoch e on e.id = :'epoch_id' and e.case_id = c.case_id
          and e.room_type = c.room_type and e.room_epoch = c.room_epoch
          where c.case_id = :'case_id' and c.command_id = :'command_id'
           and o.update_id = :'command_id'
           and o.workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and (o.outbox_status = 'RECONCILED' or o.temporal_run_id = :'case_run_id')
           and o.outbox_status in ('DELIVERED', 'RECONCILED')
       ),
       'command_outbox_no_recoverable', not exists (
         select 1 from case_command_outbox o
         join case_command c on c.id = o.case_command_id
         join case_room_epoch e on e.id = :'epoch_id' and e.case_id = c.case_id
          and e.room_type = c.room_type and e.room_epoch = c.room_epoch
          where c.case_id = :'case_id' and c.command_id = :'command_id'
           and o.update_id = :'command_id'
           and o.workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and o.outbox_status in ('PENDING', 'CLAIMED', 'RETRY')
       ),
       'command_outbox_not_dead_letter', not exists (
         select 1 from case_command_outbox o
         join case_command c on c.id = o.case_command_id
         join case_room_epoch e on e.id = :'epoch_id' and e.case_id = c.case_id
          and e.room_type = c.room_type and e.room_epoch = c.room_epoch
          where c.case_id = :'case_id' and c.command_id = :'command_id'
           and o.update_id = :'command_id'
           and o.workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and o.outbox_status = 'DEAD_LETTER'
      ),
      'no_open_critical_reconciliation', not exists (
        select 1 from process_reconciliation_issue i
         join case_room_epoch e on e.id = :'epoch_id' and e.room_epoch = i.room_epoch
          where i.case_id = :'case_id' and i.severity = 'CRITICAL'
           and i.fencing_token = e.fencing_token
           and e.temporal_workflow_id = :'case_workflow_id'
           and e.room_temporal_workflow_id = :'room_workflow_id'
           and i.issue_status <> 'RESOLVED'
      )
    )
    else '{}'::jsonb
  end
));
```

停止 worker 时采 before，Run ID 变量保持空字符串：

```bash
psql "$MIG001_DATABASE_URL" -X -qAt \
  -v snapshot_phase=before -v case_id="$CASE_ID" -v epoch_id="$EPOCH_ID" \
  -v bootstrap_update_id="$BOOTSTRAP_UPDATE_ID" -v command_id="$COMMAND_ID" \
  -v case_workflow_id="$CASE_WORKFLOW_ID" -v case_run_id="" \
  -v room_workflow_id="$ROOM_WORKFLOW_ID" -v room_run_id="" \
  -f "$RAW_DIR/mig-001-snapshot.sql" > "$RAW_DIR/sql-before.json"
```

bootstrap 和 SHADOW command 完成后采 after：

```bash
psql "$MIG001_DATABASE_URL" -X -qAt \
  -v snapshot_phase=after -v case_id="$CASE_ID" -v epoch_id="$EPOCH_ID" \
  -v bootstrap_update_id="$BOOTSTRAP_UPDATE_ID" -v command_id="$COMMAND_ID" \
  -v case_workflow_id="$CASE_WORKFLOW_ID" -v case_run_id="$CASE_RUN_ID" \
  -v room_workflow_id="$ROOM_WORKFLOW_ID" -v room_run_id="$ROOM_RUN_ID" \
  -f "$RAW_DIR/mig-001-snapshot.sql" > "$RAW_DIR/sql-after.json"
```

禁止编辑 JSON assertion 或 identity。after 时间必须不早于 before。

## 6. 绑定的 Temporal History

导出本次 receipt 对应的精确 Case/Room Run：

```bash
docker compose exec -T temporal-server tctl --ns "$TEMPORAL_NAMESPACE" workflow show \
  -w "$CASE_WORKFLOW_ID" -r "$CASE_RUN_ID" \
  --output_filename /tmp/mig001-case-history.json
docker compose exec -T temporal-server tctl --ns "$TEMPORAL_NAMESPACE" workflow show \
  -w "$ROOM_WORKFLOW_ID" -r "$ROOM_RUN_ID" \
  --output_filename /tmp/mig001-room-history.json
docker compose cp temporal-server:/tmp/mig001-case-history.json "$RAW_DIR/case-history.json"
docker compose cp temporal-server:/tmp/mig001-room-history.json "$RAW_DIR/room-history.json"
```

归档器要求：

- Case start Run ID、Case type 和 `case-control` 队列匹配。
- `BOOTSTRAP_UPDATE_ID/provisionRoomEpoch` 有唯一有序 accepted/completed。
- Room child initiated/started 绑定 `ROOM_WORKFLOW_ID/ROOM_RUN_ID`。
- History 包含 `case_process_authority_checkpoint_v1` memo。
- `COMMAND_ID/acceptCommand` 有唯一有序 accepted/completed，并向绑定 Room 发出 command signal。
- Room start 绑定 Case parent execution，并收到一次 `roomCommandAccepted` signal。

History 即使只含 synthetic ID，仍按敏感运维证据处理。上传前使用 evidence bucket 的 KMS key
加密，private ACL 禁止公开读，开启对象访问审计和不可变保留；到期由 Release 与 Security
联合删除。不得为了归档关闭 Temporal payload encryption。

## 7. 生成 content-addressed bundle

再次确认 HEAD 与 clean worktree。分别展开 Surefire/Failsafe XML，避免角色混淆：

```bash
test "$(git rev-parse HEAD)" = "$EVALUATED_HEAD"
test -z "$(git status --porcelain=v1 --untracked-files=all)"

SUREFIRE_ARGS=()
for report in java-api-service/target/surefire-reports/TEST-*.xml; do
  SUREFIRE_ARGS+=(--surefire-xml "$report")
done
FAILSAFE_ARGS=()
for report in java-api-service/target/failsafe-reports/TEST-*.xml; do
  FAILSAFE_ARGS+=(--failsafe-xml "$report")
done

python3 scripts/archive_temporal_phase_evidence.py \
  --repository . --release-id "$RELEASE_ID" --evaluated-head "$EVALUATED_HEAD" \
  --environment-name compose-phase-1 \
  --case-id "$CASE_ID" --epoch-id "$EPOCH_ID" \
  --bootstrap-update-id "$BOOTSTRAP_UPDATE_ID" --command-id "$COMMAND_ID" \
  --case-workflow-id "$CASE_WORKFLOW_ID" --case-run-id "$CASE_RUN_ID" \
  --room-workflow-id "$ROOM_WORKFLOW_ID" --room-run-id "$ROOM_RUN_ID" \
  "${SUREFIRE_ARGS[@]}" "${FAILSAFE_ARGS[@]}" \
  --temporal-history "$RAW_DIR/case-history.json" \
  --temporal-history "$RAW_DIR/room-history.json" \
  --sql-before "$RAW_DIR/sql-before.json" --sql-after "$RAW_DIR/sql-after.json" \
  --max-skips 0 \
  --metadata environment_id="$ENVIRONMENT_ID" \
  --metadata temporal_namespace="$TEMPORAL_NAMESPACE" \
  --metadata control_build_id="$CONTROL_BUILD_ID" \
  --metadata java_image_digest="$JAVA_IMAGE_DIGEST" \
  --metadata postgresql_version="$POSTGRESQL_VERSION" \
  --metadata temporal_server_version="$TEMPORAL_SERVER_VERSION" \
  --metadata kms_key_id="$EVIDENCE_KMS_KEY_ID" \
  --metadata artifact_retention_days="$ARTIFACT_RETENTION_DAYS" \
  --bundle-output-dir "$BUNDLE_DIR"
```

命令输出 `bundle_path` 和 `bundle_sha256`。ZIP 文件名包含同一 SHA-256；ZIP 内含
`evidence.json`、全部 JUnit XML、Case/Room History 和 SQL before/after 的不可变副本。上传前
用 `sha256sum` 或平台等价命令复算 ZIP hash。审批开始后禁止 `--overwrite`。

## 8. 审批、promotion 与 rollback

1. Architecture 审核 suite coverage、tuple identity、History 关键事件和 SQL assertion。
2. PhaseOwner 审核 synthetic 场景无 PII、SHADOW 没有正式副作用及所有 skip approval。
3. Security/Release 审核 KMS、private ACL、访问审计、保留期限、镜像 digest 和 bundle hash。
4. 三方对 `bundle_sha256 + evaluated_head` 生成签名 promotion attestation；不得修改原 ZIP 中
   `promotion_status=PENDING_APPROVAL` 的技术证据。
5. Phase 1 promotion 最多扩大新 epoch 的 SHADOW cohort，不允许切换 TEMPORAL 正式 writer。

Rollback 时先停止新 SHADOW cohort，将 selector 调回 LEGACY，再停止 bootstrap/command 新投递。
已存在 SHADOW Workflow 可以排空或按 runbook 终止，但不得改写 ledger、删除 History或让其输出
成为正式业务结果。保留 rollback 前后 SQL、History 和新的 content-addressed bundle供审计。

SHADOW bootstrap 若遇到已分类的永久冲突，控制面会在同一数据库事务中把失败 epoch 终止，
以更高 `room_epoch + fencing_token` 安装同房间 LEGACY epoch，并把 projection 切回 `READY`；
outbox 随后才进入 `DEAD_LETTER`。操作员必须核对旧 epoch 的 failure code、新 LEGACY fence 和
projection tuple 三者一致。TEMPORAL 正式 writer 禁止自动回退，必须保持失败门禁并走独立恢复审批。

任何必需测试缺失、tuple 不一致、History 事件缺失、SQL assertion 为假、bundle hash 不一致、
安全控制缺失或签名审批未完成时，MIG-001 均保持未通过。
