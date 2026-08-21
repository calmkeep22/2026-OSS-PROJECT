"""
평가 대상 종목 — 지수별로 규칙에 따라 뽑는다.

왜 규칙으로 뽑나
----------------
손으로 고르면 "잘 나오는 종목만 골랐다"는 말을 들을 수밖에 없다.
지수별로 **규모 순위를 매기고 정해진 자리에서 뽑는다.** 시드를 고정하므로
누가 돌려도 같은 종목이 나온다.

    상위 3  규모 1~3위          — 유동성이 크고 뉴스가 많다
    중간 3  중앙값 근처 3종목    — 대부분의 종목이 사는 자리
    하위 3  규모 하위 3위        — 거래가 얇고 튄다
    무작위 1                    — 위 셋의 편향을 깨는 대조군

규모 지표
---------
국내는 FinanceDataReader 가 시가총액(`Marcap`)을 준다.
미국 목록에는 시가총액이 없어서 **최근 3개월 거래대금 중앙값**을 쓴다.
거래대금은 규모와 유동성을 함께 반영하므로 이 용도에는 오히려 낫다 —
시총이 커도 거래가 안 되는 종목은 실제로 진입이 어렵다.

⚠️ 스페이스X 는 상장사가 아니다
------------------------------
티커가 없어 일봉도 뉴스도 존재하지 않는다. 우주 관련이 필요하면
`EXTRA` 에 로켓랩(RKLB) 같은 상장사를 넣을 수 있지만, xforecast 학습
뉴스가 없어 **기술적 지표만으로** 돌게 된다는 걸 알고 넣어야 한다.
"""

from __future__ import annotations

import re
from pathlib import Path

import numpy as np
import pandas as pd

CACHE = Path(__file__).resolve().parent.parent / "data" / "universe"
SEED = 42
N_PER_INDEX = 10
PICK = {"상위": 3, "중간": 3, "하위": 3, "무작위": 1}

# 시장별 설정. 감성 모델이 언어를 따라간다 —
# 한글 기사에 영문 FinBERT를 물리면 토크나이저가 모르는 서브워드로 쪼개
# 전부 중립으로 나온다.
MARKETS = {
    "KR": {"name": "한국", "tz": "Asia/Seoul", "close": (15, 30),
           "sentiment": "snunlp/KR-FinBert-SC", "index": "KS11",
           "unit": "원", "news_locale": "KR"},
    "US": {"name": "미국", "tz": "America/New_York", "close": (16, 0),
           "sentiment": "ProsusAI/finbert", "index": "^GSPC",
           "unit": "달러", "news_locale": "US"},
}

INDICES = {
    "KOSPI":  {"market": "KR", "index_code": "KS11"},
    "KOSDAQ": {"market": "KR", "index_code": "KQ11"},
    "NASDAQ": {"market": "US", "index_code": "^IXIC"},
    "S&P500": {"market": "US", "index_code": "^GSPC"},
}

# 미국 후보 풀 상한. NASDAQ 은 3975종목이라 전부 시세를 받으면 오래 걸린다.
# 시드 고정 표본이므로 재현된다.
US_POOL_CAP = 700

# 시연 연속성을 위해 반드시 포함할 종목. 규칙 선택과 별개로 붙인다.
PINNED = {
    "KOSPI": ["005930", "000660"],          # 삼성전자, SK하이닉스
    "NASDAQ": ["NVDA", "TSLA"],
}

EXTRA: dict[str, dict] = {}       # 손으로 추가할 종목이 있으면 여기에


# ==========================================================================
# 후보에서 빼는 것 — **보통주만 남긴다**
# ==========================================================================
#
# 필터가 없으면 "하위 3종목"이 실제로는 거래 대상이 아닌 것으로 채워진다.
# 실측(첫 실행):
#   NASDAQ 하위 3  GigCapital8 Rights / Charlton Aria Acquisition Rights /
#                  Eureka Acquisition Right  → 전부 SPAC 권리증
#   KOSPI  하위 3  진흥기업2우B / 진흥기업우B / 서울식품우 → 전부 우선주
#
# 권리증·워런트는 만기가 있고 하루 몇 주만 거래된다. 우선주는 본주와 같은
# 뉴스를 공유하면서 유동성만 낮다. 둘 다 "소형주 예측"의 예로 부적절하다.
KR_DROP = re.compile(r"(우B?|[0-9]우B?|스팩[0-9]*호?|리츠)$")
# 단어 경계를 쓴다. 없으면 'unit' 이 'United', 'fund' 가 'Fundamental',
# 'trust' 가 'Trustmark' 를 잡아 멀쩡한 회사를 지운다.
# 단어 경계를 쓴다. 없으면 'unit' 이 'United', 'fund' 가 'Fundamental',
# 'trust' 가 'Trustmark' 를 잡아 멀쩡한 회사를 지운다.
US_DROP = re.compile(
    r"\bright(s)?\b|\bwarrant(s)?\b|\bunit(s)?\b|depositary|"
    r"preferred|acquisition corp|senior note|notes due|debenture|"
    r"\bbond(s)?\b|% note|\bETF\b|\bfund\b|\btrust\b", re.I)
