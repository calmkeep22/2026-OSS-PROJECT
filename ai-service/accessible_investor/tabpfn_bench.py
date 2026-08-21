"""
TabPFN vs GBM — 이상감지 랭커에서 파운데이션 모델이 값을 하는가.

무엇을 비교하나
---------------
같은 홀드아웃 종목, 같은 피처로 두 모델을 세운다.

    GBM      HistGradientBoosting · 학습 표본 **전부**(약 11만 행)
    TabPFN   파운데이션 모델 · 학습 표본 **1000행**(CPU 상한)

공정한 비교가 아니다 — 표본 수가 100배 차이 난다. 그게 요점이다.
TabPFN 은 "학습 없이 소표본으로 즉시 쓰는" 모델이므로, 1000행으로
11만 행짜리 GBM 을 얼마나 따라붙는지가 실제로 궁금한 값이다.

왜 regressor 인가
-----------------
`TabPFNClassifier` 가중치는 gated 저장소에 있고 로그인이 필요하다.
`TabPFNRegressor` 는 이미 받아져 있다. 이진 라벨(0/1)을 연속값으로 회귀하면
예측값이 곧 P(양성)의 근사이고, **우리가 원하는 건 확률이 아니라 순위**라서
PR-AUC·ROC-AUC 비교에는 아무 문제가 없다.

classifier 가 준비되면 `--model tabpfn` 으로 같은 비교를 다시 돌리면 된다.

⚠️ 비용
-------
TabPFN 추론은 행당 약 8ms 다. 평가 후보가 6만 건이면 8분이 걸린다.
관심종목 몇 개를 보는 실사용에서는 문제가 아니지만(10종목 = 0.1초),
**대량 평가에는 맞지 않는다.** 그 사실 자체가 결과의 일부다.
"""

from __future__ import annotations

import json
import time

import numpy as np
import pandas as pd

from . import pipeline as P
from . import ranker as R
from .config import OUTPUT_DIR, RANDOM_SEED

TABPFN_MAX_ROWS = 1000


def _rank_scores(model, X: np.ndarray, batch: int = 4096) -> np.ndarray:
    """큰 평가셋을 나눠 예측한다. 한 번에 넣으면 메모리를 크게 잡는다."""
    out = np.empty(len(X), dtype=np.float64)
    for i in range(0, len(X), batch):
        out[i:i + batch] = model.predict(np.nan_to_num(X[i:i + batch]))
    return out


