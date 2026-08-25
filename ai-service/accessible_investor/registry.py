"""
종목 레지스트리 — **코스피·코스닥·나스닥·S&P500 전 종목**을 코드로 찾는다.

왜 따로 만드나
==============
`universe.py` 는 **평가용**이다. 지수마다 규칙으로 38종목을 뽑아 "이 표본에서
이만큼 맞았다"를 증명하는 게 목적이라, 표본 밖 종목은 일부러 막아 둔다
(`entry()` 가 KeyError 를 낸다). 연구에서는 그게 맞다 — 표본을 몰래 늘리면
성능 주장이 무의미해진다.

**서비스에서는 정반대가 필요하다.** 사용자가 관심종목에 카카오를 넣으면
카카오가 나와야 한다. 그래서 평가용 표본과 **분리된** 조회 경로를 둔다.
이 파일이 그 경로다. 둘은 서로를 건드리지 않는다.

    universe.py   38종목   "성능을 이 표본에서 쟀다"      — 리포트가 쓴다
    registry.py   6,900+   "아무 종목이나 찾는다"        — 서비스가 쓴다

무엇을 담나
-----------
    code          005930 / NVDA
    name          삼성전자 / NVIDIA Corp
    market        KR / US          — 뉴스 언어·감성사전·장 마감 시각이 갈린다
    index         KOSPI / KOSDAQ / NASDAQ / S&P500
    sector        전기전자 / Information Technology
    industry      반도체와반도체장비 …
    listing_date  상장일 (국내만 제공)

`sector` 를 굳이 담는 이유는 **테마 이웃**이다. 상관만으로 이웃을 고르면
거래 이력이 짧은 종목에서 이웃이 안 잡힌다. 그때 같은 섹터 대형주로
대체하면 최소한의 테마 신호는 남는다 (`reference.peers` 참조).

출처
----
전부 FinanceDataReader 한 곳이다. 조회가 느려서(나스닥 3,975종목에 8초)
**한 번 받아 `models/registry.parquet` 로 저장**하고 그 뒤로는 파일을 읽는다.
저장소에 같이 올라가므로 팀원은 네트워크 없이 바로 쓸 수 있다.
"""

from __future__ import annotations

import re
from pathlib import Path

import pandas as pd

MODEL_DIR = Path(__file__).resolve().parent.parent / "models"
REGISTRY = MODEL_DIR / "registry.parquet"

# 지수 → (시장, FDR 목록 키)
SOURCES = {
    "KOSPI":  ("KR", "KRX-DESC"),
    "KOSDAQ": ("KR", "KRX-DESC"),
    "NASDAQ": ("US", "NASDAQ"),
    "S&P500": ("US", "S&P500"),
}

# ── 거래 대상이 아닌 것을 뺀다 ───────────────────────────────────────────
# `universe.py` 와 같은 규칙이다. 우선주는 본주와 뉴스를 공유하면서 유동성만
# 낮고, 권리증·워런트는 만기가 있어 "이 종목의 내일"이라는 질문 자체가 성립하지
# 않는다. 스팩은 합병 전까지 주가가 공모가에 붙어 있어 변동성 라벨이 전부
# 한쪽으로 쏠린다.
#
# ⚠️ 전부 **비포획 그룹**`(?:...)` 으로 쓴다. 포획 그룹이 있으면 pandas 의
# `str.contains` 가 "이건 extract 하려던 것 아니냐"는 UserWarning 을 매번 뱉는다.
KR_DROP = re.compile(r"(?:우B?|[0-9]우B?|스팩[0-9]*호?|리츠)$")
US_DROP = re.compile(
    r"\bright(?:s)?\b|\bwarrant(?:s)?\b|\bunit(?:s)?\b|depositary|"
    r"preferred|acquisition corp|senior note|notes due|debenture|"
    r"\bbond(?:s)?\b|% note|\bETF\b|\bfund\b|\btrust\b", re.I)
US_DROP_TICKER = re.compile(r"^[A-Z]{4}[WRU]$")
KR_CODE = re.compile(r"^\d{6}$")
US_TICKER = re.compile(r"^[A-Z][A-Z.\-]{0,5}$")

