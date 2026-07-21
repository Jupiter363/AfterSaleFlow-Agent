from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA_MAIN = ROOT / "java-api-service/src/main/java"
TEMPORAL_WORKER = (
    JAVA_MAIN
    / "com/example/dispute/workflow/config/TemporalWorkerConfiguration.java"
)
FORMAL_ACTIVITY_ADAPTER = (
    JAVA_MAIN
    / "com/example/dispute/workflow/activity/intake/IntakeRoomActivitiesAdapter.java"
)

FORMAL_SINK_TYPES = {
    "IntakeRoomActivities",
    "IntakeRoomActivitiesAdapter",
    "IntakeTurnFinalizationPort",
    "IntakeFormalBranchCommitPort",
}
FORMAL_SINK_PACKAGE_PREFIXES = {
    "com.example.dispute.workflow.activity.intake",
    "com.example.dispute.workflow.application.intake",
    "com.example.dispute.workflow.temporal.room.intake",
    "com.example.dispute.room.infrastructure.persistence",
}
SPRING_ASSEMBLY_ANNOTATIONS = {
    "AutoConfiguration",
    "Bean",
    "Component",
    "ComponentScan",
    "Configuration",
    "ConfigurationProperties",
    "Controller",
    "ControllerAdvice",
    "EnableAutoConfiguration",
    "EnableConfigurationProperties",
    "Import",
    "ImportAutoConfiguration",
    "ManagedBean",
    "Named",
    "Repository",
    "RestController",
    "RestControllerAdvice",
    "Service",
    "Singleton",
    "SpringBootApplication",
}


def _is_identifier_start(character: str) -> bool:
    return character.isalpha() or character in "_$"


def _is_identifier_part(character: str) -> bool:
    return character.isalnum() or character in "_$"


def _java_tokens(source: str) -> tuple[str, ...]:
    """Return structural Java tokens, excluding comments and string/char literals."""
    tokens: list[str] = []
    index = 0
    while index < len(source):
        if source.startswith("//", index):
            newline = source.find("\n", index + 2)
            index = len(source) if newline < 0 else newline + 1
            continue
        if source.startswith("/*", index):
            close = source.find("*/", index + 2)
            index = len(source) if close < 0 else close + 2
            continue
        if source.startswith('\"\"\"', index):
            close = source.find('\"\"\"', index + 3)
            index = len(source) if close < 0 else close + 3
            continue

        character = source[index]
        if character in "\"'":
            delimiter = character
            index += 1
            while index < len(source):
                if source[index] == "\\":
                    index += 2
                elif source[index] == delimiter:
                    index += 1
                    break
                else:
                    index += 1
            continue
        if character.isspace():
            index += 1
            continue
        if _is_identifier_start(character):
            end = index + 1
            while end < len(source) and _is_identifier_part(source[end]):
                end += 1
            tokens.append(source[index:end])
            index = end
            continue

        tokens.append(character)
        index += 1
    return tuple(tokens)


def _is_formal_sink_type(name: str) -> bool:
    return name in FORMAL_SINK_TYPES or name.startswith("JdbcIntakeFormal")


@dataclass(frozen=True)
class JavaStructure:
    path: Path
    tokens: tuple[str, ...]

    @classmethod
    def parse(cls, path: Path) -> JavaStructure:
        return cls(path, _java_tokens(path.read_text(encoding="utf-8")))

    def imports(self) -> set[str]:
        imports: set[str] = set()
        for index, token in enumerate(self.tokens):
            if token != "import":
                continue
            cursor = index + 1
            if cursor < len(self.tokens) and self.tokens[cursor] == "static":
                cursor += 1
            parts: list[str] = []
            while cursor < len(self.tokens) and self.tokens[cursor] != ";":
                parts.append(self.tokens[cursor])
                cursor += 1
            imports.add("".join(parts))
        return imports

    def annotations(self) -> set[str]:
        annotations: set[str] = set()
        for index, token in enumerate(self.tokens[:-1]):
            if token != "@":
                continue
            cursor = index + 1
            names: list[str] = []
            while cursor < len(self.tokens):
                candidate = self.tokens[cursor]
                if _is_identifier_start(candidate[0]):
                    names.append(candidate)
                    cursor += 1
                    if cursor < len(self.tokens) and self.tokens[cursor] == ".":
                        cursor += 1
                        continue
                break
            if names:
                annotations.add(names[-1])
        return annotations

    def constructed_types(self) -> set[str]:
        constructed: set[str] = set()
        for index, token in enumerate(self.tokens[:-1]):
            if token != "new":
                continue
            cursor = index + 1
            names: list[str] = []
            while cursor < len(self.tokens):
                candidate = self.tokens[cursor]
                if _is_identifier_start(candidate[0]):
                    names.append(candidate)
                    cursor += 1
                    if cursor < len(self.tokens) and self.tokens[cursor] == ".":
                        cursor += 1
                        continue
                break
            if names:
                constructed.add(names[-1])
        return constructed

    def object_provider_targets(self) -> set[str]:
        targets: set[str] = set()
        for index, token in enumerate(self.tokens[:-1]):
            if token != "ObjectProvider" or self.tokens[index + 1] != "<":
                continue
            depth = 0
            cursor = index + 1
            while cursor < len(self.tokens):
                candidate = self.tokens[cursor]
                if candidate == "<":
                    depth += 1
                elif candidate == ">":
                    depth -= 1
                    if depth == 0:
                        break
                elif _is_identifier_start(candidate[0]) and _is_formal_sink_type(
                    candidate
                ):
                    targets.add(candidate)
                cursor += 1
        return targets

    def referenced_formal_sink_types(self) -> set[str]:
        return {
            token
            for token in self.tokens
            if _is_identifier_start(token[0]) and _is_formal_sink_type(token)
        }

    def call_arguments(self, method_name: str) -> list[tuple[str, ...]]:
        calls: list[tuple[str, ...]] = []
        for index, token in enumerate(self.tokens[:-1]):
            if token != method_name or self.tokens[index + 1] != "(":
                continue
            depth = 0
            cursor = index + 1
            arguments: list[str] = []
            while cursor < len(self.tokens):
                candidate = self.tokens[cursor]
                if candidate == "(":
                    depth += 1
                    if depth > 1:
                        arguments.append(candidate)
                elif candidate == ")":
                    depth -= 1
                    if depth == 0:
                        calls.append(tuple(arguments))
                        break
                    arguments.append(candidate)
                else:
                    arguments.append(candidate)
                cursor += 1
        return calls


