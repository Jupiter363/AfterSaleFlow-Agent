# AI Native 履约争端审理系统：统一配置说明（最终版）

> 文档性质：正式版配置规范、Docker Compose 部署约束、模型网关配置、安全密钥管理与本地启动说明  
> 适用范围：`.env.example`、`.gitignore`、`docker-compose.yml`、LiteLLM、Langfuse、Nginx、Java/Python/OCR/Frontend 配置、初始化脚本和 Smoke Test  
> 当前任务边界：本文档只定义最终配置方案，不修改代码、数据库、接口、目录、依赖、Docker 或 CI/CD  
> API 命名原则：正式版接口不使用 `/v2`、`/v3` 等路径标识  

---

## 1. 文档定位

本文档用于约束 Codex 在配置和部署本项目时如何处理：

```text
模型 API Key 配置；
统一模型选择；
LiteLLM Proxy 配置；
Docker Compose 中间件部署；
数据库与中间件用户名密码生成；
缺失依赖中间件自动补齐；
.env / .env.example / docker-compose.yml / 服务配置文件规范；
AI Native 前端配置；
Agent Harness 配置；
本地正式版一键启动配置；
配置验收标准。
```

本文档是配置落地的强约束文档。Codex 必须严格按照本文档执行，不得擅自改用其他模型、其他部署方式或手工安装中间件。

---

## 2. 总体配置原则

### 2.1 统一模型原则

本项目所有 LLM 调用统一使用：

```text
qwen3.7-plus
```

所有 Agent、证据分析、争点归纳、规则适用、裁决草案、AI 评议团、审核辅助官和 Evaluation Agent 的 LLM 调用，都必须通过 LiteLLM Proxy 访问该模型。

禁止：

```text
某个 Agent 直接绕过 LiteLLM 调用模型厂商 API；
不同 Agent 随意使用不同模型；
Java API Service 直接调用模型厂商；
前端直接调用模型；
业务代码硬编码 API Key；
Prompt 文件写入 API Key；
仓库提交真实 API Key；
日志打印 API Key；
前端暴露任何服务密钥。
```

### 2.2 API Key 配置原则

真实 Qwen 3.7 Plus API Key 只能写入本地 `.env` 文件，不得进入仓库。

要求：

```text
.env 必须加入 .gitignore；
.env.example 只能写占位符；
文档、代码、测试、README、Dockerfile、docker-compose.yml、LiteLLM config 中不得出现明文真实 Key；
代码只能通过环境变量读取；
日志必须脱敏显示；
本地运行时由开发者手动把真实 Key 写入 .env。
```

`.env.example` 写法：

```env
DASHSCOPE_API_KEY=__PASTE_YOUR_DASHSCOPE_API_KEY_HERE__
DEFAULT_LLM_MODEL=qwen3.7-plus
DEFAULT_LLM_API_BASE=https://ws-veazvl2fycrurdmv.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
```

本地 `.env` 写法：

```env
DASHSCOPE_API_KEY=<填入真实 Qwen 3.7 Plus API Key>
DEFAULT_LLM_MODEL=qwen3.7-plus
DEFAULT_LLM_API_BASE=https://ws-veazvl2fycrurdmv.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
```

### 2.3 正式版 API 路径原则

配置中的前端代理、Nginx、后端路由必须使用正式版路径：

```text
/api/disputes
/api/reviews
/internal/agents
/internal/evidence
```

禁止：

```text
/api/v2
/api/v3
/internal/v2
/internal/v3
```

版本治理通过以下字段完成，不体现在 URL 中：

```text
Schema-Version；
Agent-Profile-Version；
Prompt-Version；
Skill-Version；
Ruleset-Version；
Model-Version。
```

---

## 3. `.env.example` 标准模板

Codex 必须在仓库根目录生成 `.env.example`，不得包含真实密钥。

