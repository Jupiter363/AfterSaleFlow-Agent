# 数据与存储

- PostgreSQL：业务数据、Workflow 周边状态、审核、执行和审计事实源。
- Redis：幂等执行锁、短期状态和缓存；不保存核心裁决结果。
- MinIO：原始证据、脱敏证据、OCR 临时文件、政策文件和导出文件。
- Elasticsearch：政策、证据和历史 Case 的可重建检索投影。

数据库结构由 `java-api-service/src/main/resources/db/migration` 中的 Flyway
migration 管理。应用使用 `ddl-auto=validate`，禁止 Hibernate 自动改表。

原始证据与脱敏证据必须使用不同 Bucket；搜索索引和 Redis 数据均可从事实源重建。
