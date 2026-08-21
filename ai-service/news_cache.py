"""
뉴스 미리받기와 아카이브 적립.

왜 미리 받나
------------
`analyze()` 한 번이 실측 11~40초다. 거의 전부 구글 뉴스 RSS 요청이고 계산은
밀리초다. 즉 느린 이유는 모델이 아니라 화면이 남의 서버를 기다리는 것이다.
30초 시간 제한에 실제로 걸렸다.

화면을 볼 수 없는 사용자에게 11초는 멈춘 것과 구별되지 않는다. 그래서 결과를
들고 있다가 그대로 내준다. 처음 한 번만 느리고, 미리받기가 돌고 나면 그 한 번도
없다.

왜 매일 쌓나
------------
구글 뉴스 RSS 는 **최근 7일까지만** 준다. 오늘 안 받으면 그날치는 영영 없다.
`data/news_archive.parquet` 이 그 유일한 우회다. 아카이브가 비면 뉴스 피처가
중립 0.5 로 채워지면서, 오류 하나 없이 예측만 조금씩 무뎌진다.

두 가지를 한 일꾼이 한다. 어차피 같은 RSS 를 부르기 때문이다. 따로 돌리면 같은
기사를 두 번 받는다.
"""

from __future__ import annotations

import logging
import threading
import time
from datetime import date

LOG = logging.getLogger("ai-service.news")

# RSS 한 번에 종목 하나씩 요청이 나간다. 붙여 쏘면 구글이 막는다.
_REQUEST_GAP_SECONDS = 1.5
# 하루 한 번이면 충분하다. RSS 가 7일치를 주므로 몇 시간 늦어도 잃는 것이 없다.
_SWEEP_INTERVAL_SECONDS = 6 * 60 * 60
# 들고 있는 분석을 얼마나 믿을지. 장중에도 한 종목에 10분 안에 새 기사가 여러 건
# 쏟아지는 일은 드물고, 그보다 짧게 잡으면 화면을 열 때마다 11초를 다시 기다린다.
_DIGEST_TTL_SECONDS = 10 * 60


#: 들고 있는 것이 없다는 표시. `None` 은 "기사가 없다" 라는 뜻이라 그 자리에 쓸 수 없다.
_MISSING = object()


