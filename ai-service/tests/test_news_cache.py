"""
뉴스 분석 보관 검사 — `news_cache.py`.

`analyze()` 한 번이 실측 11~58초다. 거의 전부 구글 뉴스 RSS 요청이다. 화면을 볼 수
없는 사용자에게 그 시간은 멈춘 것과 구별되지 않고, 실제로 30초 시간 제한에 걸렸다.

여기서 지키는 것은 두 가지다 — 같은 것을 두 번 받지 않는다, 그리고 요청과 미리받기
일꾼이 같은 종목을 동시에 분석하지 않는다. 실제로 동시에 분석하다 500 이 났다.
"""

import sys
import threading
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import news_cache  # noqa: E402


class FakeNews:
    """진짜를 부르면 검사 한 번에 몇십 초가 든다. RSS 를 실제로 두드리기 때문이다."""

    def __init__(self, calls: list, delay: float = 0.0, result="기본값"):
        self.calls = calls
        self.delay = delay
        self.result = result

    def analyze(self, name, days=7):
        self.calls.append(name)
        time.sleep(self.delay)
        return {"corp": name, "score": 1.0} if self.result == "기본값" else self.result


@pytest.fixture
def calls() -> list:
    return []


@pytest.fixture
def cache(calls):
    return news_cache.NewsCache(FakeNews(calls))


def test_두_번째부터는_다시_받지_않는다(cache, calls):
    assert cache.digest("A전자")["corp"] == "A전자"
    assert cache.digest("A전자")["corp"] == "A전자"
    assert calls == ["A전자"]


def test_오래되면_다시_받는다(cache, calls, monkeypatch):    # 0 이 아니라 음수를 쓴다. 윈도우 시계는 눈금이 굵어 두 호출 사이 경과가 정확히
    # 0.0 으로 나오고, 그러면 "0.0 > 0" 이 거짓이라 오래된 것으로 치지 않는다.
    monkeypatch.setattr(news_cache, "_DIGEST_TTL_SECONDS", -1)

    cache.digest("A전자")
    cache.digest("A전자")
    assert len(calls) == 2


def test_기사가_없다는_사실도_들고_있는다(calls):
    """`None` 도 값이다. 매번 다시 받으면 기사 없는 종목이 제일 느려진다."""
    cache = news_cache.NewsCache(FakeNews(calls, result=None))

    assert cache.digest("A전자") is None
    assert cache.digest("A전자") is None
    assert calls == ["A전자"]


def test_동시에_물어도_한_번만_받는다(calls):
    """요청과 미리받기 일꾼이 같은 종목을 동시에 분석하다 실제로 500 이 났다."""
    cache = news_cache.NewsCache(FakeNews(calls, delay=0.3))

    threads = [threading.Thread(target=lambda: cache.digest("A전자")) for _ in range(5)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert calls == ["A전자"]


def test_다른_종목은_서로를_막지_않는다(calls):
    """한 종목이 느리다고 다른 종목까지 기다리면 관심 목록 전체가 멈춘다."""
    cache = news_cache.NewsCache(FakeNews(calls, delay=0.3))

    started = time.time()
    threads = [threading.Thread(target=lambda n=name: cache.digest(n))
               for name in ("A전자", "B화학", "C전기")]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert sorted(calls) == ["A전자", "B화학", "C전기"]
    assert time.time() - started < 0.9, "종목끼리 줄을 서고 있습니다."


def test_추적_목록은_덮어쓰지_않고_합친다(cache):
    """화면마다 아는 종목이 달라서, 덮어쓰면 마지막 화면의 종목만 남는다."""
    cache.track(["A전자"])
    result = cache.track(["B화학"])

    assert result["추적중"] == 2


def test_같은_종목을_두_번_넣지_않는다(cache):
    cache.track(["A전자"])
    assert cache.track(["A전자", "  "])["추가"] == 0
