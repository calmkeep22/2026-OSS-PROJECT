"""
데이터 계층 — 종목 유니버스, 일봉, 분봉, 지수.

원칙
----
1. 모든 다운로드는 로컬 parquet 캐시를 거친다. 같은 데이터를 두 번 받지 않는다.
2. 외부 API 키가 필요 없다.
3. 분봉은 **당일치가 계속 늘어나므로** 캐시 정책이 일봉과 다르다.
   일봉은 "파일이 있으면 끝"이지만, 분봉은 "마지막 봉이 오래됐으면 갱신"이다.

수집원
------
일봉   : FinanceDataReader (pykrx 전종목 조회는 2026-07 기준 로그인 없이 실패)
분봉   : yfinance — 5분봉 60일, 1분봉 7일까지 조회된다.
         "국내주식은 일봉밖에 안 된다"는 통념은 사실이 아니다 (01에서 확인).
"""

from __future__ import annotations

import time
import warnings
from datetime import datetime, timedelta

import numpy as np
import pandas as pd

from .config import (
    CLOSING_AUCTION_FROM,
    DAILY_DIR,
    DATA_DIR,
    HORIZONS,
    INTRADAY_DIR,
    MARKET_TZ,
    SESSION_CLOSE,
    SESSION_OPEN,
)

warnings.filterwarnings("ignore")

OHLCV = ["open", "high", "low", "close", "volume"]


# --------------------------------------------------------------------------
# 종목 유니버스
# --------------------------------------------------------------------------
def load_universe(refresh: bool = False) -> pd.DataFrame:
    """KOSPI/KOSDAQ 보통주 목록. 시가총액 내림차순."""
    path = DATA_DIR / "universe.parquet"
    if path.exists() and not refresh:
        return pd.read_parquet(path)

    import FinanceDataReader as fdr

    df = fdr.StockListing("KRX")
    df = df.rename(columns={"Code": "code", "Name": "name", "Market": "market"})
    keep = [c for c in ["code", "name", "market", "Close", "Marcap", "Stocks"] if c in df.columns]
    df = df[keep].copy()
    df = df[~df["name"].str.contains("스팩|리츠", na=False)]
    df = df[df["code"].str.match(r"^\d{6}$", na=False)]
    df = df[df["code"].str.endswith("0")]          # 보통주 (우선주는 5/7/9로 끝난다)
    if "Marcap" in df.columns:
        df = df.sort_values("Marcap", ascending=False)
    df = df.reset_index(drop=True)
    df.to_parquet(path, index=False)
    return df


_UNIVERSE_CACHE: pd.DataFrame | None = None


def universe() -> pd.DataFrame:
    global _UNIVERSE_CACHE
    if _UNIVERSE_CACHE is None:
        _UNIVERSE_CACHE = load_universe()
    return _UNIVERSE_CACHE


def resolve(query: str) -> tuple[str, str] | None:
    """'삼성전자' 또는 '005930' → (코드, 이름). 못 찾으면 None."""
    uni = universe()
    q = str(query).strip()
    if q.isdigit() and len(q) == 6:
        row = uni[uni["code"] == q]
    else:
        row = uni[uni["name"] == q]
        if row.empty:
            row = uni[uni["name"].str.contains(q, na=False, regex=False)]
    if row.empty:
        return None
    return row.iloc[0]["code"], row.iloc[0]["name"]


def name_of(code: str) -> str:
    uni = universe()
    row = uni[uni["code"] == code]
    return row.iloc[0]["name"] if len(row) else code


def yahoo_ticker(code: str) -> str:
    """
    yfinance 티커. 시장에 따라 접미사가 다르다.

    이걸 틀리면 조용히 빈 DataFrame이 돌아온다 — 예외가 안 나서 디버깅이 오래 걸린다.
    KONEX는 yfinance가 지원하지 않으므로 .KQ로 시도하고 실패를 받아들인다.
    """
    uni = universe()
    row = uni[uni["code"] == code]
    market = row.iloc[0]["market"] if len(row) else "KOSPI"
    return f"{code}.KS" if str(market).startswith("KOSPI") else f"{code}.KQ"