class NewsCache:
    """
    관심 종목의 뉴스를 미리 받아 아카이브에 쌓는다.

    앱이 종목 목록을 알려 준다. 서비스는 사용자가 무엇을 들고 있는지 모르고, 6,228
    종목을 전부 받는 것은 구글에도 우리에게도 무리다.
    """

    def __init__(self, news_module=None) -> None:
        # 뉴스 모듈을 밖에서 넣을 수 있게 둔다. 안 넣으면 처음 쓸 때 진짜를 불러온다.
        # 기동할 때 바로 부르지 않는 이유는 무거워서다 — 서버가 뜨는 데 그만큼 늦어진다.
        self._news = news_module
        self._lock = threading.Lock()
        self._names: list[str] = []
        self._swept_on: date | None = None
        self._last_result: dict = {"상태": "아직 돌지 않음"}
        self._thread: threading.Thread | None = None
        # 종목명 -> (받은 시각, 분석 결과). 같은 종목을 두 화면이 함께 물어도 한 번만 받는다.
        self._digests: dict[str, tuple[float, dict | None]] = {}
        # 종목별 자물쇠. 없으면 두 요청이 같은 종목을 동시에 받아 RSS 를 두 번 두드린다.
        self._fetching: dict[str, threading.Lock] = {}

    def digest(self, name: str) -> dict | None:
        """
        한 종목의 뉴스 분석. 들고 있는 것이 아직 쓸 만하면 그대로 준다.

        `None` 도 값이다 — 기사가 없다는 뜻이라 그대로 들고 있는다. 매번 다시 받으면
        기사 없는 종목이 제일 느려진다.
        """
        fresh = self._cached(name)
        if fresh is not _MISSING:
            return fresh

        # 같은 종목을 동시에 물으면 한쪽만 받아 오고 나머지는 그 결과를 쓴다. 자물쇠가
        # 없으면 요청과 미리받기 일꾼이 같은 종목을 동시에 분석하다 서로 밟는다.
        with self._gate(name):
            fresh = self._cached(name)
            if fresh is not _MISSING:
                return fresh
            return self._analyze(name)

    def _news_api(self):
        if self._news is None:
            from accessible_investor import news as N

            self._news = N
        return self._news

    def _gate(self, name: str) -> threading.Lock:
        with self._lock:
            return self._fetching.setdefault(name, threading.Lock())

    def _analyze(self, name: str) -> dict | None:
        """한 종목을 실제로 분석해 들고 있는다. 반드시 그 종목 자물쇠 안에서 부른다."""
        result = self._news_api().analyze(name, days=7)
        with self._lock:
            self._digests[name] = (time.time(), result)
        return result

    def _cached(self, name: str):
        with self._lock:
            entry = self._digests.get(name)
        if entry is None or time.time() - entry[0] > _DIGEST_TTL_SECONDS:
            return _MISSING
        return entry[1]

    def track(self, names: list[str]) -> dict:
        """
        미리 받을 종목을 알려 준다.

        목록을 덮어쓰지 않고 합친다. 화면마다 아는 종목이 달라서, 덮어쓰면 마지막에
        말한 화면의 종목만 남는다.
        """
        added = 0
        with self._lock:
            for name in names:
                cleaned = (name or "").strip()
                if cleaned and cleaned not in self._names:
                    self._names.append(cleaned)
                    added += 1
            tracked = len(self._names)
        self._ensure_worker()
        return {"추가": added, "추적중": tracked}

    def status(self) -> dict:
        with self._lock:
            return {
                "추적중": len(self._names),
                "마지막수집일": str(self._swept_on) if self._swept_on else None,
                "마지막결과": dict(self._last_result),
            }

    def _ensure_worker(self) -> None:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            # 데몬으로 둔다. 서버를 끌 때 뉴스 수집이 종료를 붙잡으면 안 된다.
            self._thread = threading.Thread(target=self._run, name="news-cache", daemon=True)
            self._thread.start()

    def _run(self) -> None:
        while True:
            try:
                self._sweep_once()
            except Exception as error:  # 일꾼이 죽으면 다시 살아나지 않는다.
                LOG.warning("뉴스 수집 실패: %s", error)
                with self._lock:
                    self._last_result = {"상태": "실패", "사유": f"{type(error).__name__}: {error}"}
            time.sleep(_SWEEP_INTERVAL_SECONDS)

    def _sweep_once(self) -> None:
        news = self._news_api()

        with self._lock:
            names = list(self._names)
            already = self._swept_on == date.today()
        if not names or already:
            return

        collected, failed = 0, []
        for name in names:
            try:
                collected += news.archive_append(news.collect(name, days=7))
                # 적립하는 김에 분석까지 만들어 둔다. 어차피 같은 기사를 방금 받았다.
                # 이걸 안 해 두면 사용자가 화면을 열 때 11초를 혼자 기다린다.
                #
                # 같은 자물쇠를 쓴다. 사용자가 마침 그 종목을 열고 있으면 둘이 동시에
                # 분석하게 되는데, 실제로 그러다 500 이 났다.
                with self._gate(name):
                    self._analyze(name)
            except Exception as error:
                failed.append(f"{name}({type(error).__name__})")
            # 마지막 종목 뒤에는 쉬지 않는다. 끝났는데 기다릴 이유가 없다.
            if name != names[-1]:
                time.sleep(_REQUEST_GAP_SECONDS)

        with self._lock:
            self._swept_on = date.today()
            self._last_result = {"상태": "완료", "신규": collected,
                                 "종목": len(names), "실패": failed}
        LOG.info("뉴스 아카이브 적립: 신규 %d건 · 종목 %d개 · 실패 %d개",
                 collected, len(names), len(failed))


CACHE = NewsCache()