```env
# =========================================================
# Project
# =========================================================
COMPOSE_PROJECT_NAME=ai-fulfillment-dispute-system
APP_ENV=local
TZ=Asia/Shanghai

# =========================================================
# LLM / Qwen 3.7 Plus / LiteLLM
# =========================================================
DASHSCOPE_API_KEY=__PASTE_YOUR_DASHSCOPE_API_KEY_HERE__
DEFAULT_LLM_PROVIDER=litellm
DEFAULT_LLM_MODEL=qwen3.7-plus
DEFAULT_LLM_API_BASE=https://ws-veazvl2fycrurdmv.cn-beijing.maas.aliyuncs.com/compatible-mode/v1

LITELLM_MASTER_KEY=__GENERATED_BY_CODEX__
LITELLM_SALT_KEY=__GENERATED_BY_CODEX__
LITELLM_BASE_URL=http://litellm-proxy:4000
LITELLM_DEFAULT_MODEL=qwen3.7-plus

# =========================================================
# Agent Harness Versioning
# =========================================================
AGENT_PROFILE_VERSION=final
PROMPT_BUNDLE_VERSION=final
SKILL_BUNDLE_VERSION=final
RULESET_VERSION=final
AGENT_OUTPUT_SCHEMA_VERSION=final

AGENT_MAX_ITERATIONS=8
AGENT_MAX_TOOL_CALLS=12
AGENT_MAX_MODEL_CALLS=8
AGENT_MAX_RUNTIME_SECONDS=120
AGENT_MAX_INPUT_TOKENS=16000
AGENT_MAX_OUTPUT_TOKENS=4000

AGENT_OUTPUT_VALIDATION_ENABLED=true
AGENT_GUARDRAIL_ENABLED=true
AGENT_TRACE_ENABLED=true
DELIBERATION_ENABLED=true
REVIEW_COPILOT_ENABLED=true
EVALUATION_AGENT_ENABLED=true

# =========================================================
# PostgreSQL
# =========================================================
POSTGRES_HOST=postgresql
POSTGRES_PORT=5432
POSTGRES_DB=dispute_system
POSTGRES_USER=__GENERATED_BY_CODEX__
POSTGRES_PASSWORD=__GENERATED_BY_CODEX__

JAVA_DB_NAME=dispute_system
TEMPORAL_DB_NAME=temporal
LANGFUSE_DB_NAME=langfuse
LITELLM_DB_NAME=litellm

# =========================================================
# Redis
# =========================================================
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=__GENERATED_BY_CODEX__

# =========================================================
# MinIO
# =========================================================
MINIO_ENDPOINT=http://minio:9000
MINIO_CONSOLE_ENDPOINT=http://localhost:19001
MINIO_ROOT_USER=__GENERATED_BY_CODEX__
MINIO_ROOT_PASSWORD=__GENERATED_BY_CODEX__
MINIO_BUCKET_EVIDENCE_ORIGINAL=evidence-original
MINIO_BUCKET_EVIDENCE_DESENSITIZED=evidence-desensitized
MINIO_BUCKET_OCR_TEMP=ocr-temp
MINIO_BUCKET_POLICY_FILES=policy-files
MINIO_BUCKET_REVIEW_EXPORTS=review-exports

# =========================================================
# Elasticsearch
# =========================================================
ELASTICSEARCH_URL=http://elasticsearch:9200
ELASTICSEARCH_USERNAME=elastic
ELASTICSEARCH_PASSWORD=__GENERATED_BY_CODEX__
ELASTICSEARCH_SECURITY_ENABLED=false
ELASTICSEARCH_INDEX_CASE=case_index
ELASTICSEARCH_INDEX_EVIDENCE=evidence_index
ELASTICSEARCH_INDEX_POLICY=policy_index
ELASTICSEARCH_INDEX_SIMILAR_CASE=similar_case_index

# =========================================================
# Temporal
# =========================================================
TEMPORAL_ADDRESS=temporal-server:7233
TEMPORAL_NAMESPACE=default
TEMPORAL_TASK_QUEUE=fulfillment-dispute-task-queue

# =========================================================
# Langfuse
# =========================================================
LANGFUSE_HOST=http://langfuse:3000
LANGFUSE_PUBLIC_KEY=__GENERATED_BY_CODEX__
LANGFUSE_SECRET_KEY=__GENERATED_BY_CODEX__
LANGFUSE_SALT=__GENERATED_BY_CODEX__
LANGFUSE_NEXTAUTH_SECRET=__GENERATED_BY_CODEX__
LANGFUSE_NEXTAUTH_URL=http://localhost:13000

# =========================================================
# Java API Service
# =========================================================
JAVA_API_PORT=18080
JAVA_API_INTERNAL_PORT=8080
JAVA_API_SERVICE_URL=http://java-api-service:8080
JAVA_SERVICE_SECRET=__GENERATED_BY_CODEX__

# =========================================================
# Python Agent Service
# =========================================================
PYTHON_AGENT_PORT=18000
PYTHON_AGENT_INTERNAL_PORT=8000
PYTHON_AGENT_SERVICE_URL=http://python-agent-service:8000
PYTHON_AGENT_SERVICE_SECRET=__GENERATED_BY_CODEX__

# =========================================================
# OCR Parser Service
# =========================================================
OCR_SERVICE_PORT=18010
OCR_SERVICE_INTERNAL_PORT=8010
OCR_SERVICE_URL=http://ocr-parser-service:8010
OCR_SERVICE_SECRET=__GENERATED_BY_CODEX__

# =========================================================
# Frontend / Nginx
# =========================================================
FRONTEND_PORT=5173
NGINX_PORT=8080
VITE_API_BASE_URL=/api
VITE_AGENT_STREAM_BASE_URL=/agent-api
VITE_ENABLE_AI_NATIVE_UI=true
VITE_ENABLE_AGENT_STATUS_STREAM=true
VITE_ENABLE_EVIDENCE_STUDIO=true
VITE_ENABLE_HEARING_COURT=true
VITE_ENABLE_DELIBERATION_PANEL_VIEW=true

# =========================================================
# Feature Flags
# =========================================================
FEATURE_AGENT_INTAKE_ENABLED=true
FEATURE_AGENT_EVIDENCE_CLERK_ENABLED=true
FEATURE_AGENT_HEARING_ENABLED=true
FEATURE_AGENT_DELIBERATION_ENABLED=true
FEATURE_REVIEW_COPILOT_ENABLED=true
FEATURE_AGENT_EVALUATION_ENABLED=true
FEATURE_OCR_ENABLED=true
FEATURE_HUMAN_REVIEW_REQUIRED=true
FEATURE_TOOL_EXECUTOR_SIMULATION=true
FEATURE_AUTO_APPROVE_ENABLED=false

# =========================================================
# Security / Logging
# =========================================================
LOG_LEVEL=INFO
ENABLE_REQUEST_LOG=true
ENABLE_AUDIT_LOG=true
ENABLE_SENSITIVE_LOG_MASKING=true
```

