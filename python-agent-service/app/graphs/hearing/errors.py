from app.graph_runtime.errors import GraphContractError


class HearingGraphContractError(GraphContractError):
    """A Hearing graph command or proposal violated the bounded graph contract."""


class HearingLcelContractError(ValueError):
    """A Hearing model path violated the governed LCEL contract."""