COLUMNS = ["code", "name", "market", "index", "sector", "industry",
           "listing_date", "size"]


class UnknownSymbol(KeyError):
    """레지스트리에 없는 종목. 서비스는 이걸 잡아 404 로 바꾸면 된다."""


# ==========================================================================
# 수집
# ==========================================================================
def _krx() -> pd.DataFrame:
    """
    코스피 + 코스닥. KONEX 는 뺀다 — 유동성이 서비스 대상이 못 된다.

    ⚠️ `KRX-DESC` 의 `Sector` 열은 **섹터가 아니다.**
    실제 값은 '중견기업부 512 · 우량기업부 466 · 벤처기업부 345 …' 로
    코스닥 **소속부**이고, 코스피 종목은 전부 NaN 이다. 이걸 섹터로 쓰면
    삼성전자의 이웃이 '우량기업부 전체'가 되어 테마 신호가 사라진다.

    진짜 분류는 `Industry` 쪽이다 — 삼성전자 '통신 및 방송 장비 제조업',
    SK하이닉스 '반도체 제조업', LG화학 '기초 화학물질 제조업'. 한국표준산업
    분류라 세밀도도 적당하다. 그래서 **`Industry` 를 섹터로 승격**한다.

    시가총액은 `KRX-DESC` 에 없고 `KRX` 목록에만 있어서 둘을 합친다.
    참조 패널을 규모 상위로 뽑으려면 이 값이 필요하다.
    """
    import FinanceDataReader as fdr

    df = fdr.StockListing("KRX-DESC")
    df = df.rename(columns={"Code": "code", "Name": "name",
                            "Market": "index", "Industry": "industry",
                            "ListingDate": "listing_date"})
    df["sector"] = df["industry"]          # 소속부가 아니라 산업분류를 쓴다

    # ⚠️ `KOSDAQ GLOBAL` 을 반드시 코스닥으로 접어야 한다.
    # 시장 값이 KOSPI / KOSDAQ / KONEX / **KOSDAQ GLOBAL** 넷이다. 마지막이
    # 별도 문자열이라 `isin(["KOSPI","KOSDAQ"])` 만 쓰면 **50종목이 통째로
    # 사라진다.** 그 50종목이 하필 알테오젠·에코프로·에코프로비엠 같은
    # 코스닥 우량 시장 종목이라, 관심종목에 가장 많이 담길 것들이 전부
    # "찾을 수 없음"이 됐다(실측). 글로벌 세그먼트는 코스닥 안의 등급이지
    # 다른 시장이 아니다.
    df["index"] = df["index"].replace({"KOSDAQ GLOBAL": "KOSDAQ"})
    df = df[df["index"].isin(["KOSPI", "KOSDAQ"])].copy()
    df["code"] = df["code"].astype(str).str.zfill(6)

    try:
        mc = fdr.StockListing("KRX").rename(columns={"Code": "code",
                                                     "Marcap": "size"})
        mc["code"] = mc["code"].astype(str).str.zfill(6)
        df = df.merge(mc[["code", "size"]].drop_duplicates("code"),
                      on="code", how="left")
    except Exception:
        df["size"] = pd.NA
    df["market"] = "KR"
    df["code"] = df["code"].astype(str).str.zfill(6)
    df = df[df["code"].str.match(KR_CODE, na=False)]
    df = df[~df["name"].astype(str).str.contains(KR_DROP, na=False)]
    # ⚠️ 코드 끝자리로 우선주를 한 번 더 거른다.
    # 이름 규칙만으로는 '현대차2우B' 같은 건 잡히지만 표기가 다른 것들이 샌다.
    # 국내 보통주는 코드가 0 으로 끝난다.
    df = df[df["code"].str.endswith("0")]
    for c in COLUMNS:
        if c not in df.columns:
            df[c] = pd.NA
    return df[COLUMNS]