---

## 4. `.gitignore` 必须包含

```gitignore
.env
.env.*
!.env.example

logs/
data/
tmp/

**/__pycache__/
**/.pytest_cache/
**/target/
**/node_modules/
**/dist/
**/.venv/
```

要求：

```text
.env.example 允许提交；
.env 不允许提交；
.env.local 不允许提交；
任何包含真实 API Key 的文件都不允许提交。
```

---

## 5. 自动生成用户名密码规范

### 5.1 必须生成脚本

Codex 必须创建：

```text
scripts/generate-secrets.sh
```

用途：

```text
生成本地 .env 中用户名、密码、salt、service secret、Langfuse secret、LiteLLM master key 等。
```

### 5.2 生成规则

```text
用户名长度：8-16 位；
密码长度：24-32 位；
secret 长度：32-64 位；
使用安全随机源；
不使用固定默认密码；
不使用 admin/admin、root/root、test/test；
生成后写入 .env；
如果 .env 已存在，不覆盖用户已填写的 DASHSCOPE_API_KEY；
输出时不得打印完整密钥。
```

### 5.3 脚本逻辑要求

脚本必须做到：

```text
.env 不存在时从 .env.example 复制；
只替换 __GENERATED_BY_CODEX__ 占位符；
不替换 DASHSCOPE_API_KEY 占位符，提示人工填写；
生成后删除临时 bak 文件；
终端只输出生成成功，不输出完整密钥。
```

---

## 6. Docker Compose 部署要求

### 6.1 所有中间件必须通过 Docker 部署

必须通过 Docker Compose 部署：

```text
postgresql
redis
elasticsearch
minio
temporal-server
langfuse
litellm-proxy
nginx
```

应用服务也必须容器化：

```text
frontend
java-api-service
python-agent-service
ocr-parser-service
```

禁止要求开发者手工安装：

```text
PostgreSQL；
Redis；
Elasticsearch；
MinIO；
Temporal；
Langfuse；
LiteLLM；
Nginx；
PaddleOCR；
MarkItDown；
OCR 系统依赖。
```

### 6.2 docker-compose.yml 必须包含

```yaml
services:
  postgresql:
  redis:
  elasticsearch:
  minio:
  temporal-server:
  langfuse:
  litellm-proxy:
  java-api-service:
  python-agent-service:
  ocr-parser-service:
  frontend:
  nginx:
```

### 6.3 自动补齐依赖原则

如果某个服务官方镜像需要额外依赖容器，Codex 必须自动补齐。

允许补齐：

```text
运行所需的附属容器；
官方 Docker Compose 推荐依赖；
初始化脚本；
healthcheck 脚本；
volume；
network。
```

不允许补齐：

```text
Kafka；
MCP；
Milvus；
Qdrant；
OPA；
Drools；
Kubernetes；
自部署大模型；
与本系统无关的新中间件。
```

---

## 7. 关键中间件配置要求

### 7.1 PostgreSQL

要求：

