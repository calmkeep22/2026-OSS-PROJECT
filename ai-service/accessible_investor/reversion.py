"""
급락 후 반등 — **검증을 통과한 두 신호 중 하나.** (다른 하나는 변동성 예측)

어떻게 여기까지 왔나
====================
다음날 방향을 맞히려고 여러 번 시도했고 전부 실패했다.

    국내 뉴스+가격 (139종목)          균형정확도 0.538
    해외 100종목 × 4년 (30조합)       최고 HR 0.5127  < 무지성 0.5167
    38종목 × 40일 방향 (24조합)        48.2~51.6%     신뢰구간이 전부 50% 포함

그러다 사용자의 관찰에서 실마리가 나왔다 —
*"이미 다 오른 뒤에 이상 상승 신호가 뜬다. robust z 가 후행이라 그런가?
그렇다면 과하게 빠졌을 때 반등 신호를 주면 맞지 않나?"*

관찰은 정확했다. `robust z` 는 **그날의 움직임**을 재므로 신호는 언제나
사후다. 그래서 "그 다음날 되돌리는가"를 재봤다.

⚠️ 표본이 독립이 아니다 — 그래서 두 번 잰다
============================================
급락은 **시장 전체가 같이 빠지는 날**에 몰린다. 실측에서 |z|>3 신호 877건이
서로 다른 날짜 563개에 몰려 있었고 하루 최대 17종목이 동시에 걸렸다.
그날 시장이 반등하면 17건이 한꺼번에 맞는다 — 독립 관측 17개가 아니라
사실상 1개다. 윌슨 구간은 독립을 가정하므로 그대로 쓰면 **유의성이
부풀려진다.**

    단순 집계 (`summary`)      877건을 877개로 셈  →  순초과 +7.09%p
    날짜 단위 (`clustered`)    563개 날짜로 셈     →  순초과 +5.28%p  ← 정직한 값

**같은 날 신호를 하나로 묶은 쪽이 진짜다.** 아래 표는 그 값이다.

    |z| 임계값   급락 후 반등   평상시 상승   순초과    날짜   유의미
    ──────────────────────────────────────────────────────────────
        2.0        49.40%      47.52%    +1.88%p  1,112   아니오
        2.5        51.13%      47.52%    +3.62%p    810   예 (아슬)
        3.0        52.80%      47.52%    +5.28%p    563   예

**임계값을 올릴수록 효과가 커진다.** 우연이라면 임계값과 무관해야 한다.
단조 증가는 "더 극단적일수록 더 되돌린다"는 실제 메커니즘이 있다는 뜻이다.
가장 약한 2.0 은 보정 후 유의성을 잃었다 — 그것도 그대로 싣는다.

사후검증 (`holdout`)
--------------------
전 구간을 한 번에 재면 "과거에 이런 규칙성이 있었다"까지만 말할 수 있다.
그래서 종목마다 시계열을 앞/뒤로 갈라 따로 쟀다.

    |z|>3   앞구간  +8.33%p (n=434)      뒤구간  +6.12%p (n=438)

**양쪽 모두에서 나온다.** 한 구간에만 있던 우연이 아니다.

비대칭 — 이 발견의 자체 검증
----------------------------
급락은 되돌리는데 **급등은 안 되돌린다**(순초과 −0.89~−2.08%p).
그럴듯한 이유가 있다 — 급락에는 반대매매·손절·마진콜 같은 **가격을 보지
않는 강제 매도**가 섞여 과도하게 밀린다. 급등은 자발적 매수라 되돌릴
압력이 약하다. 한쪽에서만 나오는 효과라는 점이 신뢰도를 스스로 증명한다.

⚠️ 무엇이 아닌가
================
이건 **매매 신호가 아니다.** 반등 확률 52.8%는 47.2%는 더 빠진다는 뜻이고,
거래비용을 넣으면 남는 게 없을 수 있다. 이 모듈이 말하는 것은
"이 종목이 지금 통계적으로 과매도 구간에 있다"까지다.

저시력 사용자에게 쓸모 있는 이유는 다르다. 화면을 못 보는 사용자는
**급락이 있었다는 사실 자체**를 놓친다. "어제 평소의 3배 넘게 빠졌고,
과거 이런 경우 다음날 52.8%가 반등했습니다(평상시 47.5%)" 는
방향을 단정하지 않으면서 상황을 정확히 전달한다.
"""

from __future__ import annotations

import json

import numpy as np
import pandas as pd

