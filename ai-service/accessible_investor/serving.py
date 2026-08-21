"""
서비스 진입점 — **트레이딩 툴이 부르는 함수 세 개.**

    predict(code, bars=None)   다음 거래일 변동성/방향 예측
    anomaly(code, bars=None)   이상 움직임 탐지 + 위험도
    similar(code, bars=None)   차트 유사 종목

세 함수 모두 **코스피·코스닥·나스닥·S&P500 아무 종목이나** 받는다.
관심종목에 담을 수 있는 것이면 다 된다.

봉 데이터는 누가 주나
=====================
둘 다 된다.

    predict("005930", bars=df)   ← 키움에서 받은 봉을 그대로 넘긴다 (권장)
    predict("005930")            ← 안 넘기면 직접 조회한다 (개발·검증용)

`bars` 를 넘기면 **네트워크 요청이 한 건도 나가지 않는다**(뉴스를 끄면).
운영에서는 이 경로를 쓴다. 증권사 시세와 다른 소스를 섞으면 값이 미묘하게
어긋나서, 사용자가 툴에서 보는 차트와 예측 근거가 달라진다.

봉 주기는 묶여 있지 않다
------------------------
일봉이 기준이지만 코드는 **주기를 모른다.** 5분봉을 넘기면 5분봉 기준으로,
60분봉을 넘기면 60분봉 기준으로 그대로 돈다. 무료로 받을 수 있는 분봉이
며칠치뿐이라 학습은 일봉으로 했지만, 팀원이 키움에서 분봉을 확보하면
`anomaly()` 와 `similar()` 는 그날로 분봉에서 쓸 수 있다.

예측만은 일봉 모델이다 — 분봉으로 학습한 적이 없다. 넣으면 답은 나오지만
보고한 성능(변동성 균형정확도 53.23%)은 일봉 기준이라 보장되지 않는다.

데이터가 모자란 종목
====================
거절하지 않는다. **신뢰도를 낮춰서 답한다.**

모델이 종목마다 새로 학습하는 구조였다면 상장 1년 미만 종목은 답이 없다.
그런데 파운데이션 모델은 여러 종목에서 배운 하나이므로, 그 종목의 과거가
짧아도 **오늘의 피처만 계산되면** 예측이 나온다. 계산 못 하는 피처는
학습 때 저장해 둔 중앙값으로 채운다.

    250봉 이상   높음      전 피처 사용
    120~249봉    보통      1년 드리프트 없음
     60~119봉    낮음      장기 피처 대부분 없음
     30~ 59봉    매우낮음  단기 피처만
     30봉 미만   거절      이건 정말 계산할 것이 없다
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from . import forecast as FC
from . import reference as RF
from . import registry as REG
from .registry import UnknownSymbol      # noqa: F401  (재수출)

NEUTRAL_NEWS = FC.NEUTRAL_NEWS

# 신뢰도 등급 — 사용 가능한 봉 수 기준
CONFIDENCE_TIERS = [
    (250, "높음", "전 피처 사용"),
    (120, "보통", "1년 드리프트 피처 없음"),
    (60, "낮음", "장기 피처 대부분 없음"),
    (30, "매우낮음", "단기 피처만 사용"),
]
MIN_BARS = 30

OHLCV = ["open", "high", "low", "close", "volume"]


class ServiceError(Exception):
    """서비스 계층 오류의 공통 조상. 백엔드는 이것만 잡으면 된다."""


class InsufficientData(ServiceError):
    def __init__(self, code: str, have: int, need: int = MIN_BARS):
        self.code, self.have, self.need = code, have, need
        super().__init__(f"{code}: 봉이 {have}개뿐입니다 (최소 {need}개 필요).")


class ReferenceMissing(ServiceError):
    """
    비교군 패널이 없다.

    저장소에는 들어 있지만(비상업 연구·시연 범위 — NOTICE.md), 새로 만들거나
    갱신하려면 `python cli.py reference --build` 를 돌리면 된다 (약 20분).

    없어도 `predict` 와 `anomaly` 는 돈다. 테마 피처가 결측으로 빠지고
    (학습 때 저장한 중앙값으로 대체된다) 위험도는 커밋된 분위수 격자를
    쓰기 때문이다. `similar` 만 후보 풀이 없어 성립하지 않는다.
    """

    def __init__(self, market: str):
        self.market = market
        super().__init__(
            f"{market} 비교군 패널이 없습니다. "
            "`python cli.py reference --build` 로 만드세요 (약 20분). "
            "예측·이상감지는 패널 없이도 동작합니다.")


# ==========================================================================
# 0. 입력 정규화
# ==========================================================================
def _normalize_bars(bars: Any, code: str) -> pd.DataFrame:
    """
    외부에서 받은 봉을 내부 형식으로 맞춘다.

    키움 API 든 무엇이든 열 이름과 날짜 표기가 제각각이다. 여기서 한 번
    접어 두지 않으면 그 차이가 피처 계산 깊숙한 곳에서 조용한 NaN 으로
    나타난다 — 예측은 나오는데 값이 틀린 게 제일 나쁘다.

    받아들이는 형태
        · 열 이름 대/소문자 무관, 한글(시가·고가·저가·종가·거래량) 허용
        · 날짜는 인덱스이거나 date/Date/일자/날짜/체결시간 열
        · 거래량이 없으면 0 으로 채운다 (유동성 축만 못 쓰게 된다)
    """
    if bars is None:
        raise ValueError("bars 가 None 입니다.")
    df = pd.DataFrame(bars).copy()
    if df.empty:
        raise InsufficientData(code, 0)

    ko = {"시가": "open", "고가": "high", "저가": "low", "종가": "close",
          "현재가": "close", "거래량": "volume"}
    df.columns = [ko.get(str(c), str(c).strip().lower()) for c in df.columns]

    # 날짜축 — 인덱스가 이미 날짜면 그대로, 아니면 열에서 찾는다
    if not isinstance(df.index, pd.DatetimeIndex):
        for cand in ("date", "일자", "날짜", "체결시간", "datetime", "time",
                     "index"):
            if cand in df.columns:
                df = df.set_index(pd.to_datetime(df[cand], errors="coerce"))
                df = df.drop(columns=[cand])
                break
        else:
            df.index = pd.to_datetime(df.index, errors="coerce")
    df.index = pd.DatetimeIndex(df.index)
    # 타임존이 붙어 있으면 떼어 낸다. 지수 시계열과 join 할 때
    # tz-aware 와 tz-naive 를 섞으면 pandas 가 전부 NaN 으로 만든다.
    if df.index.tz is not None:
        df.index = df.index.tz_localize(None)

    if "close" not in df.columns:
        raise ValueError(f"{code}: bars 에 종가 열이 없습니다 "
                         f"(받은 열: {list(df.columns)})")
    for c in OHLCV:
        if c not in df.columns:
            df[c] = 0.0 if c == "volume" else df["close"]
    df = df[OHLCV].apply(pd.to_numeric, errors="coerce")
    df = df[df["close"].notna() & (df.index.notna())]
    df = df[~df.index.duplicated(keep="last")].sort_index()
    if len(df) < MIN_BARS:
        raise InsufficientData(code, len(df))
    return df


class FetchFailed(ServiceError):
    """
    시세를 못 받았다.

    ⚠️ 반드시 `ServiceError` 하위여야 한다.
    예전엔 `FC._yf` 의 `RuntimeError` 가 그대로 올라왔다. 백엔드가
    `except ServiceError` 로 감싸 놓으면 그건 안 잡혀 **500** 이 된다.
    "그 종목 시세를 못 받았습니다"는 서버 장애가 아니라 정상적인 실패다.
    """

    def __init__(self, code: str, tried: list[str]):
        self.code, self.tried = code, tried
        super().__init__(f"{code}: 시세를 받지 못했습니다 "
                         f"(시도한 티커: {', '.join(tried)}).")


def _fetch_bars(meta: dict) -> pd.DataFrame:
    """봉을 안 넘겨줬을 때의 자체 조회 경로. 개발·검증용이다."""
    if meta["market"] == "KR":
        from . import data as D
        try:
            px = D.load_daily(meta["code"], auto_download=True)
        except Exception:
            px = None
        if px is None or not len(px):
            raise FetchFailed(meta["code"], [meta["code"]])
        px = px.sort_index()
        px.index = pd.to_datetime(px.index).normalize()
    else:
        # 티커 표기가 출처마다 달라 후보를 순서대로 시도한다
        # (`registry.yahoo_candidates` 주석 참조 — BRKB → BRK-B).
        cand = REG.yahoo_candidates(meta["code"], meta["index"])
        px = None
        for t in cand:
            try:
                px = FC._yf(t)
                break
            except Exception:
                continue
        if px is None or not len(px):
            raise FetchFailed(meta["code"], cand)
    px = px[px["close"].notna()]
    return FC.drop_partial_daily(px, meta["market"])


def _confidence(n: int) -> tuple[str, str]:
    for need, label, note in CONFIDENCE_TIERS:
        if n >= need:
            return label, note
    return "매우낮음", "단기 피처만 사용"


def _next_session(last: pd.Timestamp, market: str) -> tuple[str, bool]:
    """
    예측이 **가리키는 날**과 그게 오늘인지.

    모델은 언제나 "마지막 확정 봉의 다음 거래일"을 맞힌다. 그런데 그 날이
    오늘일 수도 있고 내일일 수도 있다 — 장이 끝났는지에 달렸다.

        장중에 조회   마지막 확정 봉 = 어제  →  대상일 = **오늘**
        마감 후 조회  마지막 확정 봉 = 오늘  →  대상일 = **다음 거래일**

    이걸 구분해 주지 않으면 사용자가 "오늘 오른다는 거야 내일 오른다는 거야"를
    알 수 없다. 툴에서는 이게 그대로 매매 시점이라 모호하면 안 된다.

    ⚠️ 휴장일은 반영하지 못한다.
    다음 **영업일**로 계산하므로 공휴일이 끼면 실제 거래일보다 앞선 날짜가
    나온다. 정확히 하려면 거래소 캘린더가 필요한데, 미래 날짜라 보유한
    시세로는 알 수 없다. UI 는 날짜보다 `금일여부` 를 믿는 편이 안전하다.
    """
    nxt = pd.Timestamp(last).normalize() + pd.offsets.BDay(1)
    tz = FC.MARKETS.get(market, FC.MARKETS["KR"])["tz"]
    today = pd.Timestamp.now(tz=tz).normalize().tz_localize(None)
    return str(nxt.date()), bool(nxt == today)


NEWS_RECENT_BARS = 5


def _news_days(df: pd.DataFrame, with_news: bool) -> int:
    """최근 5봉 중 뉴스가 실제로 잡힌 날의 수 (중립 0.5 가 아닌 날)."""
    if not with_news or "news_xfer" not in df.columns:
        return 0
    s = df["news_xfer"].tail(NEWS_RECENT_BARS)
    return int((s.sub(NEUTRAL_NEWS).abs() > 1e-9).sum())


def _news_lean(df: pd.DataFrame, with_news: bool) -> float | None:
    """마지막 봉의 뉴스 기울기. 0.5 가 중립, 1 에 가까울수록 긍정."""
    if not with_news or "news_xfer" not in df.columns or not len(df):
        return None
    v = float(df["news_xfer"].iloc[-1])
    return round(v, 4) if np.isfinite(v) else None


def _significance(mdl: dict, market: str, how: str) -> dict:
    """
    **그 시장에서** 이 타깃이 검증을 통과했는가.

    ⚠️ 전체 수치 하나로 답하면 미국 종목에 거짓말을 하게 된다.
    변동성 전체는 53.23%로 유의미하지만, 갈라 보면 다르다(실측).

        국내  54.79%  [51.17, 58.37]  유의미      15/20종목
        미국  51.91%  [48.16, 55.77]  **미검증**  11/18종목

    전체 값은 국내가 끌어올린 것이다. 학습 풀이 88% 국내(153종목 중 134)라
    그럴 수 있고, 미국 시장이 더 효율적이라 그럴 수도 있다. 어느 쪽이든
    **NVDA 조회에 "유의미"를 달아 보내면 안 된다.**

    시장별 값이 없으면(옛 모델 파일) 전체 값으로 물러선다.
    """
    key = "정밀_유의미" if how == "종목별학습" else "유의미"
    overall = bool(mdl["meta"].get(key, False))
    bym = (mdl["meta"].get("시장별") or {}).get(market)
    if not bym:
        return {"유의미": overall, "유의미근거": "전체"}
    return {
        "유의미": bool(bym["유의미"]),
        "유의미근거": f"{market} 시장 {bym['평가건수']}건",
        "검증_균형정확도": bym["균형정확도"],
        "검증_신뢰구간": bym["신뢰구간"],
        "검증_50%초과종목": bym["50%초과종목"],
    }


def _threshold_adjusted(p: float, thr: float) -> float:
    """
    **기준선을 50%로 옮긴** 확률.

    모델의 판정 기준선은 0.5 가 아니다(라벨 쏠림 보정 때문에 학습 구간에서
    따로 고른다). 그래서 원확률을 그대로 "상승 확률"이라고 읽어 주면
    **판정과 숫자가 어긋난다** — 확률 47%인데 판정은 '상승'인 상황이 생긴다.
    음성으로 읽히는 도구에서 이건 그냥 모순으로 들린다.

    기준선을 50%에 맞추도록 구간별 선형으로 다시 매긴다.

        원확률 0 ────── thr ────── 1
        보정   0 ────── 0.5 ────── 1

    단조 변환이라 순서가 바뀌지 않고, 판정과 숫자가 **항상 같은 쪽**을
    가리킨다. 원확률은 `원확률` 필드에 그대로 남겨 둔다.
    """
    thr = min(max(float(thr), 1e-6), 1 - 1e-6)
    p = min(max(float(p), 0.0), 1.0)
    if p <= thr:
        return 0.5 * p / thr
    return 0.5 + 0.5 * (p - thr) / (1 - thr)


def resolve(code: str) -> dict:
    """'005930' · '카카오' · 'NVDA' → 종목 정보. 못 찾으면 UnknownSymbol."""
    return REG.resolve(code)


# ==========================================================================
# 1. 피처 패널 — **학습과 서빙이 공유하는 단 하나의 경로**
# ==========================================================================
def _peer_features(meta: dict, px: pd.DataFrame) -> pd.DataFrame:
    """
    테마 동조. 이웃은 참조 패널(`reference.peers`)에서 고른다.

    ⚠️ `forecast.peer_features` 와 **다른 함수인 이유**가 있다.
    저쪽은 평가 유니버스 38종목 안에서 이웃을 찾는다. 임의 종목이 들어오면
    자기 자신이 풀에 없어서 이웃을 못 고른다. 이쪽은 시장별 비교군 300종목
    패널을 쓰므로 어떤 종목이든 이웃이 잡힌다.
    """
    f = pd.DataFrame(index=px.index, columns=FC.PEER_FEATURES, dtype=float)
    # 근거를 **항상** 실어 둔다. 실패했을 때도 "없음"이라고 말해야
    # UI 가 "테마 신호 없음"을 표시할 수 있다.
    f.attrs["이웃선정"], f.attrs["이웃"] = "없음", []
    codes, how = RF.peers(meta["code"], px["close"], meta["market"])
    if not codes:
        return f
    pr = RF.peer_frame(codes, meta["market"], px.index)
    if pr.empty:
        return f
    own = px["close"].pct_change()
    f["peer_ret_1"] = pr.mean(axis=1)
    f["peer_ret_5"] = pr.rolling(5, min_periods=3).mean().mean(axis=1)
    f["peer_disp"] = pr.std(axis=1)
    f["rel_peer_1"] = own - f["peer_ret_1"]
    f.attrs["이웃선정"] = how
    f.attrs["이웃"] = codes
    return f


PANEL_CACHE = FC.DATA_DIR / "serving_panels"

# 캐시의 **안전 상한**일 뿐 만료 기준이 아니다.
#
# 처음엔 12시간으로 뒀는데, 그게 만료를 좌우하게 두면 미국 종목이 망가진다.
# 미국 확정 시각은 한국 시간 05:20 이라, 12시간 뒤인 **17:20 부터 다음 날
# 새벽까지 캐시가 계속 낡음 판정**을 받는다. 그 사이 새 봉은 하나도 안 생기는데
# 조회할 때마다 패널을 다시 만든다 — 순전한 낭비이고, 네트워크가 끊기면
# 멀쩡히 쓸 수 있는 캐시를 두고 실패한다.
#
# 패널이 바뀌는 계기는 딱 둘이다. **새 봉(장 마감)** 과 **뉴스 아카이브 갱신**.
# 둘 다 mtime 으로 정확히 잡으므로 나이는 볼 필요가 없다. 7일은 시계가
# 틀어졌거나 파일이 깨진 경우를 위한 방어선이다.
PANEL_MAX_AGE_H = 24 * 7


def _cache_path(meta: dict, with_news: bool = True) -> "Path":
    """
    ⚠️ 캐시 이름에 **뉴스 사용 여부를 반드시 넣는다.**

    처음엔 `{시장}_{코드}.parquet` 하나였다. 그랬더니 `--no-news` 로 한 번
    돌린 종목이 **뉴스 없는 패널을 캐시에 남기고**, 그 뒤 뉴스를 켠 호출이
    그 캐시를 그대로 받아 갔다. 예외도 경고도 없이 뉴스만 조용히 빠진다.

    실측: 삼성전자가 `뉴스반영일수: 0` 으로 응답했다. 아카이브에 기사가
    2,905건 있고 캐시를 끄면 최근 8봉이 전부 비중립(0.378~0.591)인데도
    그랬다. "뉴스를 쓴다"고 적어 놓고 안 쓰는 상태가 며칠 갈 수 있었다.
    """
    tag = "" if with_news else "_nonews"
    return PANEL_CACHE / f"{meta['market']}_{meta['code']}{tag}.parquet"


def _last_settle_epoch(market: str) -> float:
    """
    **가장 최근에 확정된 종가의 시각.** 캐시 만료의 기준이다.

    장 마감(한국 15:30 · 미국 16:00) + 체결 정정 여유 20분이 지나면 그날
    일봉이 확정된다. 그 시각 **이전에** 만들어진 캐시는 새 봉을 모른다.
    """
    tz = FC.MARKETS.get(market, FC.MARKETS["KR"])["tz"]
    hh, mm = FC._CLOSE_HHMM.get(market, (15, 30))
    now = pd.Timestamp.now(tz=tz)
    settle = now.replace(hour=hh, minute=mm, second=0, microsecond=0) \
        + pd.Timedelta(minutes=FC._SETTLE_MIN)
    if now < settle:
        settle -= pd.Timedelta(days=1)
    return settle.timestamp()


def _cache_fresh(p: "Path", market: str) -> bool:
    """
    캐시를 그대로 써도 되는가.

    패널이 바뀌는 계기는 **딱 두 가지**다. 그래서 둘만 본다.

        ① 새 봉이 생겼나      → 마지막 확정 종가 시각과 비교
        ② 뉴스가 갱신됐나     → 아카이브 파일 mtime 과 비교

    ⚠️ 나이(시간)를 만료 기준으로 쓰면 안 된다. 두 번 데었다.

    처음엔 12시간 규칙만 뒀는데 **장 마감 직후에 터졌다.** 오후 2시에 만든
    캐시가 3시 50분(마감 확정) 뒤에도 "두 시간밖에 안 됐으니 신선"으로
    통과해서, `predict` 는 어제 종가를 말하고 `anomaly` 는 오늘 종가를 말했다
    (실측: predict 2026-08-19 1,873봉 · anomaly 2026-08-20 1,874봉).
    사용자 화면의 두 패널이 서로 다른 날을 가리켰다.

    그래서 마감 규칙을 넣었는데, 12시간 규칙을 **남겨 둔 것**이 이번엔
    반대로 물었다. 미국 확정 시각이 한국 시간 05:20 이라 17:20 부터는
    새 봉이 없는데도 계속 낡음 판정이 나서 조회마다 패널을 다시 만들었다.
    나이 규칙은 이제 7일짜리 방어선일 뿐이다.
    """
    if not p.is_file():
        return False
    mt = p.stat().st_mtime
    if mt < _last_settle_epoch(market):
        return False
    from . import news as N
    if N.NEWS_ARCHIVE.is_file() and N.NEWS_ARCHIVE.stat().st_mtime > mt:
        return False
    return (time.time() - mt) / 3600 <= PANEL_MAX_AGE_H


def panel(code: str, bars: Any = None, *, with_news: bool = True,
          meta: dict | None = None, use_cache: bool = True,
          verbose: bool = False) -> pd.DataFrame:
    """
    임의 종목 → (날짜 × 피처 + 라벨) 표.

    `forecast.build` 의 유니버스 없는 판이다. 피처 정의는 **완전히 같다** —
    기술 19 + 시장 8 + 테마 4 + 뉴스 1. 다른 것은 종목을 어떻게 찾고 이웃을
    어디서 고르느냐뿐이다.

    ⚠️ 캐시는 `bars` 를 안 넘겼을 때만 쓴다.
    외부에서 봉을 받았다는 건 그 봉이 최신이라는 뜻이고, 캐시를 돌려주면
    **넘긴 데이터가 조용히 무시된다.** 그건 어떤 오류 메시지도 없이 틀린
    답을 내는 종류의 버그다.
    """
    meta = meta or REG.resolve(code)

    cache_ok = use_cache and bars is None
    cp = _cache_path(meta, with_news)
    if cache_ok and _cache_fresh(cp, meta["market"]):
        try:
            f = pd.read_parquet(cp)
            f.attrs["종목"] = meta
            f.attrs["봉수"] = len(f)
            f.attrs["이웃선정"] = str(f.attrs.get("이웃선정", "캐시"))
            return f
        except Exception:
            pass

    px = _normalize_bars(bars, meta["code"]) if bars is not None \
        else _fetch_bars(meta)

    f = FC.tech_features(px)
    try:
        idx = FC.load_index(meta["index"])
        f = pd.concat([f, FC.market_features(px, idx)], axis=1)
    except Exception:
        # 지수를 못 받아도 나머지 피처로 답은 낸다. 학습 때 저장한
        # 중앙값이 빈자리를 채운다.
        for c in FC.MKT_FEATURES:
            f[c] = np.nan
    pf = _peer_features(meta, px)
    f = pd.concat([f, pf], axis=1)

    if with_news:
        try:
            f["news_xfer"] = FC.news_series(
                meta["code"], meta["query"], meta["label"], meta["market"],
                f.index, verbose=verbose)
        except Exception:
            f["news_xfer"] = NEUTRAL_NEWS
    else:
        f["news_xfer"] = NEUTRAL_NEWS

    f = f.replace([np.inf, -np.inf], np.nan)

    # 라벨 — 학습에 쓴다. 서빙에서는 마지막 행이 NaN 인 채로 남는다.
    f["close"] = px["close"]
    f["y_ret"] = px["close"].shift(-1) / px["close"] - 1
    f["y_up"] = np.where(f["y_ret"].notna(), (f["y_ret"] > 0).astype(float),
                         np.nan)
    absr = px["close"].pct_change().abs()
    med = absr.rolling(20, min_periods=10).median()
    nxt = absr.shift(-1)
    f["y_vol"] = np.where(nxt.notna() & med.notna(),
                          (nxt > med).astype(float), np.nan)
    f["vol_median_20"] = med * 100

    f.attrs.update({k: v for k, v in pf.attrs.items()})
    f.attrs["종목"] = meta
    f.attrs["봉수"] = len(px)

    if cache_ok:
        try:
            PANEL_CACHE.mkdir(parents=True, exist_ok=True)
            f.to_parquet(cp)
        except Exception:
            pass                 # 캐시를 못 써도 결과는 그대로다
    return f


# ==========================================================================
# 2. 예측
# ==========================================================================
def _predict_precise(df: pd.DataFrame, target: str) -> dict | None:
    """
    **이 종목 과거로만** 새로 학습해서 예측한다 (정밀 모드).

    측정 결과 이쪽이 파운데이션보다 변동성에서 2.42%p 앞선다 — 같은 38종목
    × 40거래일에서 55.65% 대 53.23%. 종목 고유의 버릇이 평균에 묻히지 않기
    때문이다.

    대가는 지연이다. 조회할 때마다 앙상블 세 모델과 임계값을 새로 적합하므로
    **건당 7.0초**가 든다(파운데이션은 167ms). 42배다.

    그래서 기본값이 아니다 — 관심종목 목록처럼 여러 종목을 한 번에 그리는
    화면에서 종목당 7초는 성립하지 않는다. 사용자가 한 종목을 펼쳐 볼 때만
    켜는 것이 맞다.

    표본이 모자라면 `None` 을 돌려준다. 호출한 쪽이 파운데이션으로 되돌린다.
    """
    tgt = FC.TARGETS[target]
    d = df.assign(y_up=df[tgt["col"]])
    labeled = d[d["y_up"].notna()]
    if len(labeled) < FC.MIN_TRAIN:
        return None
    use = FC._cols(labeled, "all")
    if not use:
        return None
    ytr = labeled["y_up"].to_numpy(int)
    if len(np.unique(ytr)) < 2:
        return None

    Xtr = labeled[use].to_numpy(np.float32)
    Xte = d.iloc[[-1]][use].to_numpy(np.float32)
    try:
        p = float(FC._proba(FC._make_clf(FC.ENSEMBLE, Xtr, ytr), Xte)[0])
        thr = FC._pick_threshold(FC.ENSEMBLE, Xtr, ytr)
        mag = float(FC._make_reg(Xtr, labeled["y_ret"].to_numpy(float))
                    .predict(Xte)[0]) * 100
    except Exception:
        return None
    return {"prob": p, "thr": float(thr), "mag": mag,
            "n_missing": int(len(PL_FEATURES) - len(use)),
            "학습표본": int(len(labeled)), "사용피처": len(use)}


# 정밀 모드의 결측 피처 수를 세려면 전체 피처 목록이 필요한데,
# `pooled` 를 여기서 import 하면 순환이 된다(pooled → serving → pooled).
# 목록 자체는 `forecast` 에만 의존하므로 여기서 다시 만든다.
PL_FEATURES = (FC.TECH_FEATURES + FC.MKT_FEATURES + FC.PEER_FEATURES
               + FC.NEWS_FEATURES)


def predict(code: str, bars: Any = None, *, target: str = "변동성",
            with_news: bool = True, precise: bool = False,
            verbose: bool = False) -> dict:
    """
    다음 거래일 예측.

    target
        "변동성"  내일 크게 움직일지 — 측정상 유일하게 유의미하게 맞는 타깃
        "방향"    내일 오를지 — 동전 던지기와 구별되지 않는다. 같이 내되
                  결과에 `유의미=False` 를 달아 보낸다.

    precise
        False (기본)  저장된 파운데이션 모델로 확률만 계산한다. **167ms.**
        True          이 종목 과거로 새로 학습한다. **7.0초**, 대신 변동성
                      균형정확도가 55.65% 로 2.42%p 높다. 표본이 250행에
                      못 미치면 조용히 파운데이션으로 되돌린다.

        어느 쪽을 썼는지는 응답의 `방식` 에 실려 나간다.

    반환은 그대로 UI 에 뿌릴 수 있는 평평한 dict 다. 중첩을 만들지 않은 건
    스크린리더가 읽을 문장(`문안`)을 그대로 꺼내 쓰게 하기 위해서다.
    """
    from . import pooled as PL

    t0 = time.time()
    meta = REG.resolve(code)
    df = panel(code, bars, with_news=with_news, meta=meta, verbose=verbose)
    n_bars = int(df.attrs.get("봉수", len(df)))
    if n_bars < MIN_BARS:
        raise InsufficientData(meta["code"], n_bars)

    mdl = PL.load(target)
    out, how = None, "파운데이션"
    if precise:
        out = _predict_precise(df, target)
        how = "종목별학습" if out else "파운데이션(표본부족)"
    if out is None:
        row = df.iloc[[-1]]
        x, n_missing = PL.vectorize(row, mdl)
        out = PL.apply(mdl, x, n_missing)

    tgt = FC.TARGETS[target]
    conf_label, conf_note = _confidence(n_bars)
    last = df.index[-1]
    vol_med = float(df["vol_median_20"].iloc[-1]) if \
        np.isfinite(df["vol_median_20"].iloc[-1]) else float("nan")

    up = _threshold_adjusted(out["prob"], out["thr"])
    대상일, 금일 = _next_session(last, meta["market"])

    res = {
        "종목코드": meta["code"], "종목명": meta["label"],
        "지수": meta["index"], "시장": meta["market"],
        "섹터": meta["sector"],
        "타깃": target,
        "기준일": str(pd.Timestamp(last).date()),
        "기준종가": float(df["close"].iloc[-1]),
        # 예측이 **가리키는 날**. 장중이면 오늘, 마감 후면 다음 거래일이다.
        "대상일": 대상일, "금일여부": 금일,
        "시점": "금일" if 금일 else "다음 거래일",
        "예측": tgt["상승"] if out["prob"] > out["thr"] else tgt["하락"],
        # 두 확률은 **합이 1** 이고 판정과 항상 같은 쪽을 가리킨다.
        # 이름을 타깃에 맞춰 바꾼다 — 방향이면 상승/하락, 변동성이면
        # 크게움직임/잔잔함. UI 가 라벨을 만들 필요가 없게 한다.
        f"{tgt['상승']}확률": round(up * 100, 1),
        f"{tgt['하락']}확률": round((1 - up) * 100, 1),
        "확률": round(up, 4),
        "원확률": round(out["prob"], 4),
        "임계값": round(out["thr"], 3),
        # 확신도는 **임계값에서 얼마나 떨어졌나**로 잰다. 확률 자체로 재면
        # 임계값이 0.41 인 모델에서 47%가 "하락 쪽 확신"으로 뒤집혀 보인다.
        "확신도": round(min(abs(out["prob"] - out["thr"])
                         / max(out["thr"], 1 - out["thr"]), 1.0), 4),
        "예상등락률": round(out["mag"], 3),
        "평소변동폭": round(vol_med, 3) if np.isfinite(vol_med) else None,
        "신뢰도": conf_label, "신뢰도근거": conf_note,
        "사용봉수": n_bars,
        "결측피처": out["n_missing"],
        "이웃선정": df.attrs.get("이웃선정", "없음"),
        "뉴스사용": bool(with_news),
        # 뉴스를 "쓴다"고 적어 놓고 실제로는 빈 열인 경우를 막는다.
        # 최근 5봉 중 중립(0.5)이 아닌 날의 수와, 마지막 날의 기울기.
        # 값이 0 이면 그 종목엔 최근 뉴스가 없었다는 뜻이고, UI 는 그걸
        # 그대로 말해 주면 된다.
        "뉴스반영일수": _news_days(df, with_news),
        "뉴스기울기": _news_lean(df, with_news),
        # 어느 경로로 답이 나왔는지 반드시 실어 보낸다. 정밀 모드를 켰는데
        # 표본이 모자라 파운데이션으로 되돌아간 경우를 UI 가 알아야 한다.
        "방식": how,
        "학습표본": out.get("학습표본", mdl["meta"].get("학습행")),
        "모델": mdl["kind"], "모델버전": mdl["version"],
        **_significance(mdl, meta["market"], how),
        "봉출처": "외부공급" if bars is not None else "자체조회",
        "소요ms": round((time.time() - t0) * 1000, 1),
    }
    res["문안"] = _speak_predict(res)
    return res


def _speak_predict(r: dict) -> str:
    """
    스크린리더가 그대로 읽을 문장.

    **언제를 말하는지 먼저 밝힌다.** "다음 거래일"이라고만 하면 사용자가
    오늘인지 내일인지 알 수 없는데, 툴에서는 그게 곧 매매 시점이다.
    장중이면 "오늘", 마감 뒤면 "다음 거래일 몇 월 며칠"로 못 박는다.

    확률은 **양쪽을 다 읽는다.** 한쪽만 읽으면 55퍼센트가 대단해 보인다.
    "상승 55, 하락 45"라고 들으면 그게 거의 반반이라는 게 바로 전달된다.
    """
    from .anomaly import josa

    nm = r["종목명"]
    when = ("오늘" if r["금일여부"]
            else f"다음 거래일({r['대상일'][5:].replace('-', '월 ')}일)")
    up_l = "크게움직임" if r["타깃"] == "변동성" else "상승"
    dn_l = "잔잔함" if r["타깃"] == "변동성" else "하락"
    up_p, dn_p = r.get(f"{up_l}확률", 50.0), r.get(f"{dn_l}확률", 50.0)

    head = f"{nm}{josa(nm, ('은', '는'))} {when} "
    if r["타깃"] == "변동성":
        body = (f"크게 움직일 확률 {up_p:.0f}퍼센트, 잔잔할 확률 "
                f"{dn_p:.0f}퍼센트로 봅니다. 평소 하루 변동폭은 "
                f"{r['평소변동폭']}퍼센트입니다.")
    else:
        body = (f"상승 확률 {up_p:.0f}퍼센트, 하락 확률 {dn_p:.0f}퍼센트로 "
                f"봅니다. 예상 등락률은 {r['예상등락률']:+.2f}퍼센트입니다. "
                "다만 방향 예측은 검증에서 동전 던지기와 구별되지 않았으니 "
                "참고만 하세요.")
    tail = ""
    if r["신뢰도"] in ("낮음", "매우낮음"):
        tail = f" 이 종목은 과거 자료가 {r['사용봉수']}봉뿐이라 신뢰도가 낮습니다."
    if r.get("뉴스사용") and r.get("뉴스반영일수"):
        lean = r.get("뉴스기울기")
        how = ("긍정 쪽" if lean and lean > 0.55 else
               "부정 쪽" if lean and lean < 0.45 else "중립 쪽")
        tail += (f" 최근 {r['뉴스반영일수']}일치 뉴스가 {how}이라 그것도 "
                 "함께 반영했습니다.")
    return head + body + tail


# ==========================================================================
# 3. 이상 탐지
# ==========================================================================
Z_GRADES = [(4.0, "매우 이례적"), (3.0, "이례적"), (2.5, "주목"),
            (2.0, "약한 신호")]


def anomaly(code: str, bars: Any = None, *, window: int = 60,
            lookback: int = 60) -> dict:
    """
    **오늘(마지막 봉)이 이 종목 기준으로 이상한가.**

    판정은 robust z 로 한다 — 최근 `window`봉 수익률의 중앙값에서 얼마나
    떨어졌는지를 MAD 로 나눈 값이다. 평균·표준편차 z 를 쓰지 않는 이유는
    **이상치가 자기 자신의 기준을 오염시키기** 때문이다. 큰 움직임 하나가
    표준편차를 키워 스스로를 정상으로 만든다. 실측에서 robust z 가 일반 z
    보다 2.8배 많은 이상일을 잡았다(47일 대 17일).

    비교군이 필요 없다
    ------------------
    이상 여부는 **그 종목 자기 과거**와의 비교라 다른 종목이 필요 없다.
    위험도 점수만 "다른 종목 대비 몇 번째"라서 비교군이 필요한데, 그건
    미리 저장해 둔 분위수 격자(`reference.risk_percentile`)로 조회 없이 낸다.

    봉 주기를 가리지 않는다 — 5분봉을 넣으면 5분봉 기준 이상치가 나온다.
    """
    from . import features as F
    from . import risk as RK

    t0 = time.time()
    meta = REG.resolve(code)
    px = _normalize_bars(bars, meta["code"]) if bars is not None \
        else _fetch_bars(meta)

    ret = px["close"].pct_change()
    z = F.robust_z(ret, window=window, min_periods=20)
    vz = F.robust_z(px["volume"].astype(float), window=window, min_periods=20)

    last = px.index[-1]
    z_now = float(z.iloc[-1]) if np.isfinite(z.iloc[-1]) else float("nan")
    vz_now = float(vz.iloc[-1]) if np.isfinite(vz.iloc[-1]) else float("nan")
    r_now = float(ret.iloc[-1]) * 100 if np.isfinite(ret.iloc[-1]) else float("nan")

    grade = "정상"
    for thr, label in Z_GRADES:
        if abs(z_now) >= thr:
            grade = label
            break
    direction = "상승" if z_now > 0 else "하락"

    # 최근 이상일 목록 — "요즘 자주 튀는 종목인가"를 보여 준다
    tail_z = z.tail(lookback)
    hits = tail_z[tail_z.abs() >= 2.5]
    recent = [{"날짜": str(pd.Timestamp(d).date()),
               "z": round(float(v), 2),
               "등락률": round(float(ret.loc[d]) * 100, 2),
               "방향": "상승" if v > 0 else "하락"}
              for d, v in hits.items()]

    # 위험도 — 저장된 기준분포에 대고 백분위를 낸다
    risk: dict = {}
    try:
        ax = RK.raw_axes(px, window=window)
        pct = {a: RF.risk_percentile(meta["market"], a, v)
               for a, v in ax.items()}
        if all(np.isfinite(list(pct.values()))):
            score = sum(pct[a] * w for a, w in RK.AXES.items())
            label, advice = RK._grade(score)
            risk = {"점수": round(float(score), 1), "등급": label,
                    "조언": advice,
                    "비교군": RF.reference_n(meta["market"]),
                    "축별백분위": {a: round(float(v), 1) for a, v in pct.items()}}
    except Exception:
        risk = {}

    res = {
        "종목코드": meta["code"], "종목명": meta["label"],
        "지수": meta["index"], "시장": meta["market"],
        "기준일": str(pd.Timestamp(last).date()),
        "기준종가": float(px["close"].iloc[-1]),
        "이상": bool(abs(z_now) >= 2.0) if np.isfinite(z_now) else False,
        "등급": grade, "방향": direction if np.isfinite(z_now) else None,
        "robust_z": round(z_now, 2) if np.isfinite(z_now) else None,
        "거래량z": round(vz_now, 2) if np.isfinite(vz_now) else None,
        "등락률": round(r_now, 2) if np.isfinite(r_now) else None,
        f"최근{lookback}봉이상일수": len(recent),
        "최근이상일": recent[-10:],
        "위험도": risk,
        "사용봉수": len(px),
        "봉출처": "외부공급" if bars is not None else "자체조회",
        "소요ms": round((time.time() - t0) * 1000, 1),
    }
    res["문안"] = _speak_anomaly(res)
    return res


def _speak_anomaly(r: dict) -> str:
    # ⚠️ 조사를 받침에 따라 고른다.
    # 음성으로 읽히는 문장이라 "카카오은"처럼 틀리면 바로 들린다. 화면을
    # 못 보는 사용자에게는 이게 유일한 출력이므로 오탈자가 아니라 결함이다.
    from .anomaly import josa

    nm = r["종목명"]
    un = josa(nm, ("은", "는"))
    i_ga = josa(nm, ("이", "가"))
    if r["robust_z"] is None:
        return f"{nm}{un} 이상 여부를 판단할 자료가 부족합니다."
    if not r["이상"]:
        s = (f"{nm}{un} {r['기준일']} 기준 평소 범위 안에서 움직였습니다. "
             f"등락률 {r['등락률']}퍼센트입니다.")
    else:
        s = (f"{nm}{i_ga} {r['기준일']}에 {r['등급']} 수준으로 "
             f"{r['방향']}했습니다. 등락률 {r['등락률']}퍼센트로, 최근 "
             f"움직임 대비 {abs(r['robust_z'])}배 수준입니다.")
    if r.get("위험도"):
        s += f" 이 종목의 위험도는 {r['위험도']['등급']}입니다."
    return s


# ==========================================================================
# 4. 차트 유사도
# ==========================================================================
_SIM_POOL: dict[str, dict] = {}
_SIM_INDEX: dict[tuple, Any] = {}


def _sim_pool(market: str) -> dict[str, pd.DataFrame]:
    """참조 패널을 유사도 검색이 먹는 형태로. 프로세스당 한 번만 만든다."""
    if market not in _SIM_POOL:
        try:
            pool = RF.load_panel(market)
        except FileNotFoundError:
            # 백엔드가 `except ServiceError` 하나로 잡을 수 있게 바꿔 준다.
            raise ReferenceMissing(market) from None
        out = {}
        for c in pool.columns:
            s = pool[c].dropna()
            if len(s) > 260:
                out[str(c)] = pd.DataFrame({"close": s.astype(float)})
        _SIM_POOL[market] = out
    return _SIM_POOL[market]


def _sim_index(market: str, window: int, forward: int):
    """
    검색 인덱스를 **미리 만들어 재사용한다.**

    인덱스 구축은 300~500종목의 모든 구간을 잘라 정규화하는 일이라 몇 초가
    든다. 조회할 때마다 다시 만들면 그 몇 초가 사용자 대기 시간이 된다.
    한 번 만들면 검색은 행렬곱 한 번이라 밀리초로 끝난다.

    질의 종목이 풀 안에 있든 없든 같은 인덱스를 쓴다 — 자기 구간 제외는
    검색 시점에 `exclude_code` 로 처리되므로 인덱스를 종목마다 다시 만들
    이유가 없다.
    """
    from . import similarity as SIM

    key = (market, window, forward)
    if key not in _SIM_INDEX:
        _SIM_INDEX[key] = SIM.PatternIndex(window).build(
            _sim_pool(market), forward_bars=forward)
    return _SIM_INDEX[key]


def similar(code: str, bars: Any = None, *, window: int = 120,
            top_k: int = 5, forward: int = 20,
            multi: bool = False) -> dict:
    """
    이 종목의 최근 모양과 **닮았던 다른 종목의 구간**을 찾는다.

    후보 풀은 참조 패널(시장별 300~500종목 × 6년)이다. 종목당 200구간으로
    솎아 6만 구간 정도가 되고, 검색은 z-정규화 후 행렬곱 한 번이라 밀리초다.

    ⚠️ 이건 **예측이 아니다.**
    "닮은 구간 다음에 무슨 일이 있었나"를 상승 몇 건 / 하락 몇 건까지만
    사실로 말한다. 평균 수익률로 요약하면 그 순간 예측처럼 읽힌다.
    """
    from . import similarity as SIM

    t0 = time.time()
    meta = REG.resolve(code)
    px = _normalize_bars(bars, meta["code"]) if bars is not None \
        else _fetch_bars(meta)
    if len(px) < window + forward:
        raise InsufficientData(meta["code"], len(px), window + forward)

    # ⚠️ 질의 종목은 **자기 코드 그대로** 넣는다.
    # 처음엔 `__query__005930` 같은 별도 키를 썼는데, 그러면 검색의
    # `exclude_code` 가 그 별도 키만 지우고 풀 안의 진짜 005930 은 그대로
    # 남는다. 삼성전자의 최근 120봉이 **삼성전자 자신의 겹치는 구간**과
    # 매칭돼 "가장 닮은 종목: 삼성전자"가 1위로 나온다. 코드를 그대로 쓰면
    # 제외가 정확히 걸린다. 사용자가 넘긴 봉이 패널 값보다 최신이므로
    # 덮어쓰는 것도 맞다.
    code = meta["code"]
    data = dict(_sim_pool(meta["market"]))
    data[code] = pd.DataFrame({"close": px["close"].astype(float)})

    tb = REG.table()
    lookup = dict(zip(tb["code"].astype(str), tb["name"].astype(str)))
    name_map = {c: lookup.get(c, c) for c in data}
    name_map[code] = meta["label"]

    if multi:
        # 다중 윈도우는 창마다 인덱스를 새로 만든다 — 느리다(수 초).
        res = SIM.find_similar_multi(code, data, top_k=top_k,
                                     name_map=name_map, forward_bars=forward)
    else:
        res = SIM.find_similar(code, data, W=window, top_k=top_k,
                               name_map=name_map, forward_bars=forward,
                               query_offset=forward,
                               index=_sim_index(meta["market"], window,
                                                forward))

    # ⚠️ **유사도와 동조도를 갈라 놓는다.**
    # `유사도` 는 "모양이 얼마나 닮았나"(형태 주도)이고, `동조도` 는
    # "봉마다 실제로 함께 움직였나"다. 둘은 다른 질문이고 값도 크게
    # 다르다 — 모양이 0.98 로 닮았는데 동조가 0.21 인 짝이 흔하다.
    # 하나로 뭉개면 이름은 유사도인데 실제로는 동조도를 재는 상태가 된다
    # (`similarity.SIM_WEIGHTS` 주석에 그 사고 기록이 있다).
    for r_ in res.get("results", []):
        r_["동조도"] = round(float(r_.get("components", {})
                                .get("수익률상관", float("nan"))), 4)

    res["query"]["code"] = code
    res["query"]["name"] = meta["label"]
    res.update({
        "종목코드": meta["code"], "종목명": meta["label"],
        "후보종목수": len(data) - 1,
        "사용봉수": len(px),
        "봉출처": "외부공급" if bars is not None else "자체조회",
        "소요ms": round((time.time() - t0) * 1000, 1),
    })
    return res


# ==========================================================================
# 5. 한 줄 브리핑 — 관심종목 화면이 부르는 것
# ==========================================================================
def brief(code: str, bars: Any = None, *, with_news: bool = True,
          with_similar: bool = False, target: str = "변동성",
          precise: bool = False) -> dict:
    """
    **세 기능을 한 번에** 불러 한 종목 요약으로 합친다.

    왜 따로 두지 않고 합치나
    ------------------------
    관심종목 화면은 종목당 한 줄이다. `predict` · `anomaly` · `similar` 를
    각자 부르고 백엔드에서 합치면 **합치는 방식이 사람마다 달라진다** —
    어느 걸 먼저 읽을지, 하나가 실패하면 전체를 실패로 볼지, 문장을 어떻게
    이을지가 전부 재량이 된다. 그러면 화면마다 다른 말이 나온다.

    여기서 한 번 정해 두면 UI 는 `문안` 을 그대로 읽고 배지 몇 개만 붙이면 된다.

    부분 실패를 전체 실패로 만들지 않는다
    ------------------------------------
    유사도가 안 돼도 예측과 이상감지는 보여 줘야 한다. 각 기능을 따로 감싸고
    실패한 것만 `오류` 에 적는다. **하나라도 성공하면 응답을 돌려준다.**

    `with_similar` 는 기본이 꺼짐이다. 유사도는 목록 화면에서 쓸 정보가
    아니고(종목당 5개씩 나온다), 인덱스를 처음 만들 때 몇 초가 든다.
    """
    t0 = time.time()
    meta = REG.resolve(code)          # 종목이 없으면 여기서 바로 예외
    out: dict = {"종목코드": meta["code"], "종목명": meta["label"],
                 "지수": meta["index"], "시장": meta["market"],
                 "섹터": meta["sector"], "오류": {}}

    try:
        p = predict(code, bars, target=target, with_news=with_news,
                    precise=precise)
        out["예측"] = p
    except Exception as e:
        out["예측"] = None
        out["오류"]["예측"] = f"{type(e).__name__}: {e}"

    try:
        out["이상감지"] = anomaly(code, bars)
    except Exception as e:
        out["이상감지"] = None
        out["오류"]["이상감지"] = f"{type(e).__name__}: {e}"

    if with_similar:
        try:
            s = similar(code, bars)
            out["유사종목"] = [
                {"종목명": r["name"], "종목코드": r["code"],
                 "유사도": r["similarity"], "성분": r.get("components", {}),
                 "종료일": r["end"], "이후수익률": r.get("forward_pct")}
                for r in s.get("results", [])[:3]]
        except Exception as e:
            out["유사종목"] = []
            out["오류"]["유사도"] = f"{type(e).__name__}: {e}"

    if out["예측"] is None and out["이상감지"] is None:
        raise ServiceError(f"{meta['label']}: 아무 기능도 수행하지 못했습니다 "
                           f"({out['오류']})")

    out["문안"] = _speak_brief(out)
    out["소요ms"] = round((time.time() - t0) * 1000, 1)
    return out


def _speak_brief(r: dict) -> str:
    """
    한 종목을 **한 호흡**으로 읽어 준다.

    순서가 중요하다 — 화면을 못 보는 사용자에게는 이 문장이 전부이므로,
    **지금 벌어진 일(이상)** 을 먼저 말하고 **앞으로의 전망(예측)** 을 뒤에
    붙인다. 반대로 하면 "내일 크게 움직입니다"를 듣고 나서야 "참고로 오늘
    이미 이례적으로 빠졌습니다"를 듣게 된다.
    """
    from .anomaly import josa

    nm = r["종목명"]

    def _drop_name(s: str) -> str:
        """
        문장 앞의 종목명을 뗀다.

        ⚠️ 두 문장을 그냥 이으면 **이름을 두 번 말한다.**
        "카카오는 오늘 이례적으로 하락했습니다. 카카오는 다음 거래일…" 처럼
        된다. 눈으로 읽으면 사소하지만 음성으로 들으면 바로 거슬리고,
        이 도구의 사용자에게는 그 음성이 출력의 전부다.
        """
        for j in ("은", "는", "이", "가"):
            head = f"{nm}{j} "
            if s.startswith(head):
                return s[len(head):]
        return s[len(nm) + 1:] if s.startswith(nm) else s

    bits = []
    a, p = r.get("이상감지"), r.get("예측")
    if a:
        # ⚠️ 이상감지 문안을 **그대로** 쓴다. 짧게 줄이려고 "오늘 평소 범위
        # 안에서 움직였습니다"로 바꿔 썼다가 틀렸다 — 미국장은 한국 시간
        # 낮에 아직 안 끝나서 마지막 확정 봉이 **어제** 다. 그런데 예측은
        # 오늘을 가리키므로 "오늘 … 오늘 …" 이 되면서 서로 다른 날을
        # 같은 말로 부르게 됐다. 원문에는 날짜가 박혀 있어 그럴 일이 없다.
        bits.append(a["문안"])
    if p:
        # 앞에서 이름을 이미 말했으므로 예측 문장은 이름을 뗀다.
        bits.append(_drop_name(p["문안"]) if bits else p["문안"])
    sim = r.get("유사종목")
    if sim:
        top = sim[0]
        tn = top["종목명"]
        bits.append(f"최근 흐름은 {tn}{josa(tn, ('과', '와'))} 가장 "
                    f"닮았습니다 (유사도 {top['유사도'] * 100:.0f}퍼센트).")
    if r.get("오류"):
        bits.append("일부 항목은 조회하지 못했습니다.")
    return " ".join(bits)


# ==========================================================================
# 6. 준비 상태
# ==========================================================================
def warm(markets: tuple[str, ...] = ("KR", "US"),
         targets: tuple[str, ...] = ("변동성", "방향"),
         probe: bool = True) -> dict:
    """
    서버 기동 시 한 번 불러 둔다. 첫 조회의 지연을 미리 치른다.

    안 부르면 첫 사용자가 모델 로딩 + 패널 읽기 + **지수 내려받기**를 혼자
    뒤집어쓴다.

    ⚠️ 지수를 빠뜨렸다가 고쳤다.
    처음엔 모델·패널·레지스트리만 올렸는데, 시장맥락 피처 8개가 쓰는
    **지수 일봉은 네트워크에서 받는다.** 그래서 워밍업을 다 해 놓고도
    시장별 첫 예측이 KOSPI 1.7초 · NASDAQ 2.3초씩 걸렸다(실측). 워밍업의
    존재 이유가 정확히 그건데 빠져 있었다.
    """
    from . import forecast as _FC
    from . import pooled as PL

    t0, done = time.time(), {}
    for t in targets:
        try:
            PL.load(t)
            done[f"모델:{t}"] = True
        except Exception as e:
            done[f"모델:{t}"] = f"{type(e).__name__}: {e}"
    for m in markets:
        try:
            RF.load_panel(m)
            done[f"참조패널:{m}"] = True
        except Exception as e:
            done[f"참조패널:{m}"] = f"{type(e).__name__}: {e}"
    # 지수는 시장이 아니라 **지수 단위**로 받는다. 국내는 KOSPI·KOSDAQ 가
    # 서로 다른 시계열이고, 미국도 NASDAQ·S&P500 이 다르다.
    for ix, spec in _FC.INDICES.items():
        if spec["market"] not in markets:
            continue
        try:
            _FC.load_index(ix)
            done[f"지수:{ix}"] = True
        except Exception as e:
            done[f"지수:{ix}"] = f"{type(e).__name__}: {e}"
    try:
        REG.table()
        done["레지스트리"] = True
    except Exception as e:
        done["레지스트리"] = f"{type(e).__name__}: {e}"

    # ⚠️ **한 번 실제로 예측해 본다.**
    # 파일을 다 올려 놓고도 워밍업 후 첫 예측이 1,856ms 였다(2회차부터
    # 159ms). 남은 비용은 sklearn·BLAS 가 처음 호출될 때 치르는 초기화라
    # 파일 적재로는 안 사라진다. 버리는 예측을 한 번 돌려 그걸 흡수한다.
    #
    # 가짜 봉을 쓴다 — 실제 종목 시세를 받으면 네트워크가 끼어들어 워밍업이
    # 시장 상황에 따라 들쭉날쭉해진다.
    if probe:
        try:
            n = 300
            rng = np.random.default_rng(0)
            close = 50_000 * np.exp(np.cumsum(rng.normal(0, 0.015, n)))
            idx = pd.bdate_range("2024-01-01", periods=n)
            fake = pd.DataFrame(
                {"open": close, "high": close * 1.01, "low": close * 0.99,
                 "close": close, "volume": 1e6}, index=idx)
            code = REG.table()["code"].iloc[0]
            for t in targets:
                predict(str(code), bars=fake, target=t, with_news=False)
            done["예열예측"] = True
        except Exception as e:
            done["예열예측"] = f"{type(e).__name__}: {e}"

    done["소요ms"] = round((time.time() - t0) * 1000, 1)
    return done


def health(check_index: bool = True) -> dict:
    """
    배포에 필요한 것이 다 있는지. 팀원이 기동 점검에 쓴다.

    파일만 보지 않고 **지수 접근까지 확인한다.** 지수는 저장소에 없고
    네트워크에서 받는데, 못 받아도 예측은 답을 낸다 — 시장맥락 피처 8개가
    조용히 결측이 될 뿐이다. 그 상태를 모르고 운영하면 32개 중 8개가 빠진
    모델을 정상이라고 믿게 된다.

    `check_index=False` 는 네트워크를 건드리지 않는 빠른 점검이다. 이때도
    `전체정상` 은 항상 들어 있고, **파일만** 보고 판정한다.
    """
    from . import forecast as _FC
    from . import pooled as PL

    out = {
        "레지스트리": REG.REGISTRY.is_file(),
        "종목수": len(REG.table()) if REG.REGISTRY.is_file() else 0,
        "참조패널_KR": RF.has_panel("KR"),
        "참조패널_US": RF.has_panel("US"),
        "위험도기준분포": RF.RISK_REF.is_file(),
        "예측모델": {t: PL.model_path(t).is_file()
                  for t in ("변동성", "방향")},
    }
    files_ok = bool(
        out["레지스트리"] and out["참조패널_KR"] and out["참조패널_US"]
        and out["위험도기준분포"] and all(out["예측모델"].values()))

    if check_index:
        idx = {}
        for ix in _FC.INDICES:
            try:
                idx[ix] = len(_FC.load_index(ix)) > 100
            except Exception as e:
                idx[ix] = f"{type(e).__name__}"
        out["지수"] = idx
        out["전체정상"] = files_ok and all(v is True for v in idx.values())
    else:
        # 지수 점검을 건너뛰어도 판정은 반드시 실어 보낸다. 빼 두면 백엔드의
        # `r["전체정상"]` 이 KeyError 로 죽거나 `.get()` 이 None 을 돌려주고,
        # 그 None 을 거짓으로 읽어 **멀쩡한 기동을 실패로 본다.**
        out["전체정상"] = files_ok
    return out