def _production_units() -> list[JavaStructure]:
    # Test sources are intentionally excluded: focused tests may assemble formal ports
    # directly.
    return [JavaStructure.parse(path) for path in sorted(JAVA_MAIN.rglob("*.java"))]


def _forbidden_imports(unit: JavaStructure) -> set[str]:
    forbidden: set[str] = set()
    for imported in unit.imports():
        simple_name = imported.rsplit(".", 1)[-1]
        package = imported.removesuffix(".*")
        if _is_formal_sink_type(simple_name):
            forbidden.add(imported)
        elif imported.endswith(".*") and package in FORMAL_SINK_PACKAGE_PREFIXES:
            forbidden.add(imported)
    return forbidden


def _format_violations(violations: dict[Path, set[str]]) -> str:
    return "\n".join(
        f"{path.relative_to(ROOT)}: {', '.join(sorted(symbols))}"
        for path, symbols in sorted(violations.items())
    )


def test_structured_java_model_detects_formal_sink_assembly_routes() -> None:
    unit = JavaStructure(
        Path("SyntheticAssembly.java"),
        _java_tokens(
            """
            import com.example.dispute.room.infrastructure.persistence.*;

            @Configuration
            final class SyntheticAssembly {
                Object assemble(
                        IntakeFormalBranchCommitPort injected,
                        ObjectProvider<IntakeTurnFinalizationPort> provider) {
                    IntakeRoomActivities activity = new IntakeRoomActivitiesAdapter(
                            null, null, provider.getIfUnique(), injected);
                    worker.registerActivitiesImplementations(activity);
                    return new JdbcIntakeFormalCommitPort();
                }
            }
            """
        ),
    )

    assert unit.annotations() == {"Configuration"}
    assert _forbidden_imports(unit) == {
        "com.example.dispute.room.infrastructure.persistence.*"
    }
    assert unit.constructed_types() == {
        "IntakeRoomActivitiesAdapter",
        "JdbcIntakeFormalCommitPort",
    }
    assert unit.object_provider_targets() == {"IntakeTurnFinalizationPort"}
    assert unit.referenced_formal_sink_types() == {
        "IntakeFormalBranchCommitPort",
        "IntakeRoomActivities",
        "IntakeRoomActivitiesAdapter",
        "IntakeTurnFinalizationPort",
        "JdbcIntakeFormalCommitPort",
    }
    assert len(unit.call_arguments("registerActivitiesImplementations")) == 1

    ignored = JavaStructure(
        Path("IgnoredText.java"),
        _java_tokens(
            """
            // new IntakeRoomActivitiesAdapter()
            class IgnoredText {
                String example = "ObjectProvider<IntakeTurnFinalizationPort>";
            }
            """
        ),
    )
    assert not ignored.referenced_formal_sink_types()


def test_temporal_worker_cannot_resolve_or_construct_a_formal_intake_sink() -> None:
    worker = JavaStructure.parse(TEMPORAL_WORKER)

    assert not _forbidden_imports(worker)
    assert not {
        name for name in worker.constructed_types() if _is_formal_sink_type(name)
    }
    assert not worker.object_provider_targets()
    # Rejecting every formal type reference also covers fields, parameters, and
    # @Bean methods.
    assert not worker.referenced_formal_sink_types()


def test_spring_assembly_has_no_formal_intake_sink_dependency() -> None:
    assembly_units = [
        unit
        for unit in _production_units()
        if unit.annotations() & SPRING_ASSEMBLY_ANNOTATIONS
    ]
    assert TEMPORAL_WORKER in {unit.path for unit in assembly_units}

    violations: dict[Path, set[str]] = {}
    for unit in assembly_units:
        symbols = (
            unit.referenced_formal_sink_types()
            | _forbidden_imports(unit)
            | unit.object_provider_targets()
        )
        symbols |= {
            name for name in unit.constructed_types() if _is_formal_sink_type(name)
        }
        if symbols:
            violations[unit.path] = symbols

    assert not violations, _format_violations(violations)


def test_formal_intake_activity_adapter_has_no_discovery_stereotype() -> None:
    adapter = JavaStructure.parse(FORMAL_ACTIVITY_ADAPTER)
    assert not (adapter.annotations() & SPRING_ASSEMBLY_ANNOTATIONS)


def test_production_activity_registration_excludes_formal_intake_activity() -> None:
    violations: dict[Path, set[str]] = {}
    for unit in _production_units():
        registrations = unit.call_arguments("registerActivitiesImplementations")
        if not registrations:
            continue
        referenced = unit.referenced_formal_sink_types()
        for arguments in registrations:
            referenced |= {
                token
                for token in arguments
                if _is_identifier_start(token[0]) and _is_formal_sink_type(token)
            }
        if referenced:
            violations[unit.path] = referenced

    assert not violations, _format_violations(violations)
