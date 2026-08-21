"""
검증 — 단타 이상 탐지가 실제로 쓸 만한가.

02 노트북은 "합성 이상치를 얼마나 잘 찾는가"를 물었다. 그건 탐지기 순위를 정할 때
필요한 질문이다. 여기서 묻는 건 다르다.

    **울렸을 때 실제로 먹을 게 있었나.**

단타 사용자에게 이게 유일하게 의미 있는 질문이다. 그래서 정답 라벨을
합성 이상치가 아니라 **알림 이후의 실제 가격 움직임**으로 잡는다.

정답 정의
--------
봉 t에서 알림이 울렸을 때, 이후 30분(6봉) 동안 시장 대비 누적 초과수익의
절대 최대값이 **그 종목 일간 변동성의 0.5배** 이상이면 "기회였다"로 본다.

    일간 σ = 5분 초과수익 표준편차 × √(하루 봉 수)

005930 기준 5분 σ≈0.40% → 일간 σ≈3.4% → 임계 1.7%.
30분에 1.7% 움직이면 단타로 잡을 만한 크기다. 수수료·슬리피지를 넘는다.

이 라벨은 **알림 시점의 입력을 쓰지 않는다.** 미래만 본다.
탐지기가 자기 자신을 평가하는 순환을 피하기 위해서다.
"""

from __future__ import annotations

import json
import time
from collections import Counter

import numpy as np
import pandas as pd

from . import anomaly as A
from . import features as F
from .config import CHANNELS, HORIZONS, OUTPUT_DIR


# --------------------------------------------------------------------------
# 정답 라벨
# --------------------------------------------------------------------------
def forward_opportunity(feat: pd.DataFrame, horizon: str = "intraday",
                        bars: int = 6, sigma_frac: float = 0.5) -> pd.Series:
    """봉 t 이후 `bars`봉 안에 단타 크기의 움직임이 있었는가."""
    ex = feat["excess_ret"].fillna(0.0)
    day = pd.Series(feat.index.date, index=feat.index)

    # 누적 초과수익의 향후 최대 절대 이동
    fwd = pd.Series(0.0, index=feat.index)
    cum = pd.Series(0.0, index=feat.index)
    for h in range(1, bars + 1):
        cum = cum + ex.shift(-h).fillna(0.0)
        # 날짜를 넘어가면 무효 (밤사이 갭은 단타 기회가 아니다)
        same_day = day.shift(-h) == day
        step = cum.where(same_day, np.nan)
        fwd = np.maximum(fwd, step.abs().fillna(0.0))

    bpd = HORIZONS[horizon]["bars_per_day"]
    daily_sigma = ex.rolling(HORIZONS[horizon]["baseline_bars"],
                             min_periods=60).std() * np.sqrt(bpd)
    thr = daily_sigma * sigma_frac
    return (fwd > thr) & thr.notna()


# --------------------------------------------------------------------------
# 비교 대상 — 증권사 급등락 알림
# --------------------------------------------------------------------------
def broker_style_alerts(feat: pd.DataFrame, pct: float = 2.0,
                        window: int = 6) -> pd.Series:
    """
    실제 HTS의 급등락 알림을 재현한다: **고정 % 기준**.

    "최근 30분간 ±2% 이상 변동" — 증권사 알림 설정에서 흔한 형태다.
    비교 기준선으로 쓴다. 이게 왜 접근성 도구에 부적합한지를 숫자로 보여주는 게 목적이다.
    """
    ret = feat["close"].pct_change(window)
    day = pd.Series(feat.index.date, index=feat.index)
    same_day = day.shift(window) == day
    return (ret.abs() > pct / 100.0) & same_day


# --------------------------------------------------------------------------
# 지표
# --------------------------------------------------------------------------
def _score(fired: pd.Series, label: pd.Series, n_days: int) -> dict:
    valid = label.notna() & fired.notna()
    f = fired[valid].astype(bool)
    y = label[valid].astype(bool)
    n_alert = int(f.sum())
    return {
        "알림수": n_alert,
        "일평균": round(n_alert / max(n_days, 1), 2),
        "정밀도": round(float(y[f].mean()), 3) if n_alert else float("nan"),
        "재현율": round(float(f[y].mean()), 3) if int(y.sum()) else float("nan"),
        "기저율": round(float(y.mean()), 3),
        "리프트": round(float(y[f].mean() / y.mean()), 2)
        if n_alert and y.mean() > 0 else float("nan"),
    }