# 나스닥 5글자 티커의 끝 W(워런트)·R(권리)·U(유닛) 규약
US_DROP_TICKER = re.compile(r"^[A-Z]{4}[WRU]$")

MIN_SIZE = {"KR": 3e10, "US": 3e5}      # 시총 300억 / 일 거래대금 30만달러

# 최근 60거래일 중 **가격이 전혀 안 움직인 날**의 비율 상한.
#
# 이 필터가 없으면 사실상 거래가 멈춘 종목이 들어온다. 실측: 에스엘에스바이오는
# 주가가 1,969원에 13거래일째 고정이었고, 전체의 24.6%가 수익률 정확히 0이었다.
# 그 상태로 변동성 예측을 돌리면 평가 7일이 전부 "잔잔함"이라 **아무 모델이나
# 100%를 받는다.** 실제로 그렇게 나와서 "적중률 100%" 라는 무의미한 값이
# 시연 후보 1위에 올랐다.
MAX_FLAT_RATIO = 0.30


def _drop_non_common(pool: pd.DataFrame, market: str,
                     pinned: "list[str] | None" = None,
                     verbose: bool = True) -> pd.DataFrame:
    """우선주·권리증·워런트·유닛과 사실상 거래되지 않는 종목을 뺀다."""
    n0 = len(pool)
    name = pool["name"].astype(str)
    code = pool["code"].astype(str)
    if market == "KR":
        keep = ~name.str.contains(KR_DROP, na=False)
    else:
        keep = ~name.str.contains(US_DROP, na=False)
        keep &= ~code.str.match(US_DROP_TICKER, na=False)
    keep &= pool["size"] >= MIN_SIZE.get(market, 0)
    if pinned:
        keep |= pool["code"].astype(str).isin(pinned)
    out = pool[keep].reset_index(drop=True)
    if verbose and len(out) < n0:
        print(f"    보통주 필터: {n0} → {len(out)}종목 "
              f"(우선주·권리증·초소형 {n0 - len(out)}개 제외)")
    return out


def _listing(index: str) -> pd.DataFrame:
    """지수 구성 종목. 열 이름을 code/name 으로 통일한다."""
    import FinanceDataReader as fdr

    df = fdr.StockListing(index)
    if "Code" in df.columns:                     # 국내
        out = df.rename(columns={"Code": "code", "Name": "name"})
        keep = ["code", "name"]
        if "Marcap" in df.columns:
            out["size"] = pd.to_numeric(out["Marcap"], errors="coerce")
            keep.append("size")
        return out[keep].dropna(subset=["code"])
    out = df.rename(columns={"Symbol": "code", "Name": "name"})
    return out[["code", "name"]].dropna(subset=["code"])


def _us_size(codes: list[str], verbose: bool = True) -> pd.Series:
    """
    미국 종목의 규모 지표 = 최근 3개월 **거래대금 중앙값**.

    yfinance 는 여러 티커를 한 번에 받을 수 있다. 200개씩 끊어 부른다 —
    한 번에 다 넣으면 실패했을 때 무엇이 실패했는지 알 수 없다.
    """
    import yfinance as yf

    out: dict[str, float] = {}
    step = 200
    for i in range(0, len(codes), step):
        chunk = codes[i:i + step]
        try:
            px = yf.download(chunk, period="3mo", interval="1d",
                             auto_adjust=True, progress=False, threads=True)
        except Exception as e:
            if verbose:
                print(f"    청크 {i // step + 1} 실패: {type(e).__name__}")
            continue
        if px is None or not len(px):
            continue
        try:
            close, vol = px["Close"], px["Volume"]
        except KeyError:
            continue
        if isinstance(close, pd.Series):         # 티커 1개면 Series 로 온다
            close = close.to_frame(chunk[0])
            vol = vol.to_frame(chunk[0])
        amt = (close * vol).median(axis=0, skipna=True)
        out.update({str(k): float(v) for k, v in amt.items() if np.isfinite(v)})
        if verbose:
            print(f"    {min(i + step, len(codes))}/{len(codes)} … "
                  f"누적 {len(out)}종목")
    return pd.Series(out, name="size", dtype=float)


