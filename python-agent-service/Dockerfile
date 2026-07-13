# 文件作用：容器镜像构建文件，定义服务运行环境、依赖安装和启动入口。
# 说明：本注释用于帮助读者先了解文件用途，再修改具体配置。

FROM public.ecr.aws/docker/library/python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements.txt .
RUN python -m pip install --upgrade pip \
    && python -m pip install --requirement requirements.txt

COPY app ./app

RUN useradd --create-home --uid 10001 app \
    && chown -R app:app /app

USER app

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"

CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