def _fired_series(alerts: list[A.Alert], index: pd.Index,
                  grades: tuple[str, ...] = ("A", "B", "C")) -> pd.Series:
    s = pd.Series(False, index=index)
    for a in alerts:
        if a.confidence in grades and a.ts in s.index:
            s.loc[a.ts] = True
    return s


# --------------------------------------------------------------------------
# 어블레이션
# --------------------------------------------------------------------------
def run_ablation(panel: dict[str, tuple[str, pd.DataFrame]],
                 horizon: str = "intraday", budget: float | None = None,
                 sigma_frac: float = 1.0, verbose: bool = True) -> dict:
    """
    구성별 비교. 각 단계가 실제로 값을 하는지 하나씩 켜 본다.

    구성
      A. 고정 |z|>3, 시간대 보정 없음    ← 순진한 단타 전환. 이게 왜 망하는지 보여준다
      B. 고정 |z|>3, 시간대 보정 있음
      C. 예산 임계값, 시간대 보정 있음    ← 채택
      D. C + 등급 A만 음성
      E. 증권사식 고정 % 알림 (비교 기준선)
    """
    if budget is None:
        budget = A.per_stock_budget(horizon, len(panel))
    results, per_stock = {}, []
    n_days_total = 0

    for code, (name, feat) in panel.items():
        n_days = pd.Series(feat.index.date).nunique()
        n_days_total = max(n_days_total, n_days)
        label = forward_opportunity(feat, horizon, sigma_frac=sigma_frac)

        # --- 시간대 보정을 끈 버전을 만든다 --------------------------------
        # 원시 초과수익에 바로 robust z를 씌운 것 = 보정 없음
        raw = feat.copy()
        cfg = HORIZONS[horizon]
        raw["excess_z"] = F.robust_z(raw["excess_ret"], cfg["baseline_bars"],
                                     cfg["min_baseline"])
        raw["vol_z"] = F.robust_z(raw["log_volume"], cfg["baseline_bars"],
                                  cfg["min_baseline"])
        raw["rvol_z"] = F.robust_z(raw["rvol"], cfg["baseline_bars"],
                                   cfg["min_baseline"])

        fixed = {n: 3.0 for n in CHANNELS if A._channel_enabled(n, horizon)}
        budgeted = A.calibrate(feat, horizon, budget=budget)

        cfgs = {
            "A. 고정 |z|>3 (순진한 단타 전환)": A.scan(raw, code, name, horizon,
                                               thresholds=fixed, cooldown_min=0,
                                               min_move_sigma=0.0),
            "B. + 채널 병합 · 쿨다운":          A.scan(raw, code, name, horizon,
                                               thresholds=fixed, min_move_sigma=0.0),
            "C. + 예산 임계값":                A.scan(feat, code, name, horizon,
                                               thresholds=budgeted, min_move_sigma=0.0),
            "D. + 절대 크기 게이트 (채택)":     A.scan(feat, code, name, horizon,
                                               thresholds=budgeted),
        }

        row = {"code": code, "name": name, "days": n_days}
        for label_name, alerts in cfgs.items():
            fired = _fired_series(alerts, feat.index)
            m = _score(fired, label, n_days)
            results.setdefault(label_name, []).append(m)
            row[label_name] = m["일평균"]

        # E: 음성으로 실제 나가는 것만 (A·B등급 · 우선순위 1~2)
        voiced = _fired_series([a for a in cfgs["D. + 절대 크기 게이트 (채택)"] if a.voice],
                               feat.index)
        results.setdefault("E. 그중 음성으로 나가는 것", []).append(
            _score(voiced, label, n_days))
        row["E. 그중 음성으로 나가는 것"] = _score(voiced, label, n_days)["일평균"]

        # E: 증권사식
        broker = broker_style_alerts(feat)
        results.setdefault("F. 증권사식 고정 ±2%/30분 (비교)", []).append(
            _score(broker, label, n_days))
        row["F. 증권사식 고정 ±2%/30분 (비교)"] = _score(broker, label, n_days)["일평균"]

        per_stock.append(row)

    summary = pd.DataFrame({
        k: {
            "일평균 알림": round(np.nanmean([m["일평균"] for m in v]), 2),
            "정밀도": round(np.nanmean([m["정밀도"] for m in v]), 3),
            "재현율": round(np.nanmean([m["재현율"] for m in v]), 3),
            "리프트": round(np.nanmean([m["리프트"] for m in v]), 2),
        } for k, v in results.items()
    }).T
    summary["기저율"] = round(np.nanmean(
        [m["기저율"] for v in results.values() for m in v]), 3)

    if verbose:
        print("=" * 78)
        print(f"단타 이상 탐지 어블레이션 — {len(panel)}종목 · {n_days_total}거래일 "
              f"· {HORIZONS[horizon]['interval']}")
        print("=" * 78)
        print(summary.to_string())
        print()
        print("읽는 법: 일평균은 종목당 하루 알림 수. 낮을수록 조용하다.")
        print("         정밀도는 '울렸을 때 실제로 30분 내 단타 크기 움직임이 있었나'.")
        print("         리프트는 무작위 대비 배수. 1.0이면 아무 정보가 없다는 뜻이다.")
    return {"summary": summary, "per_stock": pd.DataFrame(per_stock)}