```text
使用 Docker volume；
初始化多个数据库：dispute_system、temporal、langfuse、litellm；
用户名和密码来自 .env；
healthcheck 必须存在；
作为业务事实源。
```

### 7.2 Redis

要求：

```text
密码来自 .env；
开启持久化；
healthcheck 必须存在；
仅用于缓存、限流和锁；
不保存核心业务事实。
```

### 7.3 Elasticsearch

要求：

```text
单节点模式；
本地开发可关闭安全认证；
创建 policy_index、evidence_index、case_index、similar_case_index；
使用 Docker volume；
healthcheck 必须存在；
作为可重建投影，不是事实源。
```

### 7.4 MinIO

要求：

```text
root user / password 来自 .env；
自动创建 bucket；
使用 Docker volume；
暴露 API 和 Console；
healthcheck 必须存在；
下载使用短期签名 URL。
```

### 7.5 Temporal

要求：

```text
使用 PostgreSQL 持久化；
namespace 使用 default；
task queue 使用 fulfillment-dispute-task-queue；
Java Worker 连接 Temporal；
healthcheck 必须存在。
```

### 7.6 LiteLLM Proxy

要求：

```text
读取 DASHSCOPE_API_KEY；
默认模型 qwen3.7-plus；
对 Python Agent Service 提供 OpenAI-compatible 接口；
配置 master key；
日志不得打印 API Key；
可连接 PostgreSQL 保存配置或使用配置文件启动。
```

### 7.7 Langfuse

要求：

```text
用于记录 Agent Trace；
读取 LANGFUSE_PUBLIC_KEY、LANGFUSE_SECRET_KEY；
连接 PostgreSQL 或官方版本要求依赖；
Python Agent Service 必须能写入 Trace；
敏感内容脱敏或仅记录引用；
healthcheck 必须存在。
```

---

## 8. LiteLLM 配置文件要求

Codex 必须创建：

```text
deploy/litellm/config.yaml
```

建议内容：

```yaml
model_list:
  - model_name: qwen3.7-plus
    litellm_params:
      model: openai/qwen3.7-plus
      api_key: os.environ/DASHSCOPE_API_KEY
      api_base: os.environ/DEFAULT_LLM_API_BASE
      extra_body:
        enable_thinking: true

litellm_settings:
  drop_params: true
  set_verbose: false
  request_timeout: 120

general_settings:
  master_key: os.environ/LITELLM_MASTER_KEY
```

要求：

```text
model_name 必须是 qwen3.7-plus；
api_key 必须从环境变量读取；
api_base 必须从环境变量读取；
不允许在 YAML 写真实 Key；
不允许配置其他默认模型。
```

如果 LiteLLM 对 Qwen 3.7 Plus 有更推荐 provider 写法，Codex 可以适配，但对系统暴露模型名称仍必须是 `qwen3.7-plus`。

---

## 9. Java API Service 配置要求

Java 配置必须支持：

```yaml
app:
  env: ${APP_ENV:local}
  security:
    service-secret: ${JAVA_SERVICE_SECRET}
  agent:
    base-url: ${PYTHON_AGENT_SERVICE_URL:http://python-agent-service:8000}
    service-secret: ${PYTHON_AGENT_SERVICE_SECRET}
    timeout-ms: 120000
  ocr:
    base-url: ${OCR_SERVICE_URL:http://ocr-parser-service:8010}
    service-secret: ${OCR_SERVICE_SECRET}
    timeout-ms: 120000
  feature:
    agent-intake-enabled: ${FEATURE_AGENT_INTAKE_ENABLED:true}
    agent-evidence-clerk-enabled: ${FEATURE_AGENT_EVIDENCE_CLERK_ENABLED:true}
    agent-hearing-enabled: ${FEATURE_AGENT_HEARING_ENABLED:true}
    agent-deliberation-enabled: ${FEATURE_AGENT_DELIBERATION_ENABLED:true}
    review-copilot-enabled: ${FEATURE_REVIEW_COPILOT_ENABLED:true}
    agent-evaluation-enabled: ${FEATURE_AGENT_EVALUATION_ENABLED:true}
    ocr-enabled: ${FEATURE_OCR_ENABLED:true}
    human-review-required: ${FEATURE_HUMAN_REVIEW_REQUIRED:true}
    tool-executor-simulation: ${FEATURE_TOOL_EXECUTOR_SIMULATION:true}
    auto-approve-enabled: ${FEATURE_AUTO_APPROVE_ENABLED:false}

spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:postgresql}:${POSTGRES_PORT:5432}/${JAVA_DB_NAME:dispute_system}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST:redis}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD}

minio:
  endpoint: ${MINIO_ENDPOINT:http://minio:9000}
  access-key: ${MINIO_ROOT_USER}
  secret-key: ${MINIO_ROOT_PASSWORD}

elasticsearch:
  url: ${ELASTICSEARCH_URL:http://elasticsearch:9200}

temporal:
  address: ${TEMPORAL_ADDRESS:temporal-server:7233}
  namespace: ${TEMPORAL_NAMESPACE:default}
  task-queue: ${TEMPORAL_TASK_QUEUE:fulfillment-dispute-task-queue}
```