def build_pool(index: str, refresh: bool = False,
               verbose: bool = True) -> pd.DataFrame:
    """지수의 후보 풀 + 규모 지표. 한 번 만들면 캐시한다."""
    CACHE.mkdir(parents=True, exist_ok=True)
    p = CACHE / f"pool_{index.replace('&', '')}.parquet"
    if p.is_file() and not refresh:
        return pd.read_parquet(p)

    if verbose:
        print(f"  [{index}] 구성 종목 조회 …")
    lst = _listing(index)
    lst["code"] = lst["code"].astype(str)
    if "size" not in lst.columns:
        pool = lst
        if len(pool) > US_POOL_CAP:
            # ⚠️ 표본추출 **전에** 고정 종목을 떼어 놓는다.
            # 처음엔 그냥 sample 했더니 NVDA·TSLA 가 700개 표본에 안 뽑혀
            # PINNED 가 조용히 무시됐다. 고정 종목은 반드시 들어가야 한다.
            must = pool[pool["code"].isin(PINNED.get(index, []))]
            rest = pool[~pool["code"].isin(PINNED.get(index, []))]
            pool = pd.concat(
                [must, rest.sample(max(US_POOL_CAP - len(must), 1),
                                   random_state=SEED)], ignore_index=True)
            if verbose:
                print(f"    {len(lst)}종목 → 후보 {len(pool)}개로 표본추출 "
                      f"(시드 {SEED}, 고정 {len(must)}종목 포함)")
        size = _us_size(pool["code"].astype(str).tolist(), verbose=verbose)
        pool = pool.assign(size=pool["code"].astype(str).map(size))
        pool = pool.dropna(subset=["size"])
    else:
        pool = lst.dropna(subset=["size"])

    pool = pool[pool["size"] > 0].sort_values("size", ascending=False)
    pool = pool.reset_index(drop=True)
    pool["code"] = pool["code"].astype(str)
    pool = _drop_non_common(pool, INDICES[index]["market"],
                            pinned=PINNED.get(index), verbose=verbose)
    pool.to_parquet(p, index=False)
    if verbose:
        print(f"    후보 {len(pool)}종목 확정 -> {p.name}")
    return pool


def _flat_ratio(code: str, market: str, window: int = 60) -> float:
    """최근 `window` 거래일 중 수익률이 정확히 0인 날의 비율."""
    try:
        if market == "KR":
            from . import data as D
            px = D.load_daily(code, auto_download=True)
        else:
            import yfinance as yf
            px = yf.Ticker(code).history(period="6mo", interval="1d",
                                         auto_adjust=True)
            px = px.rename(columns=str.lower)
    except Exception:
        return 0.0
    if px is None or len(px) < 20:
        return 1.0                       # 데이터가 없으면 쓸 수 없는 종목
    r = px["close"].pct_change().tail(window).dropna()
    return float((r == 0).mean()) if len(r) else 1.0


def _drop_dormant(rows: list[dict], market: str,
                  verbose: bool = True) -> list[dict]:
    """거래가 사실상 멈춘 종목을 뺀다 (`MAX_FLAT_RATIO` 주석 참조)."""
    out = []
    for r in rows:
        flat = _flat_ratio(r["code"], market)
        if flat > MAX_FLAT_RATIO and r["tier"] != "고정":
            if verbose:
                print(f"    휴면 제외: {r['name']} (무변동일 {flat*100:.0f}%)")
            continue
        r["무변동일비율"] = round(flat, 3)
        out.append(r)
    return out


