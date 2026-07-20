from __future__ import annotations


class IntakeGraphContractError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code
