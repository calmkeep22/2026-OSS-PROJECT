"""
종목별 다음날 방향·변동성·등락률 예측 — 워크포워드 평가.

무엇을 하는가
=============
종목 하나마다 **그 종목만의 모델**을 세우고, 평가 구간을 하루씩 앞으로
걸어가며 다음 거래일을 예측한다.

    D일을 맞히려면
        학습:  D-1 종가까지 라벨이 확정된 행만 (t+1 <= D-1)
        입력:  D-1 종가 시점의 피처
        정답:  D일 결과

**미래를 절대 보지 않는다.** 한 번 학습해 전 구간을 평가하면 D+3의 정보로
D를 맞히게 되고 정확도가 실제보다 높게 나온다. 그래서 날짜마다 다시 학습한다.

타깃 둘
-------
    방향    다음날 상승/하락      — 차익거래로 지워져 잘 맞지 않는다
    변동성  다음날 크게 움직일지  — 변동성 군집 때문에 남아 있다

둘 다 돌려서 그 차이를 표로 보이는 것이 이 파트의 메시지다. 안 되는 것을
지우는 대신 **왜 안 되는지**를 같이 싣는 편이 정직하다.

피처 네 묶음 (총 32개)
======================
    기술적   19개  수익률·이동평균 이격·변동성·RSI·연속일수 + **드리프트**
    시장맥락  8개  지수 수익률, 지수 대비 초과, 베타, 상관
    테마      4개  이웃 종목들의 수익률·분산·나와의 격차
    뉴스      1개  `news_xfer` (미국 뉴스로 학습한 전이 모델의 출력)

시장맥락은 "어제 시장은 올랐는데 이 종목만 크게 빠졌다"를 표현한다.
**테마**는 그보다 좁다 — 지수는 반도체와 은행을 섞어 평균 내지만, 실제로
쓸모 있는 건 "내 이웃들이 어제 어땠나"다. **드리프트**는 "이 종목이 원래
우상향하는가"를 종목마다 다른 숫자로 준다.

측정은 균형정확도로 한다
========================
단순 적중률은 기준선이 50%가 아니다. 라벨이 한쪽으로 67% 쏠린 구간에서는
**무지성으로 다수쪽만 찍어도 67%** 가 나온다. 균형정확도는 상승 적중률과
하락 적중률을 따로 재서 평균하므로, 쏠림이 얼마든 **50%가 진짜 무작위선**이다.
그래서 기준선을 50%로 두고 비교하는 것이 그대로 정당해진다.

판정 임계값도 학습한다 (`_pick_threshold`)
------------------------------------------
확률을 0.5로 자르면 쏠린 쪽만 잘 맞히는 모델이 된다. 학습 구간을 8:2로 갈라
**뒤 20%에서만** 임계값을 고른다. 평가 구간은 어느 단계에서도 쓰지 않는다.

⚠️ 짧은 평가는 아무것도 증명하지 못한다
=======================================
종목당 7번 맞히기로는 85.7%(6/7)의 신뢰구간이 약 49~97%라 동전 던지기와
구별되지 않는다. 그래서 보고서는 `REPORT_DAYS = 40` 을 쓰고, 적중률에는
**항상 윌슨 신뢰구간**을 같이 낸다(n이 작을 때 정규근사는 구간이 1을 넘는다).
"""

from __future__ import annotations

import time
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

from . import data as D
from . import news as N
from . import newsxfer as NX
from . import sentiment as SENT
from .config import DATA_DIR, RANDOM_SEED
from .universe import INDICES, MARKETS, all_entries, entry

warnings.filterwarnings("ignore")

CACHE_DIR = DATA_DIR / "forecast"

EVAL_DAYS = 7                  # 시연용 평가 구간 (한 종목 빠르게 볼 때)
# 보고서용 평가 구간. 7일은 시연에는 충분하지만 **결론을 내기엔 부족하다** —
# 38종목 × 7일 = 266건이면 윌슨 구간이 ±6%p 라 55% 와 50% 가 구별되지 않는다.
# 40일이면 1,500건 전후가 되어 ±2.5%p 로 좁혀진다.
REPORT_DAYS = 40
MIN_TRAIN = 250                # 이보다 적게 학습하면 예측을 내지 않는다
TIME_PENALTY = 0.02            # 모델 선택에서 시간에 매기는 값
# 이 아래 지연은 벌점 0.
#
# 1초로 잡은 근거는 **이 도구의 사용 방식**이다. 관심종목 예측은 타이핑에
# 붙어 도는 기능이 아니라 하루 한 번 브리핑을 받는 기능이다. 1초 안이면
# 사용자가 기다린다고 느끼지 않는다.
#
# 200ms 로 잡았더니 앙상블(건당 4.0초, 균형정확도 54.7%)과
# 로지스틱(건당 0.15초, 52.1%)이 점수 0.521 대 0.521 로 붙었다.
# **2.6%p 를 0.15초와 맞바꾸는 셈**이라 이 용도에서는 명백히 손해다.
LATENCY_FREE_MS = 1000
MIN_COVERAGE = 0.20            # 학습 구간에서 이 비율 미만인 열은 뺀다

# ══════════════════════════════════════════════════════════════════════════
# 기본 설정은 **측정으로 정했다.**
#
# 38종목 × 40거래일 = 1,480건, 앙상블, 5일 재학습으로 6개 조합 전수 비교:
#
#     타깃    피처묶음     적중률   95% 신뢰구간    판정
#     ────────────────────────────────────────────────────
#     방향    기술만      50.00%  [47.5, 52.5]   미검증
#     방향    시장만      51.35%  [48.8, 53.9]   미검증
#     방향    기술+시장    49.53%  [47.0, 52.1]   미검증
#     변동성  기술만      53.18%  [50.6, 55.7]   유의미
#     변동성  시장만      52.84%  [50.3, 55.4]   유의미
#     변동성  기술+시장   55.27%  [52.7, 57.8]   유의미  ← 채택
#
# 두 가지가 동시에 보인다.
#   1. **방향은 어떤 조합으로도 50%를 유의미하게 못 넘는다.** 신뢰구간이
#      전부 50%를 품는다.
#   2. **변동성은 넘는다.** 그리고 변동성에서는 피처를 합칠수록 좋아지는데
#      (53.2 → 55.3), 방향에서는 합칠수록 나빠진다(50.0 → 49.5).
#      신호가 있으면 정보가 쌓이고, 없으면 과적합만 쌓인다는 뜻이다.
#
# 방향 예측을 지우지는 않았다. `target="방향"` 으로 그대로 돌릴 수 있고,
# 위 표를 리포트에 실어 **왜 주력으로 쓰지 않는지**를 보이는 게 더 정직하다.
DEFAULT_TARGET = "변동성"
# 뉴스를 **기본으로 쓴다.** 결측은 중립 0.5 로 채우므로 커버리지가 낮아도
# 누수가 생기지 않는다 (`news_feature` 주석 참조).
DEFAULT_FEATURES = "all"
NEUTRAL_NEWS = 0.5

TECH_FEATURES = [
    "ret_1", "ret_5", "ret_20", "ma_dev_5", "ma_dev_20", "ma_dev_60",
    "vol_20", "vol_ratio", "atr_ratio", "gap", "range_pct",
    "rsi_14", "dist_high_60", "dist_low_60", "streak", "dow",
    # 우상향 성질 — 종목마다 다르다
    "drift_250", "drift_60", "up_ratio_60",
]
# 테마 동조 — "비슷한 종목은 비슷하게 움직인다" (peer_features 주석 참조)
PEER_FEATURES = ["peer_ret_1", "peer_ret_5", "peer_disp", "rel_peer_1"]
MKT_FEATURES = [
    "mkt_ret_1", "mkt_ret_5", "mkt_vol_20", "mkt_dev_20",
    "rel_ret_1", "rel_ret_5", "beta_60", "corr_60",
]
NEWS_FEATURES = ["news_xfer"]


