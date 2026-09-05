# Production Runtime 隔离 UAT

Status: current isolated release-verification tooling. The UAT-aligned production identity is
`all-rooms.production-runtime.v2` / `production-runtime-graph.2026-08-18.3` /
`production-runtime-checkpoint.v2`; Intake uses `PARALLEL_FRAMES_V1` and `agent-stream.v4`.
The model profile resolves to `qwen3.8-flash`, with thinking disabled and strict JSON
Schema enabled.

This Compose project is independent from `docker-compose.yml`: it uses a run-scoped project name,
named networks and volumes, an isolated gateway port, separate Domain/Graph/Temporal PostgreSQL
instances, and no production endpoint or credential.

## 固定本机启动入口

在仓库根目录执行（Python 环境需安装本工具已有的 `cryptography` 依赖，Docker、
OpenSSL、JDK keytool 与本机镜像仓库 `127.0.0.1:25000` 需已可用）：

```powershell
python tools/uat/production-runtime/start.py
```

只维护版本化配置 `infra/environments/production-runtime-uat/local-start.json`、
`base-images.json` 和已有私有 `.env` 中的模型接入参数。核心镜像以现有版本的 digest
锁定，入口不升级 Docker/Temporal/数据库，不启动或替换共享核心项目。模型密钥不提交；
需要其他密钥文件时显式传 `--model-env-file <path>`。

入口要求干净的当前提交，依次执行精确镜像构建、私有配置生成、网络分配、正式预检、
Compose 启动与完整基础设施就绪验证。默认地址为 `http://127.0.0.1:25180/disputes`，
不覆盖旧的 5173/8080 本机环境。`10.247.240.0/24` 分配为 14 个独立 /28 网络；创建前
检查全部 Docker 网络与本机路由，冲突即停止，不修改 daemon 地址池、不清理历史网络。
Windows 和 Linux 路由读取受支持；其他平台明确停止，不能跳过冲突检查。

证书、随机密码、签名绑定、镜像证明及 run env 自动保存在
`~/.after-sale-flow/production-runtime-local/`（可用 `--runtime-root` 指定另一个工作区外目录）。
这些是自动生成的私有运行产物，不是需要手工维护的启动配置。

审核解释流由 API 的 `app.agent-stream.tls.mode=MUTUAL_TLS` 使用同次签发的 Java
客户端 PKCS12 与信任库。Compose 只读挂载 `client.p12` 和 `trust.p12`，不将 CA/服务端
私钥交给 API，也不修改 JVM 全局信任配置。TLS1.3、HTTPS 主机名校验和禁止重定向保持；
缺少材料即启动失败，不降级明文。Graph command/reconciliation 仍由原有独立传输负责。

重复同一命令会通过 `current-run.json` 与正式 host lock 复用同一提交/配置的运行；
源代码、模型配置或 host lock 漂移时停止。启动失败保留该运行用于取证，不自动删库重试。
换版本前先按下述 `teardown.py` 归档并清理该精确 UAT，随后归档该目录的
`current-run.json`，再执行同一入口生成新运行。

`startup-receipt.json` 仅表示基础设施就绪，明确不宣称业务 E2E 通过；仍需从表单创建新案件，
验证完整业务流程后才能推送/发布。以下分步命令保留用于诊断与显式运维，不是日常手工启动要求。

Provisioning requires a self-hashed v2 image lock. Every image records its registry manifest,
config, ordered layers, source revision, and build ID; application image source revisions must
equal the exact candidate SHA. Tags, `latest`, incomplete provenance, and v1 locks are rejected.

