"""
파이프라인 조립 — 데이터 로드부터 피처까지를 한 함수로.

각 모듈이 따로 놀면 "일봉을 안 넘겨서 갭이 틀렸다" 같은 배선 실수가 난다.
실제로 개발 중에 그 버그를 냈다. 조립은 여기 한 곳에서만 한다.
"""

from __future__ import annotations

import time

import pandas as pd

from . import data as D
from . import features as F
from .config import HORIZONS


def build_panel(codes: list[str], horizon: str = "intraday",
                refresh: bool = False, verbose: bool = False,
                profile_until: pd.Timestamp | None = None,
                strip_ca: bool = True) -> dict[str, tuple[str, pd.DataFrame]]:
    """
    관심종목 → {코드: (이름, 피처 DataFrame)}

    profile_until
    -------------
    시간대 프로파일을 이 시각 **이전 데이터로만** 추정한다.
    백테스트에서 미래를 보지 않기 위한 장치다. 실사용에서는 None(전체 사용)이 맞다 —
    프로파일은 U자 구조라 어제까지의 데이터로 추정하나 오늘 포함해 추정하나
    거의 같지만, "성능이 좋아 보이는 이유가 미래를 봐서"인 상황은 만들면 안 된다.
    """
    t0 = time.time()
    interval = HORIZONS[horizon]["interval"]
    is_daily = interval == "1d"

    idx = D.load_index_bars(horizon) if not refresh else D.load_index_bars(
        horizon, max_age_min=0)

    panel: dict[str, tuple[str, pd.DataFrame]] = {}
    for code in codes:
        bars = D.load_bars(code, horizon) if not is_daily else D.load_daily(
            code, auto_download=True)
        if bars is None or len(bars) < 100:
            if verbose:
                print(f"  {code}: 데이터 부족 → 제외")
            continue

        if is_daily:
            feat = F.add_daily_features(bars, index=idx, horizon=horizon)
        else:
            daily = D.load_daily(code, auto_download=False)
            prof = F.seasonality_profile(bars, until=profile_until)
            feat = F.add_intraday_features(bars, horizon, index=idx,
                                           profile=prof, daily=daily)
        if strip_ca:
            feat = F.strip_corporate_actions(feat)
        panel[code] = (D.name_of(code), feat)

    if verbose:
        print(f"패널 {len(panel)}/{len(codes)}종목, {time.time() - t0:.2f}초 "
              f"({HORIZONS[horizon]['name']} / {interval})")
    return panel


def ensure_intraday(codes: list[str], interval: str = "5m", verbose: bool = True):
    """분봉 캐시를 최신화한다. 장중 루프 밖에서 한 번 호출한다."""
    return D.download_intraday(codes, interval=interval, verbose=verbose)


def watchlist_from_cache(n: int = 10) -> list[str]:
    """캐시에 일봉이 있는 종목 중 시총 상위 n개. 데모·검증용 기본 관심종목."""
    cached = set(D.cached_codes())
    uni = D.universe()
    ordered = [c for c in uni["code"].tolist() if c in cached]
    return ordered[:n]