# --------------------------------------------------------------------------
# 시간대 쏠림 — 보정이 정말 필요한가
# --------------------------------------------------------------------------
def hour_distribution(panel: dict[str, tuple[str, pd.DataFrame]],
                      horizon: str = "intraday", budget: float | None = None,
                      verbose: bool = True) -> pd.DataFrame:
    """알림이 개장 직후에 몰리는지 확인. 보정 유무를 나란히 본다."""
    cfg = HORIZONS[horizon]
    rows = []
    for code, (name, feat) in panel.items():
        raw = feat.copy()
        raw["excess_z"] = F.robust_z(raw["excess_ret"], cfg["baseline_bars"],
                                     cfg["min_baseline"])
        raw["vol_z"] = F.robust_z(raw["log_volume"], cfg["baseline_bars"],
                                  cfg["min_baseline"])
        fixed = {n: 3.0 for n in CHANNELS if A._channel_enabled(n, horizon)}
        for tag, f in (("보정 없음", raw), ("보정 있음", feat)):
            for a in A.scan(f, code, name, horizon, thresholds=fixed, cooldown_min=0):
                rows.append({"구성": tag, "시": a.ts.hour})
    if not rows:
        return pd.DataFrame()

    df = pd.DataFrame(rows)
    tab = pd.crosstab(df["시"], df["구성"], normalize="columns").mul(100).round(1)
    if verbose:
        print()
        print("=" * 78)
        print("알림의 시간대 분포 (%) — 시간대 보정의 효과")
        print("=" * 78)
        print(tab.to_string())
        for col in tab.columns:
            open30 = tab[col].reindex([9]).fillna(0).sum()
            print(f"  {col}: 9시대 집중도 {open30:.1f}%")
        print("  9시대는 71봉 중 12봉(16.9%)이다. 이보다 크면 개장 효과가 남아 있다는 뜻이다.")
    return tab


