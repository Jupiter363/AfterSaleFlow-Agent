from __future__ import annotations

from urllib.parse import urlparse

import httpx

from app.models import ParseTaskCreate, ParsedDocument


class MinioObjectStorage:
    def __init__(self, endpoint: str, access_key: str, secret_key: str) -> None:
        from minio import Minio

        parsed = urlparse(endpoint)
        self._client = Minio(
            parsed.netloc,
            access_key=access_key,
            secret_key=secret_key,
            secure=parsed.scheme == "https",
        )

    def download(self, bucket: str, object_key: str) -> bytes:
        response = self._client.get_object(bucket, object_key)
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()


class CompositeResultSink:
    def __init__(
        self,
        java_api_url: str,
        elasticsearch_url: str,
        service_secret: str,
    ) -> None:
        self._java_api_url = java_api_url.rstrip("/")
        self._elasticsearch_url = elasticsearch_url.rstrip("/")
        self._headers = {
            "X-Service-Secret": service_secret,
            "X-Service-Identity": "ocr-parser-service",
        }

    def publish_success(
        self, request: ParseTaskCreate, document: ParsedDocument
    ) -> None:
        payload = {
            "status": "SUCCEEDED",
            "text": document.text,
            "metadata": document.metadata,
        }
        with httpx.Client(timeout=15.0) as client:
            client.post(
                f"{self._java_api_url}/internal/evidence/"
                f"{request.evidence_id}/parse-result",
                headers=self._headers,
                json=payload,
            ).raise_for_status()
            client.put(
                f"{self._elasticsearch_url}/evidence_index/_doc/"
                f"{request.evidence_id}",
                json={
                    "evidence_id": request.evidence_id,
                    "case_id": request.case_id,
                    "content_type": request.content_type,
                    "parsed_text": document.text,
                    "parse_status": "SUCCEEDED",
                    "extraction": document.metadata,
                },
            ).raise_for_status()

    def publish_failure(self, request: ParseTaskCreate, error_code: str) -> None:
        with httpx.Client(timeout=15.0) as client:
            client.post(
                f"{self._java_api_url}/internal/evidence/"
                f"{request.evidence_id}/parse-result",
                headers=self._headers,
                json={"status": "FAILED", "error_code": error_code},
            ).raise_for_status()
