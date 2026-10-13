from enum import StrEnum


class SourceUpstreamReason(StrEnum):
    RATE_LIMITED = "RATE_LIMITED"
    UNAVAILABLE = "UNAVAILABLE"
    NETWORK = "NETWORK"
    TIMEOUT = "TIMEOUT"


class SourceUpstreamTransientError(Exception):
    """A bounded, safe category originating at an official Source HTTP operation."""

    def __init__(self, reason: SourceUpstreamReason) -> None:
        self.reason = SourceUpstreamReason(reason)
        super().__init__(f"Official arXiv Source upstream transient: {self.reason.value}")