from . import features as F
from . import forecast as FC
from .config import PROJECT_ROOT

RESULT_DIR = PROJECT_ROOT / "results" / "03_forecast"
THRESHOLDS = (2.0, 2.5, 3.0)
DEFAULT_THRESHOLD = 3.0        # 순초과가 가장 큰 값. 대신 신호가 드물다
Z_WINDOW = 60


# ==========================================================================
def signal_series(px: pd.DataFrame, thr: float = DEFAULT_THRESHOLD
                  ) -> pd.DataFrame:
    """
    일봉 → 급락/급등 신호와 다음날 결과.

    `robust z` 는 중앙값·MAD 기준이라 극단값에 끌려가지 않는다. 표준편차를
    쓰면 급락 자체가 분모를 키워 z 가 작아지는 자기상쇄가 일어난다.
    """
    r = px["close"].pct_change()
    z = F.robust_z(r, window=Z_WINDOW, min_periods=20)
    out = pd.DataFrame({"close": px["close"], "ret": r, "z": z})
    out["급락"] = z < -thr
    out["급등"] = z > thr
    out["다음날수익"] = r.shift(-1)
    return out


def evaluate(names: "list[str] | None" = None,
             thresholds=THRESHOLDS, save: bool = True,
             verbose: bool = True) -> pd.DataFrame:
    """
    전 종목 × 임계값별로 반등 효과를 측정한다.

    기준선은 **그 종목의 평상시 다음날 상승 확률**이다. 0.5 가 아니다 —
    종목마다 추세가 달라서 평상시 상승 확률이 45~55% 로 벌어진다.
    """
    names = names or FC.universe_names()
    rows = []
    for nm in names:
        try:
            px = FC.load_prices(nm)
        except Exception:
            continue
        if len(px) < 300:
            continue
        e = FC.entry(nm)
        for thr in thresholds:
            s = signal_series(px, thr)
            ok = s["z"].notna() & s["다음날수익"].notna()
            base_up = float((s.loc[ok, "다음날수익"] > 0).mean())
            dn = ok & s["급락"]
            up = ok & s["급등"]
            rows.append({
                "종목": e["label"], "지수": e["index"], "구분": e["tier"],
                "임계값": thr, "평상시상승률": round(base_up, 4),
                "급락n": int(dn.sum()),
                "급락후반등률": round(float((s.loc[dn, "다음날수익"] > 0).mean()), 4)
                if dn.sum() else np.nan,
                "급락후평균%": round(float(s.loc[dn, "다음날수익"].mean() * 100), 4)
                if dn.sum() else np.nan,
                "급등n": int(up.sum()),
                "급등후하락률": round(float((s.loc[up, "다음날수익"] < 0).mean()), 4)
                if up.sum() else np.nan,
                "평상시평균%": round(float(s.loc[ok, "다음날수익"].mean() * 100), 4),
            })
    df = pd.DataFrame(rows)
    if save and len(df):
        d = RESULT_DIR / "data"
        d.mkdir(parents=True, exist_ok=True)
        df.to_csv(d / "reversion_by_stock.csv", index=False,
                  encoding="utf-8-sig")
    if verbose:
        print(summary(df).to_string(index=False))
    return df


