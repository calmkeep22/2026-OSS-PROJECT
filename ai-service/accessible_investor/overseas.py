"""
해외(미국) 다음날 방향 모델 — xforecast 아카이브 기반.

무엇을 학습하는가
-----------------
100개 미국 종목 × 2019~2022년 일봉 + 같은 날 뉴스 요약문(LLM 생성).
뉴스와 가격에서 피처를 뽑아 **다음 거래일 방향**을 맞히는 분류기를 만든다.

왜 임베딩을 안 쓰는가
---------------------
원본 아카이브에는 6종의 문장 임베딩(qwen/gemini/lgai/linq/nvda/bert)이
있고 합쳐서 65GB다. 이 프로젝트는 저시력 사용자의 PC에서 도는 도구다.
65GB를 요구하는 순간 배포가 불가능하다.

대신 **사전 기반 극성 + 뉴스 구조 피처**를 쓴다. 47만 건을 수십 초에
처리하고, 결과 테이블은 수 MB다. 성능은 임베딩보다 낮겠지만 —
아래 `ablate()` 결과가 보여주듯 **임베딩을 써도 기준선을 못 넘었다**.

두 개의 라벨을 같이 잰다
------------------------
    y_up_raw      다음날 종가가 오늘보다 높은가            (원 대회와 같은 정의)
    y_up_excess   다음날 수익률이 **동일가중 지수**보다 높은가

raw 를 쓰면 상승·하락 비율이 46:54 처럼 치우쳐서, "항상 하락"으로 찍는
무지성 모델이 53.7%를 받는다. 이 숫자를 성과로 착각하기 쉽다.
excess 는 정의상 50:50 근처라 그 착시가 없다. **excess 가 정직한 지표다.**
raw 는 원 대회 결과(HR)와 비교하려고 같이 남긴다.

가중 학습 (xforecast 실험에서 가져옴)
-------------------------------------
    recency   w = half_life 반감기의 지수감쇠. 최근 데이터를 더 본다
    news      뉴스가 많은 상위 30% 표본에 가중 (원 실험의 news_sample_weight=3.0)

둘 다 `ablate()` 로 효과를 측정한다. 켜서 좋아지지 않으면 끄는 게 맞다.

데이터 배포
-----------
학습 데이터는 저장소에 넣지 않는다. **학습된 모델만** `models/` 에 올린다.
`XFORECAST_DIR` 환경변수 또는 `--xf-dir` 로 원본 위치를 지정한다.
"""

from __future__ import annotations

import json
import os
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

from . import lexicon as LX
from .config import DATA_DIR, OUTPUT_DIR, PROJECT_ROOT, RANDOM_SEED

warnings.filterwarnings("ignore")

MODEL_DIR = PROJECT_ROOT / "models"
DEFAULT_XF = Path(os.getenv("XFORECAST_DIR",
                            r"C:\Users\User\Desktop\xforecast")) / "archive"
CACHE_DIR = DATA_DIR / "overseas"
MODEL_PATH = MODEL_DIR / "overseas_daily.pkl"
META_PATH = MODEL_DIR / "overseas_daily.json"

# 뉴스 슬롯. 카테고리마다 열 개수가 다르다 (macro 5, sector 5, target 3, ...).
NEWS_SLOTS = {
    "macro": [f"macro_category{i}" for i in range(1, 6)],
    "sector": [f"sector_category{i}" for i in range(1, 6)],
    "target": [f"targetCompany_category{i}" for i in range(1, 4)],
    "related": [f"relatedCompany_category{i}" for i in range(1, 4)],
    "filing": ["filing_financialStatement", "filing_governanceRisks",
               "filing_overviewProduct", "filing_recentEventCatalyst",
               "filing_strategyMarketOps"],
}

PRICE_FEATURES = [
    "ret_1", "ret_5", "ret_20", "ma_dev_5", "ma_dev_20", "ma_dev_60",
    "vol_20", "vol_ratio", "atr_ratio", "gap", "range_pct",
    "excess_1", "excess_5", "rsi_14", "dist_high_60", "dist_low_60",
]