# --------------------------------------------------------------------------
# 등급이 의미가 있는가
# --------------------------------------------------------------------------
def grade_quality(panel: dict[str, tuple[str, pd.DataFrame]],
                  horizon: str = "intraday", budget: float | None = None,
                  sigma_frac: float = 1.0, verbose: bool = True) -> pd.DataFrame:
    """
    A > B > C 순으로 정밀도가 실제로 높아지는가. 그리고 우선순위 등급은 값을 하는가.

    두 축을 따로 본다. 어블레이션에서 "음성으로 나가는 것"이 전체보다 정밀했는데,
    그 이득이 **신뢰도 등급 때문인지 우선순위 필터 때문인지** 구분되지 않았다.
    섞어서 보면 값을 안 하는 장치를 값을 하는 것으로 착각한다.
    """
    if budget is None:
        budget = A.per_stock_budget(horizon, len(panel))
    conf = {g: {"n": 0, "hit": 0} for g in "ABC"}
    prio = {p: {"n": 0, "hit": 0} for p in (1, 2, 3)}
    for code, (name, feat) in panel.items():
        label = forward_opportunity(feat, horizon, sigma_frac=sigma_frac)
        for a in A.scan(feat, code, name, horizon,
                        thresholds=A.calibrate(feat, horizon, budget=budget)):
            if a.ts not in label.index or not np.isfinite(label.loc[a.ts]):
                continue
            hit = int(bool(label.loc[a.ts]))
            conf[a.confidence]["n"] += 1
            conf[a.confidence]["hit"] += hit
            prio[a.priority]["n"] += 1
            prio[a.priority]["hit"] += hit

    rows = [{"구분": "신뢰도", "값": g, "알림수": v["n"],
             "정밀도": round(v["hit"] / v["n"], 3) if v["n"] else float("nan")}
            for g, v in conf.items()]
    rows += [{"구분": "우선순위", "값": str(p), "알림수": v["n"],
              "정밀도": round(v["hit"] / v["n"], 3) if v["n"] else float("nan")}
             for p, v in prio.items()]
    tab = pd.DataFrame(rows)
    if verbose:
        print()
        print("=" * 78)
        print("등급이 정보를 담고 있는가 (라벨 " + str(sigma_frac) + "σ)")
        print("=" * 78)
        print(tab.to_string(index=False))
        c = [conf[g]["hit"] / conf[g]["n"] for g in "ABC" if conf[g]["n"] >= 20]
        if len(c) >= 2:
            spread = (max(c) - min(c)) / max(min(c), 1e-9)
            print(f"  신뢰도 등급 간 정밀도 격차: {spread*100:.0f}%")
            if spread < 0.15:
                print("  → 격차가 작다. 등급은 정밀도를 나누지 못한다.")
                print("     다만 등급의 용도는 '무엇을 음성으로 읽을까'이지 '무엇이 맞을까'가")
                print("     아니므로 폐기하지는 않는다. 정밀도 향상 근거로 쓰면 안 된다는 뜻이다.")
    return tab


# --------------------------------------------------------------------------
# 규칙 vs 학습 랭커 — 같은 알림 수에서 비교
# --------------------------------------------------------------------------
def compare_ranker(panel: dict[str, tuple[str, pd.DataFrame]],
                   horizon: str = "intraday", budget: float | None = None,
                   sigma_frac: float = 1.0, only_codes: set | None = None,
                   verbose: bool = True) -> pd.DataFrame:
    """
    규칙 경로와 랭커 경로를 **같은 예산에서** 비교한다.

    `only_codes`를 주면 그 종목만 본다. 랭커 학습에 쓰인 종목을 빼고 평가하기
    위한 것이다 — 안 그러면 랭커가 외운 종목에서 재는 셈이 되어 무의미하다.
    """
    items = {c: v for c, v in panel.items()
             if only_codes is None or c in only_codes}
    # **평가 대상 종목 수**로 예산을 잡는다. 전체 패널 크기로 잡으면
    # 학습용 종목까지 세어 예산이 잘게 쪼개지고, 두 경로가 서로 다른 개수를 내
    # 비교가 무의미해진다 (실제로 그 실수를 했다 — 랭커 0.12건 vs 규칙 0.53건).
    if budget is None:
        budget = A.per_stock_budget(horizon, max(len(items), 1))
    if not items:
        return pd.DataFrame()

    acc: dict[str, list] = {}
    for code, (name, feat) in items.items():
        label = forward_opportunity(feat, horizon, sigma_frac=sigma_frac)
        n_days = pd.Series(feat.index.date).nunique()

        rule = A.scan(feat, code, name, horizon,
                      thresholds=A.calibrate(feat, horizon, budget=budget))
        rank = A.scan_ranked(feat, code, name, horizon, budget=budget)
        if rank is None:
            if verbose:
                print("랭커 모델이 없습니다. `python cli.py train` 을 먼저 실행하세요.")
            return pd.DataFrame()

        for tag, alerts in (("규칙 경로", rule), ("학습 랭커", rank)):
            acc.setdefault(tag, []).append(
                _score(_fired_series(alerts, feat.index), label, n_days))
            acc.setdefault(tag + " (음성만)", []).append(
                _score(_fired_series([a for a in alerts if a.voice], feat.index),
                       label, n_days))

    tab = pd.DataFrame({
        k: {"일평균 알림": round(np.nanmean([m["일평균"] for m in v]), 2),
            "정밀도": round(np.nanmean([m["정밀도"] for m in v]), 4),
            "재현율": round(np.nanmean([m["재현율"] for m in v]), 4),
            "리프트": round(np.nanmean([m["리프트"] for m in v]), 2)}
        for k, v in acc.items()}).T
    if verbose:
        print()
        print("=" * 78)
        print(f"규칙 경로 vs 학습 랭커 — {len(items)}종목 (랭커가 학습에 쓰지 않은 종목)")
        print("=" * 78)
        print(tab.to_string())
        try:
            r, k = tab.loc["규칙 경로", "정밀도"], tab.loc["학습 랭커", "정밀도"]
            print(f"\n  정밀도 {r:.4f} → {k:.4f} ({(k/r-1)*100:+.0f}%)")
        except Exception:
            pass
    return tab