# --------------------------------------------------------------------------
# 일봉
# --------------------------------------------------------------------------
def _fetch_daily_raw(code: str, start: str, end: str) -> pd.DataFrame:
    try:
        import FinanceDataReader as fdr

        df = fdr.DataReader(code, start, end)
        if len(df):
            df = df.rename(columns={
                "Open": "open", "High": "high", "Low": "low",
                "Close": "close", "Volume": "volume",
            })
            return df[OHLCV]
    except Exception:
        pass

    from pykrx import stock

    df = stock.get_market_ohlcv(start.replace("-", ""), end.replace("-", ""), code)
    df = df.rename(columns={"시가": "open", "고가": "high", "저가": "low",
                            "종가": "close", "거래량": "volume"})
    df.index.name = "Date"
    return df[OHLCV]


def download_daily(codes: list[str], start: str = "2019-01-01", end: str | None = None,
                   sleep: float = 0.05, refresh: bool = False,
                   verbose: bool = True) -> dict[str, object]:
    """일봉을 종목별 parquet으로 캐시. 이미 있으면 건너뛴다."""
    end = end or pd.Timestamp.today().strftime("%Y-%m-%d")
    saved, failed = {}, []
    today = last_session_date()
    for i, code in enumerate(codes, 1):
        path = DAILY_DIR / f"{code}.parquet"
        if path.exists() and not refresh and _daily_is_fresh(path, today):
            saved[code] = path
            continue
        try:
            df = _fetch_daily_raw(code, start, end)
            if len(df) < 100:
                failed.append((code, f"too short ({len(df)})"))
                continue
            if path.exists():
                # 기존 캐시와 병합한다. 새로 받은 구간이 짧을 수 있어 덮어쓰면 과거를 잃는다.
                old_df = pd.read_parquet(path)
                old_df.index = pd.to_datetime(old_df.index)
                df = pd.concat([old_df, df])
                df = df[~df.index.duplicated(keep="last")].sort_index()
            df.to_parquet(path)
            saved[code] = path
            time.sleep(sleep)
        except Exception as e:
            failed.append((code, f"{type(e).__name__}: {e}"))
        if verbose and i % 50 == 0:
            print(f"  {i}/{len(codes)} … 저장 {len(saved)} 실패 {len(failed)}")
    if verbose:
        print(f"일봉 완료: 저장 {len(saved)}, 실패 {len(failed)}")
        for code, reason in failed[:5]:
            print(f"   실패 {code}: {reason}")
    return saved


# 공휴일 때문에 헛다운로드하는 것을 막는 기록.
# {경로: (마지막 시도 시각, 그때의 마지막 데이터 날짜)}
_FETCH_ATTEMPT: dict[str, tuple[pd.Timestamp, object]] = {}
_RETRY_AFTER_MIN = 60.0


