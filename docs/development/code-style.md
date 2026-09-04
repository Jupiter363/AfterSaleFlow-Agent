# 代码规范

- Java 使用 Java 21、4 空格、构造器注入；Controller 只做协议转换。
- DTO、VO、Entity、Domain Model 分离，转换器集中管理。
- Python 使用 Ruff 与 pytest；服务级配置入口统一从 `app/config.py` 读取。
- 前端使用 Vue/JavaScript、Vitest 与 Playwright；前端不承载裁决和审批规则。
- 外部依赖统一位于 integration/client 或 adapter 边界。
- 枚举和错误码集中定义，不使用魔法字符串。
- 新行为遵循测试先行，异常路径与权限边界必须有测试。