# ==========================================================================
# 1. 가격
# ==========================================================================
_CLOSE_HHMM = {"KR": (15, 30), "US": (16, 0)}
_SETTLE_MIN = 20            # 마감 후 체결·정정이 반영될 여유


def drop_partial_daily(df: pd.DataFrame, market: str) -> pd.DataFrame:
    """
    아직 장이 안 끝난 **오늘 일봉을 버린다.**

    장중에 일봉을 받으면 마지막 행이 그 시점까지의 중간값이다. 실측:
    2026-08-18 장중 삼성전자 일봉의 거래량이 1,408만이었는데 직전 사흘은
    2,100만~3,553만이었다. 종가로 찍힌 값도 종가가 아니라 현재가다.

    이걸 그대로 쓰면 두 군데가 동시에 망가진다.
        1. 평가:  미완성 종가를 정답으로 써서 적중률이 엉뚱해진다
        2. 예측:  "오늘 방향을 맞힌다"가 "이미 본 값을 되읽는다"가 된다
    """
    if not len(df):
        return df
    tz = MARKETS.get(market, MARKETS["KR"])["tz"]
    now = pd.Timestamp.now(tz=tz)
    hh, mm = _CLOSE_HHMM.get(market, (15, 30))
    settled = now.replace(hour=hh, minute=mm, second=0, microsecond=0) \
        + pd.Timedelta(minutes=_SETTLE_MIN)
    last = pd.Timestamp(df.index[-1]).normalize()
    if last.date() == now.date() and now < settled:
        return df.iloc[:-1]
    return df


def _yf(ticker: str, period: str = "6y") -> pd.DataFrame:
    import yfinance as yf
    df = yf.Ticker(ticker).history(period=period, interval="1d",
                                   auto_adjust=True)
    if not len(df):
        raise RuntimeError(f"{ticker} yfinance 조회 실패")
    df = df.rename(columns=str.lower)[["open", "high", "low", "close", "volume"]]
    df.index = pd.to_datetime(df.index).tz_localize(None).normalize()
    return df.sort_index()


def load_prices(name: str, keep_partial: bool = False) -> pd.DataFrame:
    """시장에 맞는 경로로 일봉을 가져온다. 열 이름은 소문자로 통일."""
    e = entry(name)
    if e["market"] == "KR":
        df = D.load_daily(e["code"], auto_download=True)
        if df is None:
            raise RuntimeError(f"{name}({e['code']}) 일봉을 받지 못했습니다.")
        df = df.sort_index()
        df.index = pd.to_datetime(df.index).normalize()
    else:
        df = _yf(e["code"])

    # ⚠️ 종가가 없는 행을 버린다.
    # yfinance 가 **OHLC 전부 NaN 인데 거래량만 있는 행**을 주는 일이 있다.
    # 실측: TSLA 2026-08-17 이 OHLC 전부 NaN, volume 25,419,203.
    # 종가가 없는 봉은 봉이 아니다.
    df = df[df["close"].notna()]
    return df if keep_partial else drop_partial_daily(df, e["market"])


_PRICE_MEMO: dict[tuple, pd.DataFrame] = {}


def _memo_prices(name: str) -> pd.DataFrame | None:
    """
    `load_prices` 의 메모. 테마 피처는 같은 시장 종목을 여러 번 훑는다.

    메모가 없으면 종목 하나를 만들 때마다 이웃 수십 종목을 다시 받는다 —
    미국 종목은 그때마다 yfinance 요청이 나가서 몇 분이 통째로 날아간다.
    """
    key = (name,)
    if key not in _PRICE_MEMO:
        try:
            _PRICE_MEMO[key] = load_prices(name)
        except Exception:
            _PRICE_MEMO[key] = None
    df = _PRICE_MEMO[key]
    return None if df is None else df


_IDX_CACHE: dict[str, pd.DataFrame] = {}
IDX_CACHE_DIR = CACHE_DIR / "index"


def _idx_disk_fresh(p: Path, market: str) -> bool:
    """지수 캐시도 **장 마감 기준**으로 만료시킨다 (패널 캐시와 같은 규칙)."""
    if not p.is_file():
        return False
    tz = MARKETS.get(market, MARKETS["KR"])["tz"]
    hh, mm = _CLOSE_HHMM.get(market, (15, 30))
    now = pd.Timestamp.now(tz=tz)
    settle = now.replace(hour=hh, minute=mm, second=0, microsecond=0) \
        + pd.Timedelta(minutes=_SETTLE_MIN)
    if now < settle:
        settle -= pd.Timedelta(days=1)
    return p.stat().st_mtime >= settle.timestamp()


def load_index(index_name: str) -> pd.DataFrame:
    """
    지수 일봉. 시장맥락 피처 8개의 기준이 된다.

    ⚠️ 이건 **네트워크에서 받는다.** 저장소에 들어 있지 않다.
    프로세스 메모만 두었더니 재시작할 때마다 다시 받았다 — 실측으로
    KOSPI 1.7초 · NASDAQ 2.3초이고, 그 값을 **시장별 첫 사용자가 혼자
    뒤집어썼다.** 그래서 디스크에도 남긴다. 하루 한 번만 받으면 된다.

    받지 못하면 예외를 올린다. 부르는 쪽(`serving.panel`)이 잡아서 시장맥락
    피처를 결측으로 두고, 학습 때 저장한 중앙값으로 대체한다 — 답은 나오되
    32개 중 8개가 빠진 상태다. `serving.health()` 가 그 사실을 보고한다.
    """
    if index_name in _IDX_CACHE:
        return _IDX_CACHE[index_name]

    market = INDICES[index_name]["market"]
    p = IDX_CACHE_DIR / f"{index_name.replace('&', '')}.parquet"
    if _idx_disk_fresh(p, market):
        try:
            df = pd.read_parquet(p)
            _IDX_CACHE[index_name] = df
            return df
        except Exception:
            pass                     # 캐시가 깨졌으면 그냥 다시 받는다

    code = INDICES[index_name]["index_code"]
    if market == "KR":
        import FinanceDataReader as fdr
        df = fdr.DataReader(code, "2018-01-01")
        df = df.rename(columns=str.lower)
        df.index = pd.to_datetime(df.index).normalize()
    else:
        df = _yf(code)

    try:
        IDX_CACHE_DIR.mkdir(parents=True, exist_ok=True)
        df.to_parquet(p)
    except Exception:
        pass                         # 캐시를 못 써도 결과는 그대로다
    _IDX_CACHE[index_name] = df
    return df


