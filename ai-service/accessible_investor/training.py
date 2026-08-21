"""
학습 파이프라인 — 2단 탐지기와 랭커를 한 번에 만든다.

    python cli.py train --stocks 140

정직한 평가를 위한 규칙 하나
--------------------------
**종목을 먼저 나누고, 그다음에 아무것도 섞지 않는다.**

    학습 종목  →  2단 탐지기 학습, 랭커 학습
    평가 종목  →  둘 다 처음 보는 종목. 여기 숫자만 보고한다

같은 종목의 다른 구간으로 나누면 종목 고유 성질(그 종목의 변동성 수준, 유동성,
시간대 습관)을 외워 성능이 부풀려진다. 배포 시나리오는 "사용자가 아무 종목이나
관심종목에 넣는 것"이므로 평가도 그래야 한다. 02가 쓴 방식과 같다.
"""

from __future__ import annotations

import json
import time

import numpy as np
import pandas as pd

from . import models as M
from . import pipeline as P
from . import ranker as R
from .config import OUTPUT_DIR, RANDOM_SEED


def run(n_stocks: int = 140, test_frac: float = 0.35, epochs: int = 6,
        sigma_frac: float = 1.0, with_deep: bool | None = None,
        reuse_deep: bool = False, verbose: bool = True) -> dict:
    from .config import USE_DEEP_DETECTOR

    with_deep = USE_DEEP_DETECTOR if with_deep is None else with_deep
    t_start = time.time()
    codes = P.watchlist_from_cache(n_stocks)
    if verbose:
        print(f"패널 구성 — {len(codes)}종목")
    panel = P.build_panel(codes, "intraday", verbose=verbose)
    if len(panel) < 20:
        raise RuntimeError(f"학습에 쓸 종목이 부족합니다 ({len(panel)}개). "
                           "먼저 분봉을 받아야 합니다.")

    # --- 1. 종목 분할 (모든 학습이 이 분할을 따른다) ----------------------
    all_codes = np.array(sorted(panel))
    rng = np.random.default_rng(RANDOM_SEED)
    rng.shuffle(all_codes)
    n_test = max(3, int(len(all_codes) * test_frac))
    test_codes = set(all_codes[:n_test])
    train_panel = {c: v for c, v in panel.items() if c not in test_codes}
    test_panel = {c: v for c, v in panel.items() if c in test_codes}
    if verbose:
        print(f"\n종목 분할 — 학습 {len(train_panel)} / 평가 {len(test_panel)} "
              f"(평가 종목은 학습에 전혀 쓰이지 않는다)")

    out = {"n_train_stocks": len(train_panel), "n_test_stocks": len(test_panel),
           "test_codes": sorted(test_codes)}

    # --- 2. 2단 탐지기 -----------------------------------------------------
    deep_ok = False
    if with_deep:
        if verbose:
            print("\n[1/3] Transformer-Forecast (2단 탐지기)")
        cached, cmeta = (M.load() if reuse_deep else (None, None))
        if cached is not None:
            deep_ok = True
            out["deep"] = {**cmeta, "reused": True}
            if verbose:
                print(f"  기존 학습 결과 재사용 ({str(cmeta.get('trained_at', '?'))[:16]})")
        else:
            try:
                model, meta = M.train(train_panel, epochs=epochs, verbose=verbose)
                M.save(model, meta)
                deep_ok = True
                out["deep"] = dict(meta)
            except Exception as e:
                print(f"  2단 탐지기 학습 실패 ({type(e).__name__}: {e}) — 규칙 계층만 씁니다")

        # ONNX는 **별도 블록**이다. 여기서 실패해도 학습 결과를 버리지 않는다.
        # 처음엔 한 try로 묶었다가 onnx 미설치 때문에 15분짜리 학습 결과를 통째로 날렸다.
        # ONNX는 Java 배포용 편의이지 파이썬 쪽 동작의 전제가 아니다.
        if deep_ok:
            onnx = M.to_onnx()
            out["deep"]["onnx"] = str(onnx) if onnx else None
            if verbose:
                print(f"  ONNX 변환: {onnx.name if onnx else '실패 — 파이썬 경로만 사용'}")

    # --- 3. 랭커 데이터셋 --------------------------------------------------
    if verbose:
        print("\n[2/3] 랭커 데이터셋")
    use_deep = deep_ok
    tr = R.build_dataset(train_panel, sigma_frac=sigma_frac,
                         use_deep=use_deep, verbose=verbose)
    te = R.build_dataset(test_panel, sigma_frac=sigma_frac,
                         use_deep=use_deep, verbose=verbose)
    if not len(tr) or not len(te):
        raise RuntimeError("랭커 학습 데이터를 만들지 못했습니다.")

    # --- 4. 랭커 학습 + 평가 ----------------------------------------------
    if verbose:
        print("\n[3/3] 랭커 학습")

    # 최근 가중을 켤지 **재보고 정한다.** 해외 데이터에서 도움이 됐다고
    # 국내 분봉에서도 그러리란 보장은 없다 (4년 일봉 vs 60일 분봉).
    if verbose:
        print("  최근 표본 가중 — 반감기 비교")
    rec_tab = R.compare_recency(tr, te, verbose=verbose)
    best = rec_tab.loc[rec_tab["PR-AUC"].idxmax()]
    base_pr = float(rec_tab.loc[rec_tab["설정"] == "가중없음", "PR-AUC"].iloc[0])
    use_recency = bool(best["설정"] != "가중없음"
                       and best["PR-AUC"] - base_pr > 0.002)
    half_life = (float(best["설정"].split()[1].rstrip("일")) if use_recency else 20.0)
    out["recency"] = {"채택": use_recency, "반감기": half_life if use_recency else None,
                      "표": rec_tab.to_dict("records")}

    rk = R.train(tr, recency=use_recency, half_life_days=half_life, verbose=verbose)
    prob = R.predict(rk, te)

    n_days = int(pd.Series(pd.to_datetime(te["ts"]).dt.date).nunique())
    table = R.evaluate_at_budget(te, prob, n_days=n_days)
    imp = R.feature_importance(rk, te)

    from sklearn.metrics import average_precision_score, roc_auc_score
    y = te["y"].to_numpy(dtype=int)
    out["ranker"] = {
        "n_train": int(len(tr)), "n_test": int(len(te)),
        "base_rate": round(float(y.mean()), 4),
        "pr_auc": round(float(average_precision_score(y, prob)), 4),
        "roc_auc": round(float(roc_auc_score(y, prob)), 4),
        "pr_auc_baseline_absz": round(float(average_precision_score(
            y, np.nan_to_num(te["max_abs_z"], nan=0))), 4),
        "pr_auc_baseline_move": round(float(average_precision_score(
            y, np.nan_to_num(te["move_sigma"], nan=0))), 4),
        "used_deep": bool(use_deep),
    }
    R.save(rk, {"features": getattr(rk, "_ai_features", R.FEATURES),
                "sigma_frac": sigma_frac, "recency": use_recency,
                "half_life_days": half_life if use_recency else None,
                "used_deep": bool(use_deep), **out["ranker"]})

    if verbose:
        print("\n" + "=" * 78)
        print("평가 종목(처음 보는 종목)에서의 성능")
        print("=" * 78)
        m = out["ranker"]
        print(f"  후보 {m['n_test']:,}건 · 기저율 {m['base_rate']*100:.2f}%")
        print(f"  PR-AUC   랭커 {m['pr_auc']:.4f}  |  |z|최대 {m['pr_auc_baseline_absz']:.4f}"
              f"  |  절대크기 {m['pr_auc_baseline_move']:.4f}")
        print(f"  ROC-AUC  랭커 {m['roc_auc']:.4f}")
        print("\n같은 알림 수에서 누가 더 잘 고르는가")
        print(table.to_string())
        print("\n피처 중요도 (순열, PR-AUC 기준)")
        print(imp.head(10).to_string(index=False))
        if use_deep:
            d = imp[imp["피처"] == "deep_rank"]
            if len(d):
                rank_pos = int(imp.index[imp["피처"] == "deep_rank"][0]) + 1
                print(f"\n  2단 탐지기(deep_rank) 기여도: {d.iloc[0]['중요도']:.5f} "
                      f"— 전체 {len(imp)}개 중 {rank_pos}위")

    # pivot 결과는 컬럼이 MultiIndex라 to_dict()가 **튜플 키**를 만든다.
    # json.dumps는 튜플 키를 못 쓴다 — 리포트 저장 단계에서만 죽어서 늦게 발견된다.
    flat = table.round(4).copy()
    flat.columns = [" / ".join(str(x) for x in c) if isinstance(c, tuple) else str(c)
                    for c in flat.columns]
    out["budget_table"] = flat.reset_index().to_dict("records")
    out["importance"] = imp.to_dict("records")
    out["elapsed_sec"] = round(time.time() - t_start, 1)

    d = OUTPUT_DIR / "validation"
    d.mkdir(parents=True, exist_ok=True)
    (d / "08_training_report.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    table.round(4).to_csv(d / "08_ranker_budget.csv", encoding="utf-8-sig")
    imp.to_csv(d / "08_feature_importance.csv", index=False, encoding="utf-8-sig")
    rec_tab.to_csv(d / "08_recency_sweep.csv", index=False, encoding="utf-8-sig")
    if verbose:
        print(f"\n저장: {d}  (총 {out['elapsed_sec']}초)")
    return out
