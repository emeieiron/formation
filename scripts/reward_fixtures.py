import json
import os
import time
from dataclasses import dataclass
from pathlib import Path

SKR = 1_000_000


@dataclass(frozen=True)
class RewardFixture:
    challenge: str
    code: int
    amount: int
    players: int = 2
    owner_bps: int = 5_000
    difficulty: int = 0
    days: int = 1
    title: str = ""


def load_rewards():
    path = os.environ.get("FORMATION_REWARDS")
    if not path:
        return []
    data = json.loads(Path(path).read_text())
    if not isinstance(data, list):
        raise ValueError("Reward fixtures must be a JSON list")
    fixtures = []
    codes, ids = {}, {}
    for index, value in enumerate(data):
        try:
            fixture = RewardFixture(**value)
            bounds = {
                "code": (6, 65535), "amount": (1, (2**64 - 1) // SKR),
                "players": (2, 32), "owner_bps": (0, 9999), "difficulty": (0, 3),
                "days": (1, (2**63 - 1 - int(time.time() * 1000)) // 86_400_000),
            }
            for name, (low, high) in bounds.items():
                number = getattr(fixture, name)
                if type(number) is not int or not low <= number <= high:
                    raise ValueError(f"{name} must be an integer within {low}..{high}")
            if not isinstance(fixture.challenge, str) or not fixture.challenge.strip():
                raise ValueError("challenge must be a game ID")
            if fixture.challenge in {"rally", "circuit", "sync", "formation", "rush"}:
                raise ValueError("challenge uses a retired game ID")
            if not isinstance(fixture.title, str) or len(fixture.title.encode()) > 32:
                raise ValueError("title must fit 32 UTF-8 bytes")
            if codes.get(fixture.code, fixture.challenge) != fixture.challenge or ids.get(fixture.challenge, fixture.code) != fixture.code:
                raise ValueError("game IDs and codes must have a consistent one-to-one mapping")
            codes[fixture.code], ids[fixture.challenge] = fixture.challenge, fixture.code
            fixtures.append(fixture)
        except (TypeError, ValueError) as error:
            raise ValueError(f"Reward fixture {index + 1}: {error}") from error
    if sum(f.amount for f in fixtures) * SKR > 2**64 - 1:
        raise ValueError("Total fixture funding exceeds the token supply limit")
    return fixtures
