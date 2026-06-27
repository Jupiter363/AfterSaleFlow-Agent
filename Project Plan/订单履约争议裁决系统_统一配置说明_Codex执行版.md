# 订单履约争议裁决系统：统一配置说明（Codex 执行版）

## 1. 文档定位

本文档用于补充《订单履约争议裁决系统_正式版开发文档_Codex主控.md》的配置要求，专门约束 Codex 在开发和部署本项目时如何处理：

- 模型 API Key 配置；
- 统一模型选择；
- LiteLLM Proxy 配置；
- Docker Compose 中间件部署；
- 数据库、中间件用户名密码生成；
- 缺失依赖中间件自动安装；
- `.env` / `.env.example` / `docker-compose.yml` / 服务配置文件的生成规范；
- 本地正式版一键启动配置；
- 配置验收标准。

本文档是 Codex 执行配置落地时的强约束文档。Codex 必须严格按照本文档生成配置，不得擅自改用其他模型、其他部署方式或手工安装中间件。

---

## 2. 总体配置原则

### 2.1 模型统一原则

本项目所有 LLM 调用统一使用：

```text
deepseek-v4-flash
```

所有 Agent、裁决草案生成、证据分析、规则适用说明、Evaluation Agent 等 LLM 调用，都必须通过 LiteLLM Proxy 访问该模型。

禁止出现以下情况：

- 不允许某个 Agent 直接绕过 LiteLLM 调用模型厂商 API；
- 不允许不同 Agent 随意使用不同模型；
- 不允许在业务代码中硬编码 API Key；
- 不允许在 Prompt 文件中写入 API Key；
- 不允许在 Git 仓库中提交真实 API Key；
- 不允许将 API Key 打印到日志；
- 不允许在前端暴露 API Key。

### 2.2 API Key 配置原则

用户已经提供 DeepSeek API Key。

Codex 在生成配置时必须注意：

- 真实 Key 只能写入本地 `.env` 文件；
- `.env` 必须加入 `.gitignore`；
- `.env.example` 只能写占位符；
- 文档、代码、测试、README、Dockerfile、docker-compose.yml 中不得出现明文真实 Key；
- 代码中只能通过环境变量读取；
- 日志中必须脱敏显示；
- 如果需要在本地运行，由开发者手动把用户提供的 Key 写入 `.env`。

`.env.example` 中写法：

```env
DEEPSEEK_API_KEY=__PASTE_YOUR_DEEPSEEK_API_KEY_HERE__
DEFAULT_LLM_MODEL=deepseek-v4-flash
DEFAULT_LLM_API_BASE=https://api.deepseek.com
```

本地 `.env` 中写法：

```env
DEEPSEEK_API_KEY=<填入用户提供的 DeepSeek API Key>
DEFAULT_LLM_MODEL=deepseek-v4-flash
DEFAULT_LLM_API_BASE=https://api.deepseek.com
```

---

## 3. DeepSeek 与 LiteLLM 统一模型配置

### 3.1 统一模型名称

所有服务统一使用：

```env
DEFAULT_LLM_MODEL=deepseek-v4-flash
```

在 Python Agent Service 中：

```env
AGENT_LLM_MODEL=deepseek-v4-flash
```

在 LiteLLM Proxy 中：

```env
LITELLM_DEFAULT_MODEL=deepseek-v4-flash
```

在 Evaluation Agent 中：

```env
EVALUATION_LLM_MODEL=deepseek-v4-flash
```

### 3.2 模型调用边界

#### Python Agent Service

Python Agent Service 不直接读取业务配置中的厂商 Key，必须通过以下配置调用 LiteLLM：

```env
LITELLM_BASE_URL=http://litellm-proxy:4000
LITELLM_MODEL=deepseek-v4-flash
```

#### Java API Service

Java API Service 不直接调用 DeepSeek。

Java 只能调用：

```text
python-agent-service
ocr-parser-service
```

不允许 Java API Service 直接调用：

```text
DeepSeek API
OpenAI SDK
LiteLLM Chat Completion
```

#### LiteLLM Proxy

LiteLLM Proxy 是唯一可以读取 `DEEPSEEK_API_KEY` 的服务。

---

## 4. `.env.example` 标准模板