def _daily_is_fresh(path, today=None) -> bool:
    """
    일봉 캐시가 최신인가.

    **원래는 이 검사가 아예 없었다.** "파일이 있으면 끝"이라
    한 번 받은 뒤로는 어제 데이터가 영원히 안 들어왔다.
    분봉은 신선도 검사를 넣어놨는데 일봉만 빠져 있었다.

    실제로 이것 때문에 뉴스 예측력 검증이 표본 0건이 됐다 —
    뉴스는 08-17까지 수집되는데 일봉이 08-07에 멈춰 있어 겹치는 거래일이 없었다.
    매일 쓰는 도구에서 조용히 옛날 데이터를 보여주는 건 틀린 값보다 나쁘다.

    공휴일 처리
    -----------
    `last_session_date()` 는 주말만 건너뛰고 **공휴일을 모른다.**
    2026-08-15(광복절)가 토요일이라 08-17 월요일이 대체공휴일이었는데,
    이 함수는 08-17을 거래일로 보고 "캐시가 낡았다"고 판단했다.
    데이터는 멀쩡한데 호출할 때마다 네트워크를 두드리게 된다.

    그래서 **직전 시도에서 데이터가 안 늘었으면 한 시간 동안 다시 묻지 않는다.**
    공휴일인지 아닌지를 달력으로 아는 대신 **실제 응답으로 배운다** —
    공휴일 목록을 하드코딩하면 매년 갱신해야 하고, 임시공휴일은 못 따라간다.
    """
    try:
        idx = pd.read_parquet(path, columns=["close"]).index
        last = pd.to_datetime(idx.max()).date()
    except Exception:
        return False
    if last >= (today or last_session_date()):
        return True

    prev = _FETCH_ATTEMPT.get(str(path))
    if prev is not None:
        when, got = prev
        age_min = (pd.Timestamp.now() - when).total_seconds() / 60.0
        if got == last and age_min < _RETRY_AFTER_MIN:
            # 아까 받아 봤는데 그대로였다. 휴장일 가능성이 크다.
            return True
    return False


def load_daily(code: str, auto_download: bool = False,
               refresh: bool | None = None) -> pd.DataFrame | None:
    path = DAILY_DIR / f"{code}.parquet"
    stale = path.exists() and not _daily_is_fresh(path)
    if not path.exists() or (stale and (auto_download or refresh)):
        if not (auto_download or refresh):
            return None
        download_daily([code], verbose=False)
        if not path.exists():
            return None
        # 받아 본 결과를 기록한다. 데이터가 안 늘었으면 휴장일로 보고
        # 한동안 다시 묻지 않는다 (`_daily_is_fresh` 주석 참조).
        try:
            got = pd.to_datetime(
                pd.read_parquet(path, columns=["close"]).index.max()).date()
            _FETCH_ATTEMPT[str(path)] = (pd.Timestamp.now(), got)
        except Exception:
            pass
    df = pd.read_parquet(path)
    df.index = pd.to_datetime(df.index)
    return df.sort_index()


def cached_codes() -> list[str]:
    return sorted(p.stem for p in DAILY_DIR.glob("*.parquet"))


def load_market_index(start: str = "2019-01-01") -> pd.DataFrame:
    """
    KOSPI 일봉. 시장 대비 초과수익 계산용.

    종목 일봉과 **같은 신선도 규칙**을 쓴다. 지수만 옛날 것을 쓰면
    초과수익(개별 - 시장)이 통째로 틀리는데, 값이 그럴듯해서 알아채기 어렵다.
    """
    path = DATA_DIR / "KS11.parquet"
    if path.exists() and _daily_is_fresh(path):
        df = pd.read_parquet(path)
        df.index = pd.to_datetime(df.index)
        return df
    try:
        import FinanceDataReader as fdr

        end = pd.Timestamp.today().strftime("%Y-%m-%d")
        df = fdr.DataReader("KS11", start, end).rename(
            columns={"Close": "close"})[["close"]]
        if path.exists():
            old_df = pd.read_parquet(path)
            old_df.index = pd.to_datetime(old_df.index)
            df = pd.concat([old_df, df])
            df = df[~df.index.duplicated(keep="last")].sort_index()
        df.to_parquet(path)
        return df
    except Exception:
        # 못 받으면 있는 것이라도 준다. 지수가 없으면 초과수익이 원시 수익률로 폴백된다.
        if path.exists():
            df = pd.read_parquet(path)
            df.index = pd.to_datetime(df.index)
            return df
        raise