# --------------------------------------------------------------------------
# 실시간 성능
# --------------------------------------------------------------------------
def realtime_cost(panel: dict[str, tuple[str, pd.DataFrame]],
                  horizon: str = "intraday", verbose: bool = True) -> dict:
    """
    관심종목 전체를 한 번 훑는 데 몇 ms인가.

    화면을 못 보는 사용자에게 무응답 구간은 화면이 멈춘 것과 같다.
    02에서 트랜스포머(104ms/종목)를 1단에서 뺀 것도 같은 이유였다.
    """
    thr = {code: A.calibrate(feat, horizon) for code, (_n, feat) in panel.items()}
    t0 = time.time()
    n = 0
    for code, (name, feat) in panel.items():
        A.scan(feat, code, name, horizon, thresholds=thr[code],
               start=max(1, len(feat) - 2))
        n += 1
    el = (time.time() - t0) * 1000

    t1 = time.time()
    for code, (_n, feat) in panel.items():
        A.calibrate(feat, horizon)
    cal = (time.time() - t1) * 1000

    out = {"종목수": n, "1봉 스캔 총 ms": round(el, 1),
           "종목당 ms": round(el / max(n, 1), 3),
           "임계값 재보정 총 ms": round(cal, 1),
           "종목당 ms(보정)": round(cal / max(n, 1), 2)}
    if verbose:
        print()
        print("=" * 78)
        print("실시간 비용")
        print("=" * 78)
        for k, v in out.items():
            print(f"  {k:20s} {v}")
        print("  임계값 보정은 매 봉이 아니라 하루 한 번(장 시작 전)만 하면 된다.")
    return out


# --------------------------------------------------------------------------
# 전체 실행
# --------------------------------------------------------------------------
def run_all(panel: dict[str, tuple[str, pd.DataFrame]], horizon: str = "intraday",
            budget: float | None = None, save: bool = True) -> dict:
    ab = run_ablation(panel, horizon, budget)
    hd = hour_distribution(panel, horizon, budget)
    gq = grade_quality(panel, horizon, budget)
    rc = realtime_cost(panel, horizon)

    if save:
        out = OUTPUT_DIR / "validation"
        out.mkdir(parents=True, exist_ok=True)
        ab["summary"].to_csv(out / f"07_ablation_{horizon}.csv", encoding="utf-8-sig")
        ab["per_stock"].to_csv(out / f"07_per_stock_{horizon}.csv",
                               index=False, encoding="utf-8-sig")
        if len(hd):
            hd.to_csv(out / f"07_hour_dist_{horizon}.csv", encoding="utf-8-sig")
        gq.to_csv(out / f"07_grade_{horizon}.csv", encoding="utf-8-sig")
        (out / f"07_realtime_{horizon}.json").write_text(
            json.dumps(rc, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n저장: {out}")
    return {"ablation": ab, "hours": hd, "grades": gq, "realtime": rc}