Codex 必须在仓库根目录生成 `.env.example`，内容参考如下。

```env
# =========================================================
# Project
# =========================================================
COMPOSE_PROJECT_NAME=order-fulfillment-dispute-system
APP_ENV=local
TZ=Asia/Shanghai

# =========================================================
# LLM / DeepSeek / LiteLLM
# =========================================================
DEEPSEEK_API_KEY=__PASTE_YOUR_DEEPSEEK_API_KEY_HERE__
DEFAULT_LLM_PROVIDER=deepseek
DEFAULT_LLM_MODEL=deepseek-v4-flash
DEFAULT_LLM_API_BASE=https://api.deepseek.com

LITELLM_MASTER_KEY=__GENERATED_BY_CODEX__
LITELLM_SALT_KEY=__GENERATED_BY_CODEX__
LITELLM_BASE_URL=http://litellm-proxy:4000
LITELLM_DEFAULT_MODEL=deepseek-v4-flash

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

# =========================================================
# Temporal
# =========================================================
TEMPORAL_ADDRESS=temporal-server:7233
TEMPORAL_NAMESPACE=default
TEMPORAL_TASK_QUEUE=case-dispute-task-queue

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
VITE_AGENT_API_BASE_URL=/agent-api

# =========================================================
# Feature Flags
# =========================================================
FEATURE_AGENT_INTAKE_ENABLED=true
FEATURE_AGENT_HEARING_ENABLED=true
FEATURE_AGENT_EVALUATION_ENABLED=true
FEATURE_OCR_ENABLED=true
FEATURE_HUMAN_REVIEW_REQUIRED=true
FEATURE_TOOL_EXECUTOR_SIMULATION=true
FEATURE_AUTO_CLOSE_ENABLED=true

# =========================================================
# Security / Logging
# =========================================================
LOG_LEVEL=INFO
ENABLE_REQUEST_LOG=true
ENABLE_AUDIT_LOG=true
ENABLE_SENSITIVE_LOG_MASKING=true
```

---

## 5. `.gitignore` 必须包含

Codex 必须确保 `.gitignore` 至少包含：

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

注意：

- `.env.example` 允许提交；
- `.env` 不允许提交；
- `.env.local` 不允许提交；
- 任何包含真实 API Key 的文件都不允许提交。

---

## 6. 自动生成用户名密码规范

### 6.1 Codex 必须生成脚本

Codex 必须创建：

```text
scripts/generate-secrets.sh
```

该脚本用于生成本地 `.env` 文件中的用户名、密码、salt、service secret、Langfuse secret、LiteLLM master key 等。

### 6.2 生成规则

生成要求：

- 用户名长度：8-16 位；
- 密码长度：24-32 位；
- secret 长度：32-64 位；
- 只使用安全随机源；
- 不使用固定默认密码；
- 不使用 admin/admin、root/root、test/test；
- 生成后写入 `.env`；
- 如果 `.env` 已存在，不覆盖用户已填写的 `DEEPSEEK_API_KEY`；
- 输出时不得打印完整密钥，只显示是否生成成功。

### 6.3 脚本示例要求

Codex 生成脚本时应采用类似逻辑：