# ==========================================================================
# 2. 피처
# ==========================================================================
def tech_features(px: pd.DataFrame) -> pd.DataFrame:
    """기술적 지표. 전부 **당일 종가까지의 정보만** 쓴다."""
    c, h, lo, o, v = px["close"], px["high"], px["low"], px["open"], px["volume"]
    f = pd.DataFrame(index=px.index)
    ret = c.pct_change()
    f["ret_1"] = ret
    f["ret_5"] = c.pct_change(5)
    f["ret_20"] = c.pct_change(20)
    for w in (5, 20, 60):
        f[f"ma_dev_{w}"] = c / c.rolling(w, min_periods=w // 2).mean() - 1
    f["vol_20"] = ret.rolling(20, min_periods=10).std()
    f["vol_ratio"] = np.log1p(v) - np.log1p(v).rolling(20, min_periods=10).mean()
    tr = pd.concat([h - lo, (h - c.shift()).abs(), (lo - c.shift()).abs()],
                   axis=1).max(axis=1)
    f["atr_ratio"] = tr.rolling(14, min_periods=7).mean() / c
    f["gap"] = o / c.shift() - 1
    f["range_pct"] = (h - lo) / c.replace(0, np.nan)
    d = c.diff()
    up = d.clip(lower=0).rolling(14, min_periods=7).mean()
    dn = (-d).clip(lower=0).rolling(14, min_periods=7).mean()
    f["rsi_14"] = 100 - 100 / (1 + up / dn.replace(0, np.nan))
    f["dist_high_60"] = c / h.rolling(60, min_periods=20).max() - 1
    f["dist_low_60"] = c / lo.rolling(60, min_periods=20).min() - 1

    # 장기 드리프트 — 이 종목이 원래 우상향하는가.
    # 주식은 평균적으로 우상향하지만 **종목마다 그 정도가 다르다.**
    # 그 차이를 모델에 숫자로 준다. 250봉(약 1년)과 60봉을 함께 주어
    # "원래 오르던 종목인가"와 "요즘도 오르고 있나"를 구분할 수 있게 한다.
    f["drift_250"] = ret.rolling(250, min_periods=120).mean()
    f["drift_60"] = ret.rolling(60, min_periods=30).mean()
    # 상승일 비율 — 드리프트를 크기가 아니라 **빈도**로 본 것.
    # 큰 하루가 평균을 끌어올린 경우와 꾸준히 오른 경우를 갈라 준다.
    f["up_ratio_60"] = (ret > 0).rolling(60, min_periods=30).mean()

    # 연속 상승/하락 일수. 부호로 방향, 크기로 길이.
    sign = np.sign(ret.fillna(0))
    grp = (sign != sign.shift()).cumsum()
    f["streak"] = sign * sign.groupby(grp).cumcount().add(1)
    f["dow"] = px.index.dayofweek
    return f


PEER_K = 6              # 테마 이웃 몇 개를 쓸 것인가
PEER_FIT_FRAC = 0.55    # 이웃을 **고르는 데만** 쓰는 앞쪽 구간 비율
_PEER_POOL: dict[str, pd.DataFrame] = {}


def _peer_pool(market: str) -> pd.DataFrame:
    """같은 시장 종목들의 종가 패널. 테마 피처의 재료."""
    if market in _PEER_POOL:
        return _PEER_POOL[market]
    cols = {}
    for e in all_entries():
        if e["market"] != market:
            continue
        df = _memo_prices(e["label"])
        if df is not None and len(df) > 300:
            cols[e["label"]] = df["close"]
    _PEER_POOL[market] = (pd.DataFrame(cols).sort_index()
                          if cols else pd.DataFrame())
    return _PEER_POOL[market]


def peer_features(name: str, px: pd.DataFrame) -> pd.DataFrame:
    """
    테마 동조 — **"비슷한 종목들은 비슷하게 움직인다"** 를 피처로 만든다.

    지수 수익률(`mkt_ret_1`)은 시장 전체의 평균이라 반도체가 오르고 은행이
    빠지는 날에는 서로 상쇄돼 0에 가까워진다. 그런 날 실제로 쓸모 있는 정보는
    **"내 이웃들이 어제 어땠나"** 다. 그래서 지수와 별개로 넣는다.

    이웃은 어떻게 고르는가
    ----------------------
    같은 시장 종목들과의 일간 수익률 상관을 재서 상위 6개를 쓴다.

    ⚠️ 상관을 **전체 구간에서** 재면 그 자체가 미래를 보는 것이다.
    "평가 구간에서 나와 가장 닮게 움직인 종목"을 이웃으로 고르는 셈이라,
    이웃 수익률이 곧 내 수익률의 누설이 된다. 그래서 **앞쪽 55% 구간에서만**
    상관을 재고, 그 뒤로는 그 이웃을 고정한다. 평가 구간은 이웃 선정에
    한 번도 쓰이지 않는다.

        [───── 앞 55%: 이웃 고르기 ─────][───── 뒤 45%: 안 봄 ─────][평가]

    내는 값
    -------
        peer_ret_1    이웃들의 어제 평균 수익률       — 테마가 어제 어땠나
        peer_ret_5    이웃들의 5일 평균 수익률        — 테마의 최근 흐름
        peer_disp     이웃 수익률의 표준편차          — 테마가 뭉쳐 있나 흩어졌나
        rel_peer_1    내 수익률 − 이웃 평균           — 테마 안에서 나만 빠졌나
    """
    f = pd.DataFrame(index=px.index)
    for c in ("peer_ret_1", "peer_ret_5", "peer_disp", "rel_peer_1"):
        f[c] = np.nan

    e = entry(name)
    pool = _peer_pool(e["market"])
    if pool.empty or e["label"] not in pool.columns or pool.shape[1] < 4:
        return f

    rets = pool.pct_change()
    fit_n = int(len(rets) * PEER_FIT_FRAC)
    if fit_n < 120:
        return f
    head = rets.iloc[:fit_n]
    if head[e["label"]].notna().sum() < 60:
        return f

    corr = head.corrwith(head[e["label"]]).drop(labels=[e["label"]],
                                                errors="ignore")
    peers = corr.dropna().sort_values(ascending=False).head(PEER_K).index
    if not len(peers):
        return f

    pr = rets[list(peers)].reindex(px.index)
    own = px["close"].pct_change()
    f["peer_ret_1"] = pr.mean(axis=1)
    f["peer_ret_5"] = pr.rolling(5, min_periods=3).mean().mean(axis=1)
    f["peer_disp"] = pr.std(axis=1)
    f["rel_peer_1"] = own - f["peer_ret_1"]
    return f


def market_features(px: pd.DataFrame, idx: pd.DataFrame) -> pd.DataFrame:
    """
    시장 맥락 — **v2에서 새로 넣은 묶음.**

    개별 종목의 다음날 방향은 시장 방향과 강하게 얽혀 있다. "어제 시장은
    올랐는데 이 종목만 크게 빠졌다"는 정보는 기술적 지표만으로 표현되지
    않는다. 그 격차(`rel_ret`)와 종목이 시장에 얼마나 붙어 움직이는지
    (`beta_60`, `corr_60`)를 함께 준다.

    지수 시계열을 종목 날짜에 맞출 때 `reindex(...).ffill()` 을 쓴다.
    휴장일이 서로 달라(한국 대체공휴일 등) 그냥 join 하면 구멍이 생긴다.
    """
    f = pd.DataFrame(index=px.index)
    ic = idx["close"].reindex(px.index).ffill()
    iret = ic.pct_change()
    sret = px["close"].pct_change()

    f["mkt_ret_1"] = iret
    f["mkt_ret_5"] = ic.pct_change(5)
    f["mkt_vol_20"] = iret.rolling(20, min_periods=10).std()
    f["mkt_dev_20"] = ic / ic.rolling(20, min_periods=10).mean() - 1
    f["rel_ret_1"] = sret - iret
    f["rel_ret_5"] = px["close"].pct_change(5) - f["mkt_ret_5"]

    cov = sret.rolling(60, min_periods=30).cov(iret)
    var = iret.rolling(60, min_periods=30).var()
    f["beta_60"] = cov / var.replace(0, np.nan)
    f["corr_60"] = sret.rolling(60, min_periods=30).corr(iret)
    return f


def news_feature(name: str, index: pd.DatetimeIndex,
                 verbose: bool = False) -> pd.Series:
    """평가 유니버스용 얇은 껍데기. 실제 계산은 `news_series` 가 한다."""
    e = entry(name)
    return news_series(e["code"], e["query"], e["label"], e["market"],
                       index, verbose=verbose)


def news_series(code: str, query: str, label: str, market: str,
                index: pd.DatetimeIndex, verbose: bool = False) -> pd.Series:
    """
    `news_xfer` — 미국 뉴스로 학습한 전이 모델의 출력 한 개.

    9개 뉴스 피처를 그대로 국내 모델에 넣으면 커버리지 0.27% 문제가 그대로
    돌아온다. 하나로 압축하면 "뉴스가 있는 날엔 값, 없는 날엔 결측"으로
    깔끔하게 다뤄진다. 전이 모델이 미국 홀드아웃에서 기준을 못 넘으면
    (`newsxfer.usable()`) 이 피처 자체를 만들지 않는다.

    ⚠️ 유니버스를 거치지 않고 **인자로만** 받는다.
    서비스에서는 임의 종목이 들어오므로 `entry()` 를 부를 수 없다. 그런데
    학습과 서빙이 다른 함수를 쓰면 뉴스 채점 방식이 조용히 갈라진다 —
    그러면 리포트에 적은 성능이 실제 서비스에서 재현되지 않는다. 그래서
    양쪽 모두 이 함수 하나만 쓴다.
    """
    # ⚠️ 결측을 **중립 0.5 로 채운다.** NaN 으로 두면 안 된다.
    # 국내는 뉴스 아카이브가 며칠뿐이라 NaN 이 대부분인데, 그 상태로 학습하면
    # 모델이 "값이 있는 행 = 최근 며칠" 을 외워 확률이 0%/100% 로 포화한다
    # (실측). 0.5 로 채우면 열이 100% 차서 그 누수가 구조적으로 불가능해지고,
    # "뉴스 없음 = 중립" 이라는 뜻도 정확히 표현된다.
    e = {"code": code, "query": query, "label": label, "market": market}
    out = pd.Series(np.nan, index=index, name="news_xfer")

    # ① 과거 — xforecast 아카이브 (미국 종목 중 겹치는 것만, 2019~2022).
    # 이게 없으면 열이 사실상 상수가 되어 뉴스에서 배울 것이 없다.
    try:
        hist = NX.historic_features(e["code"])
        if len(hist):
            out.update(NX.apply(hist).reindex(index).dropna())
    except Exception:
        pass

    # ② 최근 — 구글 뉴스 RSS + (국내) 직접 모은 아카이브.
    try:
        rows = N.fetch_google_news(e["query"], days=7,
                                   market=MARKETS[e["market"]]["news_locale"])
        # 아카이브는 **양쪽 시장 모두** 쓴다. 예전엔 국내만 읽었는데,
        # 과거 수집(`news.backfill`)을 미국에도 돌리므로 이제 둘 다 필요하다.
        arch = N.archive_load(e["label"])
        if len(arch):
            rows = arch.to_dict("records") + rows
    except Exception:
        rows = []

    if rows:
        # ⚠️ 제목만으로 접으면 안 된다.
        # 같은 제목이 다른 날 다시 나오면 그날 뉴스가 통째로 사라진다.
        # **(제목, 날짜)** 로 접어야 그날의 건수가 보존된다.
        df = pd.DataFrame(rows)
        df["_d"] = pd.to_datetime(df["ts"], utc=True, errors="coerce").dt.date
        df = df.drop_duplicates(subset=["title", "_d"]).drop(columns=["_d"])
        # ⚠️ 전이 피처는 **사전으로** 채점한다 (BERT 아님).
        # 전이 모델은 미국 뉴스를 사전으로 채점한 값으로 학습했다. BERT 값을
        # 넣으면 학습 때와 척도가 달라 미국에서 배운 임계값이 안 맞는다.
        # 국문 사전(`lexicon_ko`)이 영문 사전 어휘를 국내 표현으로 대응시켜
        # 두었으므로 양쪽이 같은 척도가 된다. (sentiment.score_titles_lex 주석)
        sc = SENT.score_titles_lex(df["title"].tolist(), market=e["market"],
                                   verbose=verbose)
        sc["ts"] = pd.to_datetime(df["ts"].to_numpy(), utc=True,
                                  errors="coerce")
        sc = sc.dropna(subset=["ts"])
        nf = SENT.daily_features(sc)
        if len(nf):
            nf = nf.set_index(pd.to_datetime(nf["tday"]).dt.normalize())
            out.update(NX.apply(nf).reindex(index).dropna())

    # ⚠️ 남은 결측은 **중립 0.5**. NaN 으로 두면 안 된다.
    # 뉴스가 드문 종목은 NaN 이 대부분인데, 그 상태로 학습하면 모델이
    # "값이 있는 행 = 최근 며칠" 을 외워 확률이 0%/100% 로 포화한다(실측).
    # 0.5 로 채우면 열이 100% 차서 그 누수가 구조적으로 불가능해지고,
    # "뉴스 없음 = 중립" 이라는 뜻도 정확히 표현된다.
    return out.fillna(NEUTRAL_NEWS)


PANEL_CACHE_DIR = CACHE_DIR / "panels"


def _panel_cache_path(name: str) -> Path:
    return PANEL_CACHE_DIR / f"{entry(name)['code']}.parquet"


def _panel_cache_stale(path: Path) -> bool:
    """
    캐시가 낡았는가.

    가격은 매일, 뉴스 아카이브는 수집할 때마다 바뀐다. 둘 중 **더 나중** 것보다
    캐시가 오래됐으면 다시 만든다. 날짜만 보고 판단하면 아카이브를 소급
    수집한 날 캐시가 그대로 살아남아, 새로 모은 뉴스가 반영되지 않는다.
    """
    if not path.is_file():
        return True
    mt = path.stat().st_mtime
    arch = N.NEWS_ARCHIVE
    if arch.is_file() and arch.stat().st_mtime > mt:
        return True
    # 장 마감 뒤에 만든 캐시는 그날 안에서는 유효하다.
    age_h = (time.time() - mt) / 3600
    return age_h > 12


def build(name: str, verbose: bool = False,
          use_cache: bool = True) -> pd.DataFrame:
    """
    종목 하나 → (날짜 × 피처 + 라벨) 표.

    라벨은 두 개다.
        y_up   다음 거래일 상승 여부 (분류)
        y_ret  다음 거래일 수익률    (회귀 — 등락률 예측용)

    마지막 행은 정답이 없지만(아직 안 일어났다) 그 행이 곧 "내일 예측"의
    입력이라 남겨 둔다.
    """
    e = entry(name)
    cache_path = _panel_cache_path(name)
    if use_cache and not _panel_cache_stale(cache_path):
        try:
            return pd.read_parquet(cache_path)
        except Exception:
            pass                     # 캐시가 깨졌으면 그냥 다시 만든다

    px = load_prices(name)
    f = tech_features(px)
    f = pd.concat([f, market_features(px, load_index(e["index"])),
                   peer_features(name, px)], axis=1)
    f["news_xfer"] = news_feature(name, f.index, verbose=verbose)

    # ⚠️ 무한대를 결측으로 바꾼다.
    # 피처 대부분이 나눗셈이라 분모가 0이면 ±inf 가 생긴다. 실측에서
    # 초소형주(Brenx Ltd 등)의 60일 최저가가 0이라 `dist_low_60` 이 inf 가 됐다.
    #
    # `_cols` 의 커버리지 검사는 `np.isfinite` 를 쓰므로 inf 를 결측으로 세지만,
    # **열 전체를 버리지는 않는다.** 그래서 대부분 정상이고 몇 칸만 inf 인 열이
    # 그대로 통과해 SimpleImputer 에서
    # "Input X contains infinity or a value too large for dtype('float32')" 로 죽는다.
    # inf 는 값이 아니라 나눗셈 사고이므로 여기서 NaN 으로 바꾸는 게 맞다.
    f = f.replace([np.inf, -np.inf], np.nan)

    f["close"] = px["close"]
    f["y_ret"] = px["close"].shift(-1) / px["close"] - 1
    f["y_up"] = np.where(f["y_ret"].notna(), (f["y_ret"] > 0).astype(float),
                         np.nan)

    # 변동성 타깃 — **내일 크게 움직일 것인가.**
    #
    # 방향(y_up)과 달리 이건 실제로 맞는다. 38종목 × 40일 = 1,480건 측정:
    #     1일 방향    51.42%  [48.9, 54.0]   ← 구간이 50%를 품는다 = 미검증
    #     5일 방향    48.24%  [45.7, 50.8]   ← 마찬가지
    #     변동성      54.32%  [51.8, 56.8]   ← **하한이 50%를 넘는다**
    #
    # 이유는 알려져 있다. 방향은 차익거래로 지워지지만(누가 내일 오를 걸
    # 알면 오늘 산다), **변동성 군집**은 지워지지 않는다 — "오늘 크게
    # 움직였으면 내일도 크게 움직인다"는 성질은 알아도 돈이 되지 않기 때문이다.
    # 그래서 예측 가능성이 남아 있고, 저시력 사용자에게는 방향보다 오히려
    # 쓸모가 있다. "내일 이 종목은 많이 흔들립니다"가 실제로 행동을 바꾼다.
    absr = px["close"].pct_change().abs()
    med = absr.rolling(20, min_periods=10).median()
    nxt = absr.shift(-1)
    f["y_vol"] = np.where(nxt.notna() & med.notna(),
                          (nxt > med).astype(float), np.nan)
    f["vol_median_20"] = med * 100          # 기준선(%). 문안에 그대로 쓴다

    if use_cache:
        try:
            PANEL_CACHE_DIR.mkdir(parents=True, exist_ok=True)
            f.to_parquet(cache_path)
        except Exception:
            pass                     # 캐시 못 써도 결과는 그대로다
    return f


def _cols(df: pd.DataFrame, feature_set: str = DEFAULT_FEATURES,
          min_coverage: float = MIN_COVERAGE) -> list[str]:
    """
    쓸 수 있는 열만 고른다.

    ⚠️ 학습 구간 안에서 다시 골라야 한다.
    전체 df 기준으로 고르면, 뉴스 열이 최근 며칠에만 값이 있는 종목에서
    학습 구간이 전부 NaN 인 열을 넘기게 된다. sklearn 의 구간화가
    "window shape cannot be larger than input array shape" 로 죽는다.

    커버리지 하한도 필수다. 값이 0.2% 만 찬 열을 imputer 로 채우면 남은
    소수 행이 극단 이상치가 되고, 로지스틱회귀가 **"이 행이 최근 며칠 중
    하나인가"를 외워** 확률이 0%/100% 로 포화한다(실측).
    """
    pool = {"tech": TECH_FEATURES, "mkt": MKT_FEATURES,
            "news": NEWS_FEATURES, "peer": PEER_FEATURES,
            "tech+mkt": TECH_FEATURES + MKT_FEATURES,
            "tech+mkt+peer": TECH_FEATURES + MKT_FEATURES + PEER_FEATURES,
            "all": (TECH_FEATURES + MKT_FEATURES + PEER_FEATURES
                    + NEWS_FEATURES)}[feature_set]
    n = max(len(df), 1)
    keep = []
    for c in pool:
        if c not in df.columns:
            continue
        v = df[c].to_numpy(float)
        ok = np.isfinite(v)
        if ok.sum() / n < min_coverage:
            continue
        if np.unique(v[ok]).size >= 2:
            keep.append(c)
    return keep


# ==========================================================================
# 3. 모델
# ==========================================================================
def _models() -> dict:
    """
    후보. 전부 **소표본에서 견디는** 쪽으로 골랐다.
    종목당 학습 표본이 1,000~1,800개뿐이라 깊은 모델은 바로 외운다.
    """
    from sklearn.ensemble import (HistGradientBoostingClassifier,
                                  RandomForestClassifier)
    from sklearn.impute import SimpleImputer
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler

    return {
        "로지스틱회귀": lambda: make_pipeline(
            SimpleImputer(strategy="median"), StandardScaler(),
            LogisticRegression(C=0.1, max_iter=1000,
                               class_weight="balanced",
                               random_state=RANDOM_SEED)),
        "랜덤포레스트": lambda: make_pipeline(
            SimpleImputer(strategy="median"),
            RandomForestClassifier(n_estimators=300, max_depth=6,
                                   min_samples_leaf=20, n_jobs=-1,
                                   class_weight="balanced",
                                   random_state=RANDOM_SEED)),
        # class_weight="balanced" 로 쏠린 라벨을 보정한다. 이걸 안 하면
        # 모델이 다수 클래스만 찍어 균형정확도가 0.5 에 붙는다.
        "그래디언트부스팅": lambda: HistGradientBoostingClassifier(
            max_depth=3, max_iter=150, learning_rate=0.05,
            min_samples_leaf=30, l2_regularization=1.0,
            class_weight="balanced", random_state=RANDOM_SEED),
    }


ENSEMBLE = "앙상블"


def _make_clf(kind: str, Xtr, ytr):
    """
    분류기를 세워 **적합된 객체**를 돌려준다.

    앙상블은 세 모델 확률의 **단순 평균**이다. 가중 평균을 쓰려면 가중치를
    정할 검증 구간이 또 필요한데, 평가 구간이 7일뿐인 상황에서 그건
    과적합으로 가는 지름길이다. 단순 평균은 튜닝할 것이 없다.
    """
    if kind == ENSEMBLE:
        return [_make_clf(k, Xtr, ytr) for k in _models()]
    m = _models()[kind]()
    m.fit(Xtr, ytr)
    return m


def _proba(clf, Xte) -> np.ndarray:
    if isinstance(clf, list):
        return np.mean([_proba(c, Xte) for c in clf], axis=0)
    return clf.predict_proba(Xte)[:, 1]


# 판정 임계값을 찾을 범위. 0.5 에서 너무 멀어지면 사실상 "한쪽만 찍기"가 되어
# 균형정확도가 도로 0.5 로 내려간다. 그 구간을 아예 후보에서 뺀다.
THR_GRID = np.round(np.arange(0.35, 0.661, 0.01), 3)
THR_HOLDOUT = 0.20     # 학습 구간의 뒤 20% 를 임계값 고르는 데만 쓴다
# 임계값을 **매 재학습마다** 새로 고르지는 않는다.
# 임계값 고르기는 모델을 한 번 더 적합하는 일이라 워크포워드 비용이 그대로
# 두 배가 된다 (실측: 38종목 × 40일 × 4모델이 몇 시간). 임계값은 하루 만에
# 크게 변하는 값이 아니므로 5회 재학습마다 갱신하고 사이에는 재사용한다.
THR_EVERY = 5


def _pick_threshold(kind: str, Xtr: np.ndarray, ytr: np.ndarray) -> float:
    """
    판정 임계값을 **학습 구간 안에서만** 고른다.

    왜 0.5 가 아닌가
    ----------------
    라벨이 상승 55% 로 쏠려 있으면 확률도 그쪽으로 밀려 나온다. 0.5 로 자르면
    상승은 잘 맞히고 하락은 거의 못 맞히는 모델이 되는데, 균형정확도는 두 쪽을
    따로 재서 평균하므로 그게 그대로 손해가 된다. 실측에서 임계값만 옮겨도
    그래디언트부스팅이 0.550 → 0.586 이 됐다.

    ⚠️ 임계값을 평가 구간에서 고르면 그건 부정행위다.
    그래서 학습 구간을 시간순으로 8:2 로 갈라, 앞 80%로 학습하고 **뒤 20%**
    에서만 임계값을 고른다. 고른 뒤에는 전체 학습 구간으로 다시 적합한다.
    평가일 데이터는 어느 단계에서도 쓰이지 않는다.

        [────────── 학습 구간 ──────────][평가일]
        [── 적합 80% ──][임계값 고르기 20%]   ↑ 여기는 안 본다

    후보 중 최고점이 여러 개면 **0.5 에 가장 가까운 것**을 고른다. 홀드아웃
    20%는 표본이 작아 최고점이 뾰족하게 흔들리는데, 그 뾰족한 끝을 그대로
    집으면 다음 날 무너진다. 0.5 쪽으로 당겨 두면 최소한 원래 동작으로 돌아간다.
    """
    n = len(Xtr)
    cut = int(n * (1 - THR_HOLDOUT))
    if n < MIN_TRAIN or cut < 100 or n - cut < 40:
        return 0.5
    y_ho = ytr[cut:]
    if len(np.unique(ytr[:cut])) < 2 or len(np.unique(y_ho)) < 2:
        return 0.5
    try:
        clf = _make_clf(kind, Xtr[:cut], ytr[:cut])
        p = _proba(clf, Xtr[cut:])
    except Exception:
        return 0.5

    up, dn = y_ho == 1, y_ho == 0
    scores = np.array([((p[up] > t).mean() + (p[dn] <= t).mean()) / 2
                       for t in THR_GRID])
    best = scores.max()
    tied = THR_GRID[scores >= best - 1e-9]
    return float(tied[np.argmin(np.abs(tied - 0.5))])


def _make_reg(Xtr, rtr):
    """
    등락률(%) 회귀. 방향과 별개로 **얼마나** 움직일지를 낸다.

    수익률 꼬리가 두꺼워 그대로 회귀하면 극단값만 쫓는다. 1/99 분위로
    자르고 학습한다. 예측값은 "이 정도 크기의 움직임" 정도로만 읽어야 한다.
    """
    from sklearn.ensemble import HistGradientBoostingRegressor

    r = np.asarray(rtr, dtype=float)
    lo, hi = np.nanquantile(r, [0.01, 0.99])
    m = HistGradientBoostingRegressor(
        max_depth=3, max_iter=150, learning_rate=0.05,
        min_samples_leaf=30, l2_regularization=1.0,
        random_state=RANDOM_SEED)
    m.fit(Xtr, np.clip(r, lo, hi))
    return m


# ==========================================================================
# 4. 워크포워드
# ==========================================================================
def balanced_accuracy(hit: pd.Series, actual: pd.Series) -> float:
    """
    균형정확도 — **쏠린 라벨에서도 0.5 가 진짜 무작위인 지표.**

    왜 이게 필요한가
    ----------------
    단순 적중률은 라벨이 쏠리면 부풀려진다. 평가 구간 7일 중 5일이 "상승"이면
    **항상 상승으로만 찍어도 71%**다. 실제로 그 함정에 빠져 "변동성 57.9%,
    유의미" 라고 보고했다가 뒤집혔다(진짜 기준선이 67.2% 였다).

    균형정확도는 클래스별 적중률을 따로 재서 평균한다.

        균형정확도 = (상승일 적중률 + 하락일 적중률) / 2

    항상 상승으로만 찍으면 상승일 적중률 1.0, 하락일 적중률 0.0 → 0.5 다.
    **쏠림이 얼마든 무작위는 정확히 0.5** 이므로, 기준선을 0.5 로 두고
    비교하는 것이 그대로 정당해진다.

    시장이 우상향이라 상승일이 많은 것도 같은 방식으로 처리된다 —
    그 편향을 공짜로 먹지 못하게 막는 것이 이 지표의 요점이다.
    """
    d = pd.DataFrame({"hit": hit.astype(float), "y": actual})
    per = d.groupby("y")["hit"].mean()
    return float(per.mean()) if len(per) >= 2 else float("nan")


def baseline_rate(actual: pd.Series) -> float:
    """
    무지성 기준선 = **다수 클래스로만 찍었을 때의 적중률.**

    ⚠️ 0.5 가 아니다. 여기서 크게 틀렸었다.
    처음엔 윌슨 신뢰구간이 0.5 를 넘으면 "유의미"로 판정했다. 라벨이 반반일
    때만 맞는 기준이다. 변동성 라벨(내일 |수익률| > 최근 중앙값)은 학습
    구간 전체로는 반반에 가깝지만, **7일짜리 평가 구간에서는 우연히 쏠린다.**
    7일 중 5일이 같은 값이면 기준선이 이미 71%다.

    그 결과 실제로 이렇게 보고했다가 뒤집혔다.
        7일 변동성  적중률 57.92%  기준선 67.18%  →  실은 9.3%p **미달**
        7일 방향    적중률 52.90%  기준선 67.18%  →  실은 14.3%p **미달**
        40일 변동성 적중률 55.27%  기준선 55.00%  →  +0.27%p (사실상 0)

    적중률 숫자만 보면 안 되고 항상 이 기준선과 나란히 놓아야 한다.
    """
    if not len(actual):
        return 0.5
    return float(actual.value_counts(normalize=True).max())


def beats_baseline(k: int, n: int, base: float, z: float = 1.96) -> bool:
    """
    모델이 무지성 기준선을 **유의미하게** 넘었는가.

    적중률의 윌슨 신뢰구간 하한이 기준선보다 위에 있어야 한다.
    "적중률 > 기준선" 만으로는 부족하다 — 표본이 작으면 우연으로도 넘는다.
    """
    lo, _hi = _wilson(k, n, z)
    return bool(lo > base)


def _wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """
    이항 비율의 윌슨 신뢰구간.

    단순 정규근사(p ± 1.96·√(p(1-p)/n))를 쓰면 n이 작을 때 구간이
    [1.09, 0.62] 처럼 뒤집히거나 1을 넘는다. n=7 에서는 반드시 윌슨이어야 한다.
    """
    if n <= 0:
        return (0.0, 1.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * np.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


TARGETS = {
    "방향": {"col": "y_up", "상승": "상승", "하락": "하락"},
    "변동성": {"col": "y_vol", "상승": "크게움직임", "하락": "잔잔함"},
}


def walk_forward(name: str, n_days: int = EVAL_DAYS,
                 feature_set: str = DEFAULT_FEATURES, refit_every: int = 1,
                 target: str = DEFAULT_TARGET,
                 models: "list[str] | None" = None,
                 df: pd.DataFrame | None = None,
                 verbose: bool = True) -> "tuple[pd.DataFrame, dict]":
    """
    평가 구간을 하루씩 앞으로 걸으며 예측한다.

    target
        "방향"    다음날 상승/하락 — 측정 결과 동전 던지기와 구별 안 됨
        "변동성"  다음날 크게 움직일지 — **유일하게 유의미하게 맞는 타깃**

    refit_every
        1이면 매일 재학습(가장 정확·가장 비쌈), 5면 5거래일마다 재학습.
        재학습을 안 하는 날은 **직전 학습 모델을 그대로 쓴다** — 실무에서
        주 1회 재학습하는 것과 같은 구조다. 미래를 보지 않는 성질은 유지된다.
    """
    tgt = TARGETS[target]
    df = build(name, verbose=verbose) if df is None else df
    df = df.assign(y_up=df[tgt["col"]])
    usable = df[df["y_up"].notna()]
    if len(usable) < MIN_TRAIN + n_days:
        raise RuntimeError(f"{name}: 표본 부족 ({len(usable)}행)")

    kinds = models or [*_models(), ENSEMBLE]
    eval_idx = usable.index[-n_days:]
    rows: list[dict] = []
    timing: dict[str, float] = {}
    e = entry(name)

    for kind in kinds:
        t0 = time.time()
        cache: dict = {}
        n_refit = 0
        for j, d in enumerate(eval_idx):
            pos = usable.index.get_loc(d)
            tr = usable.iloc[:max(pos - 1, 0)]
            if len(tr) < MIN_TRAIN:
                continue
            use = _cols(tr, feature_set)
            if not use:
                continue
            Xte = usable.loc[[d], use].to_numpy(np.float32)

            # 재학습 주기에 걸린 날에만 모델을 새로 세운다.
            # ⚠️ 분류기뿐 아니라 **등락률 회귀도 같이 캐시해야 한다.**
            # 처음엔 회귀만 매일 새로 적합해서, refit_every 를 5로 올려도
            # 시간이 거의 안 줄었다. 병목이 거기 있었다.
            if j % refit_every == 0 or "clf" not in cache:
                Xtr = tr[use].to_numpy(np.float32)
                ytr = tr["y_up"].to_numpy(int)
                if len(np.unique(ytr)) < 2:
                    continue
                try:
                    # 임계값은 THR_EVERY 번에 한 번만 새로 고르고 재사용한다.
                    thr = (cache.get("thr", 0.5)
                           if (n_refit % THR_EVERY) else
                           _pick_threshold(kind, Xtr, ytr))
                    cache = {"use": use, "clf": _make_clf(kind, Xtr, ytr),
                             "reg": _make_reg(Xtr, tr["y_ret"].to_numpy(float)),
                             "thr": thr}
                    n_refit += 1
                except Exception as ex:
                    if verbose:
                        print(f"    {kind} {d:%m-%d} 학습 실패: {type(ex).__name__}")
                    continue
                n_train, n_feat = len(tr), len(use)
            try:
                p = float(_proba(cache["clf"], Xte)[0])
                mag = float(cache["reg"].predict(Xte)[0])
            except Exception as ex:
                if verbose:
                    print(f"    {kind} {d:%m-%d} 실패: {type(ex).__name__}")
                continue

            y = float(usable.loc[d, "y_up"])
            ret = float(usable.loc[d, "y_ret"])
            thr = float(cache.get("thr", 0.5))
            rows.append({
                "종목": e["label"], "지수": e["index"], "구분": e["tier"],
                "타깃": target, "모델": kind, "날짜": d,
                "예측": tgt["상승"] if p > thr else tgt["하락"],
                "상승확률": round(p, 4),
                "임계값": round(thr, 3),
                "예상등락률": round(mag * 100, 3),
                "실제": tgt["상승"] if y > 0.5 else tgt["하락"],
                "실제등락률": round(ret * 100, 3),
                "적중": int((p > thr) == (y > 0.5)),
                "등락률오차": round(abs(mag - ret) * 100, 3),
                "학습표본": int(n_train), "사용피처": int(n_feat),
            })
        el = time.time() - t0
        timing[kind] = timing.get(kind, 0.0) + el
        got = [r for r in rows if r["모델"] == kind]
        if verbose and got:
            acc = np.mean([r["적중"] for r in got])
            print(f"    {kind:16s} {acc * 100:5.1f}%  "
                  f"({sum(r['적중'] for r in got)}/{len(got)})  {el:.1f}초")
    return pd.DataFrame(rows), timing


# ==========================================================================
# 5. 집계
# ==========================================================================
def _class_counts(s: pd.Series) -> pd.Series:
    """두 클래스의 표본 수. 라벨 이름에 의존하지 않는다."""
    vc = s.value_counts()
    return pd.Series({"다수클래스일": int(vc.iloc[0]),
                      "소수클래스일": int(vc.iloc[1]) if len(vc) > 1 else 0})


def summarize(wf: pd.DataFrame, by: str = "종목") -> pd.DataFrame:
    """`by` × 모델 적중률 + 윌슨 신뢰구간 + 무지성 기준선."""
    if not len(wf):
        return pd.DataFrame()
    g = wf.groupby([by, "모델"])
    out = g.agg(평가일=("적중", "size"), 적중=("적중", "sum"),
                평균확률=("상승확률", "mean"),
                등락률MAE=("등락률오차", "mean")).reset_index()
    # 균형정확도 — 쏠린 라벨에서도 0.5 가 무작위인 지표 (함수 주석 참조)
    bal = (wf.groupby([by, "모델"])
             .apply(lambda s: balanced_accuracy(s["적중"], s["실제"]))
             .rename("균형정확도").reset_index())
    out = out.merge(bal, on=[by, "모델"], how="left")
    out["균형정확도"] = out["균형정확도"].round(4)
    # 클래스별 표본 수. 균형정확도의 무작위 분포를 그리려면 이게 필요하다 —
    # 이항분포 하나가 아니라 **두 이항분포의 평균**이기 때문이다.
    #
    # ⚠️ `실제` 는 0/1 이 아니라 **라벨 문자열**이다("잔잔함"/"크게움직임",
    # "상승"/"하락"). 처음엔 `(s == 1).sum()` 으로 셌는데 전부 0 이 나왔고,
    # 그 0 을 그대로 시뮬레이션에 넣는 바람에 "동전 던지기 분포"가 실제보다
    # 두 배 넓게 그려졌다. 타깃마다 라벨이 다르므로 값으로 세지 않고
    # **빈도 순위로** 센다.
    cls = (wf.groupby([by, "모델"])["실제"]
             .apply(_class_counts).unstack().reset_index())
    out = out.merge(cls, on=[by, "모델"], how="left")
    out["적중률"] = (out["적중"] / out["평가일"]).round(4)
    out["등락률MAE"] = out["등락률MAE"].round(3)
    ci = [_wilson(int(k), int(n)) for k, n in zip(out["적중"], out["평가일"])]
    out["신뢰구간하한"] = [round(a, 4) for a, _b in ci]
    out["신뢰구간상한"] = [round(b, 4) for _a, b in ci]
    # ⚠️ 기준선은 날짜별로 한 번씩만 센다. wf 는 모델 수만큼 행이 늘어나 있다.
    base = (wf.drop_duplicates(subset=[by, "날짜", "종목"])
              .groupby(by)["실제"]
              .apply(lambda s: float(s.value_counts(normalize=True).max())))
    out["무지성기준선"] = out[by].map(base).round(4)
    out["기준선대비"] = (out["적중률"] - out["무지성기준선"]).round(4)
    return out.sort_values([by, "적중률"], ascending=[True, False])


def select_model(wf: pd.DataFrame, timing: dict[str, float]) -> pd.DataFrame:
    """
    무엇을 쓸지 정한다.

        점수 = 균형정확도 − TIME_PENALTY × log10(1 + 초과ms / LATENCY_FREE_MS)
        초과ms = max(0, 건당ms − LATENCY_FREE_MS)

    ⚠️ 시간 벌점을 **총소요초로 매기면 안 된다.**
    처음엔 `log10(1 + 총소요초)` 였는데, 총소요초는 **백테스트가 얼마나 오래
    돌았는지**일 뿐 사용자가 겪는 지연이 아니다. 같은 모델인데도

        재학습 주 1회(40일)  앙상블 총 600초  → 벌점 0.056
        재학습 매일  (40일)  앙상블 총 3000초 → 벌점 0.070

    처럼 벌점이 달라져, **평가 설정을 바꾸면 선택된 모델이 뒤집혔다.**
    실측에서 어블레이션은 앙상블(54.22%)을 골랐는데 같은 데이터로 매일
    재학습하자 더 낮은 모델이 1등이 됐다. 모델의 성질이 아니라 실험의
    길이가 결정한 것이라 명백히 틀렸다.

    그래서 **건당 지연**으로 매긴다. 여기에 더해 `LATENCY_FREE_MS` 아래는
    벌점 0이다 — 관심종목을 눌렀을 때 20ms 든 80ms 든 사용자는 구별하지
    못하므로, 그 구간에서 정확도를 깎을 이유가 없다.
    """
    if not len(wf):
        return pd.DataFrame()
    agg = (wf.groupby("모델")
             .agg(평가건수=("적중", "size"), 적중=("적중", "sum"),
                  등락률MAE=("등락률오차", "mean")).reset_index())
    agg["적중률"] = (agg["적중"] / agg["평가건수"]).round(4)
    bal = (wf.groupby("모델")
             .apply(lambda s: balanced_accuracy(s["적중"], s["실제"]))
             .rename("균형정확도").reset_index())
    agg = agg.merge(bal, on="모델", how="left")
    agg["균형정확도"] = agg["균형정확도"].round(4)
    agg["등락률MAE"] = agg["등락률MAE"].round(3)
    agg["총소요초"] = agg["모델"].map(timing).round(2)
    agg["건당ms"] = (agg["총소요초"] / agg["평가건수"] * 1000).round(1)
    # ⚠️ 점수를 **균형정확도**로 매긴다. 단순 적중률로 고르면 다수 클래스만
    # 찍는 모델이 1등이 된다 — 쏠린 구간에서 그게 제일 "정확"하기 때문이다.
    over = (agg["건당ms"].fillna(0) - LATENCY_FREE_MS).clip(lower=0)
    agg["시간벌점"] = (TIME_PENALTY
                       * np.log10(1 + over / LATENCY_FREE_MS)).round(4)
    agg["점수"] = (agg["균형정확도"].fillna(0.5) - agg["시간벌점"]).round(4)
    ci = [_wilson(int(k), int(n)) for k, n in zip(agg["적중"], agg["평가건수"])]
    agg["신뢰구간하한"] = [round(a, 4) for a, _b in ci]
    agg["신뢰구간상한"] = [round(b, 4) for _a, b in ci]
    return agg.sort_values("점수", ascending=False).reset_index(drop=True)


def ablation(wf_by_set: dict[str, pd.DataFrame]) -> pd.DataFrame:
    """피처 묶음별 적중률. 시장맥락·뉴스가 실제로 기여하는지 재는 표."""
    rows = []
    for tag, wf in wf_by_set.items():
        if not len(wf):
            continue
        k, n = int(wf["적중"].sum()), int(len(wf))
        lo, hi = _wilson(k, n)
        rows.append({"피처묶음": tag, "평가건수": n, "적중": k,
                     "적중률": round(k / n, 4),
                     "신뢰구간하한": round(lo, 4), "신뢰구간상한": round(hi, 4),
                     "등락률MAE": round(float(wf["등락률오차"].mean()), 3)})
    return pd.DataFrame(rows).sort_values("적중률", ascending=False)


# ==========================================================================
# 6. 다음 거래일 예측
# ==========================================================================
def predict_next(name: str, kind: str = ENSEMBLE,
                 feature_set: str = DEFAULT_FEATURES,
                 target: str = DEFAULT_TARGET, df: pd.DataFrame | None = None,
                 verbose: bool = True) -> dict:
    """
    **확정된 마지막 종가 시점의 정보만으로** 다음 거래일을 예측한다.

    미완성 봉은 `load_prices` 가 이미 버렸으므로 여기서 쓰는 마지막 행은
    반드시 장이 끝난 날이다.
    """
    tgt = TARGETS[target]
    e = entry(name)
    df = build(name, verbose=False) if df is None else df
    d = df.assign(y_up=df[tgt["col"]])
    labeled = d[d["y_up"].notna()]
    if len(labeled) < MIN_TRAIN:
        raise RuntimeError(f"{name}: 학습 표본 부족 ({len(labeled)}행)")

    use = _cols(labeled, feature_set)
    Xtr = labeled[use].to_numpy(np.float32)
    ytr = labeled["y_up"].to_numpy(int)
    last = d.index[-1]
    Xte = d.loc[[last], use].to_numpy(np.float32)

    t0 = time.time()
    p = float(_proba(_make_clf(kind, Xtr, ytr), Xte)[0])
    # 평가 때와 **똑같은 임계값 규칙**을 쓴다. 평가에서만 임계값을 옮기고
    # 실제 예측은 0.5 로 자르면, 보고한 성능과 내놓는 답이 서로 달라진다.
    thr = _pick_threshold(kind, Xtr, ytr)
    mag = float(_make_reg(Xtr, labeled["y_ret"].to_numpy(float))
                .predict(Xte)[0]) * 100
    el = time.time() - t0

    out = {
        "종목": e["label"], "지수": e["index"], "구분": e["tier"],
        "타깃": target, "모델": kind,
        "기준일": str(pd.Timestamp(last).date()),
        "기준종가": float(d.loc[last, "close"]),
        "예측": tgt["상승"] if p > thr else tgt["하락"],
        "확률": round(p, 4),
        "임계값": round(thr, 3),
        "예상등락률": round(mag, 3),
        "평소변동폭": round(float(d.loc[last, "vol_median_20"]), 3),
        "확신도": round(min(abs(p - thr) / max(thr, 1 - thr), 1.0), 4),
        "학습표본": int(len(labeled)), "사용피처": len(use),
        "뉴스사용": bool("news_xfer" in use),
        "소요초": round(el, 2),
    }
    if verbose:
        print(f"  {e['label'][:16]:16s} {out['기준일']} → **{out['예측']}** "
              f"({p * 100:.1f}%, 예상 {mag:+.2f}%, {el:.1f}초)")
    return out


def news_evidence(name: str, top: int = 4) -> pd.DataFrame:
    """예측 옆에 붙일 근거 기사."""
    e = entry(name)
    try:
        rows = N.fetch_google_news(e["query"], days=7,
                                   market=MARKETS[e["market"]]["news_locale"])
        # 아카이브는 **양쪽 시장 모두** 쓴다. 예전엔 국내만 읽었는데,
        # 과거 수집(`news.backfill`)을 미국에도 돌리므로 이제 둘 다 필요하다.
        arch = N.archive_load(e["label"])
        if len(arch):
            rows = arch.to_dict("records") + rows
    except Exception:
        return pd.DataFrame()
    if not rows:
        return pd.DataFrame()

    df = pd.DataFrame(rows).drop_duplicates(subset=["title"])
    sc = SENT.score_titles(df["title"].tolist(), market=e["market"])
    sc["ts"] = pd.to_datetime(df["ts"].to_numpy(), utc=True, errors="coerce")
    sc["source"] = df.get("source", "")
    sc = sc.dropna(subset=["ts"]).sort_values("ts", ascending=False)

    # ⚠️ 사후보도를 근거에서 밀어낸다.
    # "[특징주] 삼성전자 3% 올라" 류는 **주가가 올랐기 때문에 쓰인 것**이라
    # 예측 근거로 보여 주면 "올랐으니 오를 것"이라는 순환 논리가 된다.
    reactive = sc["title"].str.contains(N.REACTIVE_PATTERNS, na=False)
    sc["_rank"] = sc["polarity"].abs() * np.where(reactive, N.REACTIVE_WEIGHT, 1.0)
    sc["사후보도"] = reactive
    return (sc.head(40).sort_values("_rank", ascending=False).head(top)
            [["ts", "source", "title", "polarity", "label", "사후보도"]])


def universe_names() -> list[str]:
    return [e["label"] for e in all_entries()]
