"""
뉴스 교차언어 전이 — 미국 뉴스로 배우고 한국에 쓴다.

풀려는 문제
===========
국내 뉴스 아카이브는 **9일치**뿐이다(구글 뉴스 RSS가 7일까지만 준다).
종목당 학습 구간이 1,871일인데 뉴스가 있는 날이 5일 — 커버리지 0.27%다.
그 상태로 뉴스 피처를 학습에 넣으면 모델이 "이 행이 최근 5일 중 하나인가"를
외워 버린다(실측: 예측 확률이 0.0%/100.0% 로 포화).

한편 xforecast 아카이브에는 **미국 100종목 × 2019~2022년 뉴스**가 있다.
종목당 약 1,044일이다.

왜 단어 매칭이 필요 없나
========================
"같은 뜻인데 언어가 다른 단어를 매칭해서 파인튜닝" 이 자연스러운 발상이지만,
**이 구조에서는 그럴 필요가 없다.** 감성 모델이 이미 언어별로 따로 있고,
그 출력은 언어와 무관한 숫자이기 때문이다.

    한글 기사 → KR-FinBert-SC ┐
                              ├→ [극성, 기사수, 분산, 긍정비율, …] 9개 숫자
    영문 기사 → FinBERT       ┘

즉 **피처 스키마가 이미 공통어**다. 그래서 미국 데이터로
`뉴스 피처 → 다음날 방향` 매핑을 학습해 두면, 같은 스키마로 계산한 한국
뉴스 피처에 그대로 적용할 수 있다. 번역도 사전 매칭도 끼지 않는다.

전이의 가정과 한계
==================
가정은 하나다 — **"기사가 몰리고 논조가 부정적이면 다음날 약세"** 같은
반응 패턴이 시장을 넘어 어느 정도 공유된다는 것.

이 가정은 검증해야 한다. `evaluate()` 가 미국 홀드아웃에서 먼저 재고,
그 값이 무지성 기준선을 못 넘으면 전이 자체가 무의미하므로
**전이 피처를 쓰지 않는 게 맞다.** 결과를 그대로 리포트에 싣는다.

산출물은 **숫자 하나**(`news_xfer` = 상승 확률)다. 9개 피처를 그대로
국내 모델에 넣으면 커버리지 문제가 그대로 돌아오지만, 하나로 압축하면
"뉴스가 있는 날엔 이 값, 없는 날엔 결측"으로 깔끔하게 다뤄진다.
"""

from __future__ import annotations

import json
import pickle
from pathlib import Path

import numpy as np
import pandas as pd

from .config import PROJECT_ROOT, RANDOM_SEED
from .sentiment import FEATURES as NEWS_FEATURES

MODEL_PATH = PROJECT_ROOT / "models" / "news_transfer.pkl"
META_PATH = PROJECT_ROOT / "models" / "news_transfer.json"


