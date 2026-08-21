"""
페어 관계 이탈 탐지.

**"페어 트레이딩 전략"이라고 부르지 않는다.** 매매 추천으로 읽히면
투자자문 유사 서비스가 된다. 이 모듈이 하는 일은 이상 탐지다 —
"평소 함께 움직이던 두 종목이 지금 벌어졌다"까지만 말한다.

청각 UI가 시각 UI보다 나은 사례
------------------------------
시각 차트는 두 종목을 비교하려면 선 두 개를 겹쳐 그려야 하고, 색으로 구분해야 한다.
색각 이상 사용자에게는 그 순간 실패한다.
소리는 **좌/우 채널**로 자연스럽게 분리된다. 벌어지면 좌우 음높이 차이가 커진다.
이건 접근성 때문에 어쩔 수 없이 만든 대체 수단이 아니라, 그냥 더 나은 표현이다.

실측 (03 노트북)
    60종목 1,770쌍 공적분 검정 → 15쌍 발견 (p<0.01, 119초)
    관계 이탈(|z|>2) 발생률 13.0%
"""

from __future__ import annotations

import time

import numpy as np
import pandas as pd

from .anomaly import josa
from .config import DISCLAIMER


def find_pairs(closes: pd.DataFrame, pvalue: float = 0.01,
               max_pairs: int = 20, verbose: bool = False) -> list[dict]:
    """
    공적분 페어 탐색.

    종목 수의 제곱으로 늘어난다 (60종목 = 1,770쌍 = 119초).
    관심종목 규모(10~30개)에서는 몇 초면 끝나지만, 전 종목에 돌리면 안 된다.
    """
    from statsmodels.tsa.stattools import coint

    codes = list(closes.columns)
    t0 = time.time()
    out = []
    for i in range(len(codes)):
        for j in range(i + 1, len(codes)):
            a, b = codes[i], codes[j]
            try:
                _, p, _ = coint(np.log(closes[a]), np.log(closes[b]))
            except Exception:
                continue
            if p < pvalue:
                out.append({"a": a, "b": b, "pvalue": float(p)})
    out.sort(key=lambda x: x["pvalue"])
    if verbose:
        print(f"공적분 페어 {len(out)}개 / {len(codes)*(len(codes)-1)//2}쌍 "
              f"({time.time()-t0:.0f}초, p<{pvalue})")
    return out[:max_pairs]


def spread_z(closes: pd.DataFrame, a: str, b: str,
             window: int = 60, min_periods: int = 20) -> tuple[pd.Series, float]:
    """로그가격 회귀 잔차의 z-score. 반환: (z 시리즈, 헤지비율 beta)"""
    la, lb = np.log(closes[a]), np.log(closes[b])
    beta = float(np.polyfit(lb, la, 1)[0])
    spread = la - beta * lb
    mu = spread.rolling(window, min_periods=min_periods).mean()
    sd = spread.rolling(window, min_periods=min_periods).std()
    return (spread - mu) / sd.replace(0, np.nan), beta


def scan(panel_closes: pd.DataFrame, name_map: dict[str, str] | None = None,
         threshold: float = 2.0, pvalue: float = 0.01,
         verbose: bool = False) -> dict:
    """관심종목 안에서 관계가 벌어진 페어를 찾는다."""
    name_map = name_map or {}
    closes = panel_closes.dropna()
    if closes.shape[1] < 2 or len(closes) < 120:
        return {"pairs": [], "breaks": [], "note": "데이터가 부족합니다."}

    found = find_pairs(closes, pvalue=pvalue, verbose=verbose)
    breaks = []
    for p in found:
        z, beta = spread_z(closes, p["a"], p["b"])
        z = z.dropna()
        if not len(z):
            continue
        last = float(z.iloc[-1])
        na, nb = name_map.get(p["a"], p["a"]), name_map.get(p["b"], p["b"])
        rec = {
            "a": p["a"], "b": p["b"], "name_a": na, "name_b": nb,
            "pvalue": round(p["pvalue"], 5), "beta": round(beta, 3),
            "z": round(last, 2),
            "break_rate": round(float((z.abs() > threshold).mean()), 3),
            "state": "이탈" if abs(last) > threshold else "정상",
        }
        # 조사는 받침에 따라 갈린다. 음성으로 읽히므로 틀리면 바로 어색하다
        # ("셀트리온와 두산에너빌리티는" / "셀트리온가 강합니다" 같은 오류가 실제로 났다).
        strong = na if last > 0 else nb
        wider = f"{strong}{josa(strong, ('이', '가'))} 상대적으로 강합니다"
        rec["text"] = (
            f"{na}{josa(na, ('과', '와'))} {nb}{josa(nb, ('은', '는'))} "
            f"평소 함께 움직이는 종목입니다. "
            f"현재 관계 지표는 {last:+.1f}로 평소 범위를 "
            f"{'벗어났습니다' if abs(last) > threshold else '유지하고 있습니다'}."
            + (f" {wider}." if abs(last) > threshold else "")
        )
        breaks.append(rec)

    breaks.sort(key=lambda r: -abs(r["z"]))
    return {
        "pairs": found, "breaks": breaks,
        "n_broken": sum(1 for b in breaks if b["state"] == "이탈"),
        "disclaimer": "관계 이탈은 두 종목의 상대 움직임을 설명한 것이며 "
                      "매매 신호가 아닙니다. " + DISCLAIMER,
    }