# --------------------------------------------------------------------------
# 분봉 — 단타 축의 원재료
# --------------------------------------------------------------------------
def _clean_intraday(df: pd.DataFrame) -> pd.DataFrame:
    """
    yfinance 원본 → 정규장 5분/1분봉.

    처리해야 하는 것 세 가지. 하나라도 빠지면 이상 탐지가 곧바로 망가진다.
      1. 멀티인덱스 컬럼 — 단일 종목이어도 yfinance 1.x는 (필드, 티커) 2단으로 준다
      2. 정규장 밖 봉 — 시간외 단일가가 섞이면 거래량 z-score가 통째로 오염된다
      3. 거래량 0 봉 — 09:00 봉은 시가 단일가라 yfinance에서 종종 volume=0으로 온다.
         이걸 로그 거래량에 넣으면 log1p(0)=0이 되어 "거래량 급감" 이상으로 잡힌다
    """
    if isinstance(df.columns, pd.MultiIndex):
        df = df.droplevel(-1, axis=1)
    df = df.rename(columns={"Open": "open", "High": "high", "Low": "low",
                            "Close": "close", "Volume": "volume",
                            "Adj Close": "adj_close"})
    df = df[[c for c in OHLCV if c in df.columns]].copy()

    if df.index.tz is None:
        df.index = df.index.tz_localize("UTC").tz_convert(MARKET_TZ)
    else:
        df.index = df.index.tz_convert(MARKET_TZ)
    df.index.name = "datetime"

    df = df.between_time(SESSION_OPEN, SESSION_CLOSE)
    df = df[~df.index.duplicated(keep="last")].sort_index()
    df = df.dropna(subset=["close"])
    df = df[df["close"] > 0]
    return df


_INTERVAL_MIN = {"1m": 1, "5m": 5, "15m": 15, "30m": 30, "60m": 60, "1h": 60}


def _cache_is_fresh(df: pd.DataFrame, now: pd.Timestamp, max_age_min: int) -> bool:
    """
    캐시를 다시 받아야 하는가.

    "마지막 봉이 N분보다 오래됐으면 갱신"만으로는 **장 마감 후에 매번 재다운로드**한다.
    15:30에 장이 끝나면 마지막 봉은 영원히 몇 시간 전이기 때문이다.
    140종목 학습 파이프라인을 두 번 돌리면 그것만으로 수 분이 날아간다.

    → 장중에는 시간 기준, 장 마감 후에는 **"마지막 거래일 데이터가 들어 있는가"** 기준.
    """
    if df is None or df.empty:
        return False
    last = df.index.max()
    if is_market_open(now):
        return (now - last) < pd.Timedelta(minutes=max_age_min)
    return last.date() >= last_session_date(now)


def drop_partial_bar(df: pd.DataFrame, interval: str,
                     now: pd.Timestamp | None = None) -> pd.DataFrame:
    """
    **아직 형성 중인 마지막 봉을 버린다.**

    이걸 안 하면 장중에 심각한 오작동이 난다. 봉 T는 [T, T+주기) 구간을 담는데,
    지금이 T+1분이면 그 봉에는 5분 중 1분치 거래량만 들어 있다.
    완성된 봉으로 취급하면 "거래량이 평소의 0.1배"가 되어 **관심종목 전부가
    같은 분에 거래량 이상 알림을 낸다.** 개발 중 실제로 이 현상을 봤다 —
    13시 40분에 삼성전자·SK하이닉스·현대차·KB금융이 동시에 울렸고,
    원인은 시장이 아니라 미완성 봉이었다.

    가격만 보면 미완성 봉도 유효하다(현재가니까). 하지만 거래량·변동성 채널이
    통째로 망가지므로 스캔 대상에서는 뺀다. 현재가 표시는 별도 경로로 한다.
    """
    if df is None or df.empty:
        return df
    step = pd.Timedelta(minutes=_INTERVAL_MIN.get(interval, 5))
    now = now or pd.Timestamp.now(tz=MARKET_TZ)
    last = df.index[-1]
    if last.tzinfo is None:
        last = last.tz_localize(MARKET_TZ)
    return df.iloc[:-1] if now < last + step else df


