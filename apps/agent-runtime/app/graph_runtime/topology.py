from __future__ import annotations

import re
from collections.abc import Callable, Mapping
from copy import deepcopy
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from app.contracts.v1.codec import canonicalize
from app.graph_runtime.state import CommonGraphState


_ROUTE = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


class GraphRouteError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class ClosedRouter:
    allowed_routes: Mapping[str, str]
    route_field: str = "route"

    def __post_init__(self) -> None:
        if not self.allowed_routes:
            raise GraphRouteError("router requires an explicit route table")
        for route, node in self.allowed_routes.items():
            if not _ROUTE.fullmatch(route) or not _ROUTE.fullmatch(node):
                raise GraphRouteError("router route and node names must be bounded identifiers")
        object.__setattr__(
            self,
            "allowed_routes",
            MappingProxyType(dict(sorted(self.allowed_routes.items()))),
        )

    def __call__(self, state: Mapping[str, Any]) -> str:
        route = state.get(self.route_field)
        if not isinstance(route, str) or route not in self.allowed_routes:
            raise GraphRouteError("unknown or missing graph route")
        return self.allowed_routes[route]


def bounded_sends(
    work_items: Mapping[str, Mapping[str, Any]],
    *,
    target_node: str,
    maximum: int = 8,
) -> list[Send]:
    if not _ROUTE.fullmatch(target_node):
        raise GraphRouteError("Send target must be a bounded node identifier")
    if maximum < 1 or maximum > 8:
        raise GraphRouteError("Send maximum must be between one and eight")
    if len(work_items) > maximum:
        raise GraphRouteError("room Send fan-out exceeds the configured maximum")
    if any(not _ROUTE.fullmatch(key) for key in work_items):
        raise GraphRouteError("Send work item keys must be bounded identifiers")
    for item in work_items.values():
        try:
            canonicalize(item)
        except (TypeError, ValueError) as error:
            raise GraphRouteError("Send work item must be canonical JSON") from error
    return [
        Send(
            target_node,
            {"work_item_key": key, "work_item": deepcopy(work_items[key])},
        )
        for key in sorted(work_items)
    ]


def build_shadow_kernel_graph(
    *,
    validate_command: Callable[[CommonGraphState], dict[str, Any]],
    execute_graph: Callable[[CommonGraphState], dict[str, Any]],
    project_result: Callable[[CommonGraphState], dict[str, Any]],
) -> StateGraph:
    """Build the fixed platform topology; the caller compiles it with a fenced saver."""

    builder = StateGraph(CommonGraphState)
    builder.add_node("validate_command", validate_command)
    builder.add_node("execute_graph", execute_graph)
    builder.add_node("project_result", project_result)
    builder.add_edge(START, "validate_command")
    builder.add_edge("validate_command", "execute_graph")
    builder.add_edge("execute_graph", "project_result")
    builder.add_edge("project_result", END)
    return builder