def holdout(names: "list[str] | None" = None,
            thresholds=THRESHOLDS, split: float = 0.5,
            save: bool = True, verbose: bool = True) -> pd.DataFrame:
    """
    **사후검증** — 앞 절반에서 본 효과가 뒤 절반에서도 나오는가.

    왜 필요한가
    -----------
    `evaluate` 는 전 구간을 한 번에 재므로, 엄밀히 말하면 "과거에 이런
    규칙성이 있었다"까지만 말한다. 그걸 **신호**라고 부르려면 한 구간에서
    본 것이 다른 구간에서도 나와야 한다. 그렇지 않으면 그 구간에만 있던
    우연을 발견이라고 부르는 셈이다.

    그래서 종목마다 시계열을 앞/뒤로 갈라 **따로** 잰다. 앞에서만 나오고
    뒤에서 사라지면 그건 신호가 아니다.

    표본이 독립이 아니다 — 같이 세는 것
    ------------------------------------
    급락은 **시장 전체가 같이 빠지는 날**에 몰린다. 38종목에서 877건이
    나왔다고 877개의 독립 관측이 아니다. 같은 날 30종목이 동시에 걸렸다면
    사실상 관측 1개에 가깝다. 윌슨 구간은 독립을 가정하므로 이대로면
    유의성이 부풀려진다.

    고쳐 말할 수는 없지만 **얼마나 뭉쳐 있는지는 셀 수 있다.** 서로 다른
    날짜 수와 하루 최대 동시 신호 수를 같이 낸다. 읽는 사람이 구간을
    얼마나 믿을지 스스로 판단할 근거가 된다.
    """
    names = names or FC.universe_names()
    rows = []
    for nm in names:
        try:
            px = FC.load_prices(nm)
        except Exception:
            continue
        if len(px) < 600:          # 반으로 갈라도 각 300봉은 있어야 한다
            continue
        cut = int(len(px) * split)
        e = FC.entry(nm)
        for thr in thresholds:
            # ⚠️ robust z 는 **전 구간에서 먼저** 계산하고 나서 자른다.
            # 뒤 절반만 떼어 z 를 다시 재면 앞쪽 60봉이 부분창이 되어
            # 신호 자체가 달라진다. 자르는 건 평가 대상이지 계산 창이 아니다.
            s = signal_series(px, thr)
            for part, sub in (("앞구간", s.iloc[:cut]), ("뒤구간", s.iloc[cut:])):
                ok = sub["z"].notna() & sub["다음날수익"].notna()
                dn = ok & sub["급락"]
                if not dn.sum():
                    continue
                rows.append({
                    "종목": e["label"], "구간": part, "임계값": thr,
                    "급락n": int(dn.sum()),
                    "급락후반등률": float((sub.loc[dn, "다음날수익"] > 0).mean()),
                    "평상시상승률": float((sub.loc[ok, "다음날수익"] > 0).mean()),
                    "신호일자": list(sub.index[dn]),
                })
    df = pd.DataFrame(rows)
    if not len(df):
        return df

    out = []
    for (part, thr), s in df.groupby(["구간", "임계값"]):
        n = int(s["급락n"].sum())
        reb = float((s["급락후반등률"] * s["급락n"]).sum() / max(n, 1))
        base = float(s["평상시상승률"].mean())
        lo, hi = FC._wilson(int(round(reb * n)), n)
        # 뭉침 정도 — 서로 다른 날짜 수와 하루 최대 동시 신호
        dates = [d for lst in s["신호일자"] for d in lst]
        vc = pd.Series(dates).value_counts() if dates else pd.Series(dtype=int)
        out.append({
            "구간": part, "임계값": thr, "급락n": n,
            "급락후반등률": round(reb, 4), "평상시상승률": round(base, 4),
            "순초과": round(reb - base, 4),
            "신뢰구간하한": round(lo, 4), "신뢰구간상한": round(hi, 4),
            "유의미": bool(lo > base),
            "서로다른날짜": int(len(vc)),
            "하루최대동시": int(vc.max()) if len(vc) else 0,
        })
    res = pd.DataFrame(out).sort_values(["임계값", "구간"])
    if save:
        d = RESULT_DIR / "data"
        d.mkdir(parents=True, exist_ok=True)
        res.to_csv(d / "reversion_holdout.csv", index=False,
                   encoding="utf-8-sig")
    if verbose:
        print(res.to_string(index=False))
    return res