# ==========================================================================
def build_us_table(verbose: bool = True) -> pd.DataFrame:
    """
    xforecast 아카이브 → (종목·날짜 × 뉴스피처 + 다음날 방향).

    `overseas.py` 가 이미 만들어 둔 패널을 재사용한다. 거기서 뉴스 피처는
    사전 방식(`lexicon.py`)으로 계산돼 있다 — 47만 건에 BERT를 돌릴 수 없어서다.

    ⚠️ 사전과 BERT는 척도가 다르다. 그래서 학습·적용 양쪽에서
    **종목별 표준화값**(`news_pol_z`, `news_n_z`)을 함께 쓴다. 원값만 쓰면
    미국(사전)에서 배운 임계값이 한국(BERT)에 그대로 안 맞는다.
    """
    from . import overseas as OV

    cached = OV.CACHE_DIR / "panel.parquet"
    if cached.is_file():
        df = pd.read_parquet(cached)
    else:
        df = OV.build(verbose=verbose)
        cached.parent.mkdir(parents=True, exist_ok=True)
        df.to_parquet(cached, index=False)

    # overseas 의 열 이름을 sentiment.FEATURES 스키마로 맞춘다.
    ren = {"n_total": "news_n", "pol_all": "news_pol_mean",
           "neg_all": "news_neg_ratio", "pol_spread": "news_pol_std",
           "n_total_z": "news_n_z"}
    out = df.rename(columns=ren).copy()

    if "news_pol_z" not in out.columns:
        g = out.groupby("ticker")["news_pol_mean"]
        out["news_pol_z"] = ((out["news_pol_mean"] - g.transform("mean"))
                             / g.transform("std").replace(0, np.nan))
    for c in ("news_pol_max", "news_pol_min", "news_pos_ratio"):
        if c not in out.columns:
            src = {"news_pol_max": "pol_target", "news_pol_min": "pol_macro",
                   "news_pos_ratio": "pol_sector"}.get(c)
            out[c] = out[src] if src in out.columns else np.nan

    cols = ["ticker", "date", *NEWS_FEATURES, "y_up_raw", "y_ret_raw"]
    out = out[[c for c in cols if c in out.columns]].copy()
    out = out[out["news_n"] > 0]                 # 뉴스가 없는 날은 배울 게 없다
    if verbose:
        print(f"  미국 뉴스 학습표 {len(out):,}행 · 종목 {out['ticker'].nunique()}")
    return out.dropna(subset=["y_up_raw"])


# ==========================================================================
def train(df: pd.DataFrame | None = None, test_from: str = "2022-01-01",
          save: bool = True, verbose: bool = True) -> dict:
    """
    뉴스 피처만으로 다음날 방향을 학습한다.

    **시간으로 자른다.** 종목으로 자르면 "2022년을 보고 2019년을 맞히는"
    구조가 되어 실제 사용과 다르다.
    """
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.metrics import balanced_accuracy_score, roc_auc_score

    df = build_us_table(verbose=verbose) if df is None else df
    cols = [c for c in NEWS_FEATURES if c in df.columns
            and df[c].notna().any()]
    cut = pd.Timestamp(test_from)
    tr, te = df[df["date"] < cut], df[df["date"] >= cut]
    if len(tr) < 500 or len(te) < 100:
        raise RuntimeError(f"표본 부족 (학습 {len(tr)} / 평가 {len(te)})")

    m = HistGradientBoostingClassifier(
        max_depth=3, max_iter=200, learning_rate=0.05,
        min_samples_leaf=40, l2_regularization=1.0,
        early_stopping=True, n_iter_no_change=25, validation_fraction=0.15,
        random_state=RANDOM_SEED)
    m.fit(tr[cols].to_numpy(np.float32), tr["y_up_raw"].to_numpy(int))

    p = m.predict_proba(te[cols].to_numpy(np.float32))[:, 1]
    y = te["y_up_raw"].to_numpy(int)
    base = float(max(y.mean(), 1 - y.mean()))
    res = {
        "n_train": int(len(tr)), "n_test": int(len(te)),
        "features": cols,
        "적중률": round(float(((p > 0.5).astype(int) == y).mean()), 4),
        "균형정확도": round(float(balanced_accuracy_score(y, (p > 0.5).astype(int))), 4),
        "ROC-AUC": round(float(roc_auc_score(y, p)), 4),
        "무지성기준선": round(base, 4),
    }
    res["쓸만한가"] = bool(res["ROC-AUC"] > 0.52)

    if verbose:
        print("\n" + "=" * 66)
        print("뉴스 전이 모델 — 미국 홀드아웃 평가")
        print("=" * 66)
        for k, v in res.items():
            print(f"  {k:12s} {v}")
        print("  → " + ("전이 피처를 쓴다" if res["쓸만한가"]
                        else "AUC 0.52 미달 — **전이 피처를 쓰지 않는다**"))

    if save:
        MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
        with open(MODEL_PATH, "wb") as f:
            pickle.dump({"model": m, "features": cols}, f)
        META_PATH.write_text(json.dumps(res, ensure_ascii=False, indent=2),
                             encoding="utf-8")
    return res


# ==========================================================================
_CACHE: dict = {}