```bash
#!/usr/bin/env bash
set -euo pipefail

ENV_FILE=".env"
EXAMPLE_FILE=".env.example"

if [ ! -f "$ENV_FILE" ]; then
  cp "$EXAMPLE_FILE" "$ENV_FILE"
fi

generate_secret() {
  openssl rand -base64 32 | tr -d '\n' | tr '/+=' 'abc'
}

generate_user() {
  echo "user_$(openssl rand -hex 4)"
}

replace_if_placeholder() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=__GENERATED_BY_CODEX__" "$ENV_FILE"; then
    sed -i.bak "s|^${key}=__GENERATED_BY_CODEX__|${key}=${value}|g" "$ENV_FILE"
  fi
}

replace_if_placeholder "POSTGRES_USER" "$(generate_user)"
replace_if_placeholder "POSTGRES_PASSWORD" "$(generate_secret)"
replace_if_placeholder "REDIS_PASSWORD" "$(generate_secret)"
replace_if_placeholder "MINIO_ROOT_USER" "$(generate_user)"
replace_if_placeholder "MINIO_ROOT_PASSWORD" "$(generate_secret)"
replace_if_placeholder "ELASTICSEARCH_PASSWORD" "$(generate_secret)"
replace_if_placeholder "LITELLM_MASTER_KEY" "sk-$(openssl rand -hex 24)"
replace_if_placeholder "LITELLM_SALT_KEY" "$(generate_secret)"
replace_if_placeholder "LANGFUSE_PUBLIC_KEY" "pk-lf-$(openssl rand -hex 16)"
replace_if_placeholder "LANGFUSE_SECRET_KEY" "sk-lf-$(openssl rand -hex 24)"
replace_if_placeholder "LANGFUSE_SALT" "$(generate_secret)"
replace_if_placeholder "LANGFUSE_NEXTAUTH_SECRET" "$(generate_secret)"
replace_if_placeholder "JAVA_SERVICE_SECRET" "$(generate_secret)"
replace_if_placeholder "PYTHON_AGENT_SERVICE_SECRET" "$(generate_secret)"
replace_if_placeholder "OCR_SERVICE_SECRET" "$(generate_secret)"

rm -f .env.bak

echo "Local .env generated or updated."
echo "Please manually set DEEPSEEK_API_KEY in .env before starting services."
```

Codex 可以根据系统兼容性调整 `sed` 写法，但必须保证 macOS / Linux 尽量兼容。

---

## 7. Docker Compose 中间件部署要求

### 7.1 所有中间件必须通过 Docker 部署

本项目所有中间件、数据库、基础设施服务都必须通过 Docker Compose 安装和启动。

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

如果某个服务官方镜像需要额外依赖容器，Codex 必须自动补齐。

例如：

- 如果 Langfuse 当前镜像版本需要额外 worker、clickhouse、queue、object storage 等运行依赖，Codex 应在 `docker-compose.yml` 中补充，并在注释中标注为 Langfuse 运行依赖。
- 如果 Temporal 当前部署方式需要独立 admin-tools 或 UI，Codex 可补充 `temporal-ui`，但不得影响主链路。
- 如果 Elasticsearch 需要设置单节点模式，必须在 compose 中配置。
- 如果 LiteLLM 需要独立数据库连接，必须接入 PostgreSQL。

### 7.2 应用服务也必须容器化

必须容器化：

```text
frontend
java-api-service
python-agent-service
ocr-parser-service
```

### 7.3 禁止手工安装中间件

Codex 不得要求开发者手工安装：

- PostgreSQL；
- Redis；
- Elasticsearch；
- MinIO；
- Temporal；
- Langfuse；
- LiteLLM；
- Nginx；
- PaddleOCR 运行依赖；
- MarkItDown 运行依赖。

所有依赖必须通过：

- Docker image；
- Dockerfile；
- docker-compose；
- requirements / pyproject；
- Maven；
- package.json；

自动安装。

---

## 8. Docker Compose 配置要求

### 8.1 docker-compose.yml 必须包含

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

### 8.2 基础服务配置要求

#### PostgreSQL

要求：

- 使用 Docker volume；
- 初始化多个数据库：
  - `dispute_system`
  - `temporal`
  - `langfuse`
  - `litellm`
- 用户名和密码来自 `.env`；
- healthcheck 必须存在。

#### Redis

要求：

- 密码来自 `.env`；
- 开启持久化；
- healthcheck 必须存在；
- 业务不得把核心数据只存 Redis。

#### Elasticsearch

要求：

- 单节点模式；
- 关闭或配置安全认证，当前本地开发可用 `ELASTICSEARCH_SECURITY_ENABLED=false`；
- 创建 `policy_index`、`evidence_index`、`case_index`；
- 使用 Docker volume；
- healthcheck 必须存在。

#### MinIO

要求：

- root user / password 来自 `.env`；
- 自动创建 bucket；
- 使用 Docker volume；
- 暴露 API 和 Console；
- healthcheck 必须存在。

#### Temporal

要求：

- 使用 PostgreSQL 作为持久化；
- namespace 使用 `default`；
- task queue 使用 `case-dispute-task-queue`；
- Java Worker 连接 Temporal；
- healthcheck 必须存在。

#### LiteLLM Proxy

要求：