Java 服务边界：

```text
可以调用 Python Agent Service；
可以调用 OCR Parser Service；
可以调用 PostgreSQL、Redis、MinIO、Elasticsearch、Temporal；
不可以直接调用 Qwen 3.7 Plus；
不可以直接读取 DASHSCOPE_API_KEY；
不可以把模型 API Key 返回前端。
```

---

## 10. Python Agent Service 配置要求

环境变量：

```env
APP_ENV=local
LITELLM_BASE_URL=http://litellm-proxy:4000
LITELLM_MODEL=qwen3.7-plus
LITELLM_MASTER_KEY=${LITELLM_MASTER_KEY}

LANGFUSE_HOST=http://langfuse:3000
LANGFUSE_PUBLIC_KEY=${LANGFUSE_PUBLIC_KEY}
LANGFUSE_SECRET_KEY=${LANGFUSE_SECRET_KEY}

JAVA_API_SERVICE_URL=http://java-api-service:8080
PYTHON_AGENT_SERVICE_SECRET=${PYTHON_AGENT_SERVICE_SECRET}

AGENT_PROFILE_VERSION=${AGENT_PROFILE_VERSION}
PROMPT_BUNDLE_VERSION=${PROMPT_BUNDLE_VERSION}
SKILL_BUNDLE_VERSION=${SKILL_BUNDLE_VERSION}
RULESET_VERSION=${RULESET_VERSION}

AGENT_OUTPUT_VALIDATION_ENABLED=true
AGENT_GUARDRAIL_ENABLED=true
DELIBERATION_ENABLED=true
REVIEW_COPILOT_ENABLED=true

LOG_LEVEL=INFO
ENABLE_SENSITIVE_LOG_MASKING=true
```

读取规范：

```text
集中在 python-agent-service/app/core/config.py 读取配置；
Agent 文件不直接读取环境变量；
Prompt 文件不写配置；
测试不写真实 API Key；
日志不打印完整配置。
```

调用模型要求：

```text
base_url 使用 LITELLM_BASE_URL；
model 使用 LITELLM_MODEL；
key 使用 LITELLM_MASTER_KEY；
不直接调用 https://ws-veazvl2fycrurdmv.cn-beijing.maas.aliyuncs.com/compatible-mode/v1；
所有调用记录 Langfuse Trace。
```

---

## 11. OCR Parser Service 配置要求

环境变量：

```env
APP_ENV=local
MINIO_ENDPOINT=http://minio:9000
MINIO_ROOT_USER=${MINIO_ROOT_USER}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}

JAVA_API_SERVICE_URL=http://java-api-service:8080
OCR_SERVICE_SECRET=${OCR_SERVICE_SECRET}

ELASTICSEARCH_URL=http://elasticsearch:9200
LOG_LEVEL=INFO
ENABLE_SENSITIVE_LOG_MASKING=true
```

Dockerfile 必须自动安装：

```text
Python 运行环境；
PaddleOCR 相关依赖；
MarkItDown；
文件解析系统依赖；
项目 Python 包依赖。
```

禁止要求开发者在宿主机手动安装 OCR 相关依赖。

---

## 12. Frontend 配置要求

### 12.1 前端环境变量

```env
VITE_API_BASE_URL=/api
VITE_AGENT_STREAM_BASE_URL=/agent-api
VITE_ENABLE_AI_NATIVE_UI=true
VITE_ENABLE_AGENT_STATUS_STREAM=true
VITE_ENABLE_EVIDENCE_STUDIO=true
VITE_ENABLE_HEARING_COURT=true
VITE_ENABLE_DELIBERATION_PANEL_VIEW=true
```

### 12.2 前端安全边界

前端禁止出现：

```text
Qwen 3.7 Plus API Key；
LiteLLM Master Key；
Langfuse Secret Key；
MinIO Secret Key；
Java Service Secret；
Python Agent Service Secret；
OCR Service Secret。
```

前端只允许访问 Nginx 代理后的后端 API。

### 12.3 前端 AI Native 配置

前端可通过 Feature Flag 控制：

