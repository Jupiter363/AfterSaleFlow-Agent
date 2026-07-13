# 文件作用：项目运维脚本，用于本地开发、环境初始化、密钥生成或接口校验。

from __future__ import annotations

import base64
import json
import os
import struct
import sys
import urllib.error
import urllib.request
import zlib
from pathlib import Path


# 所属模块：开发运维脚本 > verify-qwen-multimodal；函数角色：模块私有业务函数。
# 具体功能：`_load_env_file` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`splitlines`、`env_file.exists`、`raw_line.strip`。
# 上下游：上游为 本文件的 `main`；下游为 协作调用 `splitlines`、`env_file.exists`、`raw_line.strip`、`line.split`。
# 系统意义：该函数在系统中的业务边界是：仅用于开发环境，不写生产数据。
def _load_env_file() -> None:
    env_file = Path(__file__).resolve().parents[1] / ".env"
    if not env_file.exists():
        return
    for raw_line in env_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        os.environ.setdefault(name.strip(), value.strip())


# 所属模块：开发运维脚本 > verify-qwen-multimodal；函数角色：模块私有业务函数。
# 具体功能：`_png_chunk` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`struct.pack`、`zlib.crc32`。
# 上下游：上游为 本文件的 `_red_blue_test_image`；下游为 协作调用 `struct.pack`、`zlib.crc32`。
# 系统意义：该函数在系统中的业务边界是：仅用于开发环境，不写生产数据。
def _png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


# 所属模块：开发运维脚本 > verify-qwen-multimodal；函数角色：模块私有业务函数。
# 具体功能：`_red_blue_test_image` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`join`、`rows.append`、`pixels.extend`。
# 上下游：上游为 本文件的 `main`；下游为 本文件的 `_png_chunk`。
# 系统意义：该函数在系统中的业务边界是：仅用于开发环境，不写生产数据。
def _red_blue_test_image() -> bytes:
    width = 64
    height = 32
    rows = []
    for _ in range(height):
        pixels = bytearray()
        for x in range(width):
            pixels.extend((255, 0, 0) if x < width // 2 else (0, 0, 255))
        rows.append(b"\x00" + bytes(pixels))
    return b"".join(
        (
            b"\x89PNG\r\n\x1a\n",
            _png_chunk(
                b"IHDR",
                struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0),
            ),
            _png_chunk(b"IDAT", zlib.compress(b"".join(rows))),
            _png_chunk(b"IEND", b""),
        )
    )


# 所属模块：开发运维脚本 > verify-qwen-multimodal；函数角色：模块公开业务函数。
# 具体功能：`main` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`os.getenv`、`decode`、`urllib.request.Request`。
# 上下游：上游为 本地环境变量、容器端口、服务配置；下游为 本文件的 `_load_env_file`、`_red_blue_test_image`。
# 系统意义：该函数在系统中的业务边界是：仅用于开发环境，不写生产数据。
def main() -> int:
    _load_env_file()
    dashscope_key = os.getenv("DASHSCOPE_API_KEY", "")
    if not dashscope_key or dashscope_key in {
        "EMPTY",
        "__PASTE_YOUR_DASHSCOPE_API_KEY_HERE__",
    }:
        print(
            "DASHSCOPE_API_KEY 未配置，无法调用真实 qwen3.7-plus 多模态模型。",
            file=sys.stderr,
        )
        return 2
    master_key = os.getenv("LITELLM_MASTER_KEY", "")
    if not master_key or master_key.startswith("__"):
        print("LITELLM_MASTER_KEY 未配置，无法执行真实多模态探针。", file=sys.stderr)
        return 2
    base_url = os.getenv("LITELLM_PROBE_BASE_URL", "http://127.0.0.1:14000")
    image_data = base64.b64encode(_red_blue_test_image()).decode("ascii")
    request_body = {
        "model": "qwen3.7-plus",
        "temperature": 0,
        "enable_thinking": False,
        "response_format": {"type": "json_object"},
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是多模态能力探针。只返回 JSON："
                    '{"left_color":"...","right_color":"...","visual_input_read":true}。'
                ),
            },
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "识别图片左半边和右半边的颜色。"},
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/png;base64,{image_data}",
                            "detail": "high",
                        },
                    },
                ],
            },
        ],
    }
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/v1/chat/completions",
        data=json.dumps(request_body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {master_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        print(f"真实多模态探针失败：HTTP {exception.code}: {detail}", file=sys.stderr)
        return 1
    except OSError as exception:
        print(f"真实多模态探针无法连接 LiteLLM：{exception}", file=sys.stderr)
        return 1

    message = payload["choices"][0]["message"]
    result = json.loads(message.get("content") or "{}")
    left = str(result.get("left_color") or "")
    right = str(result.get("right_color") or "")
    passed = (
        result.get("visual_input_read") is True
        and any(token in left.casefold() for token in ("红", "red"))
        and any(token in right.casefold() for token in ("蓝", "blue"))
    )
    print(
        json.dumps(
            {
                "passed": passed,
                "model": payload.get("model"),
                "left_color": left,
                "right_color": right,
                "usage": payload.get("usage") or {},
            },
            ensure_ascii=False,
        )
    )
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
