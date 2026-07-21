from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass
from functools import cache
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
JAVA_MAIN = ROOT / "java-api-service/src/main/java"
PHASE4_BATCHES = ROOT / "plans/phase-4-intake-pilot-test-batches.yaml"
TEMPORAL_WORKER = (
    JAVA_MAIN
    / "com/example/dispute/workflow/config/TemporalWorkerConfiguration.java"
)
FORMAL_ACTIVITY_ADAPTER = (
    JAVA_MAIN
    / "com/example/dispute/workflow/activity/intake/IntakeRoomActivitiesAdapter.java"
)

# These are formal writer roots. IntakeRoomActivities is deliberately absent: a
# comparison-only SHADOW implementation of that Activity contract is legal.
FORMAL_WRITER_SEEDS = {
    "IntakeAgentRunDomainResultCommitter",
    "IntakeFormalBranchCommitPort",
    "IntakeFormalCommitPort",
    "IntakeGraphResultFinalizer",
    "IntakeTurnFinalizationPort",
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
TYPE_DECLARATION_KEYWORDS = {"class", "enum", "interface", "record"}


def _is_identifier_start(character: str) -> bool:
    return character.isalpha() or character in "_$"


def _is_identifier(token: str) -> bool:
    return bool(token) and _is_identifier_start(token[0]) and all(
        character.isalnum() or character in "_$" for character in token[1:]
    )


def _java_tokens(source: str) -> tuple[str, ...]:
    """Return Java structural tokens while excluding comments and literals."""
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
            while end < len(source) and (
                source[end].isalnum() or source[end] in "_$"
            ):
                end += 1
            tokens.append(source[index:end])
            index = end
            continue

        tokens.append(character)
        index += 1
    return tuple(tokens)


def _is_formal_writer_seed(name: str) -> bool:
    return name in FORMAL_WRITER_SEEDS or name.startswith("JdbcIntakeFormal")


@dataclass(frozen=True)
class JavaStructure:
    path: Path
    tokens: tuple[str, ...]

    @classmethod
    def parse(cls, path: Path) -> JavaStructure:
        return cls(path, _java_tokens(path.read_text(encoding="utf-8")))

    @classmethod
    def parse_source(cls, name: str, source: str) -> JavaStructure:
        return cls(Path(name), _java_tokens(source))

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

    def imported_type_names(self) -> set[str]:
        # A wildcard is not itself a dependency. A referenced seed from that package is
        # still present in the declaration/body tokens and is resolved by the graph.
        return {
            imported.rsplit(".", 1)[-1]
            for imported in self.imports()
            if not imported.endswith(".*")
        }

    def annotations(self) -> set[str]:
        annotations: set[str] = set()
        for index, token in enumerate(self.tokens[:-1]):
            if token != "@":
                continue
            cursor = index + 1
            names: list[str] = []
            while cursor < len(self.tokens):
                candidate = self.tokens[cursor]
                if _is_identifier(candidate):
                    names.append(candidate)
                    cursor += 1
                    if cursor < len(self.tokens) and self.tokens[cursor] == ".":
                        cursor += 1
                        continue
                break
            if names:
                annotations.add(names[-1])
        return annotations

    def code_tokens(self) -> tuple[str, ...]:
        code: list[str] = []
        cursor = 0
        while cursor < len(self.tokens):
            if self.tokens[cursor] in {"import", "package"}:
                while cursor < len(self.tokens) and self.tokens[cursor] != ";":
                    cursor += 1
                cursor += 1
                continue
            code.append(self.tokens[cursor])
            cursor += 1
        return tuple(code)

    def declared_types(self) -> dict[str, tuple[str, ...]]:
        declared: dict[str, tuple[str, ...]] = {}
        tokens = self.code_tokens()
        cursor = 0
        while cursor < len(tokens) - 1:
            token = tokens[cursor]
            if token not in TYPE_DECLARATION_KEYWORDS or not _is_identifier(
                tokens[cursor + 1]
            ):
                cursor += 1
                continue
            name = tokens[cursor + 1]
            body_start = cursor + 2
            while body_start < len(tokens) and tokens[body_start] != "{":
                body_start += 1
            if body_start == len(tokens):
                break
            depth = 1
            body_end = body_start + 1
            while body_end < len(tokens) and depth:
                if tokens[body_end] == "{":
                    depth += 1
                elif tokens[body_end] == "}":
                    depth -= 1
                body_end += 1
            declared[name] = tokens[cursor:body_end]
            cursor = body_end
        return declared

    def declared_type_names(self) -> set[str]:
        return set(self.declared_types())

    def constructed_types(self) -> set[str]:
        constructed: set[str] = set()
        for index, token in enumerate(self.tokens[:-1]):
            if token != "new":
                continue
            cursor = index + 1
            names: list[str] = []
            while cursor < len(self.tokens):
                candidate = self.tokens[cursor]
                if _is_identifier(candidate):
                    names.append(candidate)
                    cursor += 1
                    if cursor < len(self.tokens) and self.tokens[cursor] == ".":
                        cursor += 1
                        continue
                break
            if names:
                constructed.add(names[-1])
        return constructed

    def object_provider_targets(self, known_types: set[str]) -> set[str]:
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
                elif candidate in known_types:
                    targets.add(candidate)
                cursor += 1
        return targets

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

    def symbol_type_candidates(self, known_types: set[str]) -> dict[str, set[str]]:
        candidates: dict[str, set[str]] = defaultdict(set)
        aliases: dict[str, set[str]] = defaultdict(set)

        # Fields, parameters, locals, and method return types all expose a declared
        # type immediately before their symbol.
        for index, token in enumerate(self.tokens[:-1]):
            next_token = self.tokens[index + 1]
            if token in known_types and _is_identifier(next_token):
                candidates[next_token].add(token)

        # Preserve concrete RHS candidates through Object/interface/var aliases.
        for index, token in enumerate(self.tokens):
            if token != "=" or index == 0:
                continue
            left = self.tokens[index - 1]
            if not _is_identifier(left):
                continue
            cursor = index + 1
            depth = 0
            while cursor < len(self.tokens):
                candidate = self.tokens[cursor]
                if candidate in "([{":
                    depth += 1
                elif candidate in ")]}" and depth:
                    depth -= 1
                elif depth == 0 and candidate in {",", ";"}:
                    break
                if candidate in known_types:
                    candidates[left].add(candidate)
                elif _is_identifier(candidate):
                    aliases[left].add(candidate)
                cursor += 1

        changed = True
        while changed:
            changed = False
            for symbol, referenced_symbols in aliases.items():
                resolved = set().union(
                    *(candidates.get(name, set()) for name in referenced_symbols)
                )
                if not resolved <= candidates[symbol]:
                    candidates[symbol].update(resolved)
                    changed = True
        return candidates

    def registered_dependency_types(self, known_types: set[str]) -> set[str]:
        symbol_types = self.symbol_type_candidates(known_types)
        registered: set[str] = set()
        for arguments in self.call_arguments("registerActivitiesImplementations"):
            for token in arguments:
                if token in known_types:
                    registered.add(token)
                if _is_identifier(token):
                    registered.update(symbol_types.get(token, set()))
        return registered


@dataclass(frozen=True)
class TypeDependencyGraph:
    dependencies: dict[str, frozenset[str]]
    formal_types: frozenset[str]

    @property
    def known_types(self) -> set[str]:
        return set(self.dependencies) | FORMAL_WRITER_SEEDS

    @classmethod
    def build(cls, units: tuple[JavaStructure, ...]) -> TypeDependencyGraph:
        declared = {
            name for unit in units for name in unit.declared_type_names()
        }
        known = declared | FORMAL_WRITER_SEEDS
        dependencies: dict[str, set[str]] = defaultdict(set)
        for unit in units:
            for declared_type, declaration in unit.declared_types().items():
                referenced = {token for token in declaration if token in known}
                dependencies[declared_type].update(referenced - {declared_type})
        frozen_dependencies = {
            name: frozenset(dependency_names)
            for name, dependency_names in dependencies.items()
        }
        formal_types = {name for name in known if _is_formal_writer_seed(name)}
        changed = True
        while changed:
            additions = {
                name
                for name, dependency_names in frozen_dependencies.items()
                if dependency_names & formal_types
            } - formal_types
            changed = bool(additions)
            formal_types.update(additions)
        return cls(frozen_dependencies, frozenset(formal_types))

    def formal_writer_chain(self, type_name: str) -> tuple[str, ...] | None:
        if type_name not in self.formal_types:
            return None
        pending = deque([(type_name, (type_name,))])
        while pending:
            current, chain = pending.popleft()
            if _is_formal_writer_seed(current):
                return chain
            pending.extend(
                (dependency, (*chain, dependency))
                for dependency in sorted(self.dependencies.get(current, ()))
                if dependency in self.formal_types and dependency not in chain
            )
        return None

    def formal_writer_chains(
        self, type_names: set[str]
    ) -> set[tuple[str, ...]]:
        return {
            chain
            for type_name in type_names
            if (chain := self.formal_writer_chain(type_name)) is not None
        }


@cache
def _production_units() -> tuple[JavaStructure, ...]:
    # Test sources are intentionally excluded: focused tests may assemble formal ports.
    return tuple(
        JavaStructure.parse(path) for path in sorted(JAVA_MAIN.rglob("*.java"))
    )


@cache
def _production_graph() -> TypeDependencyGraph:
    return TypeDependencyGraph.build(_production_units())


def _spring_assembly_violations(
    units: tuple[JavaStructure, ...], graph: TypeDependencyGraph
) -> dict[Path, set[tuple[str, ...]]]:
    violations: dict[Path, set[tuple[str, ...]]] = {}
    for unit in units:
        if not (unit.annotations() & SPRING_ASSEMBLY_ANNOTATIONS):
            continue
        dependency_types = (
            unit.declared_type_names()
            | unit.imported_type_names()
            | unit.object_provider_targets(graph.known_types)
            | unit.constructed_types()
        )
        chains = graph.formal_writer_chains(dependency_types)
        if chains:
            violations[unit.path] = chains
    return violations


def _registration_violations(
    units: tuple[JavaStructure, ...], graph: TypeDependencyGraph
) -> dict[Path, set[tuple[str, ...]]]:
    violations: dict[Path, set[tuple[str, ...]]] = {}
    for unit in units:
        registered = unit.registered_dependency_types(graph.known_types)
        chains = graph.formal_writer_chains(registered)
        if chains:
            violations[unit.path] = chains
    return violations


def _format_violations(violations: dict[Path, set[tuple[str, ...]]]) -> str:
    lines: list[str] = []
    for path, chains in sorted(violations.items(), key=lambda item: str(item[0])):
        try:
            label = path.relative_to(ROOT)
        except ValueError:
            label = path
        rendered = ", ".join(" -> ".join(chain) for chain in sorted(chains))
        lines.append(f"{label}: {rendered}")
    return "\n".join(lines)


def test_formal_writer_seed_set_and_neutral_activity_contract() -> None:
    assert {
        "IntakeAgentRunDomainResultCommitter",
        "IntakeFormalBranchCommitPort",
        "IntakeFormalCommitPort",
        "IntakeGraphResultFinalizer",
        "IntakeTurnFinalizationPort",
    } <= FORMAL_WRITER_SEEDS
    assert "IntakeRoomActivities" not in FORMAL_WRITER_SEEDS
    assert _is_formal_writer_seed("JdbcIntakeFormalCommitPort")

    graph = _production_graph()
    assert graph.formal_writer_chain("IntakeRoomActivities") is None
    for formal_type in (
        "IntakeAgentRunDomainResultCommitter",
        "IntakeFormalBranchCommitPort",
        "IntakeFormalCommitPort",
        "IntakeGraphResultFinalizer",
        "IntakeRoomActivitiesAdapter",
        "IntakeTurnFinalizationPort",
        "JdbcIntakeFormalBranchCommitPort",
        "JdbcIntakeFormalCommitPort",
    ):
        assert graph.formal_writer_chain(formal_type), formal_type


def test_gate_is_scheduled_for_batch_2_and_the_accepted_candidate() -> None:
    matrix = yaml.safe_load(PHASE4_BATCHES.read_text(encoding="utf-8"))
    batches = matrix["batches"]
    gate_path = "tests/static/test_phase4_no_formal_sink_assembly.py"

    assert gate_path in batches["P4-BATCH-2"]["static_tests"]
    static_source = next(
        source
        for source in batches["P4-BATCH-3"]["source_commands"]
        if source["id"] == "static_phase_4"
    )
    assert gate_path in static_source["command"]


def test_dependency_graph_rejects_alias_wrapper_and_delegation_routes() -> None:
    units = (
        JavaStructure.parse_source(
            "FormalAlias.java",
            "interface FormalAlias extends IntakeFormalCommitPort {}",
        ),
        JavaStructure.parse_source(
            "FormalDelegate.java",
            """
            final class FormalDelegate {
                private final FormalAlias writer;
            }
            """,
        ),
        JavaStructure.parse_source(
            "FormalWrapper.java",
            """
            final class FormalWrapper implements IntakeRoomActivities {
                private final FormalDelegate delegate;
            }
            """,
        ),
        JavaStructure.parse_source(
            "AdversarialAssembly.java",
            """
            @Configuration
            final class AdversarialAssembly {
                @Bean
                FormalWrapper activity(FormalDelegate delegate) {
                    return new FormalWrapper(delegate);
                }

                void register(FormalWrapper wrapper) {
                    Object firstAlias = wrapper;
                    var secondAlias = firstAlias;
                    worker.registerActivitiesImplementations(secondAlias);
                }
            }
            """,
        ),
    )
    graph = TypeDependencyGraph.build(units)

    expected_chain = (
        "FormalWrapper",
        "FormalDelegate",
        "FormalAlias",
        "IntakeFormalCommitPort",
    )
    assert graph.formal_writer_chain("FormalWrapper") == expected_chain
    registration = _registration_violations(units, graph)
    assert expected_chain in registration[Path("AdversarialAssembly.java")]
    assert Path("AdversarialAssembly.java") in _spring_assembly_violations(
        units, graph
    )


def test_comparison_only_shadow_activity_is_allowed() -> None:
    units = (
        JavaStructure.parse_source(
            "IntakeRoomActivities.java",
            "interface IntakeRoomActivities {}",
        ),
        JavaStructure.parse_source(
            "ComparisonSink.java",
            "interface ComparisonSink { void append(Object comparison); }",
        ),
        JavaStructure.parse_source(
            "SafeShadowActivities.java",
            """
            final class SafeShadowActivities implements IntakeRoomActivities {
                private final ComparisonSink comparisons;
            }
            """,
        ),
        JavaStructure.parse_source(
            "SafeShadowAssembly.java",
            """
            import com.example.dispute.room.infrastructure.persistence.*;

            @Configuration
            final class SafeShadowAssembly {
                @Bean
                IntakeRoomActivities activity(ComparisonSink comparisons) {
                    return new SafeShadowActivities(comparisons);
                }

                void register(SafeShadowActivities safe) {
                    IntakeRoomActivities contract = safe;
                    Object alias = contract;
                    worker.registerActivitiesImplementations(alias);
                }
            }
            """,
        ),
    )
    graph = TypeDependencyGraph.build(units)

    assert graph.formal_writer_chain("IntakeRoomActivities") is None
    assert graph.formal_writer_chain("SafeShadowActivities") is None
    assert not _registration_violations(units, graph)
    assert not _spring_assembly_violations(units, graph)


def test_unrelated_same_file_formal_helper_does_not_taint_registration() -> None:
    unit = JavaStructure.parse_source(
        "MixedHelpers.java",
        """
        final class SafeComparisonActivities implements IntakeRoomActivities {
            private final ComparisonSink comparisons;
        }

        final class UnrelatedFormalHelper {
            private final IntakeFormalCommitPort writer;
        }

        final class SafeRegistrar {
            void register(SafeComparisonActivities safe) {
                worker.registerActivitiesImplementations(safe);
            }
        }
        """,
    )
    graph = TypeDependencyGraph.build((unit,))

    assert graph.formal_writer_chain("UnrelatedFormalHelper") == (
        "UnrelatedFormalHelper",
        "IntakeFormalCommitPort",
    )
    assert graph.formal_writer_chain("SafeComparisonActivities") is None
    assert not _registration_violations((unit,), graph)


def test_temporal_worker_has_no_formal_writer_dependency() -> None:
    worker = JavaStructure.parse(TEMPORAL_WORKER)
    graph = _production_graph()
    dependency_types = (
        worker.declared_type_names()
        | worker.imported_type_names()
        | worker.object_provider_targets(graph.known_types)
        | worker.constructed_types()
    )

    chains = graph.formal_writer_chains(dependency_types)
    assert not chains, _format_violations({worker.path: chains})


def test_spring_assembly_has_no_formal_writer_dependency() -> None:
    violations = _spring_assembly_violations(
        _production_units(), _production_graph()
    )
    assert not violations, _format_violations(violations)


def test_formal_intake_activity_adapter_has_no_discovery_stereotype() -> None:
    adapter = JavaStructure.parse(FORMAL_ACTIVITY_ADAPTER)
    assert not (adapter.annotations() & SPRING_ASSEMBLY_ANNOTATIONS)


def test_registered_production_activities_do_not_reach_formal_writers() -> None:
    violations = _registration_violations(
        _production_units(), _production_graph()
    )
    assert not violations, _format_violations(violations)
