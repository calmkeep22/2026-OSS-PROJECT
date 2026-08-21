"""
뉴스 미리받기와 아카이브 적립.

왜 미리 받나
------------
`predict(with_news=True)` 가 1~3초다. 거의 전부 구글 뉴스 RSS 요청이고 계산은
그대로 167ms 다. 즉 느린 이유는 모델이 아니라 화면이 남의 서버를 기다리는 것이다.
미리 받아 두면 그 기다림이 화면 밖으로 나간다.

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


class NewsCache:
    """
    관심 종목의 뉴스를 미리 받아 아카이브에 쌓는다.

    앱이 종목 목록을 알려 준다. 서비스는 사용자가 무엇을 들고 있는지 모르고, 6,228
    종목을 전부 받는 것은 구글에도 우리에게도 무리다.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._names: list[str] = []
        self._swept_on: date | None = None
        self._last_result: dict = {"상태": "아직 돌지 않음"}
        self._thread: threading.Thread | None = None

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
        from accessible_investor import news as N

        with self._lock:
            names = list(self._names)
            already = self._swept_on == date.today()
        if not names or already:
            return

        collected, failed = 0, []
        for name in names:
            try:
                collected += N.archive_append(N.collect(name, days=7))
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