def load():
    """저장된 전이 모델. 없으면 None."""
    if "m" in _CACHE:
        return _CACHE["m"]
    if not MODEL_PATH.is_file():
        return None
    with open(MODEL_PATH, "rb") as f:
        _CACHE["m"] = pickle.load(f)
    return _CACHE["m"]


def usable() -> bool:
    """미국 홀드아웃에서 기준을 넘었을 때만 전이 피처를 쓴다."""
    if not META_PATH.is_file():
        return False
    try:
        return bool(json.loads(META_PATH.read_text(encoding="utf-8"))
                    .get("쓸만한가"))
    except Exception:
        return False


_HIST: dict[str, pd.DataFrame] = {}


def historic_features(ticker: str) -> pd.DataFrame:
    """
    xforecast 아카이브에서 **그 종목의 과거 뉴스 피처**를 날짜별로 꺼낸다.

    왜 필요한가
    -----------
    구글 뉴스 RSS 는 최근 며칠치만 준다. 그래서 학습 구간(약 6년)의 거의 모든
    행에서 `news_xfer` 가 중립 0.5 로 채워졌고, **열이 사실상 상수**가 되어
    모델이 뉴스에서 배울 것이 없었다. "뉴스를 쓴다"고 적어 놓고 실제로는
    아무것도 안 쓰는 상태였다.

    xforecast 아카이브에는 2019~2022년 미국 종목의 일별 뉴스가 있고, 이는
    우리 학습 구간(2020~2026)과 **2년 넘게 겹친다.** 겹치는 구간만이라도
    실제 값을 넣으면 그 열이 상수를 벗어난다.

    ⚠️ 겹치는 종목은 유니버스 미국 18종목 중 4개(NVDA·TSLA·MU·BAC)뿐이다.
    나머지는 여전히 중립이다. 이 비대칭은 감추지 않고 결과표에 적는다.
    """
    if ticker in _HIST:
        return _HIST[ticker]
    empty = pd.DataFrame()
    try:
        from . import overseas as OV

        cached = OV.CACHE_DIR / "panel.parquet"
        if not cached.is_file():
            _HIST[ticker] = empty
            return empty
        df = pd.read_parquet(cached)
    except Exception:
        _HIST[ticker] = empty
        return empty

    sub = df[df["ticker"] == ticker]
    if not len(sub):
        _HIST[ticker] = empty
        return empty

    ren = {"n_total": "news_n", "pol_all": "news_pol_mean",
           "neg_all": "news_neg_ratio", "pol_spread": "news_pol_std",
           "n_total_z": "news_n_z", "pol_target": "news_pol_max",
           "pol_macro": "news_pol_min", "pol_sector": "news_pos_ratio"}
    out = sub.rename(columns=ren).copy()
    if "news_pol_z" not in out.columns:
        m, sd = out["news_pol_mean"].mean(), out["news_pol_mean"].std()
        out["news_pol_z"] = (out["news_pol_mean"] - m) / (sd or np.nan)
    out = out.set_index(pd.to_datetime(out["date"]).dt.normalize())
    out = out[[c for c in NEWS_FEATURES if c in out.columns]]
    out = out[~out.index.duplicated(keep="last")].sort_index()
    _HIST[ticker] = out
    return out


def apply(news_feat: pd.DataFrame) -> pd.Series:
    """
    뉴스 피처 표 → `news_xfer` (상승 확률).

    뉴스가 없는 날은 NaN 으로 남긴다. 0.5 로 채우면 "중립 신호"가 되는데,
    사실은 "신호 없음"이다. 트리 모델은 NaN 을 결측으로 알아서 처리한다.
    """
    bundle = load()
    if bundle is None or not len(news_feat):
        return pd.Series(np.nan, index=news_feat.index, name="news_xfer")
    cols = bundle["features"]
    X = news_feat.reindex(columns=cols).to_numpy(np.float32)
    ok = np.isfinite(X).any(axis=1)
    out = np.full(len(X), np.nan)
    if ok.any():
        out[ok] = bundle["model"].predict_proba(X[ok])[:, 1]
    return pd.Series(out, index=news_feat.index, name="news_xfer")