def run(n_stocks: int = 140, test_frac: float = 0.35, max_test: int = 20_000,
        save: bool = True, verbose: bool = True) -> dict:
    """
    max_test
        TabPFN 추론 비용 때문에 평가 후보를 표본추출한다.
        **두 모델 모두 같은 표본에서** 재므로 비교는 공정하다.
    """
    from sklearn.metrics import average_precision_score, roc_auc_score

    codes = P.watchlist_from_cache(n_stocks)
    panel = P.build_panel(codes, "intraday", verbose=verbose)
    all_codes = np.array(sorted(panel))
    rng = np.random.default_rng(RANDOM_SEED)
    rng.shuffle(all_codes)
    n_test = max(3, int(len(all_codes) * test_frac))
    test_codes = set(all_codes[:n_test])

    tr = R.build_dataset({c: v for c, v in panel.items() if c not in test_codes},
                         verbose=verbose)
    te = R.build_dataset({c: v for c, v in panel.items() if c in test_codes},
                         verbose=verbose)
    cols = R.usable_features(tr)

    if len(te) > max_test:
        # 양성이 1%대라 단순 무작위로 자르면 양성이 거의 안 남는다.
        # 양성은 전부 남기고 음성만 줄인다 — 두 모델이 같은 표본을 보므로
        # 상대 비교는 유지되고, 절대 PR-AUC 는 기저율이 올라간 값이 된다.
        pos = te[te["y"] == 1]
        neg = te[te["y"] == 0].sample(
            max(max_test - len(pos), 100), random_state=RANDOM_SEED)
        te = pd.concat([pos, neg]).sort_index()
        if verbose:
            print(f"  평가 표본 축소 → {len(te):,}건 (양성 {len(pos):,} 전부 유지)")

    Xtr = tr[cols].to_numpy(np.float32)
    ytr = tr["y"].to_numpy(int)
    Xte = te[cols].to_numpy(np.float32)
    yte = te["y"].to_numpy(int)

    res = {}

    # --- GBM: 전체 표본 ---------------------------------------------------
    t0 = time.time()
    gbm = R.train(tr, features=cols, recency=False, verbose=False)
    fit_gbm = time.time() - t0
    t0 = time.time()
    p_gbm = gbm.predict_proba(Xte)[:, 1]
    res["GBM (전체 표본)"] = {
        "학습표본": int(len(ytr)), "학습초": round(fit_gbm, 1),
        # ⚠️ 초 단위로 반올림하면 GBM 추론이 0.0 이 되고, 배수를 내려다
        # 0으로 나누게 된다. 실제로 "199000000000배"가 찍혔다. ms 로 잰다.
        "추론ms": round((time.time() - t0) * 1000, 1),
        "PR-AUC": round(float(average_precision_score(yte, p_gbm)), 4),
        "ROC-AUC": round(float(roc_auc_score(yte, p_gbm)), 4)}

    # --- GBM: TabPFN 과 같은 1000행 ---------------------------------------
    # 이게 없으면 "TabPFN 이 나쁜 것"과 "표본이 1000행뿐이라 나쁜 것"을
    # 구별할 수 없다. 파운데이션 모델의 값어치는 이 줄과의 차이에 있다.
    idx = rng.choice(len(tr), min(TABPFN_MAX_ROWS, len(tr)), replace=False)
    sub = tr.iloc[idx]
    t0 = time.time()
    gbm_s = R.train(sub, features=cols, recency=False, verbose=False)
    fit_s = time.time() - t0
    t0 = time.time()
    p_gs = gbm_s.predict_proba(Xte)[:, 1]
    res[f"GBM ({TABPFN_MAX_ROWS}행)"] = {
        "학습표본": int(len(sub)), "학습초": round(fit_s, 1),
        "추론ms": round((time.time() - t0) * 1000, 1),
        "PR-AUC": round(float(average_precision_score(yte, p_gs)), 4),
        "ROC-AUC": round(float(roc_auc_score(yte, p_gs)), 4)}

    # --- TabPFN -----------------------------------------------------------
    try:
        from tabpfn import TabPFNRegressor
        t0 = time.time()
        m = TabPFNRegressor()
        m.fit(np.nan_to_num(Xtr[idx]), ytr[idx].astype(float))
        fit_tp = time.time() - t0
        t0 = time.time()
        p_tp = _rank_scores(m, Xte)
        res[f"TabPFN ({TABPFN_MAX_ROWS}행)"] = {
            "학습표본": int(len(idx)), "학습초": round(fit_tp, 1),
            "추론ms": round((time.time() - t0) * 1000, 1),
            "PR-AUC": round(float(average_precision_score(yte, p_tp)), 4),
            "ROC-AUC": round(float(roc_auc_score(yte, p_tp)), 4)}
    except Exception as e:
        res["TabPFN"] = {"오류": f"{type(e).__name__}: {str(e).splitlines()[0][:120]}"}

    # --- 규칙 기준선 ------------------------------------------------------
    res["규칙 기준선 (|z| 최대)"] = {
        "학습표본": 0, "학습초": 0.0, "추론ms": 0.0,
        "PR-AUC": round(float(average_precision_score(
            yte, np.nan_to_num(te["max_abs_z"], nan=0))), 4),
        "ROC-AUC": round(float(roc_auc_score(
            yte, np.nan_to_num(te["max_abs_z"], nan=0))), 4)}

    out = {"n_test": int(len(te)), "기저율": round(float(yte.mean()), 4),
           "n_features": len(cols), "results": res}

    if verbose:
        print("\n" + "=" * 74)
        print(f"TabPFN vs GBM — 홀드아웃 {len(te):,}건 · 기저율 {yte.mean()*100:.2f}%")
        print("=" * 74)
        print(pd.DataFrame(res).T.to_string())
        tp = res.get(f"TabPFN ({TABPFN_MAX_ROWS}행)")
        gs = res[f"GBM ({TABPFN_MAX_ROWS}행)"]
        gf = res["GBM (전체 표본)"]
        if tp and "PR-AUC" in tp:
            print(f"\n  같은 1000행에서: TabPFN {tp['PR-AUC']:.4f} vs "
                  f"GBM {gs['PR-AUC']:.4f} ({tp['PR-AUC'] - gs['PR-AUC']:+.4f})")
            print(f"  전체 표본 GBM {gf['PR-AUC']:.4f} — "
                  f"TabPFN 이 표본 100분의 1로 "
                  f"{tp['PR-AUC'] / max(gf['PR-AUC'], 1e-9) * 100:.0f}% 도달")
            per_tp = tp["추론ms"] / max(len(te), 1)
            per_gb = gf["추론ms"] / max(len(te), 1)
            print(f"  추론 비용: 행당 TabPFN {per_tp:.2f}ms vs "
                  f"GBM {per_gb:.4f}ms ({per_tp / max(per_gb, 1e-6):,.0f}배)")
            print(f"  → 관심종목 10개(약 10행)면 {per_tp * 10:.0f}ms — 실사용엔 충분히 빠르다.")
            print(f"    대량 평가 {len(te):,}건에는 {tp['추론ms']/1000:.0f}초가 든다 — 그건 안 맞는다.")

    if save:
        d = OUTPUT_DIR / "validation"
        d.mkdir(parents=True, exist_ok=True)
        (d / "13_tabpfn_bench.json").write_text(
            json.dumps(out, ensure_ascii=False, indent=2, default=str),
            encoding="utf-8")
        pd.DataFrame(res).T.reset_index().rename(columns={"index": "모델"}).to_csv(
            d / "13_tabpfn_bench.csv", index=False, encoding="utf-8-sig")
    return out
