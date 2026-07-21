from __future__ import annotations

import re
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
JAVA_MAIN = ROOT / "java-api-service/src/main/java"
ARCHUNIT_TEST = (
    ROOT
    / "java-api-service/src/test/java/com/example/dispute/workflow/architecture"
    / "IntakeFormalSinkAssemblyTest.java"
)
FIXTURE_ROOT = (
    ROOT
    / "java-api-service/src/test/java/com/example/dispute/workflow"
    / "formalsinkarchitecturefixture"
)
PHASE4_BATCHES = ROOT / "plans/phase-4-intake-pilot-test-batches.yaml"

FORMAL_ROOT_NAMES = {
    "IntakeAgentRunDomainResultCommitter",
    "IntakeFormalBranchCommitPort",
    "IntakeFormalCommitPort",
    "IntakeGraphResultFinalizer",
    "IntakeTurnFinalizationPort",
}
FORMAL_ADAPTERS = (
    JAVA_MAIN
    / "com/example/dispute/workflow/activity/intake/IntakeRoomActivitiesAdapter.java",
    JAVA_MAIN
    / "com/example/dispute/workflow/application/intake"
    / "IntakeAgentRunDomainResultCommitter.java",
    JAVA_MAIN
    / "com/example/dispute/workflow/application/intake/IntakeGraphResultFinalizer.java",
    JAVA_MAIN
    / "com/example/dispute/room/infrastructure/persistence"
    / "JdbcIntakeFormalBranchCommitPort.java",
    JAVA_MAIN
    / "com/example/dispute/room/infrastructure/persistence"
    / "JdbcIntakeFormalCommitPort.java",
)
DISCOVERY_STEREOTYPE = re.compile(
    r"(?m)^\s*@(?:"
    r"AutoConfiguration|Bean|Component|Configuration|Controller|"
    r"ManagedBean|Named|Repository|RestController|Service|Singleton|"
    r"SpringBootApplication"
    r")\b"
)


def test_production_activity_registration_has_no_direct_formal_sink_reference() -> None:
    registration_sources = [
        path
        for path in sorted(JAVA_MAIN.rglob("*.java"))
        if "registerActivitiesImplementations" in path.read_text(encoding="utf-8")
    ]

    assert registration_sources
    for path in registration_sources:
        source = path.read_text(encoding="utf-8")
        assert not (FORMAL_ROOT_NAMES & set(re.findall(r"\b[A-Za-z_$][\w$]*\b", source))), path
        assert "JdbcIntakeFormal" not in source, path


def test_formal_intake_adapters_are_not_discoverable_components() -> None:
    for path in FORMAL_ADAPTERS:
        source = path.read_text(encoding="utf-8")
        assert not DISCOVERY_STEREOTYPE.search(source), path


def test_archunit_rule_and_compiled_fixture_contract_exist() -> None:
    source = ARCHUNIT_TEST.read_text(encoding="utf-8")
    seed_start = source.index("FORMAL_ROOT_SIMPLE_NAMES")
    seed_end = source.index(");", seed_start)
    seeds = set(re.findall(r'"([A-Za-z][A-Za-z0-9]+)"', source[seed_start:seed_end]))

    assert seeds == FORMAL_ROOT_NAMES
    assert "IntakeRoomActivities" not in seeds
    for required in (
        "ImportOption.DoNotIncludeTests.class",
        'packages = "com.example.dispute"',
        "getDirectDependenciesFromSelf()",
        'startsWith("JdbcIntakeFormal")',
        '"io.temporal.worker.Worker"',
        '"registerActivitiesImplementations"',
        "shortestFormalSinkChain",
        "isAssemblyRoot(neutralContract)",
        "isAssemblyRoot(comparisonAdapter)",
    ):
        assert required in source

    expected_fixtures = {
        "CrossFileFormalDelegate.java",
        "CrossFileFormalWrapper.java",
        "CrossFileWrapperAssembly.java",
        "FixtureFormalFactory.java",
        "LocalShadowingSafeRegistrar.java",
        "QualifiedCallAndMethodReferenceAssembly.java",
        "SafeComparisonActivities.java",
        "SafeComparisonAssembly.java",
        "StaticFieldAliasRegistrar.java",
        "StaticImportedFactoryBeanAssembly.java",
        "StaticWildcardNestedFactoryAssembly.java",
    }
    assert {path.name for path in FIXTURE_ROOT.glob("*.java")} == expected_fixtures

    fixture_source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(FIXTURE_ROOT.glob("*.java"))
    )
    for required in (
        "import static",
        ".Nested.*",
        "Object firstAlias",
        "var secondAlias",
        "FixtureFormalFactory::formalActivity",
        "worker.registerActivitiesImplementations",
        "implements IntakeRoomActivities",
        "Object FORMAL_ACTIVITY = safe",
    ):
        assert required in fixture_source


def test_gate_is_scheduled_in_batch_2_and_inherited_by_batch_3() -> None:
    batches = yaml.safe_load(PHASE4_BATCHES.read_text(encoding="utf-8"))["batches"]
    gate_path = "tests/static/test_phase4_no_formal_sink_assembly.py"
    archunit_class = "IntakeFormalSinkAssemblyTest"
    batch_2 = batches["P4-BATCH-2"]
    batch_3 = batches["P4-BATCH-3"]

    assert gate_path in batch_2["static_tests"]
    assert archunit_class in batch_2["java_test_classes"]

    java_source = next(
        source
        for source in batch_3["source_commands"]
        if source["id"] == "java_phase_4"
    )
    inherited_batches = java_source["inherits_java_test_classes_from"]
    assert inherited_batches == ["P4-BATCH-1", "P4-BATCH-2"]
    inherited_classes = {
        test_class
        for batch_name in inherited_batches
        for test_class in batches[batch_name]["java_test_classes"]
    }
    assert archunit_class in inherited_classes

    static_source = next(
        source
        for source in batch_3["source_commands"]
        if source["id"] == "static_phase_4"
    )
    assert gate_path in static_source["command"]
