"""
참조 패널 — **임의 종목을 비교군 없이 처리하기 위한 사전 계산물.**

풀어야 했던 문제
================
연구용 코드에는 "비교군을 그때그때 모은다"는 가정이 깔려 있었다.

    테마 이웃    같은 시장 종목을 전부 받아 상관을 잰다
    위험도 백분위 비교군 140종목을 받아 순위를 매긴다

관심종목 하나를 조회할 때마다 저걸 하면 수백 건의 시세 요청이 나간다.
증권 툴에서는 성립하지 않는다.

해법은 **비교군을 미리 굳혀 파일로 저장**하는 것이다. 한 번 만들어 두면
조회 시점에는 파일만 읽으면 되고, 임의 종목이 들어와도 자기 시세 하나만
있으면 이웃도 백분위도 즉시 나온다.

만드는 것 둘
------------
    refpanel_{KR,US}.parquet   비교군 종가 패널
        · 국내  시가총액 상위 300
        · 미국  S&P500 구성종목 (규모 정렬이 필요 없다 — 정의상 대형주다)
        쓰임 ① 테마 이웃 상관 계산  ② 차트 유사도 후보 풀

    risk_reference.json        위험도 5축의 **분위수 격자**
        축마다 0~100 퍼센타일 값 101개. 임의 종목의 원값을 이 격자에
        보간하면 "비교군 안에서 몇 번째"가 조회 없이 나온다.

왜 원값이 아니라 분위수 격자인가
--------------------------------
비교군 전체를 저장하면 파일이 커지고, 무엇보다 **어떤 종목이 비교군인지**가
그대로 노출된다. 분위수 격자는 분포의 모양만 남기므로 작고(축당 101개
실수) 종목을 드러내지 않는다. 백분위 계산에 필요한 정보는 정확히 그것뿐이다.

⚠️ 이 파일들은 **저장소에 같이 올라간다.**
팀원이 백엔드를 붙일 때 네트워크 없이 바로 돌아야 하기 때문이다. 시세
데이터 원본이 아니라 요약 통계이므로 재배포 문제도 없다.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np
import pandas as pd

from . import registry as REG

MODEL_DIR = Path(__file__).resolve().parent.parent / "models"
RISK_REF = MODEL_DIR / "risk_reference.json"

# 비교군 크기. 국내는 시총 상위 N, 미국은 S&P500 전체.
#
# 300 으로 정한 근거는 **백분위의 해상도**다. 100종목이면 백분위 한 칸이
# 1%p 라 위험도 점수가 뭉텅뭉텅 튄다. 300이면 0.33%p 로 충분히 매끄럽고,
# 시세 수집도 몇 분이면 끝난다. 800까지 늘려도 백분위는 거의 안 변한다 —
# 분포의 모양은 300에서 이미 수렴한다.
POOL_N = {"KR": 300, "US": 500}
PANEL_YEARS = 6

# 이웃 선정
PEER_K = 6
PEER_FIT_FRAC = 0.55       # 이웃을 **고르는 데만** 쓰는 앞쪽 구간
PEER_MIN_OVERLAP = 60      # 상관을 재려면 이만큼은 겹쳐야 한다

RISK_AXES = ["변동성", "이상빈도", "갭", "꼬리", "유동성"]
QGRID = np.round(np.linspace(0, 100, 101), 1)


def panel_path(market: str) -> Path:
    return MODEL_DIR / f"refpanel_{market}.parquet"


# ==========================================================================
# 1. 수집
# ==========================================================================
def _pool_codes(market: str, n: int) -> list[str]:
    """비교군에 넣을 종목 코드."""
    tb = REG.table()
    tb = tb[tb["market"] == market]
    if market == "KR":
        tb = tb.dropna(subset=["size"]).sort_values("size", ascending=False)
        return tb["code"].astype(str).head(n).tolist()
    # 미국은 S&P500 을 먼저 채우고 모자라면 나스닥으로 메운다.
    sp = tb[tb["index"] == "S&P500"]["code"].astype(str).tolist()
    if len(sp) >= n:
        return sp[:n]
    nq = tb[tb["index"] == "NASDAQ"]["code"].astype(str).tolist()
    return (sp + nq)[:n]


def _fetch_kr(codes: list[str], verbose: bool) -> dict[str, pd.DataFrame]:
    from . import data as D

    out, t0 = {}, time.time()
    for i, c in enumerate(codes, 1):
        try:
            px = D.load_daily(c, auto_download=True)
        except Exception:
            px = None
        if px is not None and len(px) > 120:
            out[c] = px
        if verbose and i % 50 == 0:
            print(f"    {i}/{len(codes)} … 확보 {len(out)}종목 "
                  f"({time.time() - t0:.0f}초)")
    return out


def _fetch_us(codes: list[str], verbose: bool) -> dict[str, pd.DataFrame]:
    """
    yfinance 는 여러 티커를 한 번에 받는다. 100개씩 끊는다 —
    한 번에 다 넣으면 어느 티커가 실패했는지 알 수 없다.
    """
    import yfinance as yf

    out, step, t0 = {}, 100, time.time()
    for i in range(0, len(codes), step):
        chunk = codes[i:i + step]
        try:
            raw = yf.download(chunk, period=f"{PANEL_YEARS}y", interval="1d",
                              auto_adjust=True, progress=False,
                              threads=True, group_by="ticker")
        except Exception as e:
            if verbose:
                print(f"    청크 {i // step + 1} 실패: {type(e).__name__}")
            continue
        for c in chunk:
            try:
                df = raw[c] if isinstance(raw.columns, pd.MultiIndex) else raw
            except KeyError:
                continue
            df = df.rename(columns=str.lower).dropna(subset=["close"])
            if len(df) > 120:
                df.index = pd.to_datetime(df.index).tz_localize(None).normalize()
                out[c] = df
        if verbose:
            print(f"    {min(i + step, len(codes))}/{len(codes)} … "
                  f"확보 {len(out)}종목 ({time.time() - t0:.0f}초)")
    return out


def build(market: str, n: int | None = None,
          verbose: bool = True) -> pd.DataFrame:
    """
    비교군 시세를 받아 **종가 패널**과 **위험도 분위수**를 함께 만든다.

    두 산출물을 한 함수에서 만드는 이유는 재료가 같아서다. 위험도 5축 중
    갭·꼬리·유동성은 시가/고저/거래량이 있어야 계산되는데, 패널에는 종가만
    남긴다(파일이 5배가 되니까). **전체 OHLCV 가 메모리에 있는 이 순간에**
    축을 계산해 두지 않으면 나중에 다시 받아야 한다.
    """
    from . import risk as RK

    codes = _pool_codes(market, n or POOL_N[market])
    if verbose:
        print(f"  [{market}] 비교군 {len(codes)}종목 시세 수집 …")
    px = (_fetch_kr if market == "KR" else _fetch_us)(codes, verbose)
    if len(px) < 50:
        raise RuntimeError(f"{market} 비교군 확보 실패 ({len(px)}종목).")

    # ── 종가 패널 ──────────────────────────────────────────────────────
    cutoff = pd.Timestamp.now().normalize() - pd.DateOffset(years=PANEL_YEARS)
    closes = {c: d["close"] for c, d in px.items()}
    panel = pd.DataFrame(closes).sort_index()
    panel = panel[panel.index >= cutoff].astype("float32")
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    panel.to_parquet(panel_path(market))
    if verbose:
        mb = panel_path(market).stat().st_size / 1e6
        print(f"    종가 패널 {panel.shape[0]}일 × {panel.shape[1]}종목 "
              f"→ {panel_path(market).name} ({mb:.1f}MB)")

    # ── 위험도 분위수 격자 ─────────────────────────────────────────────
    rows = []
    for c, d in px.items():
        try:
            ax = RK.raw_axes(d)
        except Exception:
            continue
        if np.isfinite(list(ax.values())).all():
            rows.append(ax)
    ref = json.loads(RISK_REF.read_text(encoding="utf-8")) if RISK_REF.is_file() else {}
    axdf = pd.DataFrame(rows)
    ref[market] = {"n": int(len(axdf)),
                   "생성일": str(pd.Timestamp.now().date()),
                   "격자": {a: [float(v) for v in
                             np.percentile(axdf[a].to_numpy(float), QGRID)]
                          for a in RISK_AXES if a in axdf.columns}}
    RISK_REF.write_text(json.dumps(ref, ensure_ascii=False, indent=1),
                        encoding="utf-8")
    if verbose:
        print(f"    위험도 기준분포 {len(axdf)}종목 → {RISK_REF.name}")
    return panel


def build_all(verbose: bool = True) -> None:
    for m in ("KR", "US"):
        build(m, verbose=verbose)


# ==========================================================================
# 2. 조회
# ==========================================================================
_PANEL: dict[str, pd.DataFrame] = {}
_RISK: dict | None = None


def load_panel(market: str) -> pd.DataFrame:
    """비교군 종가 패널. 프로세스당 한 번만 읽는다."""
    if market not in _PANEL:
        p = panel_path(market)
        if not p.is_file():
            raise FileNotFoundError(
                f"{p} 가 없습니다. `python cli.py reference --build` 로 "
                "비교군을 먼저 만드세요.")
        _PANEL[market] = pd.read_parquet(p)
    return _PANEL[market]


def has_panel(market: str) -> bool:
    return panel_path(market).is_file()


def load_risk_reference() -> dict:
    global _RISK
    if _RISK is None:
        if not RISK_REF.is_file():
            raise FileNotFoundError(
                f"{RISK_REF} 가 없습니다. `python cli.py reference --build` 를 "
                "먼저 실행하세요.")
        _RISK = json.loads(RISK_REF.read_text(encoding="utf-8"))
    return _RISK


def risk_percentile(market: str, axis: str, value: float) -> float:
    """
    원값 → 비교군 안 백분위(0~100). 저장된 분위수 격자에 **선형 보간**한다.

    격자가 단조증가라는 걸 이용한 역보간이다. `np.interp` 는 x 가 증가해야
    하므로 격자를 x, 백분위를 y 로 놓는다.
    """
    ref = load_risk_reference().get(market)
    if not ref or axis not in ref.get("격자", {}):
        return float("nan")
    grid = np.asarray(ref["격자"][axis], dtype=float)
    if not np.isfinite(value):
        return float("nan")
    return float(np.interp(value, grid, QGRID))


def reference_n(market: str) -> int:
    ref = load_risk_reference().get(market, {})
    return int(ref.get("n", 0))


# ==========================================================================
# 3. 테마 이웃
# ==========================================================================
def peers(code: str, close: pd.Series, market: str,
          k: int = PEER_K) -> tuple[list[str], str]:
    """
    이 종목과 **같이 움직이는 종목 k개**와 그 선정 근거.

    1순위는 상관이다. 비교군 패널과 일간 수익률 상관을 재서 상위 k개를 쓴다.

    ⚠️ 상관은 **앞쪽 55% 구간에서만** 잰다.
    전 구간에서 재면 "최근에 나와 가장 닮게 움직인 종목"을 이웃으로 고르는
    셈이라, 이웃 수익률이 곧 내 수익률의 누설이 된다. 학습에서든 서빙에서든
    같은 규칙을 쓴다 — 평가 때와 서빙 때 이웃이 다르게 뽑히면 보고한 성능이
    그대로 재현되지 않는다.

    2순위는 섹터다. 상장한 지 얼마 안 된 종목은 상관을 잴 표본이 없다.
    그때는 같은 산업분류의 비교군 종목을 쓴다 — 상관만큼 정밀하진 않아도
    "이차전지는 이차전지끼리 움직인다"는 최소한의 테마 신호는 남는다.

    반환
        (이웃 코드 목록, 근거)  근거 ∈ {"상관", "섹터", "없음"}
    """
    try:
        pool = load_panel(market)
    except FileNotFoundError:
        return [], "없음"
    pool = pool.drop(columns=[c for c in [str(code)] if c in pool.columns])
    if pool.empty or pool.shape[1] < 4:
        return [], "없음"

    own = pd.Series(np.asarray(close, dtype=float),
                    index=pd.DatetimeIndex(close.index)).sort_index()
    joined = pool.join(own.rename("__self__"), how="inner")
    rets = joined.pct_change()
    fit_n = int(len(rets) * PEER_FIT_FRAC)
    if fit_n >= 120:
        head = rets.iloc[:fit_n]
        if head["__self__"].notna().sum() >= PEER_MIN_OVERLAP:
            corr = head.corrwith(head["__self__"]).drop(
                labels=["__self__"], errors="ignore").dropna()
            sel = corr.sort_values(ascending=False).head(k).index.tolist()
            if sel:
                return [str(s) for s in sel], "상관"

    # 폴백 — 같은 섹터의 비교군 종목
    sect = [c for c in REG.same_sector(code, limit=200) if c in pool.columns]
    if sect:
        return sect[:k], "섹터"
    return [], "없음"


def peer_frame(peer_codes: list[str], market: str,
               index: pd.DatetimeIndex) -> pd.DataFrame:
    """이웃들의 일간 수익률을 대상 종목의 날짜축에 맞춰 돌려준다."""
    if not peer_codes:
        return pd.DataFrame(index=index)
    pool = load_panel(market)
    use = [c for c in peer_codes if c in pool.columns]
    if not use:
        return pd.DataFrame(index=index)
    return pool[use].pct_change().reindex(index)
