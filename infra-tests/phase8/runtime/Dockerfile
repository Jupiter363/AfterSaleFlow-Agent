FROM --platform=linux/amd64 docker.io/library/python@sha256:cdbd05fb6f457ca275ff51ce00d93d865ca0b6a25f5ffb08262d94f6835771e5 AS runtime

ARG SOURCE_DATE_EPOCH=0

LABEL org.opencontainers.image.title="AfterSaleFlow Phase 8 engineering test runtime" \
      org.opencontainers.image.base.digest="sha256:cdbd05fb6f457ca275ff51ce00d93d865ca0b6a25f5ffb08262d94f6835771e5" \
      com.aftersaleflow.authority="ENGINEERING_TEST_EXECUTION_ONLY"

COPY requirements.lock /opt/phase8/requirements.lock

RUN --network=none --mount=type=bind,from=wheelhouse,source=/,target=/wheelhouse,readonly \
    python -m pip install \
        --no-index \
        --find-links=/wheelhouse \
        --require-hashes \
        --only-binary=:all: \
        --no-compile \
        --no-cache-dir \
        --disable-pip-version-check \
        --target=/opt/phase8/site-packages \
        --requirement=/opt/phase8/requirements.lock \
    && find /opt/phase8/site-packages -type d -name __pycache__ -prune -exec rm -rf '{}' + \
    && find /opt/phase8/site-packages -type f -name '*.py[co]' -delete

ENV CI=1 \
    HOME=/tmp \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PIP_CONFIG_FILE=/dev/null \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_NO_INDEX=1 \
    PIP_ONLY_BINARY=:all: \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONHASHSEED=0 \
    PYTHONNOUSERSITE=1 \
    PYTHONPATH=/opt/phase8/site-packages \
    PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
    TMPDIR=/tmp \
    TZ=UTC

WORKDIR /workspace
USER 65532:65532
CMD ["python"]