- 读取 `DEEPSEEK_API_KEY`；
- 默认模型为 `deepseek-v4-flash`；
- 对 Python Agent Service 提供 OpenAI-compatible 接口；
- 配置 master key；
- 日志不得打印 API Key；
- 可连接 PostgreSQL 保存配置或使用配置文件启动。

#### Langfuse

要求：

- 用于记录 Agent Trace；
- 读取 `LANGFUSE_PUBLIC_KEY`、`LANGFUSE_SECRET_KEY`；
- 连接 PostgreSQL 或其当前版本官方要求的依赖容器；
- Python Agent Service 必须能写入 Trace；
- healthcheck 必须存在。

#### Nginx

要求：

- 统一代理前端；
- 代理 Java API；
- 代理 Python Agent API；
- 代理 OCR API；
- 可选代理 Langfuse 和 LiteLLM 管理入口；
- 不直接暴露内部服务密钥。

---

## 9. LiteLLM 配置文件要求

Codex 必须创建：

```text
deploy/litellm/config.yaml
```

建议内容如下：

```yaml
model_list:
  - model_name: deepseek-v4-flash
    litellm_params:
      model: openai/deepseek-v4-flash
      api_key: os.environ/DEEPSEEK_API_KEY
      api_base: os.environ/DEFAULT_LLM_API_BASE

litellm_settings:
  drop_params: true
  set_verbose: false
  request_timeout: 120

general_settings:
  master_key: os.environ/LITELLM_MASTER_KEY
```

要求：

- `model_name` 必须是 `deepseek-v4-flash`；
- `api_key` 必须从环境变量读取；
- `api_base` 必须从环境变量读取；
- 不允许在 YAML 写真实 Key；
- 不允许配置其他默认模型；
- 如果 LiteLLM 对 DeepSeek 有更推荐的 provider 写法，Codex 可以适配，但对外暴露模型名称仍必须是 `deepseek-v4-flash`。

---

## 10. Python Agent Service 配置要求

### 10.1 环境变量

```env
APP_ENV=local
LITELLM_BASE_URL=http://litellm-proxy:4000
LITELLM_MODEL=deepseek-v4-flash
LITELLM_MASTER_KEY=${LITELLM_MASTER_KEY}

LANGFUSE_HOST=http://langfuse:3000
LANGFUSE_PUBLIC_KEY=${LANGFUSE_PUBLIC_KEY}
LANGFUSE_SECRET_KEY=${LANGFUSE_SECRET_KEY}

JAVA_API_SERVICE_URL=http://java-api-service:8080
PYTHON_AGENT_SERVICE_SECRET=${PYTHON_AGENT_SERVICE_SECRET}

LOG_LEVEL=INFO
ENABLE_SENSITIVE_LOG_MASKING=true
```

### 10.2 代码读取规范

Python 必须集中在：

```text
python-agent-service/app/core/config.py
```

读取配置。

禁止：

- 在 Agent 文件中直接读取环境变量；
- 在 Prompt 文件中写配置；
- 在测试中写真实 API Key；
- 在日志中打印完整配置。

### 10.3 调用 LiteLLM 要求

Python Agent Service 调用模型时：

- base_url 使用 `LITELLM_BASE_URL`；
- model 使用 `LITELLM_MODEL`；
- key 使用 `LITELLM_MASTER_KEY` 或 LiteLLM 内部认证配置；
- 不直接调用 `https://api.deepseek.com`；
- 所有调用必须记录 Langfuse Trace。

---

## 11. Java API Service 配置要求

### 11.1 application-local.yml 配置项

Codex 必须在 Java 配置中支持：

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
    agent-hearing-enabled: ${FEATURE_AGENT_HEARING_ENABLED:true}
    agent-evaluation-enabled: ${FEATURE_AGENT_EVALUATION_ENABLED:true}
    ocr-enabled: ${FEATURE_OCR_ENABLED:true}
    human-review-required: ${FEATURE_HUMAN_REVIEW_REQUIRED:true}
    tool-executor-simulation: ${FEATURE_TOOL_EXECUTOR_SIMULATION:true}

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
  task-queue: ${TEMPORAL_TASK_QUEUE:case-dispute-task-queue}
