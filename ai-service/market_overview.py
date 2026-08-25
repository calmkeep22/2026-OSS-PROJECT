"""
국내·해외 지수와 환율.

홈 화면 맨 위 카드 세 개가 쓴다. 종목 하나를 열기 전에 "오늘 시장이 어땠는지"를
먼저 듣는 자리다.

받지 못한 지표는 **빼고 준다.** 0 이나 직전 값으로 채우지 않는다. 화면을 볼 수 없는
사용자는 채워 넣은 값과 실제로 받은 값을 구별할 방법이 없고, 그 구별이 이 앱의 전부다.

기준일을 함께 준다. 미국장은 한국 시간으로 하루 늦고 주말에는 금요일 종가가 월요일
내내 그대로 남는다. 날짜가 없으면 사용자는 그것을 오늘 값으로 읽는다.
"""

from __future__ import annotations

import datetime as dt
from concurrent.futures import ThreadPoolExecutor
import json
import logging
import threading
import time
from pathlib import Path
from zoneinfo import ZoneInfo

from accessible_investor.config import CACHE_DIR

LOG = logging.getLogger("market_overview")

#: 홈 카드가 쓰는 지표. 순서가 곧 화면 순서다.
SYMBOLS = (
    ("KS11", "코스피", "국내지수"),
    ("US500", "S&P 500", "해외지수"),
    ("USD/KRW", "원/달러", "환율"),
)

#: 조회가 완전히 실패했을 때만 다시 시도하기까지 기다리는 시간.
CACHE_SECONDS = 60.0

#: 성공한 일별 지표는 앱을 껐다 켜도 다시 받지 않는다. 화면에는 각 값의 실제 기준일을
#: 따로 표시하므로 주말·휴장일에 오늘 값인 것처럼 보이지 않는다.
CACHE_FILE: Path = CACHE_DIR / "market-overview.json"
KST = ZoneInfo("Asia/Seoul")

#: 직전 종가를 찾으려면 며칠은 봐야 한다. 연휴가 끼면 사흘로는 모자란다.
LOOKBACK_DAYS = 14

#: 국내장 종가가 공개 시세에 반영될 여유를 두고 하루 캐시를 바꾸는 시각.
#: 오전에 받은 값은 직전 거래일 칸에 보관하고, 오후 4시가 지나면 오늘
#: 거래일 칸을 한 번 다시 받는다. 자정에 먼저 받은 어제 종가가 오늘 장 마감
#: 후에도 계속 남는 문제를 막는다.
MARKET_REFRESH_AFTER = dt.time(16, 0)

_lock = threading.Lock()
_cached: list[dict] | None = None
_cached_at = 0.0
_cached_slot: dt.date | None = None


def _now() -> dt.datetime:
    return dt.datetime.now(KST)


def _refresh_slot(now: dt.datetime) -> dt.date:
    """Returns the latest domestic trading day whose close should be available."""
    day = now.date()
    if now.time().replace(tzinfo=None) < MARKET_REFRESH_AFTER:
        day -= dt.timedelta(days=1)
    # Weekend launches reuse Friday's successful close instead of making a new daily slot.
    while day.weekday() >= 5:
        day -= dt.timedelta(days=1)
    return day


def _read_daily_cache(slot: dt.date) -> list[dict] | None:
    """현재 시장 칸의 성공 결과만 읽는다. 깨진 파일은 다시 조회한다."""
    try:
        payload = json.loads(CACHE_FILE.read_text(encoding="utf-8"))
        if payload.get("refresh_slot") != slot.isoformat():
            return None
        indices = payload.get("indices")
        if not isinstance(indices, list) or not indices:
            return None
        return [item for item in indices if isinstance(item, dict)] or None
    except (OSError, ValueError, TypeError, AttributeError):
        return None