# 뉴스 피처: 카테고리별 개수 5 + 총계 2 + 카테고리별 극성 5 + 전체 극성 4
NEWS_FEATURES = (
    [f"n_{k}" for k in NEWS_SLOTS]
    + ["n_total", "n_total_z"]
    + [f"pol_{k}" for k in NEWS_SLOTS]
    + ["pol_all", "neg_all", "unc_all", "pol_spread"]
)


# ==========================================================================
# 1. 원본 로드
# ==========================================================================
def load_panel(xf_dir: Path | str | None = None) -> pd.DataFrame:
    """train.parquet — 일봉 + 뉴스 text_id 포인터."""
    d = Path(xf_dir) if xf_dir else DEFAULT_XF
    p = d / "train.parquet"
    if not p.is_file():
        raise FileNotFoundError(
            f"{p} 가 없습니다. XFORECAST_DIR 환경변수로 위치를 지정하세요.")
    df = pd.read_parquet(p)
    df["date"] = pd.to_datetime(df["date"])
    return df.sort_values(["ticker", "date"]).reset_index(drop=True)


def _text_scores(xf_dir: Path | str | None, used_ids: set[str],
                 verbose: bool = True) -> pd.DataFrame:
    """
    text_id → 극성 점수 표. 한 번 만들면 캐시한다.

    47만 건 전부가 아니라 **패널에서 실제로 참조하는 id만** 채점한다.
    """
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache = CACHE_DIR / "text_polarity.parquet"
    if cache.is_file():
        cached = pd.read_parquet(cache)
        if used_ids <= set(cached["text_id"]):
            if verbose:
                print(f"  극성 캐시 사용 ({len(cached):,}건)")
            return cached.set_index("text_id")

    d = Path(xf_dir) if xf_dir else DEFAULT_XF
    if verbose:
        print(f"  text.parquet 읽는 중 … ({len(used_ids):,}건 채점)")
    t = pd.read_parquet(d / "text.parquet")
    t = t[t["text_id"].isin(used_ids)]

    rows = LX.score_many(t["text"].tolist())
    out = pd.DataFrame(rows)
    out.insert(0, "text_id", t["text_id"].to_numpy())
    out.to_parquet(cache, index=False)
    if verbose:
        print(f"  극성 계산 완료 · 캐시 저장 → {cache.name}")
    return out.set_index("text_id")


