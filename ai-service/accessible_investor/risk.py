"""
종목별 위험도 — "지금 이 종목에 들어가기 얼마나 어려운가".

이상감지는 "언제 이상한가"를 답한다. 이 모듈은 다른 걸 묻는다 —
**"이 종목은 원래 얼마나 험한가"**. 둘은 다르다.

    이상감지  오늘 09:35 거래량이 평소의 8배다        ← 시점
    위험도    이 종목은 이런 일이 자주 나는 종목이다   ← 성질

관심종목을 고를 때 필요한 건 후자다. 이상 신호가 하루 3번 뜨는 종목과
2주에 한 번 뜨는 종목은 같은 방식으로 다룰 수 없다.

다섯 축
=======
전부 **지난 60거래일 실측**이고, 각 축을 0~100으로 환산해 가중 평균한다.

    변동성    일간 수익률 표준편차(연율)      — 얼마나 흔들리는가
    이상빈도  이상 신호가 뜬 날의 비율        — 얼마나 자주 튀는가
    갭       시가 갭 절대값 평균             — 밤사이 얼마나 벌어지는가
    꼬리     하루 고저폭 / 종가              — 하루 안에서 얼마나 휘두르는가
    유동성   거래대금의 역수                 — 빠져나오기 얼마나 어려운가

왜 절대 기준이 아니라 분위수인가
--------------------------------
"변동성 2%면 위험"이라는 절대 기준은 시장 국면마다 달라진다. 2020년 3월엔
대형주도 5%를 찍었고 조용한 장에서는 0.8%가 높은 축이다. 그래서 각 축을
**같은 날 전체 종목 분포에서의 백분위**로 환산한다. "이 종목이 지금
시장에서 몇 번째로 험한가"가 사용자에게 실제로 쓸모 있는 값이다.

⚠️ 이건 수익 예측이 아니다
--------------------------
위험도가 높다고 떨어진다는 뜻이 아니고, 낮다고 오른다는 뜻도 아니다.
**변동 폭과 진입·청산 난이도**만 말한다. 등급 문구에도 그렇게 쓴다.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from . import data as D
from . import features as F

RESULT_DIR = Path(__file__).resolve().parent.parent / "results" / "01_anomaly"
WINDOW = 60

AXES = {
    "변동성": 0.30,
    "이상빈도": 0.25,
    "갭": 0.15,
    "꼬리": 0.15,
    "유동성": 0.15,
}

GRADES = [
    (80, "매우 높음", "하루 안에 크게 흔들립니다. 소액으로 시작하고 "
                      "손절 폭을 넓게 잡아야 합니다"),
    (60, "높음", "변동이 큰 편입니다. 분할 매수를 권합니다"),
    (40, "보통", "일반적인 수준입니다"),
    (20, "낮음", "비교적 완만합니다"),
    (-1, "매우 낮음", "움직임이 작습니다. 단타로는 기회가 적을 수 있습니다"),
]


def _grade(score: float) -> tuple[str, str]:
    for thr, label, advice in GRADES:
        if score >= thr:
            return label, advice
    return GRADES[-1][1], GRADES[-1][2]


def raw_axes(px: pd.DataFrame, window: int = WINDOW) -> dict[str, float]:
    """
    다섯 축의 **원값**. 백분위 환산은 여러 종목을 모은 뒤에 한다.

    한 종목만 놓고는 백분위를 낼 수 없다 — 비교 대상이 있어야 한다.
    """
    if len(px) < 40:
        return {k: np.nan for k in AXES}

    # ⚠️ 롤링 통계는 **전체 시계열에서 먼저** 계산하고 나서 자른다.
    # 처음엔 `px.tail(60)` 을 넘긴 뒤 60일 롤링을 걸었는데, 그러면 60행 중
    # 앞쪽 대부분이 부분창으로 계산돼 값이 뭉개진다. 실측에서 서로 다른
    # 세 종목의 이상빈도 백분위가 전부 54로 같게 나왔다.
    ret_full = px["close"].pct_change()
    z_full = F.robust_z(ret_full, window=window, min_periods=20)

    d = px.tail(window)
    c, h, lo, o, v = d["close"], d["high"], d["low"], d["open"], d["volume"]
    ret = ret_full.tail(window)

    # 연율화 변동성. 252는 연간 거래일 수.
    vol = float(ret.std() * np.sqrt(252))

    # 이상빈도 — |robust z| > 2.5 인 날의 비율.
    # 정규분포라면 1.2%다. 이보다 훨씬 높으면 꼬리가 두꺼운 종목이다.
    freq = float((z_full.tail(window).abs() > 2.5).mean())

    gap = float((o / c.shift() - 1).abs().mean())
    tail = float(((h - lo) / c.replace(0, np.nan)).mean())

    # 유동성은 클수록 좋으므로 부호를 뒤집어 "위험" 방향으로 맞춘다.
    turnover = float((c * v).median())
    return {"변동성": vol, "이상빈도": freq, "갭": gap, "꼬리": tail,
            "유동성": -np.log10(max(turnover, 1.0))}


def score_universe(names: dict[str, str] | None = None,
                   pool: int = 200, window: int = WINDOW,
                   verbose: bool = True) -> pd.DataFrame:
    """
    관심종목 위험도. 백분위 기준이 되는 비교군을 함께 계산한다.

    names
        {표시이름: 코드}. None이면 캐시된 국내 종목 상위 몇 개.
    pool
        백분위를 매길 비교군 크기. 관심종목만으로 백분위를 내면
        4종목 중 1등이 무조건 100점이 된다 — 의미가 없다.
    """
    from . import pipeline as P

    names = names or {D.name_of(c): c for c in P.watchlist_from_cache(5)}
    pool_codes = P.watchlist_from_cache(pool)
    target_codes = set(names.values())

    rows = []
    for code in dict.fromkeys(list(target_codes) + pool_codes):
        px = D.load_daily(code, auto_download=code in target_codes)
        if px is None or len(px) < 40:
            continue
        ax = raw_axes(px, window)
        if not np.isfinite(list(ax.values())).all():
            continue
        rows.append({"code": code, "name": D.name_of(code), **ax})

    pooled = pd.DataFrame(rows)
    if len(pooled) < 20:
        raise RuntimeError(f"비교군이 부족합니다 ({len(pooled)}종목). "
                           "일봉 캐시를 먼저 채우세요.")

    # 각 축을 비교군 분포의 백분위로. rank(pct=True)가 정확히 그 값이다.
    for ax in AXES:
        pooled[f"pct_{ax}"] = pooled[ax].rank(pct=True) * 100

    pooled["위험도"] = sum(pooled[f"pct_{ax}"] * w for ax, w in AXES.items())
    pooled["위험도"] = pooled["위험도"].round(1)

    out = pooled[pooled["code"].isin(target_codes)].copy()
    inv = {v: k for k, v in names.items()}
    out["표시이름"] = out["code"].map(inv).fillna(out["name"])
    out[["등급", "설명"]] = out["위험도"].apply(
        lambda s: pd.Series(_grade(s)))
    out["비교군"] = len(pooled)

    cols = (["표시이름", "code", "위험도", "등급", "설명", "비교군"]
            + [f"pct_{a}" for a in AXES] + list(AXES))
    out = out[cols].sort_values("위험도", ascending=False).reset_index(drop=True)

    if verbose:
        print(f"위험도 (비교군 {len(pooled)}종목 · 최근 {window}거래일)")
        for _i, r in out.iterrows():
            print(f"  {r['표시이름']:12s} {r['위험도']:5.1f}  {r['등급']:8s} "
                  f"변동성 {r['pct_변동성']:.0f} · 이상빈도 {r['pct_이상빈도']:.0f} "
                  f"· 유동성 {r['pct_유동성']:.0f}")
    return out


def speak(row: pd.Series) -> str:
    """한 종목 위험도를 문장으로. UI가 그대로 읽으면 된다."""
    top = max(AXES, key=lambda a: row.get(f"pct_{a}", 0))
    return (f"{row['표시이름']} 위험도 {row['위험도']:.0f}점, {row['등급']}입니다. "
            f"비교군 {row['비교군']}종목 중 {row['위험도']:.0f}번째 백분위이며, "
            f"{top} 축이 가장 높습니다. {row['설명']}. "
            "이 값은 변동 폭과 진입 난이도를 말할 뿐 "
            "주가 방향을 예측하지 않습니다.")


def save(out: pd.DataFrame, verbose: bool = True) -> Path:
    d = RESULT_DIR / "data"
    d.mkdir(parents=True, exist_ok=True)
    out.to_csv(d / "risk_scores.csv", index=False, encoding="utf-8-sig")
    (d / "risk_scores.json").write_text(
        json.dumps({"axes": AXES, "window": WINDOW,
                    "rows": out.to_dict("records")},
                   ensure_ascii=False, indent=2, default=str),
        encoding="utf-8")
    if verbose:
        print(f"  → {d / 'risk_scores.csv'}")
    return d / "risk_scores.csv"
