"""
뉴스로 다음날을 맞출 수 있는가 — 3-way 측정.

물음을 셋으로 나눈다. 하나로 물으면 "뉴스가 도움이 되는가"에 답할 수 없다.

    뉴스 피처만  →  뉴스 자체에 신호가 있는가
    가격 피처만  →  기준선. 이것보다 못하면 뉴스는 무의미하다
    뉴스 + 가격  →  뉴스가 **가격이 모르는 것**을 더하는가  ← 진짜 질문

세 번째가 핵심이다. 뉴스만으로 55%가 나와도 가격만으로 55%가 나오고
둘을 합쳐도 55%면, 뉴스는 가격이 이미 아는 걸 반복한 것뿐이다.

정직하게 재기 위한 장치
----------------------
1. **종목 단위 분할.** 같은 종목이 학습·평가에 같이 들어가면 부풀려진다.
2. **거래일 귀속.** 15:30 이후 기사는 다음 거래일 것이다. 이걸 안 하면
   "장 마감 후 실적 기사"가 당일 수익률과 상관 있어 보이는데, 사실은
   주가가 움직여서 기사가 난 것이다 — 인과가 거꾸로다.
3. **시장 대비 초과수익**을 라벨로 쓴다. 원시 수익률을 쓰면 모델이
   "그날 시장이 올랐나"를 맞히는 것으로 점수를 벌 수 있다.

⚠️ 표본의 한계를 먼저 밝힌다
---------------------------
구글 뉴스 RSS는 최근 7일까지만 준다. 종목이 많아도 **날짜가 5~7개뿐**이라
같은 날의 종목들이 시장 요인으로 강하게 묶여 있다. 실질 표본은
행 수보다 훨씬 작다. 그래서 이 결과는 **결론이 아니라 참고치**다.
아카이브(`news.archive_collect`)를 매일 돌려 날짜를 늘리는 게 유일한 해법이다.
"""

from __future__ import annotations

import json

import numpy as np
import pandas as pd

from . import data as D
from . import features as F
from . import news as N
from .config import OUTPUT_DIR, RANDOM_SEED

PRICE_FEATURES = ["ret_z", "vol_z", "rvol_z", "ma_dev_20", "ma_dev_60",
                  "volatility_20", "move_sigma", "excess_ret", "vol_mult"]


# --------------------------------------------------------------------------
# 데이터셋
# --------------------------------------------------------------------------
def build(codes: list[str], min_articles: int = 1,
          verbose: bool = True) -> pd.DataFrame:
    """아카이브 + 일봉 → (종목·거래일) 단위 학습 테이블."""
    arch = N.archive_load()
    if not len(arch):
        raise RuntimeError("뉴스 아카이브가 비어 있습니다. "
                           "`news.archive_collect(...)` 를 먼저 실행하세요.")
    idx = D.load_market_index()
    rows = []

    for code in codes:
        name = D.name_of(code)
        sub = arch[arch["corp"] == name]
        if len(sub) < min_articles:
            continue
        daily = D.load_daily(code)
        if daily is None or len(daily) < 120:
            continue

        scored = N.score_articles(N.dedup(sub.copy(), corp_name=name))
        # 종목별 '평소 하루 기사 수'. article_velocity의 분모다.
        n_days_span = max(scored["ts"].dt.date.nunique(), 1)
        base_per_day = len(scored) / n_days_span
        nf = N.daily_features(scored, name, baseline_per_day=base_per_day)
        if not len(nf):
            continue

        feat = F.add_daily_features(daily, index=idx)
        feat.index = pd.to_datetime(feat.index)
        nxt = feat["excess_ret"].shift(-1)          # 다음 거래일 초과수익

        for _i, r in nf.iterrows():
            tday = pd.Timestamp(r["tday"])
            if tday not in feat.index:
                continue
            y_ret = nxt.get(tday, np.nan)
            if not np.isfinite(y_ret):
                continue
            row = {"code": code, "name": name, "tday": tday,
                   "y_ret": float(y_ret), "y_up": int(y_ret > 0)}
            for c in N.NEWS_FEATURES:
                row[c] = r.get(c, np.nan)
            for c in PRICE_FEATURES:
                v = feat[c].get(tday, np.nan) if c in feat.columns else np.nan
                row[c] = float(v) if np.isfinite(v) else np.nan
            rows.append(row)

    df = pd.DataFrame(rows)
    if verbose and len(df):
        print(f"표본 {len(df):,}행 · 종목 {df['code'].nunique()} "
              f"· 거래일 {df['tday'].nunique()} · 상승 비율 {df['y_up'].mean()*100:.1f}%")
        if df["tday"].nunique() < 10:
            print(f"  ⚠️ 거래일이 {df['tday'].nunique()}개뿐입니다. "
                  "같은 날 종목들이 시장 요인으로 묶여 있어 실질 표본은 훨씬 작습니다.")
    return df