# ==========================================================================
# 2. 피처
# ==========================================================================
def _price_features(g: pd.DataFrame) -> pd.DataFrame:
    """종목 하나의 일봉 → 가격 피처. `g` 는 날짜순 정렬돼 있어야 한다."""
    c, h, lo, o, v = (g["close"], g["high"], g["low"], g["open"], g["volume"])
    out = pd.DataFrame(index=g.index)

    ret = c.pct_change()
    out["ret_1"] = ret
    out["ret_5"] = c.pct_change(5)
    out["ret_20"] = c.pct_change(20)

    for w in (5, 20, 60):
        out[f"ma_dev_{w}"] = c / c.rolling(w, min_periods=w // 2).mean() - 1

    out["vol_20"] = ret.rolling(20, min_periods=10).std()
    out["vol_ratio"] = (np.log1p(v)
                        - np.log1p(v).rolling(20, min_periods=10).mean())

    tr = pd.concat([h - lo, (h - c.shift()).abs(), (lo - c.shift()).abs()],
                   axis=1).max(axis=1)
    out["atr_ratio"] = tr.rolling(14, min_periods=7).mean() / c
    out["gap"] = o / c.shift() - 1
    out["range_pct"] = (h - lo) / c.replace(0, np.nan)

    # RSI(14). Wilder 평활 대신 단순이동평균 — 차이는 미미하고 벡터화가 쉽다.
    d = c.diff()
    up = d.clip(lower=0).rolling(14, min_periods=7).mean()
    dn = (-d).clip(lower=0).rolling(14, min_periods=7).mean()
    out["rsi_14"] = 100 - 100 / (1 + up / dn.replace(0, np.nan))

    out["dist_high_60"] = c / h.rolling(60, min_periods=20).max() - 1
    out["dist_low_60"] = c / lo.rolling(60, min_periods=20).min() - 1
    return out


def build(xf_dir: Path | str | None = None, verbose: bool = True) -> pd.DataFrame:
    """
    xforecast 원본 → 학습 테이블.

    반환 열: date, ticker, 피처들, y_ret_raw, y_ret_excess, y_up_raw, y_up_excess
    """
    panel = load_panel(xf_dir)
    if verbose:
        print(f"패널 {len(panel):,}행 · 종목 {panel['ticker'].nunique()} "
              f"· {panel['date'].min():%Y-%m-%d} ~ {panel['date'].max():%Y-%m-%d}")

    slot_cols = [c for cols in NEWS_SLOTS.values() for c in cols]
    used = set(pd.unique(panel[slot_cols].to_numpy().ravel()))
    used.discard(None)
    used = {u for u in used if isinstance(u, str)}
    pol = _text_scores(xf_dir, used, verbose=verbose)

    # --- 뉴스 피처 -------------------------------------------------------
    # text_id 를 정수 위치로 바꿔 numpy 인덱싱한다. 딕셔너리 조회보다 훨씬 빠르다.
    pol_vals = pol["polarity"].to_numpy(np.float32)
    neg_vals = pol["neg_ratio"].to_numpy(np.float32)
    unc_vals = pol["uncertainty"].to_numpy(np.float32)
    pos_index = pd.Index(pol.index)

    news = pd.DataFrame(index=panel.index)
    cat_pol = {}
    for cat, cols in NEWS_SLOTS.items():
        ids = panel[cols].to_numpy()
        loc = pos_index.get_indexer(ids.ravel()).reshape(ids.shape)
        ok = loc >= 0
        safe = np.where(ok, loc, 0)
        news[f"n_{cat}"] = ok.sum(axis=1)
        with np.errstate(invalid="ignore"):
            p = np.where(ok, pol_vals[safe], np.nan)
            cat_pol[cat] = np.nanmean(p, axis=1) if p.size else np.nan
        news[f"pol_{cat}"] = cat_pol[cat]

    all_ids = panel[slot_cols].to_numpy()
    loc = pos_index.get_indexer(all_ids.ravel()).reshape(all_ids.shape)
    ok = loc >= 0
    safe = np.where(ok, loc, 0)
    news["n_total"] = ok.sum(axis=1)
    with np.errstate(invalid="ignore"):
        news["pol_all"] = np.nanmean(np.where(ok, pol_vals[safe], np.nan), axis=1)
        news["neg_all"] = np.nanmean(np.where(ok, neg_vals[safe], np.nan), axis=1)
        news["unc_all"] = np.nanmean(np.where(ok, unc_vals[safe], np.nan), axis=1)
    # 기사 간 극성 편차. 크면 "호재와 악재가 섞였다" = 방향이 불분명하다.
    with np.errstate(invalid="ignore"):
        news["pol_spread"] = np.nanstd(np.where(ok, pol_vals[safe], np.nan), axis=1)

    df = pd.concat([panel[["date", "ticker", "open", "high", "low",
                           "close", "volume"]], news], axis=1)

    # --- 가격 피처 (종목별) ---------------------------------------------
    feats = (df.groupby("ticker", group_keys=False)
               .apply(lambda g: _price_features(g.sort_values("date"))))
    df = pd.concat([df, feats], axis=1)

    # n_total 을 종목 평소 기사량 대비로 표준화. 종목마다 커버리지가 다르다.
    grp = df.groupby("ticker")["n_total"]
    df["n_total_z"] = ((df["n_total"] - grp.transform("mean"))
                       / grp.transform("std").replace(0, np.nan))

    # --- 라벨 ------------------------------------------------------------
    df["_ret_next"] = df.groupby("ticker")["close"].shift(-1) / df["close"] - 1
    # 동일가중 지수 = 그날 전 종목 평균 수익률. 시장 요인을 뺀다.
    mkt = df.groupby("date")["_ret_next"].transform("mean")
    df["y_ret_raw"] = df["_ret_next"]
    df["y_ret_excess"] = df["_ret_next"] - mkt
    df["y_up_raw"] = (df["y_ret_raw"] > 0).astype("int8")
    df["y_up_excess"] = (df["y_ret_excess"] > 0).astype("int8")

    df = df[df["_ret_next"].notna()].drop(columns=["_ret_next"])
    df = df.reset_index(drop=True)

    if verbose:
        print(f"학습 테이블 {len(df):,}행 · 피처 "
              f"{len(PRICE_FEATURES) + len(NEWS_FEATURES)}개")
        print(f"  상승 비율  raw {df['y_up_raw'].mean()*100:.2f}%  "
              f"excess {df['y_up_excess'].mean()*100:.2f}%")
    return df


# ==========================================================================
# 3. 표본 가중 (xforecast 실험에서 이식)
# ==========================================================================
def sample_weight(df: pd.DataFrame, recency: bool = True,
                  news: bool = True, half_life_days: float = 365.0,
                  news_weight: float = 3.0, news_q: float = 0.70) -> np.ndarray:
    """
    학습 표본 가중치.

    recency
        `w = 0.5 ** (경과일 / half_life)`. 시장 구조는 변한다. 2019년 표본과
        2021년 표본을 같은 무게로 두면 오래된 체제를 계속 학습한다.
    news
        기사 수 상위 (1-news_q) 분위 표본에 `news_weight` 배. 뉴스가 없는 날은
        어차피 뉴스 피처가 전부 0이라 신호가 없다. 있는 날에 집중시킨다.

    ⚠️ 분위수는 **학습 구간 안에서만** 계산해야 한다. 전체로 계산하면
    평가 구간 정보가 새어 들어간다. 그래서 `df` 는 학습 부분만 넘길 것.
    """
    w = np.ones(len(df), dtype=np.float64)
    if recency and len(df):
        age = (df["date"].max() - df["date"]).dt.days.to_numpy(float)
        w *= 0.5 ** (age / max(half_life_days, 1.0))
    if news and "n_total" in df.columns:
        thr = df["n_total"].quantile(news_q)
        w *= np.where(df["n_total"].to_numpy() >= thr, news_weight, 1.0)
    return w / w.mean()             # 평균 1로 정규화 — 학습률 스케일을 유지한다


# ==========================================================================
# 4. 학습 · 평가
# ==========================================================================
def _metrics(y, p, thr: float = 0.5) -> dict:
    from sklearn.metrics import (accuracy_score, balanced_accuracy_score,
                                 roc_auc_score)
    pred = (p > thr).astype(int)
    base = float(np.mean(y))
    return {
        # 원 대회의 hit_rate 와 같은 정의 — 단순 방향 적중률
        "HR": round(float(accuracy_score(y, pred)), 4),
        "균형정확도": round(float(balanced_accuracy_score(y, pred)), 4),
        "ROC-AUC": round(float(roc_auc_score(y, p)), 4),
        # 항상 다수 클래스로 찍었을 때의 HR. 이걸 못 넘으면 모델은 무의미하다
        "무지성기준선": round(max(base, 1 - base), 4),
        "상승비율": round(base, 4),
    }


# TabPFN 은 in-context learning 이다 — 학습 표본을 **프롬프트로 넣는다.**
# 그래서 표본이 늘면 어텐션 비용이 제곱으로 커진다. 라이브러리 자체가 CPU에서
# 1000행을 넘기면 막아 놓았다(validation.py:297). 억지로 풀 수는 있지만
# 이 프로젝트는 저시력 사용자의 일반 PC에서 도는 도구라 그러면 안 된다.
#
# 1000행 제약은 결함이 아니라 이 모델의 설계 의도다. TabPFN 은 "소표본에서
# 학습 없이 즉시 쓰는" 모델이고, 우리 용도(관심종목 몇 개 × 최근 며칠)가
# 정확히 그 조건이다.
TABPFN_MAX_ROWS = int(os.getenv("TABPFN_MAX_ROWS", "1000"))


def _subsample(n_rows: int, w: np.ndarray, cap: int | None = None):
    """
    가중 확률로 `cap` 행을 뽑는다.

    GBM 은 `sample_weight` 를 직접 받지만 TabPFN 은 안 받는다. 그래서
    **가중치를 표본 선택 확률로 옮긴다** — 가중치가 큰 표본이 프롬프트에
    들어갈 확률이 높아지므로 효과가 같다.
    """
    cap = TABPFN_MAX_ROWS if cap is None else cap
    if n_rows <= cap:
        return np.arange(n_rows)
    rng = np.random.default_rng(RANDOM_SEED)
    return rng.choice(n_rows, cap, replace=False, p=w / w.sum())


def _to_pseudo_prob(score: np.ndarray) -> np.ndarray:
    """
    연속 예측값 → [0,1] 백분위.

    회귀 모델은 확률이 아니라 수익률을 뱉는다. 그대로 0.5로 자르면 의미가
    없다. 순위 백분위로 바꾸면 0.5 = "예측 수익률 중앙값 위"가 되고,
    단조 변환이라 ROC-AUC 는 원본과 정확히 같다.
    """
    order = np.argsort(np.argsort(score))
    return (order + 0.5) / len(score)


def _fit(Xtr, ytr, w, Xte, model: str = "gbm", ytr_cont=None):
    """
    학습 후 평가셋 점수를 돌려준다.

    model
        gbm         HistGradientBoostingClassifier — 기준 모델
        tabpfn      TabPFNClassifier — 파운데이션 모델. **가중치에 로그인 필요**
        tabpfn_reg  TabPFNRegressor — 다음날 초과수익을 회귀. classifier 가중치가
                    없어도 되고, 방향뿐 아니라 **크기**까지 나와서 매수/매도
                    판단에 쓸 정보가 더 많다
    """
    if model == "tabpfn":
        from tabpfn import TabPFNClassifier
        idx = _subsample(len(Xtr), w)
        m = TabPFNClassifier()
        m.fit(np.nan_to_num(Xtr[idx]), ytr[idx])
        return m, m.predict_proba(np.nan_to_num(Xte))[:, 1]

    if model == "tabpfn_reg":
        if ytr_cont is None:
            raise ValueError("tabpfn_reg 에는 연속 타깃(ytr_cont)이 필요합니다.")
        from tabpfn import TabPFNRegressor
        idx = _subsample(len(Xtr), w)
        # 수익률 꼬리가 두꺼우면 회귀가 극단값만 쫓는다. 1/99 분위로 자른다.
        yc = np.asarray(ytr_cont, dtype=np.float64)[idx]
        lo, hi = np.nanquantile(yc, [0.01, 0.99])
        m = TabPFNRegressor()
        m.fit(np.nan_to_num(Xtr[idx]), np.clip(yc, lo, hi))
        return m, _to_pseudo_prob(m.predict(np.nan_to_num(Xte)))

    from sklearn.ensemble import HistGradientBoostingClassifier
    m = HistGradientBoostingClassifier(
        max_depth=4, max_iter=300, learning_rate=0.05,
        min_samples_leaf=50, l2_regularization=1.0,
        early_stopping=True, n_iter_no_change=30, validation_fraction=0.15,
        random_state=RANDOM_SEED)
    m.fit(Xtr, ytr, sample_weight=w)
    return m, m.predict_proba(Xte)[:, 1]


def split(df: pd.DataFrame, test_from: str = "2022-01-01"):
    """
    시간 분할. 종목 분할이 아니다.

    시계열에서 무작위·종목 분할을 하면 **미래를 보고 과거를 맞히게** 된다.
    실제 사용은 "오늘까지 배워서 내일을 맞힌다"이므로 시간으로 잘라야 한다.
    """
    cut = pd.Timestamp(test_from)
    return df[df["date"] < cut].copy(), df[df["date"] >= cut].copy()


def train(df: pd.DataFrame | None = None, target: str = "y_up_excess",
          model: str = "gbm", recency: bool = True, news: bool = True,
          test_from: str = "2022-01-01", xf_dir=None,
          save: bool = True, verbose: bool = True) -> dict:
    """모델 하나 학습 + 홀드아웃 평가."""
    if df is None:
        df = build(xf_dir, verbose=verbose)
    tr, te = split(df, test_from)
    cols = PRICE_FEATURES + NEWS_FEATURES
    cols = [c for c in cols if c in df.columns]

    Xtr = tr[cols].to_numpy(np.float32)
    Xte = te[cols].to_numpy(np.float32)
    ytr = tr[target].to_numpy(int)
    yte = te[target].to_numpy(int)
    w = sample_weight(tr, recency=recency, news=news)
    cont = tr[target.replace("y_up_", "y_ret_")].to_numpy(float)

    m, p = _fit(Xtr, ytr, w, Xte, model=model, ytr_cont=cont)
    res = _metrics(yte, p)

    if verbose:
        print(f"\n[{model}] target={target} "
              f"recency={recency} news={news}")
        print(f"  학습 {len(tr):,}행 (~{tr['date'].max():%Y-%m-%d}) / "
              f"평가 {len(te):,}행 ({te['date'].min():%Y-%m-%d}~)")
        for k, v in res.items():
            print(f"  {k:12s} {v}")
        edge = res["HR"] - res["무지성기준선"]
        print(f"  → 무지성 대비 {edge:+.4f}"
              + ("  ✅ 넘음" if edge > 0 else "  ❌ 못 넘음"))

    if save:
        import pickle
        MODEL_DIR.mkdir(parents=True, exist_ok=True)
        with open(MODEL_PATH, "wb") as f:
            pickle.dump({"model": m, "features": cols, "target": target}, f)
        META_PATH.write_text(json.dumps(
            {"target": target, "model": model, "features": cols,
             "recency": recency, "news": news, "test_from": test_from,
             "n_train": int(len(tr)), "n_test": int(len(te)),
             "metrics": res}, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"metrics": res, "n_train": int(len(tr)), "n_test": int(len(te)),
            "features": cols}


def ablate(df: pd.DataFrame | None = None, xf_dir=None,
           models: "list[str] | None" = None,
           save: bool = True, verbose: bool = True) -> pd.DataFrame:
    """
    피처군 × 가중 × 라벨 어블레이션.

    이 표 하나로 세 질문에 답한다.
        1. 뉴스가 가격 위에 값을 더하는가
        2. 최근 가중·뉴스 가중이 실제로 도움이 되는가
        3. 어느 라벨(raw/excess)로 봐도 결론이 같은가
    """
    if df is None:
        df = build(xf_dir, verbose=verbose)

    price = [c for c in PRICE_FEATURES if c in df.columns]
    newsf = [c for c in NEWS_FEATURES if c in df.columns]
    groups = {"가격만": price, "뉴스만": newsf, "뉴스+가격": price + newsf}
    weights = {"가중없음": (False, False), "최근가중": (True, False),
               "뉴스가중": (False, True), "최근+뉴스": (True, True)}
    models = models or ["gbm"]

    tr, te = split(df)
    rows = []
    for target in ("y_up_excess", "y_up_raw"):
        ytr, yte = tr[target].to_numpy(int), te[target].to_numpy(int)
        cont = tr[target.replace("y_up_", "y_ret_")].to_numpy(float)
        for mdl in models:
            for gname, cols in groups.items():
                Xtr = tr[cols].to_numpy(np.float32)
                Xte = te[cols].to_numpy(np.float32)
                for wname, (rec, nw) in weights.items():
                    # TabPFN 은 평가 1회에 3분 넘게 걸린다(2.6만행 × 8ms).
                    # 전 조합을 돌리면 40분이다. 가중 효과는 GBM 쪽에서 이미
                    # 재고 있으므로 여기서는 최선 설정 한 줄만 남긴다.
                    if mdl.startswith("tabpfn") and wname != "최근+뉴스":
                        continue
                    w = sample_weight(tr, recency=rec, news=nw)
                    try:
                        _, p = _fit(Xtr, ytr, w, Xte, model=mdl, ytr_cont=cont)
                    except Exception as e:
                        if verbose:
                            print(f"  {mdl} 건너뜀 — {type(e).__name__}: "
                                  f"{str(e).splitlines()[0][:90]}")
                        break           # 이 모델은 못 쓴다. 조합 전체를 건너뛴다
                    rows.append({"라벨": target, "모델": mdl, "피처": gname,
                                 "가중": wname, **_metrics(yte, p)})
                    if verbose:
                        r = rows[-1]
                        print(f"  {target:13s} {mdl:10s} {gname:9s} {wname:9s} "
                              f"HR {r['HR']:.4f}  균형 {r['균형정확도']:.4f}  "
                              f"AUC {r['ROC-AUC']:.4f}")

    out = pd.DataFrame(rows)
    if save:
        d = OUTPUT_DIR / "validation"
        d.mkdir(parents=True, exist_ok=True)
        out.to_csv(d / "11_overseas_ablation.csv", index=False,
                   encoding="utf-8-sig")
    if verbose:
        _verdict(out)
    return out


def _verdict(ab: pd.DataFrame) -> None:
    """어블레이션 표를 사람이 읽는 결론으로 바꾼다."""
    print("\n" + "=" * 74)
    print("해외 모델 — 결론")
    print("=" * 74)
    for target in ab["라벨"].unique():
        sub = ab[ab["라벨"] == target]
        best = sub.loc[sub["ROC-AUC"].idxmax()]
        base = float(sub["무지성기준선"].iloc[0])
        print(f"\n[{target}]  무지성 기준선 HR {base:.4f}")
        print(f"  최고: {best['피처']} · {best['가중']} · {best['모델']}")
        print(f"        HR {best['HR']:.4f} · 균형 {best['균형정확도']:.4f} "
              f"· AUC {best['ROC-AUC']:.4f}")
        print(f"  기준선 대비 HR {best['HR'] - base:+.4f}")

        p_only = sub[sub["피처"] == "가격만"]["ROC-AUC"].max()
        both = sub[sub["피처"] == "뉴스+가격"]["ROC-AUC"].max()
        print(f"  뉴스 기여: AUC {p_only:.4f} → {both:.4f} ({both - p_only:+.4f})")

        w_off = sub[sub["가중"] == "가중없음"]["ROC-AUC"].max()
        w_on = sub[sub["가중"] == "최근+뉴스"]["ROC-AUC"].max()
        print(f"  가중 기여: AUC {w_off:.4f} → {w_on:.4f} ({w_on - w_off:+.4f})")


# ==========================================================================
# 5. 추론
# ==========================================================================
def load():
    """저장된 모델. 없으면 None."""
    if not MODEL_PATH.is_file():
        return None
    import pickle
    with open(MODEL_PATH, "rb") as f:
        return pickle.load(f)


def predict(rows: pd.DataFrame, bundle=None) -> np.ndarray:
    """피처 표 → 상승 확률. `build()` 가 만든 열 이름을 그대로 쓴다."""
    bundle = bundle or load()
    if bundle is None:
        raise RuntimeError("학습된 해외 모델이 없습니다. "
                           "`python cli.py overseas --train` 을 먼저 실행하세요.")
    X = rows.reindex(columns=bundle["features"]).to_numpy(np.float32)
    return bundle["model"].predict_proba(X)[:, 1]