def _us() -> pd.DataFrame:
    """
    나스닥 + S&P500.

    두 목록은 겹친다(NVDA 는 양쪽에 있다). **S&P500 을 먼저 놓고** 중복을
    지운다 — S&P500 목록에만 `Sector` 가 있어서 그쪽을 남겨야 섹터가 산다.
    """
    import FinanceDataReader as fdr

    parts = []

    sp = fdr.StockListing("S&P500")
    sp = sp.rename(columns={"Symbol": "code", "Name": "name",
                            "Sector": "sector", "Industry": "industry"})
    sp["index"] = "S&P500"
    parts.append(sp)

    nq = fdr.StockListing("NASDAQ")
    nq = nq.rename(columns={"Symbol": "code", "Name": "name",
                            "Industry": "industry"})
    nq["sector"] = nq.get("industry")     # 나스닥 목록엔 섹터가 없다
    nq["index"] = "NASDAQ"
    parts.append(nq)

    df = pd.concat(parts, ignore_index=True)
    df["market"] = "US"
    df["code"] = df["code"].astype(str).str.strip().str.upper()
    df = df[df["code"].str.match(US_TICKER, na=False)]
    df = df[~df["name"].astype(str).str.contains(US_DROP, na=False)]
    df = df[~df["code"].str.match(US_DROP_TICKER, na=False)]
    df = df.drop_duplicates(subset=["code"], keep="first")
    for c in COLUMNS:
        if c not in df.columns:
            df[c] = pd.NA
    return df[COLUMNS]


def refresh(verbose: bool = True) -> pd.DataFrame:
    """네 지수 전체를 다시 받아 저장한다. 몇 분 걸린다."""
    parts = []
    for fn, label in ((_krx, "국내(KOSPI+KOSDAQ)"), (_us, "미국(NASDAQ+S&P500)")):
        try:
            d = fn()
            parts.append(d)
            if verbose:
                by = d["index"].value_counts().to_dict()
                print(f"  {label}: {len(d):,}종목 {by}")
        except Exception as e:
            print(f"  {label} 실패 — {type(e).__name__}: {e}")
    if not parts:
        raise RuntimeError("종목 목록을 하나도 받지 못했습니다.")

    df = pd.concat(parts, ignore_index=True)
    df["listing_date"] = pd.to_datetime(df["listing_date"], errors="coerce")
    df = df.drop_duplicates(subset=["code"], keep="first").reset_index(drop=True)
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    df.to_parquet(REGISTRY, index=False)
    if verbose:
        print(f"  → {REGISTRY.name} 저장 ({len(df):,}종목)")
    return df


# ==========================================================================
# 조회
# ==========================================================================
_CACHE: pd.DataFrame | None = None


def table(refresh_if_missing: bool = True) -> pd.DataFrame:
    """레지스트리 전체. 프로세스당 한 번만 읽는다."""
    global _CACHE
    if _CACHE is not None:
        return _CACHE
    if REGISTRY.is_file():
        _CACHE = pd.read_parquet(REGISTRY)
    elif refresh_if_missing:
        _CACHE = refresh(verbose=False)
    else:
        raise FileNotFoundError(
            f"{REGISTRY} 가 없습니다. `python cli.py registry --refresh` 를 "
            "먼저 실행하세요.")
    _CACHE["code"] = _CACHE["code"].astype(str)
    return _CACHE


def resolve(query: str) -> dict:
    """
    '005930' · '삼성전자' · 'NVDA' · 'NVIDIA' → 종목 정보.

    찾는 순서를 **정확 일치 먼저**로 둔다. 부분 일치를 먼저 보면 'LG' 가
    'LG생활건강'을 먼저 잡는 식으로 엉뚱한 종목이 나온다.
    """
    q = str(query).strip()
    if not q:
        raise UnknownSymbol("빈 값입니다.")
    df = table()

    # ① 코드 정확 일치 — 국내는 6자리 0채움, 미국은 대문자
    for cand in (q.zfill(6) if q.isdigit() else None, q.upper(), q):
        if cand is None:
            continue
        hit = df[df["code"] == cand]
        if len(hit):
            return _row(hit.iloc[0])

    # ② 이름 정확 일치
    hit = df[df["name"].astype(str).str.casefold() == q.casefold()]
    if len(hit):
        return _row(hit.iloc[0])

    # ③ 이름 부분 일치 — 여러 개면 **가장 짧은 이름**을 고른다.
    # '삼성전' 으로 검색했을 때 '삼성전자'(4자)가 '삼성전자우'보다 먼저 와야 한다.
    hit = df[df["name"].astype(str).str.contains(q, case=False, na=False,
                                                 regex=False)]
    if len(hit):
        return _row(hit.loc[hit["name"].astype(str).str.len().idxmin()])

    raise UnknownSymbol(
        f"'{query}' 를 코스피·코스닥·나스닥·S&P500 에서 찾지 못했습니다.")


