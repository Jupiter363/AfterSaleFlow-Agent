// 文件作用：自动化测试文件，验证 devLocalPorts.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

// @vitest-environment node

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import viteConfig from "../vite.config.js";

// 业务位置：【前端应用】readProjectFile：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function readProjectFile(relativePath) {
  return readFileSync(new URL(relativePath, new URL("../../", import.meta.url)), "utf8");
}

// 业务位置：【前端应用】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
describe("local service port contract", () => {
  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("keeps dev and Docker ports fixed and waits for the Python health endpoint", () => {
    const envExample = readProjectFile(".env.example");
    const compose = readProjectFile("docker-compose.yml");
    const devLocal = readProjectFile("scripts/dev-local.ps1");
    const smokeTest = readProjectFile("scripts/smoke-test.sh");
    const generateOpenapi = readProjectFile("scripts/generate-openapi.sh");
    const javaLocalConfig = readProjectFile(
      "java-api-service/src/main/resources/application-local.yml",
    );

    expect(envExample).toContain("FRONTEND_PORT=5173");
    expect(envExample).toContain("JAVA_API_PORT=8080");
    expect(envExample).toContain("PYTHON_AGENT_PORT=18000");
    expect(envExample).toContain("OCR_SERVICE_PORT=18010");
    expect(envExample).toContain("NGINX_PORT=18080");

    expect(compose).toContain("${FRONTEND_PORT:-5173}:5173");
    expect(compose).toContain("${JAVA_API_PORT:-8080}:8080");
    expect(compose).toContain("${PYTHON_AGENT_PORT:-18000}:8000");
    expect(compose).toContain("${OCR_SERVICE_PORT:-18010}:8010");
    expect(compose).toContain("${NGINX_PORT:-18080}:80");
    expect(smokeTest).toContain(
      'NGINX_BASE_URL="${NGINX_BASE_URL:-http://localhost:18080}"',
    );
    expect(smokeTest).toContain(
      'JAVA_BASE_URL="${JAVA_BASE_URL:-http://localhost:8080}"',
    );
    expect(generateOpenapi).toContain(
      '"${JAVA_BASE_URL:-http://localhost:8080}/v3/api-docs"',
    );
    expect(javaLocalConfig).toContain(
      "${PYTHON_AGENT_SERVICE_URL:http://127.0.0.1:18000}",
    );
    expect(javaLocalConfig).toContain(
      "${OCR_SERVICE_URL:http://127.0.0.1:18010}",
    );

    expect(viteConfig.server.port).toBe(5173);
    expect(viteConfig.server.proxy["/api"].target).toBe("http://127.0.0.1:8080");
    expect(viteConfig.server.proxy["/agent-api"].target).toBe(
      "http://127.0.0.1:18000",
    );
    expect(viteConfig.server.proxy["/ocr-api"].target).toBe(
      "http://127.0.0.1:18010",
    );

    const pythonHealthUrl =
      'http://127.0.0.1:$($env:PYTHON_AGENT_PORT)/health';
    expect(devLocal).toContain(pythonHealthUrl);
    expect(devLocal).toContain('"-m", "uvicorn", "app.main:create_app"');
    expect(devLocal).toContain(
      "[System.IO.File]::WriteAllText($pythonAgentPidFile, $pythonAgentProcess.Id.ToString())",
    );
    expect(devLocal.match(/\$LASTEXITCODE -ne 0/g).length).toBeGreaterThanOrEqual(2);
    expect(devLocal.indexOf(pythonHealthUrl)).toBeLessThan(
      devLocal.indexOf('Write-Output "Local development services are ready."'),
    );
  });
});