# --------------------------------------------------------------------------
# 3-way 비교
# --------------------------------------------------------------------------
def _fit_eval(tr, te, cols, use_tabpfn=False):
    from sklearn.metrics import (accuracy_score, balanced_accuracy_score,
                                 roc_auc_score)

    cols = [c for c in cols if c in tr.columns
            and np.unique(tr[c].to_numpy(dtype=float,
                                         na_value=np.nan)[
                np.isfinite(tr[c].to_numpy(dtype=float, na_value=np.nan))]).size >= 2]
    if not cols:
        return None
    Xtr = tr[cols].to_numpy(dtype=np.float32)
    Xte = te[cols].to_numpy(dtype=np.float32)
    ytr, yte = tr["y_up"].to_numpy(int), te["y_up"].to_numpy(int)
    if len(np.unique(ytr)) < 2 or len(np.unique(yte)) < 2:
        return None

    if use_tabpfn:
        from tabpfn import TabPFNClassifier
        m = TabPFNClassifier()
        Xtr, Xte = np.nan_to_num(Xtr), np.nan_to_num(Xte)
    else:
        from sklearn.ensemble import HistGradientBoostingClassifier
        m = HistGradientBoostingClassifier(
            max_depth=3, max_iter=200, learning_rate=0.05,
            min_samples_leaf=20, l2_regularization=1.0,
            random_state=RANDOM_SEED)
    m.fit(Xtr, ytr)
    p = m.predict_proba(Xte)[:, 1]
    pred = (p > 0.5).astype(int)
    return {"n_features": len(cols),
            "정확도": round(float(accuracy_score(yte, pred)), 4),
            # **균형정확도가 진짜 지표다.**
            # 라벨이 한쪽으로 쏠리면(평가 구간 상승 20.6%) 그냥 "항상 하락"으로 찍어도
            # 정확도 79%가 나온다. 그 숫자를 성과로 착각하기 쉽다.
            # 균형정확도는 상승·하락 각각의 적중률을 평균해서 그 착시를 없앤다. 0.5가 무작위다.
            "균형정확도": round(float(balanced_accuracy_score(yte, pred)), 4),
            "ROC-AUC": round(float(roc_auc_score(yte, p)), 4),
            "상승비율(평가)": round(float(yte.mean()), 4)}


def run(codes: list[str] | None = None, test_frac: float = 0.35,
        use_tabpfn: bool = False, save: bool = True,
        verbose: bool = True) -> dict:
    from . import pipeline as P

    codes = codes or P.watchlist_from_cache(140)
    df = build(codes, verbose=verbose)
    if len(df) < 60:
        out = {"n": int(len(df)), "verdict": "표본 부족으로 판단 불가"}
        if verbose:
            print(f"표본 {len(df)}행 — 판단할 수 없습니다.")
        return out

    all_codes = np.array(sorted(df["code"].unique()))
    rng = np.random.default_rng(RANDOM_SEED)
    rng.shuffle(all_codes)
    test = set(all_codes[:max(3, int(len(all_codes) * test_frac))])
    tr, te = df[~df["code"].isin(test)], df[df["code"].isin(test)]

    sets = {"뉴스 피처만": N.NEWS_FEATURES,
            "가격 피처만": PRICE_FEATURES,
            "뉴스 + 가격": N.NEWS_FEATURES + PRICE_FEATURES}
    res = {}
    for tag, cols in sets.items():
        r = _fit_eval(tr, te, cols, use_tabpfn=use_tabpfn)
        if r:
            res[tag] = r

    # 항상 "상승"으로 찍는 무지성 기준선. 이걸 못 이기면 아무 의미가 없다.
    base = float(te["y_up"].mean())
    # 무지성 기준선. 다수 클래스로만 찍는다. 이걸 균형정확도로 못 이기면 의미가 없다.
    res["다수클래스 찍기 (기준선)"] = {
        "n_features": 0, "정확도": round(max(base, 1 - base), 4),
        "균형정확도": 0.5, "ROC-AUC": 0.5, "상승비율(평가)": round(base, 4)}

    out = {"n_total": int(len(df)), "n_train": int(len(tr)), "n_test": int(len(te)),
           "n_stocks": int(df["code"].nunique()),
           "n_days": int(df["tday"].nunique()),
           "model": "TabPFN" if use_tabpfn else "HistGradientBoosting",
           "results": res}

    if verbose:
        print()
        print("=" * 74)
        print(f"뉴스로 다음날 방향을 맞출 수 있는가 — {out['model']}")
        print("=" * 74)
        print(pd.DataFrame(res).T.to_string())
        print()
        n_only = res.get("뉴스 피처만", {}).get("ROC-AUC", 0.5)
        p_only = res.get("가격 피처만", {}).get("ROC-AUC", 0.5)
        both = res.get("뉴스 + 가격", {}).get("ROC-AUC", 0.5)
        print(f"  뉴스가 가격 위에 더하는 값: ROC-AUC {p_only:.4f} → {both:.4f} "
              f"({(both - p_only):+.4f})")
        if both - p_only < 0.02:
            print("  → 뉴스는 가격이 이미 아는 것을 반복하고 있다.")
        if n_only < 0.55:
            print(f"  → 뉴스 단독 ROC-AUC {n_only:.4f}. 0.5가 무작위다.")
        best_ba = max((v.get("균형정확도", 0.5) for k, v in res.items()
                       if "기준선" not in k), default=0.5)
        print(f"  최고 균형정확도 {best_ba:.4f} (0.5가 무작위)")
        print("  ⚠️ '정확도' 열은 보지 말 것. 평가 구간 상승 비율이 "
              f"{base*100:.0f}%라 아무렇게나 찍어도 {max(base, 1-base)*100:.0f}%가 나온다.")
        print(f"\n  거래일 {out['n_days']}개 · 종목 {out['n_stocks']}개. "
              "같은 날 종목들이 시장 요인으로 묶여 실질 표본은 더 작다.")
        print("  결론이 아니라 참고치다.")

    if save:
        d = OUTPUT_DIR / "validation"
        d.mkdir(parents=True, exist_ok=True)
        tag = "tabpfn" if use_tabpfn else "gbm"
        (d / f"10_news_predict_{tag}.json").write_text(
            json.dumps(out, ensure_ascii=False, indent=2, default=str),
            encoding="utf-8")
        df.to_csv(d / "10_news_dataset.csv", index=False, encoding="utf-8-sig")
    return out