```text
AI 受理卡；
Agent 状态流；
证据工作室；
审理庭；
评议团视图；
审核辅助官；
Trace 抽屉；
非最终裁决提示。
```

关闭任一 AI UI 能力时，只能降级到平台人工审核或静态视图，不能绕过人审。

---

## 13. Nginx 代理配置要求

Codex 必须生成：

```text
deploy/nginx/nginx.conf
deploy/nginx/conf.d/default.conf
```

代理规则至少包括：

```nginx
location / {
    proxy_pass http://frontend:5173;
}

location /api/ {
    proxy_pass http://java-api-service:8080/api/;
}

location /agent-api/ {
    proxy_pass http://python-agent-service:8000/agent-api/;
}

location /ocr-api/ {
    proxy_pass http://ocr-parser-service:8010/ocr-api/;
}

location /langfuse/ {
    proxy_pass http://langfuse:3000/;
}
```

要求：

```text
内部服务间调用优先使用 Docker service name；
对外统一通过 Nginx；
不暴露中间件管理端口给前端代码；
Nginx 日志不得记录敏感 Header；
正式版路径不使用 /v2、/v3。
```

---

## 14. 启动脚本要求

Codex 必须生成：

```text
scripts/generate-secrets.sh
scripts/dev-up.sh
scripts/dev-down.sh
scripts/dev-reset.sh
scripts/init-db.sh
scripts/init-minio.sh
scripts/init-es.sh
scripts/smoke-test.sh
```

### 14.1 dev-up.sh

职责：

```text
检查 Docker；
检查 .env；
提示生成 secrets；
检查 DASHSCOPE_API_KEY 是否占位符；
拉取镜像；
构建应用镜像；
启动 compose；
执行初始化；
输出访问地址。
```

### 14.2 smoke-test.sh

职责：

```text
检查所有服务 health；
检查 Java API；
检查 Python Agent；
检查 OCR；
检查 LiteLLM；
检查 Langfuse；
检查 MinIO；
检查 Elasticsearch；
检查 Temporal；
创建测试 dispute；
验证主链路至少进入 case 创建阶段。
```

---

## 15. 开发者启动流程

首次启动：

```bash
cp .env.example .env
chmod +x scripts/*.sh
./scripts/generate-secrets.sh
```

手动编辑 `.env`：

```bash
DASHSCOPE_API_KEY=<填入真实 Qwen 3.7 Plus API Key>
```

启动：

```bash
./scripts/dev-up.sh
```

验证：

```bash
./scripts/smoke-test.sh
```

访问入口：

```text
系统入口：http://localhost:8080
Java API：http://localhost:18080
Python Agent：http://localhost:18000
OCR Service：http://localhost:18010
MinIO Console：http://localhost:19001
Langfuse：http://localhost:13000
LiteLLM：http://localhost:14000
Elasticsearch：http://localhost:19200
```

---

## 16. Codex 配置开发任务

### 16.1 任务目标

Codex 需要根据本文档完成配置体系落地：

```text
生成 .env.example；
生成 .gitignore；
生成 scripts/generate-secrets.sh；
完善 docker-compose.yml；
生成 deploy/litellm/config.yaml；
生成 deploy/nginx 配置；
生成数据库、中间件初始化脚本；
修改 Java / Python / OCR / Frontend 配置读取；
确保所有中间件通过 Docker 自动部署；
确保所有缺失运行依赖自动安装；
确保所有模型调用统一走 qwen3.7-plus；
确保正式版 API 路径不包含 /v2 或 /v3。
```

### 16.2 禁止事项

Codex 不允许：

```text
把真实 Qwen 3.7 Plus API Key 写入仓库；
把 API Key 写入 README；
把 API Key 写入 Dockerfile；
把 API Key 写入 docker-compose.yml 明文字段；
把 API Key 写入 LiteLLM config 明文；
让 Java 直接调用 Qwen 3.7 Plus；
让前端读取任何服务密钥；
引入非必要中间件；
使用宿主机手动安装代替 Docker；
使用弱默认密码；
输出完整密钥到日志；
跳过 healthcheck；
跳过 smoke test；
让关闭 AI 能力绕过人审；
在正式版 API 使用 /v2 或 /v3。
```

---

## 17. 配置验收清单

### 17.1 API Key 安全

- [ ] `.env.example` 不包含真实 API Key。
- [ ] `.env` 已加入 `.gitignore`。
- [ ] 仓库内搜索不到真实 API Key。
- [ ] Dockerfile 不包含真实 API Key。
- [ ] docker-compose.yml 不包含真实 API Key 明文。
- [ ] LiteLLM config 不包含真实 API Key 明文。
- [ ] 日志不会打印真实 API Key。
- [ ] 前端不包含任何 API Key。