def _intraday_path(code: str, interval: str):
    return INTRADAY_DIR / f"{code}_{interval}.parquet"


def download_intraday(codes: list[str], interval: str = "5m", period: str | None = None,
                      refresh: bool = False, max_age_min: int = 5,
                      verbose: bool = True) -> dict[str, pd.DataFrame]:
    """
    분봉 수집 + 증분 병합.

    일봉과 캐시 정책이 다르다. 분봉은 장중에 계속 늘어나므로
    "파일이 있으면 끝"이면 영원히 옛날 데이터를 본다.
    → 마지막 봉이 `max_age_min`분보다 오래됐으면 다시 받아 **병합**한다.
      (덮어쓰지 않는다. yfinance 조회 한도(5분봉 60일)를 넘어선 과거를 잃지 않기 위해서다.
       60일 한도 때문에 매일 조금씩 밀려나는데, 병합해두면 캐시가 그만큼 길어진다.)
    """
    import yfinance as yf

    h = next((v for v in HORIZONS.values() if v["interval"] == interval), None)
    period = period or f"{h['lookback_days'] if h else 60}d"
    now = pd.Timestamp.now(tz=MARKET_TZ)
    out = {}

    for i, code in enumerate(codes, 1):
        path = _intraday_path(code, interval)
        old = None
        if path.exists():
            old = pd.read_parquet(path)
            old.index = pd.to_datetime(old.index)
            if old.index.tz is None:
                old.index = old.index.tz_localize(MARKET_TZ)
            if not refresh and _cache_is_fresh(old, now, max_age_min):
                out[code] = drop_partial_bar(old, interval)
                continue

        try:
            raw = yf.download(yahoo_ticker(code), period=period, interval=interval,
                              progress=False, auto_adjust=False, threads=False)
            new = _clean_intraday(raw) if len(raw) else None
        except Exception as e:
            if verbose:
                print(f"  {code} 분봉 실패: {type(e).__name__}: {e}")
            new = None

        if new is None or new.empty:
            if old is not None:
                out[code] = drop_partial_bar(old, interval)
            continue

        merged = new if old is None else pd.concat([old, new])
        merged = merged[~merged.index.duplicated(keep="last")].sort_index()
        merged.to_parquet(path)          # 캐시에는 미완성 봉도 남긴다 (다음 조회에서 갱신)
        out[code] = drop_partial_bar(merged, interval)   # 스캔에는 완성된 봉만 준다

        if verbose and i % 10 == 0:
            print(f"  분봉 {i}/{len(codes)} …")

    if verbose:
        print(f"분봉({interval}) 완료: {len(out)}/{len(codes)}종목")
    return out


def load_intraday(code: str, interval: str = "5m", auto_download: bool = True,
                  max_age_min: int = 5, partial: bool = False) -> pd.DataFrame | None:
    """partial=False면 형성 중인 마지막 봉을 버린다. 스캔 경로의 기본값이다."""
    path = _intraday_path(code, interval)
    if auto_download:
        got = download_intraday([code], interval=interval,
                                max_age_min=max_age_min, verbose=False)
        df = got.get(code)
    elif not path.exists():
        return None
    else:
        df = pd.read_parquet(path)
        df.index = pd.to_datetime(df.index)
        if df.index.tz is None:
            df.index = df.index.tz_localize(MARKET_TZ)
        df = df.sort_index()
    if df is None:
        return None
    return df if partial else drop_partial_bar(df, interval)