def _row(r: pd.Series) -> dict:
    """레지스트리 한 행 → 서비스가 쓰는 형태."""
    listed = r.get("listing_date")
    return {
        "code": str(r["code"]),
        "label": str(r["name"]),
        "name": str(r["name"]),
        "market": str(r["market"]),
        "index": str(r["index"]),
        "sector": None if pd.isna(r.get("sector")) else str(r["sector"]),
        "industry": None if pd.isna(r.get("industry")) else str(r["industry"]),
        "listing_date": None if pd.isna(listed) else str(pd.Timestamp(listed).date()),
        # 뉴스 검색어는 **회사 이름**이다. 티커로 검색하면 동음이의가 섞인다
        # (실측: 'MU' 로 검색하면 마이크론 대신 대학 뉴스가 나온다).
        "query": str(r["name"]),
    }


def exists(query: str) -> bool:
    try:
        resolve(query)
        return True
    except UnknownSymbol:
        return False


def yahoo_ticker(code: str, index: str) -> str:
    """미국은 티커 그대로, 국내는 시장에 따라 접미사가 붙는다."""
    return yahoo_candidates(code, index)[0]


def yahoo_candidates(code: str, index: str) -> list[str]:
    """
    야후에 물어볼 티커 후보들. **순서대로 시도한다.**

    ⚠️ 이중 클래스 주식의 표기가 출처마다 다르다.
    FinanceDataReader 의 S&P500 목록은 버크셔 B 를 `BRKB`, 브라운포맨 B 를
    `BFB` 로 주는데 **야후는 `BRK-B` · `BF-B`** 다. 그대로 물으면
    "possibly delisted" 로 빈 응답이 오고, 조회가 통째로 실패한다.

    실측: 스트레스 검사에서 이 둘이 세 기능 모두 RuntimeError 로 죽었다.
    버크셔는 관심종목에 확실히 담길 종목이라 그냥 둘 수 없다.

    하드코딩 목록 대신 **규칙**으로 푼다 — 끝 한 글자 앞에 하이픈을 넣어
    본다(BRKB → BRK-B, BFB → BF-B). 목록으로 막으면 다음에 편입되는
    클래스주에서 같은 일이 반복된다.

    GOOGL·FOXA·NWSA 처럼 야후가 그대로 받는 티커는 첫 후보에서 끝나므로
    추가 비용이 없다.
    """
    if index == "KOSPI":
        return [f"{code}.KS"]
    if index == "KOSDAQ":
        return [f"{code}.KQ"]

    out = [code]
    if len(code) >= 3 and code[-1].isalpha() and code.isalpha():
        alt = f"{code[:-1]}-{code[-1]}"
        if alt not in out:
            out.append(alt)
    return out


def codes(index: str | None = None, market: str | None = None) -> list[str]:
    df = table()
    if index:
        df = df[df["index"] == index]
    if market:
        df = df[df["market"] == market]
    return df["code"].astype(str).tolist()


def same_sector(code: str, limit: int = 40) -> list[str]:
    """
    같은 섹터의 다른 종목 코드.

    상관 기반 이웃(`reference.peers`)이 실패했을 때의 **대체 경로**다.
    상장 두 달 된 종목은 상관을 잴 표본이 없지만 섹터는 첫날부터 정해져 있다.
    """
    df = table()
    hit = df[df["code"] == str(code)]
    if not len(hit):
        return []
    sec = hit.iloc[0].get("sector")
    if pd.isna(sec) or not sec:
        return []
    same = df[(df["sector"] == sec) & (df["code"] != str(code))
              & (df["market"] == hit.iloc[0]["market"])]
    return same["code"].astype(str).head(limit).tolist()