### 17.2 模型统一

- [ ] `DEFAULT_LLM_MODEL=qwen3.7-plus`。
- [ ] `LITELLM_DEFAULT_MODEL=qwen3.7-plus`。
- [ ] `LITELLM_MODEL=qwen3.7-plus`。
- [ ] Python Agent Service 只调用 LiteLLM。
- [ ] Java API Service 不直接调用 Qwen 3.7 Plus。
- [ ] 所有 Agent 默认模型一致。
- [ ] Evaluation Agent 默认模型一致。

### 17.3 Docker 中间件

- [ ] PostgreSQL 使用 Docker 部署。
- [ ] Redis 使用 Docker 部署。
- [ ] Elasticsearch 使用 Docker 部署。
- [ ] MinIO 使用 Docker 部署。
- [ ] Temporal 使用 Docker 部署。
- [ ] Langfuse 使用 Docker 部署。
- [ ] LiteLLM 使用 Docker 部署。
- [ ] Nginx 使用 Docker 部署。
- [ ] 所有必要附属容器已自动补齐。
- [ ] 不要求宿主机手动安装中间件。

### 17.4 正式版 API 路径

- [ ] 前端配置使用 `/api`。
- [ ] 外部 API 使用 `/api/disputes`、`/api/reviews`。
- [ ] 内部 API 使用 `/internal/agents`、`/internal/evidence`。
- [ ] Nginx 不配置 `/api/v2`、`/api/v3`。
- [ ] 文档和配置不再出现 `/internal/v2`、`/internal/v3`。

### 17.5 Agent Harness 配置

- [ ] Agent Profile、Prompt、Skill 和规则版本可配置。
- [ ] Loop 的迭代、工具、模型调用和 Token 预算可配置。
- [ ] AI 评议团和审核辅助官使用服务端 Feature Flag。
- [ ] 关闭新增 AI 能力后仍强制进入平台人工审核。
- [ ] Agent 输出校验和 Guardrail 默认启用。
- [ ] 任何 Agent 配置都不能授予退款、补发、关闭或审批权限。

---

## 18. Codex 一次性配置执行提示词

```text
你现在作为 Codex，为“AI Native 履约争端审理系统”补齐正式版配置体系。

请严格按照《AI Native 履约争端审理系统：统一配置说明（最终版）》执行。

任务目标：
1. 统一所有模型调用为 qwen3.7-plus。
2. 通过 LiteLLM Proxy 统一代理 Qwen 3.7 Plus。
3. Qwen 3.7 Plus API Key 只能从 .env 读取，不允许写入仓库。
4. 所有数据库和中间件都必须使用 Docker Compose 部署。
5. PostgreSQL、Redis、Elasticsearch、MinIO、Temporal、Langfuse、LiteLLM、Nginx 等服务必须可一键启动。
6. 用户名、密码、secret、salt、service token 由 scripts/generate-secrets.sh 自动生成。
7. 缺少的运行依赖中间件或附属容器必须自动补齐。
8. 生成 dev-up、dev-down、dev-reset、init-db、init-minio、init-es、smoke-test 等脚本。
9. 更新 Java、Python Agent、OCR、Frontend 的配置读取方式。
10. 补齐 healthcheck 和配置验收说明。
11. 正式版 API 路径不得使用 /v2 或 /v3。
12. Agent Harness 配置必须包含 Profile、Prompt、Skill、Ruleset、Loop、Guardrail 和 Trace 相关版本与开关。

禁止事项：
- 不得把真实 Qwen 3.7 Plus API Key 写入任何仓库文件。
- 不得把 API Key 打印到日志。
- 不得让前端读取任何服务密钥。
- 不得让 Java 直接调用 Qwen 3.7 Plus。
- 不得绕过 LiteLLM。
- 不得要求宿主机手动安装 PostgreSQL、Redis、MinIO、Elasticsearch、Temporal、Langfuse、LiteLLM、PaddleOCR 或 MarkItDown。
- 不得引入 Kafka、MCP、Milvus、Qdrant、OPA、Drools、Kubernetes、自部署大模型。
- 不得使用 admin/admin、root/root、test/test 等弱默认密码。
- 不得跳过 healthcheck 和 smoke test。
- 不得配置 /api/v2、/api/v3、/internal/v2、/internal/v3 路径。

输出要求：
- 输出新增和修改文件列表。
- 输出环境变量清单。
- 输出 Docker Compose 服务清单。
- 输出中间件自动安装说明。
- 输出密钥生成说明。
- 输出启动命令。
- 输出 smoke test 结果。
- 输出待确认事项。

验收标准：
- docker compose config 通过。
- scripts/generate-secrets.sh 可生成本地 .env。
- .env.example 不包含真实密钥。
- docker compose up 可启动所有服务。
- 所有 healthcheck 正常。
- LiteLLM 默认模型为 qwen3.7-plus。
- Python Agent Service 可通过 LiteLLM 调用 qwen3.7-plus。
- Java API Service 不直接调用 Qwen 3.7 Plus。
- 前端不包含任何服务密钥。
- smoke-test.sh 通过。
```

