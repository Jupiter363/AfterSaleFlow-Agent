from app.harness.evidence_asset_loader import EvidenceAssetLoader


def test_production_runtime_exchange_proxy_is_an_approved_java_origin() -> None:
    EvidenceAssetLoader(
        java_api_service_url="http://graph-exchange-proxy:8080",
        java_service_secret="test-java-service-secret",
    )