def _write_daily_cache(today: dt.date, slot: dt.date, indices: list[dict]) -> None:
    """완성된 JSON만 보이도록 임시 파일을 바꿔 끼운다."""
    temporary = CACHE_FILE.with_suffix(".tmp")
    try:
        CACHE_FILE.parent.mkdir(parents=True, exist_ok=True)
        temporary.write_text(json.dumps({
            "cached_on": today.isoformat(),
            "refresh_slot": slot.isoformat(),
            "indices": indices,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(CACHE_FILE)
    except OSError as error:
        LOG.warning("시장 지표 일일 캐시를 저장하지 못함: %s", error)
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass


def _read_one(code: str, name: str, group: str) -> dict | None:
    """한 지표. 못 받으면 None — 지어내지 않는다."""
    import FinanceDataReader as fdr

    start = (dt.date.today() - dt.timedelta(days=LOOKBACK_DAYS)).isoformat()
    frame = fdr.DataReader(code, start)
    if frame is None or frame.empty or "Close" not in frame:
        return None

    closes = frame["Close"].dropna()
    if closes.empty:
        return None

    last = float(closes.iloc[-1])
    # 등락은 직전 거래일이 있어야 낼 수 있다. 없으면 값만 주고 등락은 비운다.
    change = None
    if len(closes) >= 2:
        previous = float(closes.iloc[-2])
        if previous:
            change = round((last / previous - 1.0) * 100.0, 2)

    return {
        "code": code,
        "name": name,
        "group": group,
        "value": round(last, 2),
        "change_percent": change,
        "as_of": closes.index[-1].date().isoformat(),
    }


def overview(force: bool = False) -> list[dict]:
    """
    받은 것만 순서대로 돌려준다.

    하나가 실패해도 나머지는 준다. 코스피는 국내 서버, S&P 는 해외 서버라 함께
    죽지 않는다. 한 묶음으로 실패시키면 멀쩡한 지표까지 화면에서 사라진다.
    """
    global _cached, _cached_at, _cached_slot
    with _lock:
        now = _now()
        today = now.date()
        slot = _refresh_slot(now)
        if _cached and _cached_slot == slot and not force:
            return _cached

        if not force:
            stored = _read_daily_cache(slot)
            if stored is not None:
                _cached = stored
                _cached_at = time.time()
                _cached_slot = slot
                return stored

            # 모두 실패한 응답은 하루 동안 굳히지 않는다. 외부 서버가 잠깐 끊긴 것일 수
            # 있으므로 1분 뒤에는 다시 시도한다.
            recent_failure = (_cached == [] and _cached_slot == slot
                              and (time.time() - _cached_at) < CACHE_SECONDS)
            if recent_failure:
                return []

        # 세 공급원은 서로 독립적이다. 순서대로 기다리면 첫 실행이 자바 앱의 HTTP
        # 제한시간을 넘길 수 있으므로 동시에 요청하되, 결과 순서는 SYMBOLS 그대로 둔다.
        def safely_read(symbol: tuple[str, str, str]) -> dict | None:
            code, name, group = symbol
            try:
                return _read_one(code, name, group)
            except Exception as error:  # 한 지표의 실패가 나머지를 막지 않는다.
                LOG.warning("%s(%s) 받지 못함: %s: %s", name, code, type(error).__name__, error)
                return None

        with ThreadPoolExecutor(max_workers=len(SYMBOLS),
                                thread_name_prefix="market-overview") as pool:
            received = list(pool.map(safely_read, SYMBOLS))

        collected: list[dict] = []
        for (code, name, _group), item in zip(SYMBOLS, received, strict=True):
            if item is None:
                LOG.warning("%s(%s) 응답이 비어 있음", name, code)
                continue
            collected.append(item)

        # 하나도 못 받았으면 이전 것을 그대로 두지 않는다. 오래된 값이 계속 보이면
        # 사용자는 연결이 끊긴 것을 알 수 없다.
        _cached = collected
        _cached_at = time.time()
        _cached_slot = slot
        if collected:
            _write_daily_cache(today, slot, collected)
        return collected


def status() -> dict:
    """health 에 끼워 넣을 한 줄."""
    got = 0 if _cached is None else len(_cached)
    return {"지표": f"{got}/{len(SYMBOLS)}", "마지막수신": None if not _cached_at
            else dt.datetime.fromtimestamp(_cached_at).isoformat(timespec="seconds")}
