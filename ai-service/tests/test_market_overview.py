import datetime as dt
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import market_overview as M  # noqa: E402


@pytest.fixture(autouse=True)
def isolated_cache(monkeypatch, tmp_path):
    monkeypatch.setattr(M, "CACHE_FILE", tmp_path / "market-overview.json")
    M._cached = None
    M._cached_at = 0.0
    M._cached_slot = None


def _item(code: str, name: str, group: str) -> dict:
    return {
        "code": code,
        "name": name,
        "group": group,
        "value": 100.0,
        "change_percent": 1.0,
        "as_of": "2026-08-24",
    }


def test_successful_overview_is_fetched_only_once_per_day(monkeypatch):
    now = dt.datetime(2026, 8, 24, 16, 30, tzinfo=M.KST)
    calls = []
    monkeypatch.setattr(M, "_now", lambda: now)
    monkeypatch.setattr(M, "_read_one", lambda *args: calls.append(args) or _item(*args))

    first = M.overview()
    second = M.overview()

    assert second == first
    assert len(calls) == len(M.SYMBOLS)
    assert M.CACHE_FILE.is_file()


def test_daily_cache_survives_ai_service_restart(monkeypatch):
    now = dt.datetime(2026, 8, 24, 16, 30, tzinfo=M.KST)
    monkeypatch.setattr(M, "_now", lambda: now)
    monkeypatch.setattr(M, "_read_one", lambda *args: _item(*args))
    expected = M.overview()

    M._cached = None
    M._cached_at = 0.0
    M._cached_slot = None
    monkeypatch.setattr(M, "_read_one", lambda *args: pytest.fail("외부 조회를 다시 하면 안 됩니다"))

    assert M.overview() == expected


def test_next_day_refreshes_and_replaces_the_cache(monkeypatch):
    current = [dt.datetime(2026, 8, 24, 16, 30, tzinfo=M.KST)]
    calls = []
    monkeypatch.setattr(M, "_now", lambda: current[0])
    monkeypatch.setattr(M, "_read_one", lambda *args: calls.append(args) or _item(*args))

    M.overview()
    current[0] = dt.datetime(2026, 8, 25, 16, 30, tzinfo=M.KST)
    M.overview()

    assert len(calls) == len(M.SYMBOLS) * 2
    assert '"cached_on": "2026-08-25"' in M.CACHE_FILE.read_text(encoding="utf-8")


def test_pre_close_cache_refreshes_once_after_market_close(monkeypatch):
    current = [dt.datetime(2026, 8, 25, 9, 0, tzinfo=M.KST)]
    calls = []
    monkeypatch.setattr(M, "_now", lambda: current[0])
    monkeypatch.setattr(M, "_read_one", lambda *args: calls.append(args) or _item(*args))

    M.overview()
    M.overview()
    current[0] = dt.datetime(2026, 8, 25, 16, 30, tzinfo=M.KST)
    M.overview()
    M.overview()

    assert len(calls) == len(M.SYMBOLS) * 2
    assert '"refresh_slot": "2026-08-25"' in M.CACHE_FILE.read_text(encoding="utf-8")