```

### 11.2 Java 服务边界

Java API Service：

- 可以调用 Python Agent Service；
- 可以调用 OCR Parser Service；
- 可以调用 PostgreSQL、Redis、MinIO、Elasticsearch、Temporal；
- 不可以直接调用 DeepSeek；
- 不可以直接读取 `DEEPSEEK_API_KEY`；
- 不可以将模型 API Key 返回前端。

---

## 12. OCR Parser Service 配置要求

### 12.1 环境变量

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

### 12.2 依赖自动安装

OCR Parser Service Dockerfile 必须自动安装：

- Python 运行环境；
- PaddleOCR 相关依赖；
- MarkItDown；
- 文件解析所需系统依赖；
- 项目 Python 包依赖。

禁止要求开发者在宿主机手动安装 PaddleOCR 或 MarkItDown。

---

## 13. Frontend 配置要求

### 13.1 前端环境变量

```env
VITE_API_BASE_URL=/api
VITE_AGENT_API_BASE_URL=/agent-api
```

### 13.2 前端安全边界

前端禁止出现：

- DeepSeek API Key；
- LiteLLM Master Key；
- Langfuse Secret Key；
- MinIO Secret Key；
- Java Service Secret；
- Python Agent Service Secret；
- OCR Service Secret。

前端只允许访问 Nginx 代理后的后端 API。

---

## 14. Nginx 代理配置要求

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

- 内部服务间调用优先使用 Docker service name；
- 对外统一通过 Nginx；
- 不暴露中间件管理端口给前端代码；
- Nginx 日志不得记录敏感 Header。

---

## 15. 自动安装缺失依赖中间件要求

### 15.1 自动发现原则

Codex 在实现 Docker Compose 时，如果发现某个服务运行需要额外依赖中间件，必须自动补齐。

示例：

- Langfuse 官方镜像若需要额外数据库、队列、worker、clickhouse 等，必须补齐；
- Temporal 若需要 admin-tools 或 UI，可补充；
- LiteLLM 若需要 migration 或数据库，可接入 PostgreSQL；
- Elasticsearch 若需要 JVM 参数，必须在 compose 中补齐；
- OCR 若需要系统动态库，必须在 Dockerfile 中安装。

### 15.2 补齐依赖的约束

可以补齐：

- 运行某个已选技术所必需的附属容器；
- 官方 Docker Compose 推荐的依赖；
- 初始化脚本；
- 健康检查脚本；
- volume；
- network。

不可以补齐：

- 与本项目无关的技术；
- 新的业务中间件；
- Kafka；
- Milvus；
- Qdrant；
- MCP；
- OPA；
- Drools；
- Kubernetes；
- 自部署大模型。

---

## 16. 启动脚本要求

Codex 必须生成以下脚本。

### 16.1 `scripts/dev-up.sh`

职责：

- 检查 Docker 是否可用；
- 检查 `.env` 是否存在；
- 如不存在，提示执行 `generate-secrets.sh`；
- 检查 `DEEPSEEK_API_KEY` 是否仍是占位符；
- 拉取 Docker images；
- 构建应用服务 images；
- 启动 docker compose；
- 执行初始化脚本；
- 输出访问地址。

### 16.2 `scripts/dev-down.sh`

职责：

- 停止服务；
- 不删除 volume。

### 16.3 `scripts/dev-reset.sh`

职责：

- 停止服务；
- 删除本地 volume；
- 重新初始化；
- 明确提醒会清空数据。

### 16.4 `scripts/init-db.sh`

职责：

- 初始化 PostgreSQL 数据库；
- 创建 `dispute_system`、`temporal`、`langfuse`、`litellm` 数据库；
- 执行 Flyway migration。

### 16.5 `scripts/init-minio.sh`

职责：

- 创建 bucket；
- 配置 bucket policy；
- 校验 bucket 存在。

### 16.6 `scripts/init-es.sh`

职责：

- 创建 index template；
- 创建 `policy_index`、`evidence_index`、`case_index`；
- 导入示例政策数据。

### 16.7 `scripts/smoke-test.sh`

职责：

- 检查所有服务 health；
- 检查 Java API；
- 检查 Python Agent；
- 检查 OCR；
- 检查 LiteLLM；
- 检查 Langfuse；
- 检查 MinIO；
- 检查 Elasticsearch；
- 检查 Temporal；
- 创建一个测试 case；
- 验证主链路至少可进入 case 创建阶段。

---

## 17. 配置生成后的开发者操作流程

首次启动流程：

```bash
cp .env.example .env
chmod +x scripts/*.sh
./scripts/generate-secrets.sh
```

然后手动编辑 `.env`：

```bash
DEEPSEEK_API_KEY=<填入用户提供的 DeepSeek API Key>
```

启动服务：

```bash
./scripts/dev-up.sh
```

验证服务：

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

## 18. Codex 配置开发任务

### 18.1 任务目标

Codex 需要根据本文档完成配置体系落地：

- 生成 `.env.example`；
- 生成 `.gitignore`；
- 生成 `scripts/generate-secrets.sh`；
- 完善 `docker-compose.yml`；
- 生成 `deploy/litellm/config.yaml`；
- 生成 `deploy/nginx` 配置；
- 生成数据库、中间件初始化脚本；
- 修改 Java / Python / OCR / Frontend 配置读取；
- 确保所有中间件通过 Docker 自动部署；
- 确保所有缺失运行依赖自动安装；
- 确保所有模型调用统一走 `deepseek-v4-flash`。

### 18.2 Codex 禁止事项

Codex 不允许：

- 把真实 DeepSeek API Key 写入仓库；
- 把 API Key 写入 README；
- 把 API Key 写入 Dockerfile；
- 把 API Key 写入 docker-compose.yml 明文字段；
- 把 API Key 写入 LiteLLM config 明文；
- 让 Java 直接调用 DeepSeek；
- 让前端出现任何服务密钥；
- 引入非必要中间件；
- 使用本地手动安装代替 Docker；
- 使用固定弱密码；
- 输出完整密钥到日志；
- 跳过 healthcheck；
- 跳过 smoke test。

### 18.3 Codex 输出要求

Codex 完成配置后必须输出：

```text
1. 新增/修改文件列表
2. 环境变量清单
3. Docker Compose 服务清单
4. 自动生成密钥说明
5. DeepSeek / LiteLLM 配置说明
6. 中间件初始化说明
7. 启动命令
8. Smoke Test 结果
9. 未完成或待确认事项
```

---

## 19. 配置验收清单

### 19.1 API Key 安全验收

- [ ] `.env.example` 不包含真实 API Key。
- [ ] `.env` 已加入 `.gitignore`。
- [ ] 仓库内搜索不到真实 API Key。
- [ ] Dockerfile 不包含真实 API Key。
- [ ] docker-compose.yml 不包含真实 API Key 明文。
- [ ] LiteLLM config 不包含真实 API Key 明文。
- [ ] 日志不会打印真实 API Key。
- [ ] 前端不包含任何 API Key。

### 19.2 模型统一验收

- [ ] `DEFAULT_LLM_MODEL=deepseek-v4-flash`。
- [ ] `LITELLM_DEFAULT_MODEL=deepseek-v4-flash`。
- [ ] `AGENT_LLM_MODEL=deepseek-v4-flash` 或等价配置。
- [ ] Python Agent Service 只调用 LiteLLM。
- [ ] Java API Service 不直接调用 DeepSeek。
- [ ] 所有 Agent 默认模型一致。
- [ ] Evaluation Agent 默认模型一致。

### 19.3 Docker 中间件验收

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

### 19.4 用户名密码验收

- [ ] `scripts/generate-secrets.sh` 存在。
- [ ] 用户名密码由脚本生成。
- [ ] 不使用弱默认密码。
- [ ] 不覆盖用户已填写的 DeepSeek API Key。
- [ ] 密钥不会完整打印到终端。
- [ ] 所有生成值写入 `.env`。

### 19.5 初始化脚本验收

- [ ] `scripts/init-db.sh` 存在。
- [ ] `scripts/init-minio.sh` 存在。
- [ ] `scripts/init-es.sh` 存在。
- [ ] `scripts/smoke-test.sh` 存在。
- [ ] 数据库可初始化。
- [ ] MinIO bucket 可初始化。
- [ ] Elasticsearch index 可初始化。
- [ ] smoke test 可执行。

### 19.6 服务配置验收

- [ ] Java 能读取 PostgreSQL 配置。
- [ ] Java 能读取 Redis 配置。
- [ ] Java 能读取 MinIO 配置。
- [ ] Java 能读取 Elasticsearch 配置。
- [ ] Java 能读取 Temporal 配置。
- [ ] Java 能调用 Python Agent Service。
- [ ] Python Agent 能调用 LiteLLM。
- [ ] Python Agent 能写入 Langfuse。
- [ ] OCR 能读取 MinIO 文件。
- [ ] OCR 能写入 Elasticsearch。
- [ ] Frontend 只访问 Nginx 代理路径。

### 19.7 启动验收

- [ ] `docker compose config` 通过。
- [ ] `./scripts/dev-up.sh` 可执行。
- [ ] 所有容器启动成功。
- [ ] 所有 healthcheck 正常。
- [ ] Nginx 可访问。
- [ ] Java API 可访问。
- [ ] Python Agent Service 可访问。
- [ ] OCR Parser Service 可访问。
- [ ] LiteLLM 可访问。
- [ ] Langfuse 可访问。
- [ ] MinIO Console 可访问。
- [ ] Elasticsearch 可访问。
- [ ] Temporal 可连接。

---

## 20. Codex 一次性配置执行提示词

```text
你现在作为 Codex，为“订单履约争议裁决系统”补齐正式版配置体系。

请严格按照《订单履约争议裁决系统：统一配置说明（Codex 执行版）》执行。

任务目标：
1. 统一所有模型调用为 deepseek-v4-flash。
2. 通过 LiteLLM Proxy 统一代理 DeepSeek。
3. DeepSeek API Key 只能从 .env 读取，不允许写入仓库。
4. 所有数据库和中间件都必须使用 Docker Compose 部署。
5. PostgreSQL、Redis、Elasticsearch、MinIO、Temporal、Langfuse、LiteLLM、Nginx 等服务必须可一键启动。
6. 用户名、密码、secret、salt、service token 由 scripts/generate-secrets.sh 自动生成。
7. 缺少的运行依赖中间件或附属容器必须自动补齐。
8. 生成 dev-up、dev-down、dev-reset、init-db、init-minio、init-es、smoke-test 等脚本。
9. 更新 Java、Python Agent、OCR、Frontend 的配置读取方式。
10. 补齐 healthcheck 和配置验收说明。

修改范围：
- .env.example
- .gitignore
- docker-compose.yml
- deploy/
- scripts/
- java-api-service/src/main/resources/
- python-agent-service/app/core/config.py
- ocr-parser-service/app/core/config.py
- frontend/.env.example 或相关配置文件

禁止事项：
- 不得把真实 DeepSeek API Key 写入任何仓库文件。
- 不得把 API Key 打印到日志。
- 不得让前端读取任何服务密钥。
- 不得让 Java 直接调用 DeepSeek。
- 不得绕过 LiteLLM。
- 不得要求宿主机手动安装 PostgreSQL、Redis、MinIO、Elasticsearch、Temporal、Langfuse、LiteLLM、PaddleOCR 或 MarkItDown。
- 不得引入 Kafka、MCP、Milvus、Qdrant、OPA、Drools、Kubernetes、自部署大模型。
- 不得使用 admin/admin、root/root、test/test 等弱默认密码。
- 不得跳过 healthcheck 和 smoke test。

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
- LiteLLM 默认模型为 deepseek-v4-flash。
- Python Agent Service 可通过 LiteLLM 调用 deepseek-v4-flash。
- Java API Service 不直接调用 DeepSeek。
- smoke-test.sh 通过。
```

---

## 21. 最终配置结论

本项目配置体系最终定版为：

```text
统一模型：deepseek-v4-flash
统一模型代理：LiteLLM Proxy
模型 API Key：仅写入本地 .env，不进入仓库
中间件部署：全部 Docker Compose 部署
用户名密码：Codex 生成脚本自动生成
缺失依赖：Codex 根据镜像运行要求自动补齐
配置入口：.env + docker-compose.yml + deploy/* + 各服务 config
启动方式：scripts/dev-up.sh
验收方式：scripts/smoke-test.sh + 配置验收清单
```

Codex 必须严格按照本文档执行配置开发。