```json
{
  "schema_version": "production-runtime-image-lock.v2",
  "candidate_sha": "<exact 40-character candidate Git SHA>",
  "source_revision": "<same candidate Git SHA>",
  "build_provenance": {
    "builder_id": "<builder identity>",
    "invocation_id": "<unique build invocation>",
    "source_tree_sha256": "sha256:<digest>",
    "built_at": "<ISO-8601 timestamp>",
    "attestation_type": "<provenance format>",
    "attestation_digest": "sha256:<digest>"
  },
  "images": {
    "java": {
      "reference": "registry.example/after-sale-java@sha256:<manifest digest>",
      "manifest_digest": "sha256:<manifest digest>",
      "config_digest": "sha256:<config digest>",
      "layer_digests": ["sha256:<layer digest>"],
      "source_revision": "<candidate Git SHA>",
      "build_id": "<build identity>"
    }
  },
  "self_hash": "<SHA-256 of canonical JSON without self_hash>"
}
```

The `images` object must contain the exact inventory accepted by `common.py`; the Java record
above illustrates the required record shape for every entry.

Create the lock from a clean checkout of the exact candidate with `build_image_lock.py`. The
`--base-images` file is a strict JSON object containing the eight non-application keys accepted by
`common.py` (`postgres`, `redis`, `minio`, `minio_mc`, `elasticsearch`, `temporal`, `nginx`, and
`curl`); every value must already be an immutable `repository@sha256:...` reference. The command
builds and pushes the Java `production-runtime` artifact plus Python, OCR, and frontend images, pulls every image
by digest, measures config and ordered layer identities, and writes a self-hashed lock together
with its bound build attestation to a new directory outside the worktree.

```text
python tools/uat/production-runtime/build_image_lock.py \
  --candidate <exact-40-char-SHA> \
  --base-images <external-base-images.json> \
  --repository-prefix <registry/repository-prefix> \
  --output-directory <new-external-output-directory> \
  --invocation-id <unique-build-invocation>
```

Provisioning discovers an existing OpenSSL configuration from the selected executable (including
Conda's `Library/bin/openssl.exe` to `Library/ssl/openssl.cnf` layout) and explicitly supplies it as
`OPENSSL_CONF` to every OpenSSL subprocess. Missing, empty, oversized, or unreadable configs block
provisioning; no activated Conda shell or manual environment repair is required.

Use `provision.py`, then pass its printed external env-file path to `preflight.py` and `up.py`.
Java writes its fresh ES256 final-evidence JWS to the run-local
`evidence/inbox/<case-id>.java-evidence.jws`; validate it with `assert_evidence.py --env-file ...
--case-id ...`. The assertion path cannot accept an arbitrary file or URL. It binds the Java JWS
to the signed append-only harness ledger, live container/image identities, activation and
environment generation, Compose project, Temporal namespace, both database identities, case, and
run nonce.

Finish with `teardown.py`. It requires forensic export, validates the active host and port locks,
and removes only the exact labeled container IDs, network names, and volume names reserved for the
run. It never uses a broad Compose teardown.

The unified checkpoint has an executable wrapper in `batch4.py`. Journey/recovery and drain/revoke
drivers are supplied as strict JSON argv arrays; the wrapper appends `--env-file`, `--case-id`, and
`--stage`, runs the fixed readiness/assertion/forensic sequence, and refuses to emit evidence unless
`evidence/batch-4-scenario.json` proves every frozen Batch 4 assertion and preserves the external
promotion ceiling. A successful run creates `p9.0-evidence.json` with
`PASS_AWAITING_ACCEPTANCE`. It does not self-accept.

```text
python tools/uat/production-runtime/batch4.py --env-file <external-env> \
  --case-id CASE_P9_SYNTHETIC_0001 \
  --journey-command <journey-argv.json> --drain-command <drain-argv.json>
```

Only a separate P-256 key, distinct from the run harness key, can create the engineering acceptance
object. The current source baseline subsequently completed a fresh browser six-station UAT as
`CASE_P9_6A98633E_11`. This proves the application release candidate in an isolated environment;
production routing, feature flags and core platform versions still require an explicit release
decision. The UAT tooling never authorizes a Temporal/PostgreSQL upgrade.

```text
python tools/uat/production-runtime/p9_gate.py accept --env-file <external-env> \
  --acceptance-private-key <external-private.pem> \
  --acceptance-public-key <external-public.pem> \
  --acceptance-key-id <independent-reviewer-key-id>
```
