"""
구간 특이성 마킹 — Matrix Profile.

02에서 **탐지 엔진에서 제외한** 도구다. 점수 귀속 방식 24조합을 전수 비교해
최적을 골라도 PR-AUC 0.0356으로 단순 수익률 z-score(0.1412)의 1/4이었다.
(처음 쓴 조합은 ROC-AUC 0.338로 **무작위보다 나빴다**.)

이유는 도구를 잘못 쓴 것이다. Matrix Profile은 *반복 vs 특이 구간*을 찾는 도구지
점 이상 탐지기가 아니다. 그래서 용도를 바꿔서 여기 되살린다.

    점 이상(earcon "삐빅")  = "이 봉에서 무슨 일이 있었다"   ← 규칙 계층 + 랭커
    구간 특이(earcon "우웅") = "이 구간의 모양이 낯설다"      ← 여기

둘은 다른 질문이고 소리도 달라야 한다. 저시력자 사용자가 1년치를 훑을 때
"여기서 급등이 있었다"와 "이 구간 전체가 평소와 다른 모양이다"는
서로 다른 정보이고, 드릴다운할 이유도 다르다.

성능 함정 (01에서 실측)
    stumpy 첫 호출 29초 — numba JIT 컴파일이다. 두 번째부터 0.02초.
    → 프로세스 시작 시 한 번 예열한다. 안 하면 사용자의 첫 요청이 29초 걸린다.
"""

from __future__ import annotations

import time

import numpy as np
import pandas as pd

_WARMED = False


def warmup(verbose: bool = False) -> float:
    """
    numba JIT 예열. 앱 시작 시 백그라운드로 한 번 부른다.

    이걸 안 하면 사용자의 **첫** 드릴다운이 29초 걸린다.
    화면을 못 보는 사용자에게 29초 무응답은 앱이 죽은 것과 구분되지 않는다.
    """
    global _WARMED
    if _WARMED:
        return 0.0
    try:
        import stumpy
    except ImportError:
        _WARMED = True
        return 0.0
    t0 = time.time()
    stumpy.stump(np.random.RandomState(0).randn(200).astype(np.float64), m=16)
    _WARMED = True
    el = time.time() - t0
    if verbose:
        print(f"Matrix Profile 예열 {el:.1f}초 (이후 호출은 밀리초 단위)")
    return el


def profile(close, m: int = 24) -> np.ndarray | None:
    """
    Matrix Profile. 각 위치의 값 = 가장 가까운 다른 구간과의 거리.

    값이 크면 = 닮은 구간이 없다 = 특이하다 (discord)
    값이 작으면 = 똑같은 구간이 또 있다 = 반복 패턴이다 (motif)

    m은 구간 길이. 5분봉 24 = 2시간. 단타에서 "한 흐름"이라 부를 만한 길이다.
    """
    try:
        import stumpy
    except ImportError:
        return None
    x = np.asarray(close, dtype=np.float64)
    x = x[np.isfinite(x)]
    if len(x) < m * 3:
        return None
    warmup()
    try:
        return stumpy.stump(x, m=m)[:, 0].astype(float)
    except Exception:
        return None


def _crosses_day(index, m: int) -> np.ndarray:
    """
    각 시작 위치의 윈도우가 날짜 경계를 넘는가.

    5분봉 2시간 윈도우가 밤을 건너뛰면 그 구간은 "14:55 → 다음날 09:00"이 되어
    **연속된 흐름이 아니다.** Matrix Profile은 그걸 모르고 그냥 이어붙여 계산하므로
    밤을 낀 구간이 항상 "가장 특이한 구간" 상위에 올라온다.
    실제로 그 버그를 냈다 — "13시 05분부터 09시 00분까지"라는 거꾸로 된 안내가 나왔다.
    """
    if not isinstance(index, pd.DatetimeIndex):
        return np.zeros(max(len(index) - m + 1, 0), dtype=bool)
    d = np.asarray([t.date() for t in index])
    n = len(d) - m + 1
    if n <= 0:
        return np.zeros(0, dtype=bool)
    return np.asarray([d[i] != d[i + m - 1] for i in range(n)])


