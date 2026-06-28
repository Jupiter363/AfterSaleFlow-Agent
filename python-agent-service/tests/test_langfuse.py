from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

from app.tracing import AgentTraceContext, LangfuseAgentTracer


def test_real_langfuse_sdk_exports_workflow_and_generation_observations() -> None:
    received_paths: list[str] = []

    class Receiver(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            self.rfile.read(length)
            received_paths.append(self.path)
            self.send_response(200)
            self.end_headers()

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
