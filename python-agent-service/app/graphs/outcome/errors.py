class OutcomeReviewContractError(ValueError):
    """The private review graph violated its frozen packet or authority contract."""


class OutcomeReviewLcelError(ValueError):
    """The review model path violated the governed LCEL contract."""
