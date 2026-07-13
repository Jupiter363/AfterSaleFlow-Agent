# 文件作用：自动化测试文件，验证 test_langfuse 相关模块的行为、契约或页面布局。

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

from app.tracing import AgentTraceContext, LangfuseAgentTracer


# 所属模块：Python 支撑模块 > test_langfuse；函数角色：回归测试用例。
# 具体功能：`test_real_langfuse_sdk_exports_workflow_and_generation_observations` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`ThreadingHTTPServer`、`Thread`、`thread.start`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `ThreadingHTTPServer`、`Thread`、`thread.start`、`LangfuseAgentTracer`。
# 系统意义：固定“Python 支撑模块 > test_langfuse”的可观察契约，防止后续重构改变业务结果。
def test_real_langfuse_sdk_exports_workflow_and_generation_observations() -> None:
    received_paths: list[str] = []

    class Receiver(BaseHTTPRequestHandler):
        # 所属模块：Python 支撑模块 > test_langfuse；函数角色：类/闭包内部方法。
        # 具体功能：`do_POST` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self.rfile.read`、`received_paths.append`、`self.send_response`。
        # 上下游：上游为 相邻模块输入；下游为 协作调用 `self.rfile.read`、`received_paths.append`、`self.send_response`、`self.end_headers`。
        # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            self.rfile.read(length)
            received_paths.append(self.path)
            self.send_response(200)
            self.end_headers()

        # 所属模块：Python 支撑模块 > test_langfuse；函数角色：类/闭包内部方法。
        # 具体功能：`log_message` 围绕房间消息计算该函数独立负责的业务派生值。
        # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
        # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        tracer = LangfuseAgentTracer(
            "pk-test-key",
            "sk-test-secret",
            f"http://127.0.0.1:{server.server_port}",
        )
        context = AgentTraceContext(
            trace_id="TRACE_langfuse",
            request_id="REQ_langfuse",
            case_id="CASE_langfuse",
            workflow_id="WORKFLOW_langfuse",
            user_id="USER_langfuse",
            role="SYSTEM",
            prompt_version="hearing-v1",
        )
        with tracer.workflow(context, {"case_id": context.case_id}) as trace:
            tracer.generation(
                context,
                "issue_framing_node",
                "test-model",
                "[REDACTED_INPUT sha256=test length=1]",
                {"issues": []},
                5,
                {"total": 2},
            )
            trace.complete({"status": "COMPLETED"})
        tracer.flush()
    finally:
        server.shutdown()
        thread.join(timeout=5)

    assert received_paths
    assert any("otel" in path or "ingestion" in path for path in received_paths)