def select(index: str, n: int = N_PER_INDEX, seed: int = SEED,
           refresh: bool = False, verbose: bool = True) -> pd.DataFrame:
    """상위3 · 중간3 · 하위3 · 무작위1."""
    pool = build_pool(index, refresh=refresh, verbose=verbose)
    if len(pool) < n * 2:
        raise RuntimeError(f"{index} 후보가 부족합니다 ({len(pool)}종목).")

    rng = np.random.default_rng(seed)
    m = len(pool)
    mid = m // 2
    picks: list[tuple[str, int]] = []
    picks += [("상위", i) for i in range(PICK["상위"])]
    lo = mid - PICK["중간"] // 2
    picks += [("중간", i) for i in range(lo, lo + PICK["중간"])]
    picks += [("하위", m - 1 - i) for i in range(PICK["하위"])]
    used = {i for _t, i in picks}
    cand = [i for i in range(m) if i not in used]
    picks.append(("무작위", int(rng.choice(cand))))

    rows = []
    for tier, i in picks[:n]:
        r = pool.iloc[i]
        rows.append({"index": index, "tier": tier, "규모순위": i + 1,
                     "code": str(r["code"]), "name": str(r["name"]),
                     "size": float(r["size"])})

    have = {r["code"] for r in rows}
    for code in PINNED.get(index, []):
        if code in have:
            continue
        hit = pool[pool["code"] == code]
        if not len(hit):
            continue
        r = hit.iloc[0]
        rows.append({"index": index, "tier": "고정",
                     "규모순위": int(hit.index[0]) + 1, "code": code,
                     "name": str(r["name"]), "size": float(r["size"])})

    rows = _drop_dormant(rows, INDICES[index]["market"], verbose=verbose)
    # 휴면으로 빠진 자리는 **같은 구간의 다음 순위**로 채운다.
    # 그냥 비우면 "하위 3종목"이 1~2개로 줄어 구간 비교가 깨진다.
    have = {r["code"] for r in rows}
    for tier, want in PICK.items():
        cur = [r for r in rows if r["tier"] == tier]
        idx = mid if tier == "중간" else (0 if tier == "상위" else m - 1)
        step = 1 if tier != "하위" else -1
        probe = idx + len(cur) * step
        while len(cur) < want and 0 <= probe < m:
            cand = pool.iloc[probe]
            probe += step
            if str(cand["code"]) in have:
                continue
            r = {"index": index, "tier": tier, "규모순위": probe + 1,
                 "code": str(cand["code"]), "name": str(cand["name"]),
                 "size": float(cand["size"])}
            kept = _drop_dormant([r], INDICES[index]["market"], verbose=False)
            if kept:
                rows.append(kept[0])
                cur.append(kept[0])
                have.add(r["code"])
    return pd.DataFrame(rows)


def build(indices: "list[str] | None" = None, refresh: bool = False,
          verbose: bool = True) -> pd.DataFrame:
    """네 지수 전부. 결과를 `data/universe/universe.csv` 로 남긴다."""
    indices = indices or list(INDICES)
    parts = []
    for ix in indices:
        try:
            df = select(ix, refresh=refresh, verbose=verbose)
        except Exception as e:
            print(f"  [{ix}] 건너뜀 — {type(e).__name__}: {e}")
            continue
        df["market"] = INDICES[ix]["market"]
        parts.append(df)
    if not parts:
        raise RuntimeError("종목을 하나도 뽑지 못했습니다.")
    out = pd.concat(parts, ignore_index=True)
    # 한 종목이 여러 지수에 있으면(NASDAQ ∩ S&P500) 먼저 나온 쪽만 남긴다
    out = out.drop_duplicates(subset=["code"], keep="first").reset_index(drop=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    out.to_csv(CACHE / "universe.csv", index=False, encoding="utf-8-sig")
    if verbose:
        print(f"\n최종 {len(out)}종목")
        for ix in out["index"].unique():
            s = out[out["index"] == ix]
            print(f"  {ix:8s} {len(s):2d}종목  "
                  + ", ".join(f"{r['name']}({r['tier']})"
                              for _i, r in s.iterrows()))
    return out


def load(verbose: bool = False) -> pd.DataFrame:
    """저장된 유니버스. 없으면 만든다."""
    p = CACHE / "universe.csv"
    if p.is_file():
        return pd.read_csv(p, dtype={"code": str})
    return build(verbose=verbose)


# ==========================================================================
# 조회 도우미
# ==========================================================================
def entry(name_or_code: str) -> dict:
    """표시이름 또는 코드 → {label, code, market, query}."""
    if name_or_code in EXTRA:
        return {"label": name_or_code, **EXTRA[name_or_code]}
    uni = load()
    hit = uni[(uni["name"] == name_or_code) | (uni["code"] == name_or_code)]
    if not len(hit):
        raise KeyError(f"{name_or_code} 은 유니버스에 없습니다. "
                       "`python cli.py universe` 로 목록을 확인하세요.")
    r = hit.iloc[0]
    return {"label": str(r["name"]), "code": str(r["code"]),
            "market": str(r["market"]), "query": str(r["name"]),
            "index": str(r["index"]), "tier": str(r["tier"])}


def all_entries() -> list[dict]:
    uni = load()
    return [{"label": str(r["name"]), "code": str(r["code"]),
             "market": str(r["market"]), "query": str(r["name"]),
             "index": str(r["index"]), "tier": str(r["tier"])}
            for _i, r in uni.iterrows()]


def market_of(name_or_code: str) -> str:
    return entry(name_or_code)["market"]