def load_market_intraday(interval: str = "5m", max_age_min: int = 5) -> pd.DataFrame | None:
    """
    KOSPI 지수 분봉. 장중 초과수익 계산용.

    지수는 캐시 키가 종목코드가 아니라서 따로 둔다.
    지수를 못 받으면 초과수익 대신 원시 수익률로 폴백한다 — 기능이 죽지는 않지만
    "시장 전체가 빠진 날 개별 종목 알림이 쏟아지는" 06에서 잡은 문제가 되살아난다.
    """
    import yfinance as yf

    path = _intraday_path("_KS11", interval)
    now = pd.Timestamp.now(tz=MARKET_TZ)
    old = None
    if path.exists():
        old = pd.read_parquet(path)
        old.index = pd.to_datetime(old.index)
        if old.index.tz is None:
            old.index = old.index.tz_localize(MARKET_TZ)
        if (now - old.index.max()) < pd.Timedelta(minutes=max_age_min):
            return drop_partial_bar(old, interval)

    h = next((v for v in HORIZONS.values() if v["interval"] == interval), None)
    period = f"{h['lookback_days'] if h else 60}d"
    try:
        raw = yf.download("^KS11", period=period, interval=interval,
                          progress=False, auto_adjust=False, threads=False)
        new = _clean_intraday(raw) if len(raw) else None
    except Exception:
        new = None
    if new is None or new.empty:
        return old
    merged = new if old is None else pd.concat([old, new])
    merged = merged[~merged.index.duplicated(keep="last")].sort_index()
    merged.to_parquet(path)
    return merged


# --------------------------------------------------------------------------
# 시간축 통합 로더
# --------------------------------------------------------------------------
def load_bars(code: str, horizon: str = "intraday", **kw) -> pd.DataFrame | None:
    """시간축 이름으로 봉을 가져온다. 엔진은 이 함수만 쓴다."""
    interval = HORIZONS[horizon]["interval"]
    if interval == "1d":
        return load_daily(code, auto_download=True)
    return load_intraday(code, interval=interval, **kw)


def load_index_bars(horizon: str = "intraday", **kw) -> pd.DataFrame | None:
    interval = HORIZONS[horizon]["interval"]
    if interval == "1d":
        return load_market_index()
    return load_market_intraday(interval=interval, **kw)


# --------------------------------------------------------------------------
# 품질 점검
# --------------------------------------------------------------------------
def quality_report(df: pd.DataFrame, intraday: bool = False) -> dict:
    ret = df["close"].pct_change()
    rep = {
        "rows": len(df),
        "start": str(df.index.min()),
        "end": str(df.index.max()),
        "nan_close": int(df["close"].isna().sum()),
        "zero_volume": int((df["volume"] == 0).sum()),
    }
    if intraday:
        rep["days"] = int(pd.Series(df.index.date).nunique())
        rep["bars_per_day"] = round(len(df) / max(rep["days"], 1), 1)
        rep["closing_auction_bars"] = int(
            len(df.between_time(CLOSING_AUCTION_FROM, SESSION_CLOSE)))
    else:
        rep["beyond_limit"] = int((ret.abs() > 0.31).sum())   # 액면분할·감자 의심
    return rep


def flag_corporate_actions(df: pd.DataFrame, threshold: float = 0.35) -> pd.Series:
    """
    액면분할·감자 의심일. 상·하한 ±30%를 넘는 등락은 정상 거래로는 불가능하다.
    이상 탐지에서 빼야 오탐이 줄어든다.
    """
    return df["close"].pct_change().abs() > threshold


def is_market_open(now: pd.Timestamp | None = None) -> bool:
    now = now or pd.Timestamp.now(tz=MARKET_TZ)
    if now.weekday() >= 5:
        return False
    t = now.strftime("%H:%M")
    return SESSION_OPEN <= t <= SESSION_CLOSE


def last_session_date(now: pd.Timestamp | None = None) -> datetime:
    """가장 최근 거래일(추정). 공휴일은 반영하지 못하므로 데이터로 재확인해야 한다."""
    now = now or pd.Timestamp.now(tz=MARKET_TZ)
    d = now
    if d.strftime("%H:%M") < SESSION_OPEN:
        d = d - timedelta(days=1)
    while d.weekday() >= 5:
        d = d - timedelta(days=1)
    return d.date()