---

## 19. 最终配置结论

本项目配置体系最终定版为：

```text
统一模型：qwen3.7-plus；
统一模型代理：LiteLLM Proxy；
模型 API Key：仅写入本地 .env，不进入仓库；
中间件部署：全部 Docker Compose 部署；
用户名密码：Codex 生成脚本自动生成；
缺失依赖：Codex 根据镜像运行要求自动补齐；
配置入口：.env + docker-compose.yml + deploy/* + 各服务 config；
API 路径：正式版不使用 /v2、/v3；
启动方式：scripts/dev-up.sh；
验收方式：scripts/smoke-test.sh + 配置验收清单。
```

---

## 20. 房间协作与时效配置

### 20.1 环境变量

`.env.example` 必须包含：

```dotenv
# Room-based dispute workflow
EVIDENCE_WINDOW=PT2H
HEARING_WINDOW=PT3H
MAX_HEARING_ROUNDS=3
SSE_HEARTBEAT=PT15S
SSE_EMITTER_TIMEOUT=PT4H
SEED_DEMO_DISPUTES=true
NOTIFICATION_OUTBOX_BATCH_SIZE=100
NOTIFICATION_OUTBOX_POLL_INTERVAL=PT1S
```

生产默认：

```text
举证窗口：PT2H；
庭审窗口：PT3H；
庭审轮次：3；
SSE 心跳：PT15S。
```

自动化测试通过 Spring Profile 或测试构造参数注入秒级 Duration，不得修改生产默认值。

### 20.2 Java 配置

```yaml
dispute:
  evidence-window: ${EVIDENCE_WINDOW:PT2H}
  hearing-window: ${HEARING_WINDOW:PT3H}
  max-hearing-rounds: ${MAX_HEARING_ROUNDS:3}
  sse-heartbeat: ${SSE_HEARTBEAT:PT15S}
  sse-emitter-timeout: ${SSE_EMITTER_TIMEOUT:PT4H}
  seed-demo-disputes: ${SEED_DEMO_DISPUTES:true}
  notification-outbox:
    batch-size: ${NOTIFICATION_OUTBOX_BATCH_SIZE:100}
    poll-interval: ${NOTIFICATION_OUTBOX_POLL_INTERVAL:PT1S}
```

配置校验：

```text
Duration 必须为正；
MAX_HEARING_ROUNDS 必须在 1..5；
SSE_EMITTER_TIMEOUT 必须大于 HEARING_WINDOW；
Outbox batch size 必须在 1..1000。
```

### 20.3 Nginx SSE

```nginx
location ~ ^/api/disputes/.*/events$ {
    proxy_pass http://java-api-service:8080;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 4h;
}
```

SSE 响应禁止压缩缓冲。Nginx 不暴露 `/internal/disputes/import`、内部 Agent、Parser、Temporal 或数据组件。

### 20.4 演示争议数据

`SEED_DEMO_DISPUTES=true` 只用于本地和演示环境，创建：

```text
INTAKE_PENDING
EVIDENCE_OPEN
HEARING_OPEN
REVIEW_PENDING
CLOSED
```

等状态的争议订单。生产环境必须关闭种子开关，真实数据通过内部导入接口或争议接待官创建。

### 20.5 传票信箱

传票信箱不配置外部短信、邮件或推送供应商。通知事实保存在 PostgreSQL，Outbox 发布失败可重试，重复事件按业务键去重。

### 20.6 配置验收

- [ ] `EVIDENCE_WINDOW=PT2H`。
- [ ] `HEARING_WINDOW=PT3H`。
- [ ] `MAX_HEARING_ROUNDS=3`。
- [ ] SSE 心跳与长连接超时合法。
- [ ] 前端无法通过本地配置改变服务端截止时间。
- [ ] 演示种子在生产关闭。
- [ ] Outbox 批量和轮询参数有边界校验。
- [ ] Nginx 对 SSE 关闭缓冲。
- [ ] 内部导入与内部 Agent API 不对公网暴露。
