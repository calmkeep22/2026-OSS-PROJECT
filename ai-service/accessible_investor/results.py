"""
AI 결과물 생성 — `results/` 아래에 파트별로 데이터와 그림을 쌓는다.

    results/
    ├── 01_anomaly/      이상감지 · 종목별 위험도
    ├── 02_similarity/   차트 유사도 (1·3·6·12개월 네 창)
    ├── 03_forecast/     다음날 예측 · 급락 반등
    │   ├── data/        워크포워드 원본 · 타깃 비교 · 예측 CSV
    │   └── figures/     급락 반등 · 타깃 비교 · 분포 · 모델 비교 · 예측 카드
    └── index.html       전체 요약 한 장

`python cli.py results` 한 줄로 전부 만든다. 파트별 README 는 여기가 아니라
`package.py` 가 쓴다 (한 파일은 한 곳에서만 쓴다).
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np
import pandas as pd

from . import forecast as FC
from . import risk as RK
from . import viz as V
from .universe import all_entries, entry

# 출력 루트. **한 곳뿐이다.**
# 전에는 parts/ · ai_results/ · outputs/ 세 갈래에 같은 결과가 흩어져
# 같은 그림 61개(6.9MB)가 중복됐다. 이제 여기 하나만 쓴다.
ROOT = Path(__file__).resolve().parent.parent / "results"
PARTS = {"01_anomaly": "이상감지 · 위험도",
         "02_similarity": "차트 유사도",
         "03_forecast": "다음날 예측 · 급락 반등"}


def _names() -> list[str]:
    """유니버스의 표시이름 목록. 지수별 규칙 선택 결과를 그대로 쓴다."""
    return [e["label"] for e in all_entries()]


def _dirs(part: str) -> tuple[Path, Path]:
    d = ROOT / part
    (d / "data").mkdir(parents=True, exist_ok=True)
    (d / "figures").mkdir(parents=True, exist_ok=True)
    # README 는 여기서 쓰지 않는다. package.py 한 곳만 쓴다 —
    # 두 모듈이 같은 파일을 쓰면 나중에 돈 쪽이 이기고, 어느 쪽이 진짜인지
    # 매번 확인해야 했다. 실제로 그림 이름이 바뀐 뒤에도 옛 목록이 남아 있었다.
    return d / "data", d / "figures"


def rebuild_forecast(verbose: bool = True) -> dict:
    """
    **저장된 워크포워드 결과로 뒷단만 다시 만든다.** 재학습 없음.

    왜 필요한가
    -----------
    38종목 × 40일 × 매일 재학습은 7시간이 걸린다. 그런데 실제로 고치고 싶은
    것은 대부분 그 **뒤쪽**이다 — 모델 선택 규칙, 집계, 그림, 요약 페이지.
    그때마다 7시간을 다시 쓰면 고칠 엄두가 안 나고, 결국 틀린 채로 남는다.

    실제로 그런 일이 있었다. 시간 벌점을 총소요초로 매기는 바람에 앙상블
    (54.73%)을 버리고 로지스틱(52.09%)이 뽑혔는데, 그걸 고치자고 전체를
    다시 돌릴 수는 없었다. 워크포워드 **원본은 이미 CSV 로 남아 있으므로**
    거기서부터 다시 만들면 몇 분이면 된다.

    다시 만드는 것
        모델 선택 · 타깃 비교 · 종목/지수/규모별 집계 · 그림 · 요약 JSON
        다음 거래일 예측 (선택 모델이 바뀌면 예측도 바뀌므로 다시 적합한다)

    ⚠️ 워크포워드 자체는 건드리지 않는다. 피처나 학습 방식을 고쳤다면
    `run_forecast` 를 다시 돌려야 한다.
    """
    data, figs = _dirs("03_forecast")
    raw = data / "walkforward_raw.csv"
    if not raw.exists():
        raise RuntimeError(f"{raw} 가 없습니다. 먼저 `python cli.py results`.")

    by_target = {}
    for tgt, fn in (("변동성", "walkforward_raw.csv"),
                    ("방향", "walkforward_direction.csv")):
        f = data / fn
        if f.exists():
            df = pd.read_csv(f)
            df["날짜"] = pd.to_datetime(df["날짜"])
            by_target[tgt] = df

    # 모델별 소요 시간은 **타깃과 무관**하다(같은 모델, 같은 표본 크기).
    # 저장된 model_selection.csv 에서 가져와 양쪽에 같이 쓴다.
    sel_old = data / "model_selection.csv"
    timing = {}
    if sel_old.exists():
        t = pd.read_csv(sel_old)
        if "총소요초" in t.columns:
            timing = dict(zip(t["모델"], t["총소요초"]))
    return _finish_forecast(by_target, {k: timing for k in by_target},
                            data, figs, verbose=verbose)


ABLATION_SETS = ("tech+mkt", "tech+mkt+peer", "all")


def run_ablation(n_days: int = FC.REPORT_DAYS, refit_every: int = 5,
                 sets: tuple[str, ...] = ABLATION_SETS,
                 targets: tuple[str, ...] = ("변동성", "방향"),
                 verbose: bool = True) -> pd.DataFrame:
    """
    피처 묶음을 하나씩 얹으며 잰다 — **무엇이 실제로 기여했는가.**

    "뉴스를 넣었다"는 말은 증거가 아니다. 넣기 전과 후를 같은 조건에서 재고
    그 차이를 보여야 기여를 주장할 수 있다.

    ⚠️ `results` 에 포함하지 않고 따로 둔 이유는 **비용** 이다.
    조합 하나가 38종목 × 40일 × 4모델이라 45분쯤 걸린다. 6조합이면 4시간이다.
    그래서 `python cli.py ablate` 로 가끔 돌려 CSV 로 남기고, 일반 실행은
    그 CSV 가 있으면 그림만 그린다. 기본 `refit_every=5` 인 것도 같은 이유다 —
    어느 묶음이 나은지 **비교**하는 데는 5일 주기로 충분하다.
    """
    data, _figs = _dirs("03_forecast")
    names = _names()
    panels, build_fail = {}, []
    for name in names:
        try:
            panels[name] = FC.build(name, verbose=False)
        except Exception as ex:
            build_fail.append(f"{name}({type(ex).__name__})")
    if build_fail and verbose:
        print(f"  ⚠️ 패널 실패 {len(build_fail)}종목: {', '.join(build_fail[:6])}")
    if not panels:
        raise RuntimeError("학습 테이블을 만들 수 있는 종목이 없습니다.")

    rows = []
    for target in targets:
        for fs in sets:
            t0 = time.time()
            parts, n_fail = [], 0
            for name, df in panels.items():
                try:
                    wf, _tm = FC.walk_forward(name, n_days=n_days, df=df,
                                              target=target, feature_set=fs,
                                              refit_every=refit_every,
                                              verbose=False)
                    if len(wf):
                        parts.append(wf)
                    else:
                        n_fail += 1
                except Exception:
                    n_fail += 1
            if not parts:
                continue
            wf = pd.concat(parts, ignore_index=True)
            for m, s in wf.groupby("모델"):
                bal = FC.balanced_accuracy(s["적중"], s["실제"])
                per = s.groupby("종목").apply(
                    lambda x: FC.balanced_accuracy(x["적중"], x["실제"]))
                nmin = int(s["실제"].value_counts().min())
                lo, hi = FC._wilson(int(round(bal * nmin * 2)), nmin * 2)
                rows.append({"타깃": target, "피처": fs, "모델": m,
                             "평가건수": len(s),
                             "균형정확도": round(bal, 4),
                             "신뢰하한": round(lo, 4), "신뢰상한": round(hi, 4),
                             "유의미": bool(lo > 0.5),
                             "50%초과": int((per > 0.5).sum()),
                             "종목수": int(per.notna().sum())})
            if verbose:
                b = max((r for r in rows
                         if r["타깃"] == target and r["피처"] == fs),
                        key=lambda r: r["균형정확도"])
                print(f"  {target:4s} {fs:14s} 최고 {b['모델']:10s} "
                      f"{b['균형정확도']:.4f}  50%초과 {b['50%초과']}/"
                      f"{b['종목수']}  ({time.time() - t0:.0f}초)"
                      + (f"  ⚠️ 실패 {n_fail}종목" if n_fail else ""))

    abl = pd.DataFrame(rows)
    if len(abl):
        abl.to_csv(data / "feature_ablation.csv", index=False,
                   encoding="utf-8-sig")
        if verbose:
            print(f"\n→ {data / 'feature_ablation.csv'}")
    return abl


# ==========================================================================
# 01 예측
# ==========================================================================
def run_forecast(n_days: int = FC.REPORT_DAYS, refit_every: int = 1,
                 verbose: bool = True) -> dict:
    """
    38종목 워크포워드 — **두 타깃을 나란히** 돌린다.

    방향은 측정 결과 동전 던지기와 구별되지 않고, 변동성은 구별된다.
    둘 다 돌려서 그 차이를 표로 보여 주는 것이 이 파트의 핵심 메시지다.

    refit_every=1 이면 평가일마다 다시 학습한다(요청받은 방식).
    """
    data, figs = _dirs("03_forecast")
    names = _names()
    panels = {}

    for i, name in enumerate(names, 1):
        if verbose:
            print(f"  [{i}/{len(names)}] {name[:22]}")
        try:
            panels[name] = FC.build(name, verbose=False)
        except Exception as e:
            print(f"    건너뜀 — {type(e).__name__}: {str(e)[:60]}")
    if not panels:
        raise RuntimeError("학습 테이블을 만들 수 있는 종목이 없습니다.")

    # 뉴스가 실제로 얼마나 차 있는지 — **감추지 않고 표로 낸다.**
    # 한동안 국내 종목은 학습 구간의 0.3% 만 차 있었다. 열이 사실상 상수라
    # "뉴스를 쓴다"고 적어 놓고 실제로는 아무것도 안 쓰는 상태였다.
    # 과거 뉴스를 소급 수집(`news.backfill`)한 뒤 이 표로 확인한다.
    cov = []
    for name, df in panels.items():
        if "news_xfer" not in df.columns:
            continue
        nx = df["news_xfer"]
        e = entry(name)
        cov.append({"종목": name, "시장": e["market"], "지수": e["index"],
                    "행수": int(len(df)),
                    "뉴스있는날": int((nx != FC.NEUTRAL_NEWS).sum()),
                    "전체커버리지": round(float((nx != FC.NEUTRAL_NEWS).mean()), 4),
                    "평가구간커버리지": round(
                        float((nx.tail(n_days) != FC.NEUTRAL_NEWS).mean()), 4)})
    news_cov = pd.DataFrame(cov).sort_values("평가구간커버리지",
                                             ascending=False)
    if len(news_cov):
        news_cov.to_csv(data / "news_coverage.csv", index=False,
                        encoding="utf-8-sig")
        if verbose:
            m = news_cov.groupby("시장")["평가구간커버리지"].mean()
            print("\n뉴스 커버리지(평가 구간) — "
                  + " · ".join(f"{k} {v:.1%}" for k, v in m.items()))

    by_target, timing_all = {}, {}
    for target in ("변동성", "방향"):
        parts, timing = [], {}
        if verbose:
            print(f"\n▶ 타깃 = {target}")
        skipped = []
        for name, df in panels.items():
            try:
                wf, tm = FC.walk_forward(name, n_days=n_days, df=df,
                                         target=target, refit_every=refit_every,
                                         verbose=False)
            except Exception as ex:
                # ⚠️ 조용히 넘기지 않는다.
                # 전에는 그냥 continue 였다. 10종목이 죽어도 결과표에는
                # "38종목"이라고 적혀 나갔고, 아무도 몰랐다.
                skipped.append(f"{name}({type(ex).__name__})")
                continue
            if not len(wf):
                skipped.append(f"{name}(결과0행)")
                continue
            parts.append(wf)
            for k, v in tm.items():
                timing[k] = timing.get(k, 0.0) + v
        if skipped and verbose:
            print(f"  ⚠️ 건너뛴 종목 {len(skipped)}개: "
                  + ", ".join(skipped[:6])
                  + (" …" if len(skipped) > 6 else ""))
        if not parts:
            continue
        wf = pd.concat(parts, ignore_index=True)
        by_target[target] = wf
        timing_all[target] = timing

    return _finish_forecast(
        by_target, timing_all, data, figs, n_days=n_days,
        refit_every=refit_every, panels=panels, verbose=verbose)


# --------------------------------------------------------------------------
def _target_row(target: str, wf: pd.DataFrame, timing: dict,
                model: str | None = None) -> dict:
    """
    타깃 하나의 요약 한 줄. `run_forecast` 와 `rebuild_forecast` 가 함께 쓴다.

    ⚠️ `model` 을 주면 **그 모델로 고정**해서 잰다.
    타깃마다 자기 최고 모델을 고르게 두면 비교가 성립하지 않는다 — 실제로
    변동성은 앙상블(54.73%), 방향은 로지스틱(50.77%)이 뽑혀서 **서로 다른
    모델끼리 견주는 표**가 나갔다. "무엇을 맞힐 수 있는가"를 묻는 표에서
    모델이 다르면 차이가 타깃 때문인지 모델 때문인지 알 수 없다.

    그래서 주력 타깃에서 정한 모델 하나로 양쪽을 잰다. 모델별 성적을 감추는
    것은 아니다 — `model_by_target.csv` 에 전 모델 × 전 타깃을 그대로 싣는다.
    """
    best = model or str(FC.select_model(wf, timing).iloc[0]["모델"])
    s = wf[wf["모델"] == best]
    k, n = int(s["적중"].sum()), len(s)
    g = s.groupby("종목")["적중"].agg(["sum", "size"])
    # ⚠️ 기준선은 **다수 클래스 비율**이다. 0.5 가 아니다.
    per_base = s.groupby("종목")["실제"].apply(FC.baseline_rate)
    base = float((per_base * g["size"]).sum() / g["size"].sum())
    # **균형정확도가 주 지표다.** 라벨이 쏠려도 0.5 가 무작위다.
    bal = FC.balanced_accuracy(s["적중"], s["실제"])
    n_min = int(s["실제"].value_counts().min())
    blo, bhi = FC._wilson(int(round(bal * n_min * 2)), n_min * 2)
    per_bal = s.groupby("종목").apply(
        lambda x: FC.balanced_accuracy(x["적중"], x["실제"]))
    return {"타깃": target, "모델": best, "평가건수": n, "적중": k,
            "적중률": round(k / n, 4), "균형정확도": round(bal, 4),
            "균형_신뢰하한": round(blo, 4), "균형_신뢰상한": round(bhi, 4),
            "유의미": bool(blo > 0.5), "다수클래스비율": round(base, 4),
            "50%초과종목": int((per_bal > 0.5).sum()), "종목수": int(len(g))}


# --------------------------------------------------------------------------
# 파운데이션(풀드) 모델 vs 종목별 재학습
# --------------------------------------------------------------------------
def run_pooled_eval(days: int = FC.REPORT_DAYS, stack_path: str | None = None,
                    refit_every: int = 1, targets=("변동성", "방향"),
                    holdout: bool = False, verbose: bool = True) -> dict:
    """
    두 방식을 **완전히 같은 평가 행**에서 견준다.

    비교가 성립하려면 평가 대상이 같아야 한다. 풀드 모델은 153종목을
    학습하지만 평가는 종목별 모델이 이미 돈 **38종목 × 40거래일** 그대로에
    한정한다. 학습 자료가 다른 것은 두 방식의 차이 그 자체이므로 그대로 두고,
    **맞히려는 문제만** 똑같이 맞춘다.

        종목별   삼성전자 과거 1,470행       → 삼성전자 8/20 예측
        풀드     153종목 24만행 (8/19까지)   → 삼성전자 8/20 예측
                                              ↑ 같은 날 같은 종목을 맞힌다

    ⚠️ 이 함수는 종목별 워크포워드 CSV 가 이미 있어야 돈다.
    없으면 비교 상대가 없어 "풀드가 54%" 라는 숫자만 남는데, 그건 혼자서는
    아무 뜻이 없다.
    """
    from . import pooled as PL

    data, figs = _dirs("03_forecast")
    if stack_path and Path(stack_path).is_file():
        stacked = pd.read_parquet(stack_path)
        if verbose:
            print(f"  저장된 학습자료 — {stacked.shape[0]:,}행 · "
                  f"{stacked['종목'].nunique()}종목")
    else:
        stacked = PL.stack(_pooled_codes(), verbose=verbose)

    # 종목별 결과 — 비교 상대
    per_files = {"변동성": "walkforward_raw.csv",
                 "방향": "walkforward_direction.csv"}
    label_to_code = {e["label"]: e["code"] for e in all_entries()}

    rows, keep_pooled = [], {}
    for target in targets:
        p = data / per_files[target]
        if not p.is_file():
            print(f"  ⚠️ {p.name} 이 없어 {target} 비교를 건너뜁니다 — "
                  "`python cli.py results` 를 먼저 돌리세요.")
            continue
        per = pd.read_csv(p)
        per["날짜"] = pd.to_datetime(per["날짜"])
        per["종목코드"] = per["종목"].map(label_to_code)
        # 종목별 쪽은 여러 모델이 섞여 있다. 채택 모델(앙상블)만 남긴다.
        best = (FC.ENSEMBLE if FC.ENSEMBLE in set(per["모델"])
                else per["모델"].iloc[0])
        per = per[per["모델"] == best]

        if verbose:
            print(f"\n▶ 타깃 = {target} — 풀드 워크포워드")
        pool_wf = PL.evaluate(stacked, target=target, n_days=days,
                              refit_every=refit_every, verbose=verbose)
        if not len(pool_wf):
            continue
        pool_wf["종목코드"] = pool_wf["종목"].astype(str)

        # ⚠️ **교집합만** 남긴다.
        # 풀드는 153종목을 다 예측하지만 종목별은 38종목만 돌았다. 그대로
        # 비교하면 서로 다른 문제를 푼 성적을 견주게 된다.
        key = ["종목코드", "날짜"]
        common = per[key].merge(pool_wf[key], on=key, how="inner")
        per_c = per.merge(common, on=key, how="inner")
        pool_c = pool_wf.merge(common, on=key, how="inner")
        keep_pooled[target] = pool_c

        # ── 처음 보는 종목 ─────────────────────────────────────────────
        # 기본 설정에서는 평가 38종목이 학습 풀 153종목 안에 있다. 그래서
        # 재는 것이 **시간 일반화**("어제까지 배운 걸로 오늘을 맞히나")다.
        # 서비스는 그 위에 **종목 일반화**("한 번도 안 배운 종목을 맞히나")를
        # 주장하는데, 그건 학습에서 종목을 빼야만 검증된다.
        cases = [("종목별 재학습", per_c), ("파운데이션(풀드)", pool_c)]
        if holdout:
            if verbose:
                print(f"\n▶ 타깃 = {target} — 처음 보는 종목 (학습에서 제외)")
            hw = PL.evaluate(stacked, target=target, n_days=days,
                             refit_every=refit_every,
                             holdout_codes=set(label_to_code.values()),
                             verbose=verbose)
            if len(hw):
                hw["종목코드"] = hw["종목"].astype(str)
                hw = hw.merge(common, on=key, how="inner")
                keep_pooled[f"{target}_홀드아웃"] = hw
                cases.append(("파운데이션(미학습 종목)", hw))

        for tag, s in cases:
            if not len(s):
                continue
            bal = FC.balanced_accuracy(s["적중"], s["실제"])
            nmin = int(s["실제"].value_counts().min())
            lo, hi = FC._wilson(int(round(bal * nmin * 2)), nmin * 2)
            per_bal = s.groupby("종목코드").apply(
                lambda x: FC.balanced_accuracy(x["적중"], x["실제"]))
            rows.append({
                "타깃": target, "방식": tag,
                "모델": str(s["모델"].iloc[0]),
                "평가건수": len(s), "적중": int(s["적중"].sum()),
                "적중률": round(float(s["적중"].mean()), 4),
                "균형정확도": round(bal, 4),
                "균형_신뢰하한": round(lo, 4), "균형_신뢰상한": round(hi, 4),
                "유의미": bool(lo > 0.5),
                "50%초과종목": int((per_bal > 0.5).sum()),
                "종목수": int(per_bal.notna().sum()),
                "학습표본중앙값": int(s["학습표본"].median()),
            })
        if verbose and len(rows) >= 2:
            a, b = rows[-2], rows[-1]
            print(f"    {target}: 종목별 {a['균형정확도']:.4f} vs "
                  f"풀드 {b['균형정확도']:.4f}  "
                  f"({(b['균형정확도'] - a['균형정확도']) * 100:+.2f}%p)")

    if not rows:
        raise RuntimeError("비교표를 만들지 못했습니다.")
    cmp_df = pd.DataFrame(rows)
    cmp_df.to_csv(data / "pooled_vs_perstock.csv", index=False,
                  encoding="utf-8-sig")
    for t, s in keep_pooled.items():
        s.to_csv(data / f"pooled_walkforward_{t}.csv", index=False,
                 encoding="utf-8-sig")

    # ── 배포 구조를 정한다 ─────────────────────────────────────────────
    # 정확도만 보고 정하면 안 된다. 종목별이 이겨도 **건당 7.0초**이고
    # 파운데이션은 **167ms** 다. 42배 차이를 2%p 남짓과 맞바꾸는 셈이라,
    # 화면 종류에 따라 답이 갈린다.
    main = "변동성" if "변동성" in set(cmp_df["타깃"]) else cmp_df["타깃"].iloc[0]
    m = cmp_df[cmp_df["타깃"] == main].sort_values("균형정확도",
                                                  ascending=False)
    winner = str(m.iloc[0]["방식"])
    gap = float(m.iloc[0]["균형정확도"] - m.iloc[-1]["균형정확도"]) * 100
    pooled_row = cmp_df[(cmp_df["타깃"] == main)
                        & (cmp_df["방식"] == "파운데이션(풀드)")]
    pooled_ok = bool(pooled_row["유의미"].iloc[0]) if len(pooled_row) else False

    decision = {
        "주력타깃": main,
        "정확도우위": winner,
        "격차%p": round(gap, 2),
        "파운데이션_50%초과_유의미": pooled_ok,
        "배포": "기본=파운데이션(167ms) · 정밀모드=종목별학습(7.0초)",
        "판단근거": (
            f"{winner}가 {abs(gap):.2f}%p 앞서지만 그 대가가 167ms → 7,000ms "
            "(42배)다. 관심종목 목록처럼 여러 종목을 한 번에 그리는 화면에서는 "
            "종목당 7초가 성립하지 않으므로 기본값은 파운데이션으로 둔다. "
            "파운데이션도 신뢰하한이 50%를 넘어 단독으로 쓸 수 있다는 것이 "
            "이 표의 핵심이다. 한 종목을 펼쳐 볼 때는 precise=True 로 "
            "종목별 학습을 켜면 된다."
            if pooled_ok else
            "파운데이션이 50%를 유의미하게 넘지 못했다. 기본값을 파운데이션으로 "
            "둘 수 없으므로 종목별 학습을 기본으로 하고, 표본이 모자란 종목에만 "
            "파운데이션을 폴백으로 쓴다."),
    }
    (data / "pooled_decision.json").write_text(
        json.dumps(decision, ensure_ascii=False, indent=1), encoding="utf-8")

    # ⚠️ 유의성 판정을 **모델 파일 옆에 적어 둔다.**
    # 서빙 응답이 "이 타깃이 검증에서 50%를 넘었나"를 실어 보내야 하는데,
    # 그 판정은 여기서만 나온다. 모델과 따로 두면 재학습할 때 조용히
    # 어긋나서, 미검증 타깃이 검증된 것처럼 나가게 된다.
    from . import pooled as PL

    for t in cmp_df["타깃"].unique():
        r = cmp_df[(cmp_df["타깃"] == t)
                   & (cmp_df["방식"] == "파운데이션(풀드)")]
        if not len(r):
            continue
        mp = PL.meta_path(t)
        try:
            meta = json.loads(mp.read_text(encoding="utf-8")) \
                if mp.is_file() else {}
        except Exception:
            meta = {}
        meta.update({
            "유의미": bool(r["유의미"].iloc[0]),
            "검증_균형정확도": float(r["균형정확도"].iloc[0]),
            "검증_신뢰구간": [float(r["균형_신뢰하한"].iloc[0]),
                        float(r["균형_신뢰상한"].iloc[0])],
            "검증_평가건수": int(r["평가건수"].iloc[0]),
            "검증_50%초과종목": f"{int(r['50%초과종목'].iloc[0])}/"
                            f"{int(r['종목수'].iloc[0])}",
            "검증일": str(pd.Timestamp.now().date()),
        })
        # 정밀 모드(종목별 재학습)의 판정도 같이 적어 둔다.
        # 서빙이 `precise=True` 로 답했으면 **그 방식의** 유의성을 실어야
        # 한다. 지금은 두 방식의 판정이 우연히 같지만, 재측정에서 갈리면
        # 파운데이션 판정이 정밀 응답에 붙어 나가게 된다.
        pr = cmp_df[(cmp_df["타깃"] == t)
                    & (cmp_df["방식"] == "종목별 재학습")]
        if len(pr):
            meta.update({
                "정밀_유의미": bool(pr["유의미"].iloc[0]),
                "정밀_균형정확도": float(pr["균형정확도"].iloc[0]),
            })
        mp.write_text(json.dumps(meta, ensure_ascii=False, indent=1),
                      encoding="utf-8")

    try:
        V.pooled_comparison(cmp_df, figs / "10_pooled_vs_perstock.png")
    except Exception as e:
        print(f"  그림 실패 — {type(e).__name__}: {e}")

    if verbose:
        print("\n" + cmp_df[["타깃", "방식", "균형정확도", "균형_신뢰하한",
                             "유의미", "50%초과종목", "종목수"]].to_string(
            index=False))
        print(f"\n  → 주력 타깃({main}) 채택: **{winner}**")
    return {"비교": cmp_df, "결정": decision}


def _pooled_codes(min_articles: int = 30) -> list[str]:
    """
    파운데이션 학습에 쓸 종목.

    **뉴스 아카이브가 있는 종목**을 쓴다. 뉴스가 없는 종목을 잔뜩 넣으면
    `news_xfer` 가 그 종목들에서 전부 중립 0.5 상수가 되어, 뉴스가 실제로
    있는 종목의 신호까지 평균에 묻힌다. 열은 채워져 있는데 배울 것이 없는
    상태가 되는 것이다.
    """
    from . import news as N
    from . import registry as REG

    codes, seen = [], set()
    for e in all_entries():                       # 평가 38종목을 먼저
        if e["code"] not in seen:
            codes.append(e["code"])
            seen.add(e["code"])
    try:
        vc = pd.read_parquet(N.NEWS_ARCHIVE)["corp"].value_counts()
    except Exception:
        return codes
    for corp, n in vc.items():
        if n < min_articles:
            break
        try:
            c = REG.resolve(str(corp))["code"]
        except Exception:
            continue
        if c not in seen:
            codes.append(c)
            seen.add(c)
    return codes


def _finish_forecast(by_target: dict, timing_all: dict, data, figs,
                     n_days: int = FC.REPORT_DAYS, refit_every: int = 1,
                     panels: dict | None = None, verbose: bool = True) -> dict:
    """
    워크포워드 **이후** 단계 — 집계 · 선택 · 그림 · 요약.

    `run_forecast`(새로 돌릴 때)와 `rebuild_forecast`(저장된 CSV 로 다시
    만들 때)가 이 함수를 공유한다. 두 벌로 두면 한쪽만 고쳐져 어긋난다.
    """
    # 주력 타깃에서 모델을 먼저 정하고, **그 모델로 양쪽을 잰다.**
    main = FC.DEFAULT_TARGET if FC.DEFAULT_TARGET in by_target else \
        list(by_target)[0]
    prod_model = str(FC.select_model(by_target[main],
                                     timing_all.get(main, {})).iloc[0]["모델"])
    rows = [_target_row(t, wf, timing_all.get(t, {}), model=prod_model)
            for t, wf in by_target.items()]

    # 모델 × 타깃 전수 성적 — 하나로 고정한 대신 나머지를 전부 싣는다.
    mrows = []
    for t, wf_ in by_target.items():
        for m, sub in wf_.groupby("모델"):
            bal = FC.balanced_accuracy(sub["적중"], sub["실제"])
            per = sub.groupby("종목").apply(
                lambda x: FC.balanced_accuracy(x["적중"], x["실제"]))
            nmin = int(sub["실제"].value_counts().min())
            lo, hi = FC._wilson(int(round(bal * nmin * 2)), nmin * 2)
            mrows.append({"타깃": t, "모델": m, "평가건수": len(sub),
                          "균형정확도": round(bal, 4),
                          "신뢰하한": round(lo, 4), "신뢰상한": round(hi, 4),
                          "유의미": bool(lo > 0.5),
                          "50%초과": int((per > 0.5).sum()),
                          "종목수": int(per.notna().sum()),
                          "채택": bool(m == prod_model)})
    by_model = pd.DataFrame(mrows).sort_values(
        ["타깃", "균형정확도"], ascending=[True, False])
    by_model.to_csv(data / "model_by_target.csv", index=False,
                    encoding="utf-8-sig")
    if verbose:
        for r in rows:
            print(f"  {r['타깃']:4s} → 적중률 {r['적중률']*100:.2f}%  "
                  f"**균형정확도 {r['균형정확도']*100:.2f}%** "
                  f"[{r['균형_신뢰하한']*100:.1f}, {r['균형_신뢰상한']*100:.1f}]  "
                  + ("유의미" if r["유의미"] else "미검증")
                  + f"  (다수클래스 {r['다수클래스비율']*100:.1f}%)")
    cmp_df = pd.DataFrame(rows)
    wf = by_target[main]
    sel = FC.select_model(wf, timing_all.get(main, {}))
    best = prod_model
    by_stock = FC.summarize(wf, by="종목")
    by_index = FC.summarize(wf, by="지수")
    by_tier = FC.summarize(wf, by="구분")

    if verbose:
        print(f"\n선택: 타깃 {main} · 모델 {best}\n다음 거래일 예측")
    # `rebuild_forecast` 는 패널 없이 들어온다. 예측은 패널이 있어야 하므로
    # 여기서 만든다 — 디스크 캐시가 있으면 38종목이 1초면 올라온다.
    if panels is None:
        panels = {}
        for name in _names():
            try:
                panels[name] = FC.build(name, verbose=False)
            except Exception:
                continue
    preds, pred_fail = [], []
    for name, df in panels.items():
        try:
            preds.append(FC.predict_next(name, kind=best, target=main, df=df,
                                         verbose=verbose))
        except Exception as ex:
            pred_fail.append(f"{name}({type(ex).__name__})")
    if pred_fail and verbose:
        print(f"  ⚠️ 예측 실패 {len(pred_fail)}종목: {', '.join(pred_fail[:6])}")
    pred_df = pd.DataFrame(preds)

    # 시연 상위는 **균형정확도**로 고른다.
    # 단순 적중률로 고르면 라벨이 한쪽으로 쏠린 종목이 위로 올라온다 —
    # "잔잔함"만 계속 나온 종목은 그것만 찍어도 적중률이 높기 때문이다.
    # 발표에서 보여 줄 종목이 하필 그런 종목이면 곤란하다.
    demo = (by_stock[by_stock["모델"] == best]
            .sort_values("균형정확도", ascending=False).head(8))

    for df_, nm in ((wf, "walkforward_raw"), (cmp_df, "target_comparison"),
                    (by_stock, "accuracy_by_stock"),
                    (by_index, "accuracy_by_index"),
                    (by_tier, "accuracy_by_tier"),
                    (sel, "model_selection"),
                    (pred_df, "next_day_prediction"),
                    (demo, "demo_top_stocks")):
        if len(df_):
            df_.to_csv(data / f"{nm}.csv", index=False, encoding="utf-8-sig")
    if "방향" in by_target:
        by_target["방향"].to_csv(data / "walkforward_direction.csv",
                                index=False, encoding="utf-8-sig")

    # --- 급락 반등 — 검증을 통과한 두 신호 중 하나 ----------------------
    if verbose:
        print("\n▶ 급락 반등 분석")
    from . import reversion as RV
    rev = RV.run(save=True, verbose=verbose)

    if verbose:
        print("\n그림 저장")
    # ⚠️ 그림 하나가 죽어도 나머지는 나와야 한다.
    # 전에는 두 번째 그림에서 KeyError 가 나 나머지 6장이 통째로 날아갔고,
    # 로그 끝에 "실패" 한 줄만 남아 무엇이 빠졌는지 바로 보이지 않았다.
    def _fig(fn, *args, **kw):
        try:
            fn(*args, **kw)
        except Exception as e:
            print(f"    그림 실패 [{fn.__name__}] {type(e).__name__}: "
                  f"{str(e)[:70]}")

    best_stock = by_stock[by_stock["모델"] == best]
    _fig(V.reversion_effect, pd.DataFrame(rev["요약"]),
         figs / "01_reversion.png", verbose,
         clustered=pd.DataFrame(rev.get("날짜단위", [])))
    _fig(V.target_comparison, cmp_df, figs / "02_target_comparison.png",
         verbose)
    _fig(V.accuracy_hist, best_stock,
         figs / "03_accuracy_distribution.png", verbose)
    # 지수별·규모별은 **선택 모델 한 개로 걸러서** 넘긴다 (viz 주석 참조)
    _fig(V.group_accuracy, by_index[by_index["모델"] == best],
         by_tier[by_tier["모델"] == best], figs / "04_by_group.png", verbose)
    _fig(V.forecast_accuracy, by_stock[by_stock["종목"].isin(demo["종목"])],
         figs / "05_accuracy_top.png", verbose)
    _fig(V.forecast_timeline, wf[wf["종목"].isin(demo["종목"].head(5))], best,
         figs / "06_daily_hits.png", verbose)
    _fig(V.model_tradeoff, sel, figs / "07_model_tradeoff.png", verbose)
    if len(pred_df):
        _fig(V.prediction_card, pred_df.head(12),
             figs / "08_next_day.png", verbose)
    # 어블레이션은 따로 돌린다(`python cli.py ablate`). CSV 가 있으면 그린다.
    abl_path = data / "feature_ablation.csv"
    if abl_path.exists():
        try:
            _fig(V.feature_ablation, pd.read_csv(abl_path),
                 figs / "09_ablation.png", verbose)
        except Exception as e:
            print(f"    어블레이션 그림 건너뜀 — {type(e).__name__}")

    k = int(wf[wf["모델"] == best]["적중"].sum())
    n = int((wf["모델"] == best).sum())
    lo, hi = FC._wilson(k, n)
    out = {"평가일수": n_days, "재학습주기": refit_every,
           "타깃": main, "피처묶음": FC.DEFAULT_FEATURES, "선택모델": best,
           "종목수": int(wf["종목"].nunique()),
           "전체평가건수": n, "전체적중": k, "전체적중률": round(k / n, 4),
           "신뢰구간하한": round(lo, 4), "신뢰구간상한": round(hi, 4),
           "유의미": bool(lo > 0.5),
           "타깃비교": cmp_df.to_dict("records"),
           "모델비교": sel.to_dict("records"),
           "종목별": by_stock.to_dict("records"),
           "지수별": by_index.to_dict("records"),
           "규모별": by_tier.to_dict("records"),
           "시연상위": demo.to_dict("records"),
           "예측": pred_df.to_dict("records"),
           # ⚠️ 보정 결과도 같이 저장한다.
           # 요약만 넣었더니 리포트가 **보정 전 수치(+6.88%p)** 를 대표
           # 지표로 걸었다. 정직한 값은 날짜 단위 쪽(+4.94%p)이다.
           "급락반등": rev["요약"], "반등신호": rev["신호"],
           "날짜단위": rev.get("날짜단위", []),
           "사후검증": rev.get("사후검증", [])}
    (data / "summary.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8")
    if verbose:
        print(f"\n{main}: {k}/{n} = {k/n*100:.2f}%  "
              f"[{lo*100:.1f}, {hi*100:.1f}]")
    return out


# ==========================================================================
# 02 이상감지 · 위험도
# ==========================================================================
def run_anomaly(verbose: bool = True) -> dict:
    from . import data as D
    from . import features as F

    data, figs = _dirs("01_anomaly")
    kr = {e["label"]: e["code"] for e in all_entries() if e["market"] == "KR"}
    risk = RK.score_universe(kr, pool=140, verbose=verbose)
    # ⚠️ `RK.save()` 를 쓴다. 여기서 to_csv 만 부르면 **축 가중치와 창 길이가
    # 담긴 JSON 이 안 만들어진다** — risk.py 는 그 함수를 갖고 있는데 아무도
    # 부르지 않아 죽은 경로로 남아 있었다. 점수만 있고 어떻게 매겼는지가
    # 빠지면 심사자가 재현할 수 없다.
    RK.save(risk, verbose=verbose)

    if verbose:
        print("\n그림 저장")
    V.risk_profile(risk, figs / "01_risk_profile.png", verbose)
    V.risk_ranking(risk, figs / "02_risk_ranking.png", verbose)

    # 종목별 이상 신호 타임라인. 미국 종목도 같은 규칙으로 그린다.
    rows = []
    for name in _names():
        try:
            px = FC.load_prices(name).tail(250)
        except Exception:
            continue
        z = F.robust_z(px["close"].pct_change(), window=60, min_periods=20)
        V.anomaly_timeline(px, z, name,
                           figs / f"03_timeline_{entry(name)['code']}.png",
                           verbose=verbose)
        hit = z.abs() > 2.5
        rows.append({"종목": name, "구간일수": len(px),
                     "이상신호": int(hit.sum()),
                     "빈도": round(float(hit.mean()), 4),
                     "최대z": round(float(z.abs().max()), 2)})
    tl = pd.DataFrame(rows)
    tl.to_csv(data / "anomaly_counts.csv", index=False, encoding="utf-8-sig")

    out = {"위험도": risk.to_dict("records"),
           "이상신호": tl.to_dict("records"),
           "설명": [RK.speak(r) for _i, r in risk.iterrows()]}
    (data / "summary.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8")
    return out


# ==========================================================================
# 03 차트 유사도
# ==========================================================================
def run_similarity(windows=None, top_k: int = 4, forward: int = 20,
                   pool: int = 140, verbose: bool = True) -> dict:
    """
    차트 유사도 — **1·3·6·12개월 네 창**에서 각각 닮은 구간을 찾는다.

    창마다 다른 질문에 답하기 때문에 하나로 합치면 안 된다.
    20봉은 "지금 이 모양", 250봉은 "올해 전체 흐름"이다. 같은 종목이
    네 창에서 **공통으로** 올라오면 그게 진짜 닮은 것이다.
    """
    from . import data as D
    from . import pipeline as P
    from . import similarity as SIM

    windows = list(windows or SIM.MULTI_WINDOWS)
    data, figs = _dirs("02_similarity")
    codes = P.watchlist_from_cache(pool)
    bars, names = {}, {}
    need = max(windows) + forward + 10
    for c in codes:
        px = D.load_daily(c)
        if px is not None and len(px) >= need:
            bars[c] = px
            names[c] = D.name_of(c)
    if verbose:
        print(f"  후보 풀 {len(bars)}종목 (최소 {need}일 필요)")

    kr = [(e["label"], e["code"]) for e in all_entries()
          if e["market"] == "KR" and e["code"] in bars]
    allrows, consensus, out = [], [], {}

    for W in windows:
        idx = SIM.PatternIndex(W).build(bars, forward_bars=forward)
        for label, code in kr:
            try:
                # ⚠️ 질의 구간을 `forward` 만큼 과거로 물린다.
                # 가장 최근 구간을 질의로 쓰면 그 다음 20일이 존재하지 않아
                # **매치 쪽만 이후 구간이 그려지고 비교가 되지 않는다.**
                # 물려 두면 질의도 결과를 아는 구간이 되어, 닮은 모양이
                # 실제로 닮은 결과로 이어졌는지 눈으로 확인할 수 있다.
                res = SIM.find_similar(code, bars, W=W, top_k=top_k,
                                       name_map=names, forward_bars=forward,
                                       index=idx, query_offset=forward)
            except Exception:
                continue
            out.setdefault(label, {})[f"{W}봉"] = res
            for r in res["results"]:
                allrows.append({"질의종목": label, "창": f"{W}봉",
                                "순위": r["rank"], "닮은종목": r["name"],
                                "종료일": r["end"], "유사도": r["similarity"],
                                f"이후{forward}일수익률": r.get("forward_pct"),
                                "설명": r["explain"]})

    # 여러 창에서 공통으로 올라온 종목 = 진짜 닮은 것
    df = pd.DataFrame(allrows)
    if len(df):
        for label, s in df.groupby("질의종목"):
            cnt = s.groupby("닮은종목")["창"].nunique().sort_values(ascending=False)
            for nm, c in cnt.head(5).items():
                consensus.append({"질의종목": label, "닮은종목": nm,
                                  "공통창수": int(c),
                                  "최고유사도": float(
                                      s[s["닮은종목"] == nm]["유사도"].max())})
        df.to_csv(data / "similar_matches.csv", index=False,
                  encoding="utf-8-sig")
    con = pd.DataFrame(consensus)
    if len(con):
        con.to_csv(data / "similar_consensus.csv", index=False,
                   encoding="utf-8-sig")

    if verbose:
        print("  그림 저장")
    for label, code in kr[:6]:
        per = out.get(label, {})
        if not per:
            continue
        V.similarity_windows(bars[code], per, label, windows,
                             figs / f"01_windows_{code}.png", verbose)

    # 곡선 배열(`segment`·`forward_path`)은 그림을 그릴 때만 필요하다.
    # 요약 JSON 에 그대로 담으면 수만 개의 실수가 들어가 사람이 열어 볼 수
    # 없는 파일이 된다. 그림은 이미 PNG 로 저장돼 있으므로 여기선 뺀다.
    slim = {lab: {w: {**r, "results": [{k: v for k, v in hit.items()
                                        if k not in ("segment",
                                                     "forward_path")}
                                       for hit in r.get("results", [])]}
                  for w, r in per.items()}
            for lab, per in out.items()}
    (data / "summary.json").write_text(
        json.dumps({"windows": windows, "pool": len(bars), "forward": forward,
                    "합의": consensus, "결과": slim},
                   ensure_ascii=False, indent=2, default=str),
        encoding="utf-8")
    return {"pool": len(bars), "rows": len(df), "windows": windows,
            "합의": consensus}


# ==========================================================================
# 전체
# ==========================================================================
def run_all(n_days: int = FC.REPORT_DAYS, refit_every: int = 1,
            verbose: bool = True) -> dict:
    t0 = time.time()
    res, fails = {}, []
    for key, fn in (("01_anomaly", lambda: run_anomaly(verbose)),
                    ("02_similarity", lambda: run_similarity(verbose=verbose)),
                    ("03_forecast",
                     lambda: run_forecast(n_days, refit_every,
                                          verbose=verbose))):
        print(f"\n{'='*70}\n▶ {key} — {PARTS[key]}\n{'='*70}")
        try:
            res[key] = fn()
        except Exception as e:
            fails.append(f"{key}: {type(e).__name__}: {e}")
            print(f"  ❌ 실패 — {type(e).__name__}: {e}")
    res["_elapsed_sec"] = round(time.time() - t0, 1)
    res["_failed"] = fails
    build_index(res)
    return res


def rebuild_index(verbose: bool = True) -> Path:
    """
    저장된 `*/data/summary.json` 만 읽어 요약 페이지를 다시 만든다.

    그림만 손봤을 때 전체 파이프라인(25분)을 다시 돌리지 않아도 되게 하는 길.
    """
    res = {}
    for part in PARTS:
        p = ROOT / part / "data" / "summary.json"
        if p.is_file():
            res[part] = json.loads(p.read_text(encoding="utf-8"))
    res.setdefault("_elapsed_sec", 0)
    res.setdefault("_failed", [])
    return build_index(res, verbose)


def build_index(res: dict, verbose: bool = True) -> Path:
    """전체 요약 HTML. 그림을 상대경로로 걸어 두면 그대로 열린다."""
    from .report_ai import render

    path = ROOT / "index.html"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render(res), encoding="utf-8")
    if verbose:
        print(f"\n요약 → {path}")
    return path