def discords(close, m: int = 24, k: int = 3,
             exclusion: int | None = None, index=None) -> list[int]:
    """
    가장 특이한 구간 k개의 시작 위치.

    배타 구역(exclusion)이 필요하다. 안 두면 상위 k개가 전부
    **같은 구간의 한 칸씩 밀린 위치**가 된다 — 정보량 0이다.
    (03에서 유사 검색 결과가 같은 종목으로 도배된 것과 같은 문제다.)
    """
    mp = profile(close, m)
    if mp is None or not np.isfinite(mp).any():
        return []
    exclusion = exclusion if exclusion is not None else m
    mp = mp.copy()
    if index is not None:
        bad = _crosses_day(index, m)
        mp[: len(bad)][bad] = np.nan
    out = []
    for _ in range(k):
        if not np.isfinite(mp).any():
            break
        i = int(np.nanargmax(mp))
        out.append(i)
        lo, hi = max(0, i - exclusion), min(len(mp), i + exclusion + 1)
        mp[lo:hi] = np.nan
    return sorted(out)


def motifs(close, m: int = 24, k: int = 2,
           exclusion: int | None = None, index=None) -> list[int]:
    """가장 자주 반복된 구간 k개. "이 모양은 익숙한 모양입니다"에 쓴다."""
    mp = profile(close, m)
    if mp is None or not np.isfinite(mp).any():
        return []
    exclusion = exclusion if exclusion is not None else m
    mp = mp.copy()
    if index is not None:
        bad = _crosses_day(index, m)
        mp[: len(bad)][bad] = np.nan
    out = []
    for _ in range(k):
        if not np.isfinite(mp).any():
            break
        i = int(np.nanargmin(mp))
        out.append(i)
        lo, hi = max(0, i - exclusion), min(len(mp), i + exclusion + 1)
        mp[lo:hi] = np.nan
    return sorted(out)


def describe(bars: pd.DataFrame, start: int, m: int, kind: str = "discord") -> str:
    """구간 마킹 → TTS 문안. 시작·끝 시각과 그 구간의 변화폭을 말한다."""
    end = min(start + m - 1, len(bars) - 1)
    if start >= len(bars):
        return ""
    t0, t1 = bars.index[start], bars.index[end]
    chg = float(bars["close"].iloc[end] / bars["close"].iloc[start] - 1) * 100
    intraday = isinstance(t0, pd.Timestamp) and (t0.hour or t0.minute)
    if intraday:
        # 날짜가 바뀌면 끝에도 날짜를 붙인다. 안 그러면
        # "13시 05분부터 09시 00분까지"처럼 거꾸로 읽힌다.
        head = t0.strftime("%m월 %d일 %H시 %M분")
        tail = (t1.strftime("%H시 %M분") if t0.date() == t1.date()
                else t1.strftime("%m월 %d일 %H시 %M분"))
        span = f"{head}부터 {tail}"
    else:
        span = f"{t0.strftime('%m월 %d일')}부터 {t1.strftime('%m월 %d일')}"
    what = ("평소 이 종목에서 보기 힘든 모양입니다" if kind == "discord"
            else "이 종목에서 자주 나오는 모양입니다")
    return f"{span}까지 구간. {chg:+.1f}퍼센트. {what}."


def mark(bars: pd.DataFrame, m: int = 24, k: int = 3,
         kind: str = "discord") -> list[dict]:
    """
    소리 드릴다운용 구간 마킹.

    반환 항목은 구간 인덱스라 시각화·요약 어디에나 그대로 넘길 수 있다.
    """
    idx = (discords if kind == "discord" else motifs)(
        bars["close"], m, k, index=bars.index)
    return [{"start": i, "end": min(i + m - 1, len(bars) - 1), "kind": kind,
             "text": describe(bars, i, m, kind)} for i in idx]