def clustered(names: "list[str] | None" = None, thresholds=THRESHOLDS,
              save: bool = True, verbose: bool = True) -> pd.DataFrame:
    """
    **날짜 단위로 다시 센다** — 표본이 독립이 아니기 때문이다.

    급락은 시장 전체가 같이 빠지는 날에 몰린다. 실측에서 |z|>3 신호 877건이
    서로 다른 날짜 **577개**에 몰려 있었고, 하루에 최대 17종목이 동시에
    걸렸다. 그날 시장이 반등했으면 17건이 한꺼번에 맞는다 — 독립 관측
    17개가 아니라 사실상 1개다.

    윌슨 구간은 독립을 가정하므로 그대로 쓰면 **유의성이 부풀려진다.**
    그래서 같은 날 신호를 하나로 묶어(그날의 반등 비율로 평균) 날짜를
    관측 단위로 삼는다. 표본 수가 877 → 577 로 줄어 구간이 넓어지지만,
    그게 정직한 폭이다.

    이렇게 해도 효과가 남으면 그때 신호라고 부를 수 있다.
    """
    names = names or FC.universe_names()
    per_date: dict[float, dict] = {t: {} for t in thresholds}
    base_all: dict[float, list] = {t: [] for t in thresholds}

    for nm in names:
        try:
            px = FC.load_prices(nm)
        except Exception:
            continue
        if len(px) < 300:
            continue
        for thr in thresholds:
            s = signal_series(px, thr)
            ok = s["z"].notna() & s["다음날수익"].notna()
            base_all[thr].append(float((s.loc[ok, "다음날수익"] > 0).mean()))
            dn = ok & s["급락"]
            for d, up in zip(s.index[dn], (s.loc[dn, "다음날수익"] > 0)):
                hit, n = per_date[thr].get(d, (0, 0))
                per_date[thr][d] = (hit + int(up), n + 1)

    rows = []
    for thr in thresholds:
        if not per_date[thr]:
            continue
        # 하루를 관측 하나로. 그날 걸린 종목들의 반등 비율이 그날의 값이다.
        vals = np.array([h / n for h, n in per_date[thr].values()], dtype=float)
        n_dates = len(vals)
        n_sig = int(sum(n for _h, n in per_date[thr].values()))
        reb = float(vals.mean())
        base = float(np.mean(base_all[thr]))
        # 날짜 수를 표본 수로 쓴 윌슨 구간
        lo, hi = FC._wilson(int(round(reb * n_dates)), n_dates)
        rows.append({
            "임계값": thr, "신호건수": n_sig, "서로다른날짜": n_dates,
            "날짜당평균": round(n_sig / n_dates, 2),
            "급락후반등률": round(reb, 4), "평상시상승률": round(base, 4),
            "순초과": round(reb - base, 4),
            "신뢰구간하한": round(lo, 4), "신뢰구간상한": round(hi, 4),
            "유의미": bool(lo > base),
        })
    res = pd.DataFrame(rows)
    if save and len(res):
        d = RESULT_DIR / "data"
        d.mkdir(parents=True, exist_ok=True)
        res.to_csv(d / "reversion_clustered.csv", index=False,
                   encoding="utf-8-sig")
    if verbose and len(res):
        print(res.to_string(index=False))
    return res


def summary(df: pd.DataFrame) -> pd.DataFrame:
    """임계값별 종합. 종목별 비율을 표본수로 가중평균한다."""
    rows = []
    for thr, s in df.groupby("임계값"):
        nd = int(s["급락n"].sum())
        nu = int(s["급등n"].sum())
        sd = s.dropna(subset=["급락후반등률"])
        su = s.dropna(subset=["급등후하락률"])
        reb = float((sd["급락후반등률"] * sd["급락n"]).sum() / max(nd, 1))
        drop = float((su["급등후하락률"] * su["급등n"]).sum() / max(nu, 1))
        base_up = float(s["평상시상승률"].mean())
        lo, hi = FC._wilson(int(round(reb * nd)), nd)
        rows.append({
            "임계값": thr,
            "급락n": nd, "급락후반등률": round(reb, 4),
            "평상시상승률": round(base_up, 4),
            "순초과": round(reb - base_up, 4),
            "신뢰구간하한": round(lo, 4), "신뢰구간상한": round(hi, 4),
            # 기준선을 **유의미하게** 넘었는가. 단순 비교로는 부족하다.
            "유의미": bool(lo > base_up),
            "급등n": nu, "급등후하락률": round(drop, 4),
            "급등순초과": round(drop - (1 - base_up), 4),
        })
    return pd.DataFrame(rows)


