"""
피처 엔지니어링 — 일봉(스윙)과 분봉(단타)을 한 인터페이스로.

단타 축에서 가장 중요한 건 **시간대 정규화**다.
01에서 측정한 대로 개장 30분 변동성은 장중의 2.40배다. 보정 없이 5분봉에
그대로 z-score를 씌우면 알림의 대부분이 09:00~09:30에 몰린다.
그건 이상 탐지가 아니라 "장이 열렸습니다" 알림이다.

거래량은 더 심하다. 개장·마감 거래량이 점심 무렵의 수 배라
로그 거래량에 원시 z-score를 씌우면 매일 같은 시각에 같은 알림이 나온다.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .config import HORIZONS, SEASONALITY_ALPHA


# --------------------------------------------------------------------------
# robust z-score
# --------------------------------------------------------------------------
def robust_z(s: pd.Series, window: int = 60, min_periods: int = 20) -> pd.Series:
    """
    중앙값·MAD 기반 robust z-score.

    평균/표준편차 z-score는 이상치 자신이 통계량을 오염시킨다 —
    이상치 하나가 표준편차를 키워 스스로를 정상으로 만든다.
    01 실측: robust z가 일반 z보다 2.8배 많은 이상일을 잡는다 (47일 vs 17일).

    MAD가 0인 구간(가격이 며칠째 붙어 있는 저유동성 종목)에서는 z가 무한대가 되므로
    표준편차로 폴백한다. 폴백이 없으면 그런 종목은 사소한 움직임에도 |z|=∞가 되어
    알림을 독점한다. 실제로 거래정지 직후 종목에서 이 현상이 난다.
    """
    med = s.rolling(window, min_periods=min_periods).median()
    mad = (s - med).abs().rolling(window, min_periods=min_periods).median()
    scale = mad / 0.6745                      # MAD → 표준편차 환산
    std = s.rolling(window, min_periods=min_periods).std()
    scale = scale.where(scale > 0, std)
    return (s - med) / scale.replace(0, np.nan)


# --------------------------------------------------------------------------
# 장중 시간대 프로파일
# --------------------------------------------------------------------------
def _tod_key(idx: pd.DatetimeIndex) -> pd.Index:
    return pd.Index(idx.strftime("%H:%M"))


def seasonality_profile(df: pd.DataFrame, until: pd.Timestamp | None = None,
                        smooth: int = 3) -> dict[str, pd.Series]:
    """
    시간대(HH:MM)별 기준 크기 프로파일.

    반환
    ----
    {"ret": |수익률| 중앙값, "vol": 로그거래량 중앙값, "rng": 봉 내 변동폭 중앙값}

    `until`을 주면 그 시점 **이전 데이터만** 쓴다. 백테스트에서 미래를 보지 않기
    위한 장치다. 프로파일은 U자 모양의 구조적 성질이라 하루이틀로 안 바뀌지만,
    "성능이 좋아 보이는 이유가 미래를 봐서"인 상황은 만들면 안 된다.

    스무딩: 슬롯당 표본이 20~60개뿐이라 인접 슬롯 중앙값으로 한 번 눌러준다.
    안 하면 특정 5분 슬롯 하나가 우연히 조용했다는 이유로 그 시각 알림이 폭증한다.
    """
    sub = df if until is None else df[df.index < until]
    if len(sub) < 20:
        sub = df

    ret = sub["close"].pct_change().abs()
    ret[_first_bar_mask(sub.index)] = np.nan     # 갭은 시간대 프로파일과 무관하다
    logv = np.log1p(sub["volume"].where(sub["volume"] > 0))
    rng = (sub["high"] - sub["low"]) / sub["close"]

    key = _tod_key(sub.index)
    out = {}
    for name, series in (("ret", ret), ("vol", logv), ("rng", rng)):
        prof = series.groupby(key).median()
        if smooth and len(prof) > smooth:
            prof = prof.rolling(smooth, center=True, min_periods=1).median()
        out[name] = prof.replace(0, np.nan)
    return out


def _first_bar_mask(idx: pd.DatetimeIndex) -> np.ndarray:
    """각 거래일의 첫 봉 위치. 밤사이 갭이 '5분 수익률'로 둔갑하는 걸 막는다."""
    d = pd.Series(idx.date, index=range(len(idx)))
    return (d != d.shift(1)).to_numpy()


def _apply_profile(series: pd.Series, prof: pd.Series, how: str = "div",
                   alpha: float = 1.0) -> pd.Series:
    """
    시간대 프로파일로 정규화. how='div'는 비율, 'sub'는 로그공간 차이.

    alpha — 보정 강도. 0이면 보정 없음, 1이면 완전 보정.
    ------
    완전 보정이 항상 옳지 않다는 것을 실측으로 확인했다. 자세한 근거는
    anomaly.py의 SEASONALITY_ALPHA 주석에 있다. 요약하면,
    **단타 기회 자체가 개장 구간에 몰려 있어서** 완전 보정은 진짜 기회를 눌러버린다.
    """
    if prof is None or len(prof) == 0 or alpha <= 0:
        return series
    base = pd.Series(_tod_key(series.index).map(prof).to_numpy(),
                     index=series.index, dtype=float)
    base = base.ffill().bfill()
    if how == "sub":
        return series - alpha * base
    return series / base.replace(0, np.nan).pow(alpha)


# --------------------------------------------------------------------------
# 장중 피처
# --------------------------------------------------------------------------
def add_intraday_features(df: pd.DataFrame, horizon: str = "intraday",
                          index: pd.DataFrame | None = None,
                          profile: dict | None = None,
                          daily: pd.DataFrame | None = None,
                          alpha_override: dict[str, float] | None = None) -> pd.DataFrame:
    """
    분봉 → 단타 이상 탐지용 피처.

    채널별로 z-score 컬럼을 만든다. 채널을 하나로 합치지 않는 이유는 02에서
    확인했다 — 점수를 섞으면 약한 채널이 강한 채널을 희석한다.
    앙상블은 '강한 멤버 2개의 순위 평균'일 때만 이겼고, 약한 멤버를 넣으면
    최고 단일보다 나빠졌다.

    `daily`
    -------
    **yfinance 국내 분봉은 14:55까지만 준다.** 15:00~15:30(마감 30분 + 종가 단일가)이
    통째로 빠진다. 실측: 005930 2026-07-30 분봉 마지막 종가 209,750 vs 실제 종가 207,000
    (1.3% 차이), 분봉 거래량 합계는 일봉의 86% 수준이다.

    갭은 '전일 종가 대비'로 정의되므로 이 차이가 그대로 오차가 된다.
    일봉을 넘겨주면 전일 종가를 정확한 값으로 대체한다. 넘기지 않으면
    분봉 마지막 종가로 폴백하되 정확도가 떨어진다.
    """
    cfg = HORIZONS[horizon]
    W, MP = cfg["baseline_bars"], cfg["min_baseline"]
    VW = cfg["vol_window"]
    alpha = {**SEASONALITY_ALPHA, **(alpha_override or {})}

    out = df.copy()
    first = _first_bar_mask(out.index)
    day = pd.Series(out.index.date, index=out.index)

    # --- 수익률: 날짜 경계를 넘지 않는다 ---------------------------------
    out["ret"] = out["close"].pct_change()
    out.loc[first, "ret"] = np.nan

    # --- 시장 대비 초과수익 ----------------------------------------------
    # 코스피가 -1.5%인 시각에 개별 종목이 -1.8%인 것은 이상이 아니다.
    if index is not None and len(index):
        mkt = index["close"].pct_change()
        mkt = mkt.reindex(out.index, method="nearest",
                          tolerance=pd.Timedelta(minutes=6))
        out["mkt_ret"] = mkt
        out["excess_ret"] = out["ret"] - mkt.fillna(0.0)
    else:
        out["mkt_ret"] = np.nan
        out["excess_ret"] = out["ret"]

    profile = profile or seasonality_profile(out)

    # --- 채널 1: 가격 (시간대 정규화된 초과수익) --------------------------
    out["excess_n"] = _apply_profile(out["excess_ret"], profile.get("ret"),
                                     alpha=alpha["price"])
    out["excess_z"] = robust_z(out["excess_n"], W, MP)

    # --- 채널 2: 거래량 ---------------------------------------------------
    # 로그공간에서 빼면 "그 시각 평소 거래량의 몇 배"가 된다.
    # 09:00 봉은 시가 단일가라 yfinance가 volume=0으로 주는 경우가 있어 NaN 처리.
    vol = out["volume"].where(out["volume"] > 0)
    out["log_volume"] = np.log1p(vol)
    out["vol_n"] = _apply_profile(out["log_volume"], profile.get("vol"), how="sub",
                                  alpha=alpha["volume"])
    out["vol_z"] = robust_z(out["vol_n"], W, MP)
    out["vol_mult"] = np.exp(out["vol_n"])          # 사용자에게 읽어줄 "평소의 N배"

    # --- 채널 3: VWAP 이탈 (단타 핵심) ------------------------------------
    # 기관 체결 기준선. 단타에서 실제로 쓰는 지표라 여기 넣었다.
    # 당일 누적이라 매일 09:00에 리셋된다.
    typical = (out["high"] + out["low"] + out["close"]) / 3.0
    pv = (typical * out["volume"]).groupby(day).cumsum()
    vv = out["volume"].groupby(day).cumsum().replace(0, np.nan)
    out["vwap"] = pv / vv
    out["vwap_dev"] = (out["close"] - out["vwap"]) / out["vwap"]
    # VWAP 이탈은 장 초반엔 구조적으로 작고(분모가 몇 봉뿐) 시간이 갈수록 커진다.
    # 보정 없이 z-score를 씌우면 오후 알림만 나온다 → 이 채널만 전용 프로파일을 쓴다.
    _vwap_prof = out["vwap_dev"].abs().groupby(_tod_key(out.index)).median()
    _vwap_prof = _vwap_prof.rolling(3, center=True, min_periods=1).median().replace(0, np.nan)
    out["vwap_n"] = _apply_profile(out["vwap_dev"], _vwap_prof, alpha=alpha["price"])
    out["vwap_z"] = robust_z(out["vwap_n"], W, MP)

    # --- 채널 4: 추세 가속 -------------------------------------------------
    # 한 봉씩 보면 아무것도 아닌데 15분 누적하면 큰 움직임인 경우가 단타에 흔하다.
    # (+0.4%가 다섯 봉 연속이면 +2%인데, 봉 단위 z-score로는 하나도 안 걸린다)
    k = 3
    out["thrust"] = out["excess_ret"].rolling(k, min_periods=k).sum()
    # 하루 첫 k봉은 전날 봉이 섞여 있으므로 버린다
    out.loc[day.groupby(day).cumcount() < k, "thrust"] = np.nan
    out["thrust_n"] = _apply_profile(out["thrust"], profile.get("ret"),
                                     alpha=alpha["price"]) / np.sqrt(k)
    out["thrust_z"] = robust_z(out["thrust_n"], W, MP)

    # --- 채널 5: 일중 신고가/신저가 돌파 -----------------------------------
    # 직전 봉까지의 당일 고가/저가를 얼마나 넘어섰는가. 돌파 매매의 신호다.
    prior_hi = out["high"].groupby(day).cummax().groupby(day).shift(1)
    prior_lo = out["low"].groupby(day).cummin().groupby(day).shift(1)
    up = (out["close"] - prior_hi) / prior_hi
    dn = (out["close"] - prior_lo) / prior_lo
    out["hilo"] = np.where(up > 0, up, np.where(dn < 0, dn, 0.0))
    # 돌파가 아닌 봉은 값이 정확히 0이다. 전체 분포에 z-score를 씌우면
    # 중앙값·MAD가 0이 되어(70%가 0) 폴백 경로로 빠지고, 아주 작은 돌파에도
    # 큰 z가 붙는다. 실제로 이 채널이 알림의 40%를 먹는 현상이 났다.
    # → **돌파한 봉들끼리만** 비교한다. "얼마나 결정적인 돌파인가"가 질문이다.
    hilo_nz = pd.Series(out["hilo"], index=out.index).replace(0.0, np.nan)
    out["hilo_n"] = _apply_profile(hilo_nz, profile.get("ret"), alpha=alpha["price"])
    out["hilo_z"] = robust_z(out["hilo_n"], W, max(MP // 3, 10))

    # --- 채널 6: 갭 (각 거래일 첫 봉에만) ----------------------------------
    day_open = out["open"].groupby(day).transform("first")
    if daily is not None and len(daily):
        # 일봉 종가가 정답이다. 분봉 마지막 봉(14:55)은 종가 단일가를 반영하지 못한다.
        dc = daily["close"].copy()
        dc.index = pd.to_datetime(dc.index).date
        prev_close_by_day = pd.Series(dc.to_numpy(), index=dc.index).shift(1)
        prev_close = day.map(prev_close_by_day)
        # 일봉에 없는 날(가장 최근 며칠)은 분봉으로 폴백
        fallback = day.map(out["close"].groupby(day).last().shift(1))
        prev_close = prev_close.fillna(fallback)
    else:
        prev_close = day.map(out["close"].groupby(day).last().shift(1))
    gap = (day_open - prev_close) / prev_close
    out["gap"] = np.where(first, gap, np.nan)
    gap_series = pd.Series(out["gap"], index=out.index)
    # 갭은 하루 한 번뿐이라 롤링 창을 봉이 아니라 '일수'로 잡아야 한다
    gap_only = gap_series.dropna()
    if len(gap_only) >= 10:
        gz = robust_z(gap_only, window=40, min_periods=10)
        out["gap_z"] = gz.reindex(out.index)
    else:
        out["gap_z"] = np.nan

    # --- 채널 7: 실현변동성 ------------------------------------------------
    # 일봉의 ATR(14)은 장중에 쓰기엔 너무 길다. 06에서 "한 사건이 며칠간 반복
    # 알림을 만든다"고 확인한 그 문제가 장중에서는 몇 시간 단위로 재현된다.
    out["rvol"] = out["ret"].rolling(VW, min_periods=max(3, VW // 2)).std()
    out["rvol_n"] = _apply_profile(out["rvol"], profile.get("ret"),
                                   alpha=alpha["volatility"])
    out["rvol_z"] = robust_z(out["rvol_n"], W, MP)

    # --- 부가 정보 (문안 생성용) -------------------------------------------
    # --- 절대 크기 (게이트용) ---------------------------------------------
    # "이례적인가"와 "충분히 큰가"는 다른 질문이다. z-score는 앞을,
    # 이 값은 뒤를 담당한다. 단위는 '그 종목 일간 변동성의 몇 배'다.
    out["daily_sigma"] = (out["excess_ret"].rolling(W, min_periods=MP).std()
                          * np.sqrt(cfg["bars_per_day"]))
    out["move_sigma"] = (pd.concat([out["excess_ret"].abs(), out["thrust"].abs()],
                                   axis=1).max(axis=1) / out["daily_sigma"])

    out["day_ret"] = out["close"] / out["open"].groupby(day).transform("first") - 1
    out["tod"] = _tod_key(out.index)
    return out


# --------------------------------------------------------------------------
# 일봉 피처 (기존 02/06 확정 로직)
# --------------------------------------------------------------------------
def add_daily_features(df: pd.DataFrame, index: pd.DataFrame | None = None,
                       horizon: str = "swing") -> pd.DataFrame:
    cfg = HORIZONS[horizon]
    W, MP = cfg["baseline_bars"], cfg["min_baseline"]

    out = df.copy()
    out["ret"] = out["close"].pct_change()
    out["ret_z"] = robust_z(out["ret"], W, MP)

    out["log_volume"] = np.log1p(out["volume"].where(out["volume"] > 0))
    out["vol_z"] = robust_z(out["log_volume"], W, MP)
    base = out["log_volume"].rolling(20, min_periods=5).median()
    out["vol_mult"] = np.expm1(out["log_volume"]) / np.expm1(base).clip(lower=1)

    prev_close = out["close"].shift(1)
    tr = pd.concat([out["high"] - out["low"],
                    (out["high"] - prev_close).abs(),
                    (out["low"] - prev_close).abs()], axis=1).max(axis=1)
    out["atr"] = tr.rolling(cfg["vol_window"], min_periods=5).mean()
    out["atr_ratio"] = out["atr"] / out["close"]
    out["rvol_z"] = robust_z(out["atr_ratio"], W, MP)      # 채널 이름 통일

    for w in (20, 60):
        ma = out["close"].rolling(w, min_periods=w // 2).mean()
        out[f"ma_dev_{w}"] = (out["close"] - ma) / ma
    out["volatility_20"] = out["ret"].rolling(20, min_periods=10).std()

    if index is not None and len(index):
        mkt = index["close"].pct_change().reindex(out.index)
        out["mkt_ret"] = mkt
        out["excess_ret"] = out["ret"] - mkt.fillna(0.0)
    else:
        out["mkt_ret"] = np.nan
        out["excess_ret"] = out["ret"]
    out["excess_z"] = robust_z(out["excess_ret"], W, MP)

    # 절대 크기 — 장중과 같은 의미('그 종목 일간 변동성의 몇 배')로 맞춘다.
    # 이게 없으면 일봉 알림은 신뢰도 등급이 영원히 A에 못 간다 (실제로 그랬다).
    out["daily_sigma"] = out["excess_ret"].rolling(W, min_periods=MP).std()
    out["move_sigma"] = out["excess_ret"].abs() / out["daily_sigma"]

    out["day_ret"] = out["ret"]
    return out


def add_features(df: pd.DataFrame, horizon: str = "intraday",
                 index: pd.DataFrame | None = None, **kw) -> pd.DataFrame:
    """시간축에 맞는 피처를 붙인다. 엔진은 이 함수만 쓴다."""
    if HORIZONS[horizon]["interval"] == "1d":
        return add_daily_features(df, index=index, horizon=horizon)
    return add_intraday_features(df, horizon=horizon, index=index, **kw)


def strip_corporate_actions(df: pd.DataFrame, threshold: float = 0.31) -> pd.DataFrame:
    """
    액면분할·감자·권리락 의심 구간 제거.

    상·하한 ±30%를 넘는 등락은 정상 거래로 나올 수 없다. 이런 날의 수익률을
    남겨두면 robust z의 중앙값·MAD가 통째로 오염돼 이후 60일이 무감각해진다.

    주의: **±30% 안쪽의 큰 갭은 지우면 안 된다.** 실측에서 005930이
    2026-07-31에 +24% 갭 상승했는데(일봉 교차 확인 완료) 이건 진짜 사건이다.
    임계값을 낮게 잡으면 알려야 할 것을 지운다.
    """
    out = df.copy()
    bad = out["close"].pct_change().abs() > threshold
    for col in ("ret", "excess_ret", "excess_n", "thrust", "thrust_n"):
        if col in out:
            out.loc[bad, col] = np.nan
    return out


FEATURE_COLS_DAILY = ["ret_z", "vol_z", "rvol_z", "ma_dev_20", "ma_dev_60",
                      "volatility_20", "excess_ret"]
FEATURE_COLS_INTRADAY = ["excess_z", "vol_z", "vwap_z", "thrust_z",
                         "hilo_z", "rvol_z"]


def feature_matrix(df: pd.DataFrame, cols: list[str] | None = None):
    cols = cols or FEATURE_COLS_DAILY
    sub = df[cols].replace([np.inf, -np.inf], np.nan).dropna()
    return sub.to_numpy(dtype=np.float32), sub.index
