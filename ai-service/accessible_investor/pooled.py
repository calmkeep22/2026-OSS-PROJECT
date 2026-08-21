"""
파운데이션(풀드) 모델 — **여러 종목을 한꺼번에 학습해 모델 하나를 만든다.**

왜 바꿨나
=========
원래 구조는 종목마다 그 종목 과거로 따로 학습했다. 연구에서는 이게 맞다 —
종목별 성질을 그대로 살리고, 한 종목의 성능을 그 종목 데이터로만 증명한다.

그런데 서비스에 올리는 순간 세 가지가 동시에 막힌다.

    ① 학습 표본 250행이 필요해 **상장 1년 반 미만 종목은 답이 없다**
    ② 조회할 때마다 학습하니 **종목당 7초**가 든다
    ③ 이웃 풀·비교군을 그때그때 모아야 한다

셋 다 "조회 시점에 학습한다"에서 나온 문제다. 미리 학습해 두면 전부 사라진다.

핵심은 **피처가 이미 종목 중립**이라는 것이다
---------------------------------------------
32개 피처가 전부 무차원 값이다 — 수익률, 이동평균 대비 이격률, 변동성 비율,
RSI, 지수 대비 초과수익, 베타, 상관, 뉴스 확률. 가격 수준이나 통화가 들어가는
자리가 하나도 없다. 그래서 삼성전자의 8만원짜리 행과 NVDA의 180달러짜리 행이
**같은 척도의 같은 의미**를 갖는다. 그냥 세로로 쌓으면 된다.

    종목별   삼성전자 1,470행 → 모델A     NVDA 1,470행 → 모델B     …
    풀드     삼성전자 + NVDA + … = 5만행 → **모델 하나**

부수 효과가 큰데, 학습 표본이 종목당 1,470행에서 **5만행 이상**으로 늘어난다.
표본이 30배가 되면 과적합 여지가 그만큼 줄어든다.

⚠️ 그래서 더 정확해진다는 보장은 없다
--------------------------------------
종목 고유의 버릇(이 종목은 목요일에 튄다 같은)은 평균에 묻힌다. 표본이 느는
이득과 고유성이 묻히는 손해 중 뭐가 큰지는 **재 봐야 안다.** `evaluate()` 가
종목별 재학습과 **완전히 같은 조건**으로 풀드 모델을 재고, 그 표를 리포트에
싣는다. 좋은 쪽을 쓰고 진 쪽도 숫자로 남긴다.

저장하는 것
-----------
    clf       분류기 (앙상블이면 세 모델의 리스트)
    reg       등락률 회귀
    thr       판정 임계값 — 평가와 서빙이 반드시 같은 값을 써야 한다
    medians   피처별 중앙값. **결측 대체에 쓴다** (아래 주석 참조)
    cols      피처 순서. 이게 어긋나면 조용히 틀린 답이 나온다
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np
import pandas as pd

from . import forecast as FC

MODEL_DIR = Path(__file__).resolve().parent.parent / "models"

# ⚠️ 피처 목록을 **고정한다.**
# `forecast._cols` 는 종목마다 커버리지를 보고 열을 골라낸다. 평가에서는
# 그게 맞지만 서빙에서는 재앙이다 — 종목마다 다른 열로 학습된 모델에
# 다른 열을 넣게 된다. 파운데이션 모델은 언제나 이 32개를 이 순서로 받는다.
FEATURES = (FC.TECH_FEATURES + FC.MKT_FEATURES + FC.PEER_FEATURES
            + FC.NEWS_FEATURES)

VERSION = 1


def model_path(target: str) -> Path:
    return MODEL_DIR / f"pooled_{target}.pkl"


def meta_path(target: str) -> Path:
    return MODEL_DIR / f"pooled_{target}.json"


# ==========================================================================
# 1. 학습 자료 쌓기
# ==========================================================================
def stack(codes: list[str], with_news: bool = True,
          verbose: bool = True) -> pd.DataFrame:
    """
    여러 종목의 패널을 세로로 붙인다.

    ⚠️ `라벨확정일` 을 반드시 같이 남긴다.
    워크포워드에서 "D일을 맞히려면 D 이전에 정답이 확정된 행만 쓴다"를
    지켜야 하는데, 시장마다 휴장일이 달라 **행 번호로는 그 경계를 못 긋는다.**
    한국은 쉬고 미국은 여는 날이 있어서 같은 위치의 행이 서로 다른 날짜다.
    각 행의 정답이 실제로 밝혀진 날짜를 적어 두면 시장이 섞여도 경계가 정확해진다.
    """
    from . import serving as SV

    parts, skipped = [], []
    for i, c in enumerate(codes, 1):
        try:
            df = SV.panel(c, with_news=with_news)
        except Exception as e:
            skipped.append(f"{c}({type(e).__name__})")
            continue
        d = df.copy()
        d["종목"] = str(c)
        d["날짜"] = d.index
        # 이 행의 정답(다음 거래일 결과)이 밝혀지는 날짜
        d["라벨확정일"] = d.index.to_series().shift(-1).to_numpy()
        parts.append(d)
        if verbose and i % 10 == 0:
            print(f"    {i}/{len(codes)} … 누적 {sum(len(p) for p in parts):,}행")
    if not parts:
        raise RuntimeError("학습 자료를 하나도 만들지 못했습니다.")
    out = pd.concat(parts, ignore_index=True)
    if verbose:
        print(f"    총 {len(out):,}행 · {out['종목'].nunique()}종목"
              + (f" · 건너뜀 {len(skipped)}개" if skipped else ""))
        if skipped:
            print(f"      {', '.join(skipped[:8])}"
                  + (" …" if len(skipped) > 8 else ""))
    return out


def _xy(df: pd.DataFrame, target: str) -> tuple[np.ndarray, np.ndarray,
                                                np.ndarray, pd.DataFrame]:
    col = FC.TARGETS[target]["col"]
    d = df[df[col].notna() & df["라벨확정일"].notna()].copy()
    X = d.reindex(columns=FEATURES).to_numpy(np.float32)
    y = d[col].to_numpy(int)
    r = d["y_ret"].to_numpy(float)
    return X, y, r, d


# ==========================================================================
# 2. 학습
# ==========================================================================
def _pick_threshold_by_date(kind: str, X: np.ndarray, y: np.ndarray,
                            dates: np.ndarray) -> float:
    """
    임계값을 **날짜로 갈라** 고른다.

    종목별 모델에서는 행 순서가 곧 시간이라 `X[:cut]` 으로 갈라도 됐다.
    풀드에서는 행이 종목별로 뭉쳐 있어서(삼성전자 1,470행 → NVDA 1,470행 …)
    행 번호로 자르면 **앞 80%가 그냥 앞쪽 종목들**이 된다. 시간 분할이 아니라
    종목 분할이 되어 버려서, 임계값을 미래 데이터로 고르는 셈이 된다.

    그래서 날짜 분위수로 자른다. 뒤 20% 기간이 홀드아웃이다.
    """
    dt = pd.to_datetime(pd.Series(dates))
    if dt.notna().sum() < 200:
        return 0.5
    cut = dt.quantile(1 - FC.THR_HOLDOUT)
    fit, ho = (dt <= cut).to_numpy(), (dt > cut).to_numpy()
    if fit.sum() < 200 or ho.sum() < 100:
        return 0.5
    if len(np.unique(y[fit])) < 2 or len(np.unique(y[ho])) < 2:
        return 0.5
    try:
        clf = FC._make_clf(kind, X[fit], y[fit])
        p = FC._proba(clf, X[ho])
    except Exception:
        return 0.5
    yh = y[ho]
    up, dn = yh == 1, yh == 0
    scores = np.array([((p[up] > t).mean() + (p[dn] <= t).mean()) / 2
                       for t in FC.THR_GRID])
    best = scores.max()
    tied = FC.THR_GRID[scores >= best - 1e-9]
    return float(tied[np.argmin(np.abs(tied - 0.5))])


def train(target: str = "변동성", codes: list[str] | None = None,
          kind: str = FC.ENSEMBLE, stacked: pd.DataFrame | None = None,
          with_news: bool = True, save: bool = True,
          verbose: bool = True) -> dict:
    """전 구간으로 한 번 학습해 파일로 굳힌다. 이게 배포되는 모델이다."""
    import joblib

    if stacked is None:
        codes = codes or FC.universe_names()
        if verbose:
            print(f"  [{target}] 학습 자료 수집 — {len(codes)}종목")
        stacked = stack(codes, with_news=with_news, verbose=verbose)

    X, y, r, d = _xy(stacked, target)
    if len(X) < 2000:
        raise RuntimeError(f"학습 표본이 부족합니다 ({len(X)}행).")

    # ⚠️ 결측 대체값을 **학습 시점에 계산해 저장한다.**
    # 파이프라인 안의 SimpleImputer 는 학습 데이터의 중앙값을 갖고 있지만,
    # 서빙에서는 그 전에 이미 문제가 생긴다 — 상장 3개월 종목은 drift_250 이
    # NaN 인 채로 들어오는데, 그게 몇 개나 비었는지 **알려 줘야** 사용자에게
    # 신뢰도를 정직하게 표시할 수 있다. 그래서 밖에서 명시적으로 채운다.
    med = np.nanmedian(np.where(np.isfinite(X), X, np.nan), axis=0)
    med = np.where(np.isfinite(med), med, 0.0).astype(np.float32)
    Xf = np.where(np.isfinite(X), X, med).astype(np.float32)

    t0 = time.time()
    thr = _pick_threshold_by_date(kind, Xf, y, d["날짜"].to_numpy())
    clf = FC._make_clf(kind, Xf, y)
    reg = FC._make_reg(Xf, r)
    el = time.time() - t0

    mdl = {
        "clf": clf, "reg": reg, "thr": float(thr),
        "medians": med, "cols": list(FEATURES), "kind": kind,
        "target": target, "version": VERSION,
        "meta": {
            "학습행": int(len(Xf)), "학습종목": int(d["종목"].nunique()),
            "학습기간": [str(pd.Timestamp(d["날짜"].min()).date()),
                     str(pd.Timestamp(d["날짜"].max()).date())],
            "라벨분포": {"1": int((y == 1).sum()), "0": int((y == 0).sum())},
            "임계값": float(thr), "뉴스사용": bool(with_news),
            "학습초": round(el, 1),
            "생성일": str(pd.Timestamp.now().date()),
        },
    }
    if save:
        MODEL_DIR.mkdir(parents=True, exist_ok=True)
        joblib.dump(mdl, model_path(target), compress=3)
        meta_path(target).write_text(
            json.dumps(mdl["meta"], ensure_ascii=False, indent=1),
            encoding="utf-8")
        if verbose:
            mb = model_path(target).stat().st_size / 1e6
            print(f"    {model_path(target).name} 저장 ({mb:.1f}MB) · "
                  f"{len(Xf):,}행 · 임계값 {thr:.2f} · {el:.0f}초")
    return mdl


# ==========================================================================
# 3. 적재 · 적용
# ==========================================================================
_LOADED: dict[str, dict] = {}


def load(target: str = "변동성") -> dict:
    """저장된 모델. 프로세스당 한 번만 읽는다."""
    import joblib

    if target not in _LOADED:
        p = model_path(target)
        if not p.is_file():
            raise FileNotFoundError(
                f"{p} 가 없습니다. `python cli.py train-pooled` 를 먼저 "
                "실행하세요.")
        m = joblib.load(p)
        # 평가에서 확정한 유의성을 모델에 붙여 둔다. 서빙 응답이
        # "이 타깃은 검증에서 유의미했나"를 그대로 실어 나를 수 있어야 한다.
        mp = meta_path(target)
        if mp.is_file():
            try:
                m["meta"].update(json.loads(mp.read_text(encoding="utf-8")))
            except Exception:
                pass
        _LOADED[target] = m
    return _LOADED[target]


def vectorize(row: pd.DataFrame, mdl: dict) -> tuple[np.ndarray, int]:
    """
    피처 한 행 → (모델이 먹는 벡터, 대체값으로 메운 피처 수).

    ⚠️ `reindex(columns=...)` 로 **저장된 순서에 맞춘다.**
    딕셔너리 순서나 concat 순서에 기대면 안 된다. 피처가 하나 밀리면
    예외도 안 나고 확률만 조용히 틀려서, 이런 건 며칠 뒤에나 발견된다.

    ⚠️ 결측 개수는 **메우기 전에** 센다.
    처음엔 메운 뒤의 배열에서 셌는데, 그 시점엔 NaN 이 하나도 없으니
    언제나 0 이 나왔다. 상장 3개월 종목이 "결측피처 0개"로 보고돼
    신뢰도 표시가 통째로 거짓말이 됐다.
    """
    raw = row.reindex(columns=mdl["cols"]).to_numpy(np.float64)
    n_missing = int((~np.isfinite(raw)).sum())
    x = np.where(np.isfinite(raw),
                 raw, np.asarray(mdl["medians"], np.float64))
    return x.astype(np.float32), n_missing


def apply(mdl: dict, x: np.ndarray, n_missing: int = 0) -> dict:
    """벡터 → 확률·등락률. 여기가 서빙의 실제 계산 전부다."""
    p = float(FC._proba(mdl["clf"], x)[0])
    mag = float(mdl["reg"].predict(x)[0]) * 100
    return {"prob": p, "thr": float(mdl["thr"]), "mag": mag,
            "n_missing": int(n_missing)}


# ==========================================================================
# 4. 평가 — 종목별 재학습과 **같은 조건**으로
# ==========================================================================
def evaluate(stacked: pd.DataFrame, target: str = "변동성",
             n_days: int = 40, kind: str = FC.ENSEMBLE,
             refit_every: int = 1, holdout_codes: "set | None" = None,
             verbose: bool = True) -> pd.DataFrame:
    """
    풀 단위 워크포워드.

    D일을 맞히려면 **D 이전에 정답이 확정된 행만** 학습에 쓴다. 종목별
    워크포워드와 규칙이 정확히 같고, 다른 것은 학습 자료가 그 종목 것만이
    아니라 전 종목이라는 점뿐이다. 그래야 두 수치를 나란히 놓을 수 있다.

    비용은 오히려 싸다 — 종목별은 38종목 × 40일 = 1,520회 학습이지만
    풀드는 **40회**다. 하루치 재학습 한 번으로 전 종목을 예측하기 때문이다.

    holdout_codes — **처음 보는 종목** 검증
    ---------------------------------------
    주면 그 종목들을 **학습에서 통째로 빼고** 그 종목들만 평가한다.

    이게 없으면 측정의 뜻이 달라진다. 기본 설정에서는 평가 종목이 학습 풀
    안에 들어 있어서, 재는 것이 **시간 일반화**("어제까지 배운 걸로 오늘을
    맞히나")다. 서비스는 그걸 넘어 **종목 일반화**("한 번도 안 배운 종목을
    맞히나")를 주장해야 하는데, 그 주장은 학습에서 종목을 빼야만 검증된다.

        기본          학습 153종목(38 포함) → 38종목 평가   시간 일반화
        holdout       학습 115종목(38 제외) → 38종목 평가   **종목 일반화**

    두 수치의 차이가 곧 "그 종목을 봤다는 것의 값어치"다.
    """
    col = FC.TARGETS[target]["col"]
    d = stacked[stacked[col].notna() & stacked["라벨확정일"].notna()].copy()
    d["날짜"] = pd.to_datetime(d["날짜"])
    d["라벨확정일"] = pd.to_datetime(d["라벨확정일"])
    d["종목"] = d["종목"].astype(str)

    hold = {str(c) for c in holdout_codes} if holdout_codes else None
    if hold and verbose:
        n_tr = d[~d["종목"].isin(hold)]["종목"].nunique()
        print(f"    홀드아웃 {len(hold)}종목 제외 — 학습 {n_tr}종목")

    all_dates = np.sort(d["날짜"].unique())
    eval_dates = all_dates[-n_days:]
    tgt = FC.TARGETS[target]

    rows: list[dict] = []
    cache: dict = {}
    t0 = time.time()
    for j, D in enumerate(eval_dates):
        # ⚠️ 경계는 **라벨확정일 < D** 다.
        # "행 날짜 < D" 로 하면 D-1 행이 들어오는데, 그 행의 정답은 D 의
        # 결과다 — 맞히려는 바로 그 값을 학습에 넣게 된다.
        tr = d[d["라벨확정일"] < D]
        te = d[d["날짜"] == D]
        if hold:
            tr = tr[~tr["종목"].isin(hold)]      # 학습에서 완전히 뺀다
            te = te[te["종목"].isin(hold)]       # 홀드아웃만 평가한다
        if len(tr) < 2000 or not len(te):
            continue

        if j % refit_every == 0 or "clf" not in cache:
            Xtr = tr.reindex(columns=FEATURES).to_numpy(np.float32)
            med = np.nanmedian(np.where(np.isfinite(Xtr), Xtr, np.nan), axis=0)
            med = np.where(np.isfinite(med), med, 0.0).astype(np.float32)
            Xtr = np.where(np.isfinite(Xtr), Xtr, med).astype(np.float32)
            ytr = tr[col].to_numpy(int)
            if len(np.unique(ytr)) < 2:
                continue
            try:
                thr = (cache.get("thr", 0.5)
                       if (cache.get("n_refit", 0) % FC.THR_EVERY) else
                       _pick_threshold_by_date(kind, Xtr, ytr,
                                               tr["날짜"].to_numpy()))
                cache = {"clf": FC._make_clf(kind, Xtr, ytr),
                         "reg": FC._make_reg(Xtr, tr["y_ret"].to_numpy(float)),
                         "thr": thr, "med": med,
                         "n_refit": cache.get("n_refit", 0) + 1,
                         "n_train": len(tr)}
            except Exception as ex:
                if verbose:
                    print(f"    {pd.Timestamp(D):%m-%d} 학습 실패: "
                          f"{type(ex).__name__}")
                continue

        Xte = te.reindex(columns=FEATURES).to_numpy(np.float32)
        Xte = np.where(np.isfinite(Xte), Xte, cache["med"]).astype(np.float32)
        try:
            p = FC._proba(cache["clf"], Xte)
            mag = cache["reg"].predict(Xte)
        except Exception:
            continue

        thr = float(cache["thr"])
        y = te[col].to_numpy(float)
        ret = te["y_ret"].to_numpy(float)
        for i, (_, rw) in enumerate(te.iterrows()):
            rows.append({
                "종목": rw["종목"], "타깃": target, "모델": f"풀드-{kind}",
                "날짜": pd.Timestamp(D),
                "예측": tgt["상승"] if p[i] > thr else tgt["하락"],
                "상승확률": round(float(p[i]), 4), "임계값": round(thr, 3),
                "예상등락률": round(float(mag[i]) * 100, 3),
                "실제": tgt["상승"] if y[i] > 0.5 else tgt["하락"],
                "실제등락률": round(float(ret[i]) * 100, 3),
                "적중": int((p[i] > thr) == (y[i] > 0.5)),
                "등락률오차": round(abs(float(mag[i]) - ret[i]) * 100, 3),
                "학습표본": int(cache["n_train"]), "사용피처": len(FEATURES),
            })
        if verbose and (j + 1) % 10 == 0:
            got = [r["적중"] for r in rows]
            print(f"    {j + 1}/{len(eval_dates)}일 … {len(rows):,}건 "
                  f"적중률 {np.mean(got) * 100:.1f}% ({time.time() - t0:.0f}초)")

    out = pd.DataFrame(rows)
    if verbose and len(out):
        print(f"    풀드 워크포워드 완료 — {len(out):,}건 · "
              f"재학습 {cache.get('n_refit', 0)}회 · {time.time() - t0:.0f}초")
    return out