# ==========================================================================
# 실사용 — 오늘 신호가 뜬 종목
# ==========================================================================
def scan(names: "list[str] | None" = None, thr: float = DEFAULT_THRESHOLD,
         lookback: int = 1, stats: pd.DataFrame | None = None,
         verbose: bool = True) -> pd.DataFrame:
    """
    최근 `lookback` 거래일 안에 급락 신호가 뜬 종목.

    각 종목의 **자기 과거 통계**를 함께 붙인다. "이 종목은 과거 이런 급락이
    12번 있었고 그중 8번 반등했다"가 시장 전체 평균보다 훨씬 구체적이다.
    """
    names = names or FC.universe_names()
    if stats is None:
        stats = evaluate(names, thresholds=(thr,), save=False, verbose=False)
    st = stats[stats["임계값"] == thr].set_index("종목")

    rows = []
    for nm in names:
        try:
            px = FC.load_prices(nm)
        except Exception:
            continue
        s = signal_series(px, thr)
        recent = s.tail(lookback)
        hit = recent[recent["급락"]]
        if not len(hit):
            continue
        last = hit.iloc[-1]
        e = FC.entry(nm)
        r = st.loc[e["label"]] if e["label"] in st.index else None
        rows.append({
            "종목": e["label"], "지수": e["index"],
            "신호일": str(pd.Timestamp(hit.index[-1]).date()),
            "당일수익%": round(float(last["ret"]) * 100, 2),
            "z": round(float(last["z"]), 2),
            "종가": float(last["close"]),
            "과거급락n": int(r["급락n"]) if r is not None else 0,
            "과거반등률": float(r["급락후반등률"]) if r is not None else np.nan,
            "평상시상승률": float(r["평상시상승률"]) if r is not None else np.nan,
        })
    df = pd.DataFrame(rows)
    if len(df):
        df = df.sort_values("z").reset_index(drop=True)
    if verbose:
        if not len(df):
            print(f"  최근 {lookback}거래일 내 |z| < -{thr} 급락 신호 없음")
        else:
            for _i, r in df.iterrows():
                print(f"  {r['종목'][:16]:16s} {r['신호일']} "
                      f"{r['당일수익%']:+6.2f}% (z={r['z']:+.2f})  "
                      f"과거 {r['과거급락n']}회 중 반등 "
                      f"{r['과거반등률']*100:.0f}%")
    return df


def speak(row: pd.Series) -> str:
    """음성으로 읽을 문안. 방향을 단정하지 않는다."""
    reb = row.get("과거반등률")
    base = row.get("평상시상승률")
    core = (f"{row['종목']}이 {row['신호일']}에 "
            f"{abs(row['당일수익%']):.1f}퍼센트 하락했습니다. "
            f"평소 변동폭의 {abs(row['z']):.1f}배로 통계적 급락 구간입니다.")
    if reb is None or not np.isfinite(reb) or not row.get("과거급락n"):
        return core + " 이 종목의 과거 사례가 부족해 참고 수치를 드릴 수 없습니다."
    return (core + f" 이 종목에 같은 급락이 과거 {int(row['과거급락n'])}번 있었고 "
            f"그중 {reb*100:.0f}퍼센트가 다음날 반등했습니다. "
            f"평상시 상승 확률은 {base*100:.0f}퍼센트입니다. "
            "반등 확률이 높다는 것이지 오른다는 뜻은 아닙니다. "
            "투자 결정은 본인이 하셔야 합니다.")


def run(save: bool = True, verbose: bool = True) -> dict:
    """
    측정 + 검증 + 오늘 신호를 한 번에. 결과 파이프라인이 부르는 진입점.

    검증을 **두 겹**으로 한다.
        holdout()    앞/뒤 구간에서 각각 나오는가 — 한 구간의 우연이 아닌가
        clustered()  날짜를 관측 단위로 세도 남는가 — 표본이 독립이 아니다

    둘 다 통과해야 "신호"라고 부른다. 단순 집계(`summary`)만 보면 효과가
    실제보다 크게 보인다 — 같은 날 여러 종목이 함께 걸리기 때문이다.
    """
    df = evaluate(save=save, verbose=verbose)
    summ = summary(df)
    if verbose:
        print("\n▶ 날짜 단위 (표본 독립성 보정)")
    clu = clustered(save=save, verbose=verbose)
    if verbose:
        print("\n▶ 앞/뒤 구간 사후검증")
    ho = holdout(save=save, verbose=verbose)
    today = scan(thr=DEFAULT_THRESHOLD, lookback=3, stats=df, verbose=verbose)
    if save:
        d = RESULT_DIR / "data"
        d.mkdir(parents=True, exist_ok=True)
        summ.to_csv(d / "reversion_summary.csv", index=False,
                    encoding="utf-8-sig")
        if len(today):
            today.to_csv(d / "reversion_signals.csv", index=False,
                         encoding="utf-8-sig")
        (d / "reversion.json").write_text(
            json.dumps({"임계값별": summ.to_dict("records"),
                        "날짜단위": clu.to_dict("records"),
                        "사후검증": ho.to_dict("records"),
                        "오늘신호": today.to_dict("records"),
                        "문안": [speak(r) for _i, r in today.iterrows()]},
                       ensure_ascii=False, indent=2, default=str),
            encoding="utf-8")
    return {"요약": summ.to_dict("records"),
            "날짜단위": clu.to_dict("records"),
            "사후검증": ho.to_dict("records"),
            "신호": today.to_dict("records"),
            "종목별": df.to_dict("records")}
