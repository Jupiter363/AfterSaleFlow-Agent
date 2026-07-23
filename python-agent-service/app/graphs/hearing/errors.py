class HearingGraphContractError(ValueError):
    """A Hearing graph command or proposal violated the bounded graph contract."""


class HearingLcelContractError(ValueError):
    """A Hearing model path violated the governed LCEL contract."""
