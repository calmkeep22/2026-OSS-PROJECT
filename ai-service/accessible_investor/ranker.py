"""
학습된 알림 랭커 — 정밀도를 올리는 본체.

왜 필요한가
----------
지금까지 "무엇을 알릴까"는 손으로 짠 규칙이 정했다.
채널별 분위수 임계값 + 절대 크기 게이트 + 점수제 등급.
측정해 보니 각 요소가 정밀도를 나누기는 하는데(등급 A 0.096 / C 0.018),
**요소들을 어떻게 섞을지는 아무도 최적화하지 않았다.** 내가 눈으로 정한 가중치다.

그런데 우리에겐 라벨이 있다 — `validation.forward_opportunity`,
"이 봉 이후 30분 안에 단타 크기 움직임이 있었는가". 라벨이 있으면 학습하면 된다.

    후보 봉  →  [랭커]  →  P(기회)  →  상위 N개만 알림

알림 개수(예산)는 그대로 두고 **고르는 방법만** 바꾼다.
같은 시끄러움에서 정밀도가 오른다.

정직한 평가
----------
**종목 단위로 학습/평가를 나눈다.** 같은 종목의 다른 구간으로 나누면
종목 고유 성질을 외워 성능이 부풀려진다. 배포 시나리오는 "처음 보는 종목에
바로 쓰는 것"이므로 평가도 그래야 한다 (02가 쓴 방식과 같다).

`sklearn`만 쓴다. 별도 의존성(xgboost/lightgbm)을 늘리지 않는다 —
오픈소스 제출물에서 설치 부담은 그대로 진입 장벽이다.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from . import anomaly as A
from .config import CHANNELS, FLOOR_Z, HORIZONS, RANDOM_SEED
from .models import MODEL_DIR

# 후보 생성 임계값. 최종 임계값보다 **낮게** 잡아 넓게 건진 뒤 랭커가 고른다.
# 여기서 놓친 봉은 랭커가 아무리 좋아도 되살릴 수 없으므로 넉넉해야 한다.
CANDIDATE_Z = 2.0
FIRED_PREFIX = "f_"

FEATURES = [
    "excess_z", "vol_z", "vwap_z", "thrust_z", "hilo_z", "gap_z", "rvol_z",
    "move_sigma", "vol_mult", "vwap_dev", "day_ret", "mkt_ret", "rvol",
    "n_fired", "max_abs_z", "top_priority", "bar_of_day", "hour",
    "deep_rank", "deep_price", "deep_vol",
]


# --------------------------------------------------------------------------
# 후보 + 피처
# --------------------------------------------------------------------------
def candidates(feat: pd.DataFrame, horizon: str = "intraday",
               deep: np.ndarray | None = None,
               z_threshold: float = CANDIDATE_Z) -> pd.DataFrame:
    """후보 봉 추출 + 랭커 입력 피처 구성."""
    cols = {n: CHANNELS[n][0] for n in CHANNELS if A._channel_enabled(n, horizon)}
    present = {n: c for n, c in cols.items() if c in feat.columns}
    if not present:
        return pd.DataFrame()

    # 전부 넘파이로 돌린다. pandas 경로는 실시간 루프에서 병목이 된다 —
    # 컬럼마다 reindex/align이 걸려 120봉짜리 호출이 4,285봉짜리보다 느려졌다.
    names = list(present)
    Zm = feat[[present[n] for n in names]].to_numpy(dtype=np.float64, copy=True)
    Zm[~np.isfinite(Zm)] = np.nan
    absZ = np.abs(Zm)

    with np.errstate(invalid="ignore"):
        F = absZ > z_threshold
        # 방향 제한이 걸린 채널은 음수 쪽을 후보에서 뺀다 (거래량 급감은 사건이 아니다)
        for j, n in enumerate(names):
            if CHANNELS[n][4] == "up":
                F[:, j] &= Zm[:, j] > 0
    F = np.nan_to_num(F.astype(float), nan=0.0).astype(bool)

    mv = F.any(axis=1)
    if not mv.any():
        return pd.DataFrame()

    # 우선순위: 켜진 채널 중 가장 높은 등급.
    #
    # 처음엔 후보마다 파이썬 루프로 `fired.loc[t, n]`을 훑었다. 그게
    # 실시간 경로의 병목이었다 — 30종목 한 바퀴에 2.3초가 나왔고, 그중 대부분이 여기였다.
    # 켜지지 않은 채널을 큰 값으로 채운 행렬에서 행 최소를 취하면 같은 결과가
    # 넘파이 한 번에 나온다.
    pr_row = np.array([CHANNELS[n][1] for n in names], dtype=np.int16)
    prio_all = np.where(F, pr_row[None, :], np.int16(99)).min(axis=1)
    prio_all = np.where(prio_all == 99, 3, prio_all)

    # 날짜 경계. `index.date`는 파이썬 date 객체를 n개 만들어 느리다 —
    # datetime64를 하루 단위로 자르면 같은 결과가 넘파이 안에서 끝난다.
    d = feat.index.to_numpy().astype("datetime64[D]")
    # 하루 안에서 몇 번째 봉인가 — groupby 없이 경계 위치로 계산한다
    new_day = np.empty(len(d), dtype=bool)
    new_day[0] = True
    new_day[1:] = d[1:] != d[:-1]
    starts = np.flatnonzero(new_day)
    bar_of_day = np.arange(len(d)) - np.repeat(starts, np.diff(np.append(starts, len(d))))

    idx = feat.index[mv]
    # 컬럼별 reindex 대신 **위치 인덱싱**. 인덱스 정렬 비용이 통째로 사라진다.
    data = {}
    for c in ("excess_z", "vol_z", "vwap_z", "thrust_z", "hilo_z", "gap_z",
              "rvol_z", "move_sigma", "vol_mult", "vwap_dev", "day_ret",
              "mkt_ret", "rvol"):
        data[c] = (feat[c].to_numpy(dtype=np.float64)[mv] if c in feat.columns
                   else np.full(mv.sum(), np.nan))
    data["n_fired"] = F[mv].sum(axis=1)
    with np.errstate(invalid="ignore", all="ignore"):
        data["max_abs_z"] = np.nanmax(np.where(np.isnan(absZ[mv]), -np.inf,
                                               absZ[mv]), axis=1)
    data["top_priority"] = prio_all[mv]
    data["bar_of_day"] = bar_of_day[mv]
    data["hour"] = (idx.hour.to_numpy() if isinstance(idx, pd.DatetimeIndex)
                    else np.zeros(mv.sum()))
    out = pd.DataFrame(data, index=idx)

    # 2단 탐지기 점수는 **순위로** 넣는다. 원시 MSE는 종목마다 스케일이 달라
    # 그대로 넣으면 종목 판별기가 되어버린다.
    # deep이 dict면 축별 잔차까지 들어온다. 평균 하나만 쓰면
    # "무엇이 놀라웠는지"가 사라져 기존 z-score와 중복이 된다.
    if isinstance(deep, dict):
        from .models import rank_normalize
        out["deep_rank"] = rank_normalize(deep.get("mean"))[mv]
        out["deep_price"] = rank_normalize(deep.get("excess_n"))[mv]
        out["deep_vol"] = rank_normalize(deep.get("vol_n"))[mv]
    elif deep is not None:
        from .models import rank_normalize
        out["deep_rank"] = rank_normalize(deep)[mv]
        out["deep_price"] = np.nan
        out["deep_vol"] = np.nan
    else:
        out["deep_rank"] = np.nan
        out["deep_price"] = np.nan
        out["deep_vol"] = np.nan

    # 채널 조합을 보존한다 (문안 생성용). **컬럼으로** 남기는 게 중요하다 —
    # 처음엔 `out.attrs`에 DataFrame을 넣었는데, pandas가 concat 때 attrs를
    # 비교하려다 "Can only compare identically-labeled DataFrame" 으로 죽는다.
    for j, name in enumerate(names):
        out[FIRED_PREFIX + name] = F[mv, j]
    return out


def build_dataset(panel: dict, horizon: str = "intraday",
                  sigma_frac: float = 1.0, use_deep: bool = False,
                  verbose: bool = False) -> pd.DataFrame:
    """패널 → 학습용 테이블. 종목 코드를 남겨 종목 단위 분할을 가능하게 한다."""
    from .validation import forward_opportunity

    rows = []
    for code, (name, feat) in panel.items():
        deep = None
        if use_deep:
            from .models import score_detail
            deep = score_detail(feat)
        cand = candidates(feat, horizon, deep=deep)
        if not len(cand):
            continue
        label = forward_opportunity(feat, horizon, sigma_frac=sigma_frac)
        cand = cand.copy()
        cand["y"] = label.reindex(cand.index).astype(float)
        cand["code"] = code
        cand["ts"] = cand.index
        rows.append(cand.dropna(subset=["y"]))
    if not rows:
        return pd.DataFrame()
    df = pd.concat(rows, ignore_index=True)
    if verbose:
        print(f"후보 {len(df):,}건 · 양성 {int(df['y'].sum()):,}건 "
              f"({df['y'].mean()*100:.2f}%) · 종목 {df['code'].nunique()}개")
    return df


# --------------------------------------------------------------------------
# 학습
# --------------------------------------------------------------------------
def split_by_stock(df: pd.DataFrame, test_frac: float = 0.35,
                   seed: int = RANDOM_SEED):
    codes = np.array(sorted(df["code"].unique()))
    rng = np.random.default_rng(seed)
    rng.shuffle(codes)
    n_test = max(1, int(len(codes) * test_frac))
    test_codes = set(codes[:n_test])
    te = df[df["code"].isin(test_codes)]
    tr = df[~df["code"].isin(test_codes)]
    return tr, te, sorted(test_codes)


def usable_features(df: pd.DataFrame,
                    features: list[str] | None = None) -> list[str]:
    """
    실제로 학습에 쓸 수 있는 피처만 남긴다.

    **전부 NaN이거나 값이 하나뿐인 컬럼을 빼야 한다.**
    sklearn의 HistGradientBoosting은 비닝 단계에서
    `sliding_window_view(distinct_values, 2)`를 부르는데,
    고유값이 0~1개면 `window shape cannot be larger than input array shape`로 죽는다.
    에러 메시지가 numpy 내부라 원인이 "내 피처가 상수"라는 걸 바로 알 수 없다.

    2단 탐지기를 끄고 학습하면 deep_* 3개가 전부 NaN이 되어 정확히 이 일이 난다.
    """
    cols = features or FEATURES
    out = []
    for c in cols:
        if c not in df.columns:
            continue
        v = df[c].to_numpy(dtype=np.float64, na_value=np.nan)
        if np.unique(v[np.isfinite(v)]).size >= 2:
            out.append(c)
    return out


def recency_weight(ts: pd.Series, half_life_days: float = 20.0) -> np.ndarray:
    """
    최근 표본에 더 큰 가중치. `w = 0.5 ** (경과일 / 반감기)`

    왜 넣었나
    ---------
    해외 데이터(미국 100종목 × 4년) 어블레이션에서 **유일하게 재현된 개선**이
    이것이었다. 뉴스 피처를 넣으면 ROC-AUC 가 0.5105 → 0.4991 로 떨어졌지만,
    최근 가중은 0.5017 → 0.5105 (+0.0088) 로 올랐다.
    (`outputs/validation/11_overseas_ablation.csv`)

    이유는 그럴듯하다 — 시장의 변동성 체제와 거래 시간대 습성은 몇 달 단위로
    변한다. 두 달 전 표본과 어제 표본을 같은 무게로 두면 지나간 체제를 계속
    학습한다.

    반감기 20일은 분봉 학습 구간(약 60거래일)의 1/3이다. 더 짧게 잡으면
    실질 표본이 급격히 줄고, 더 길게 잡으면 가중이 사실상 없는 것과 같아진다.
    아래 `compare_recency()` 로 실제 효과를 재고 나서 켤지 정한다.
    """
    t = pd.to_datetime(ts)
    age = (t.max() - t).dt.total_seconds() / 86400.0
    w = 0.5 ** (age.to_numpy(float) / max(half_life_days, 1e-6))
    return w / w.mean()             # 평균 1로 정규화 — 학습률 스케일을 유지한다


def train(df: pd.DataFrame, features: list[str] | None = None,
          recency: bool = True, half_life_days: float = 20.0,
          verbose: bool = True):
    """
    HistGradientBoosting 이진 분류.

    표본이 수천 건이고 피처가 19개다. 깊은 트리는 바로 과적합하므로
    max_depth를 얕게 두고 잎 최소 표본을 크게 잡는다.
    `class_weight="balanced"`는 쓰지 않는다 — 우리가 원하는 건 **순위**이지
    임계값 0.5의 분류가 아니라서 굳이 분포를 흔들 이유가 없다.

    recency
        최근 표본 가중. `recency_weight()` 주석 참조.
    """
    from sklearn.ensemble import HistGradientBoostingClassifier

    cols = usable_features(df, features)
    dropped = [c for c in (features or FEATURES) if c not in cols]
    X = df[cols].to_numpy(dtype=np.float32)
    y = df["y"].to_numpy(dtype=int)

    w = None
    if recency and "ts" in df.columns:
        w = recency_weight(df["ts"], half_life_days)

    model = HistGradientBoostingClassifier(
        max_depth=4, max_iter=250, learning_rate=0.06,
        min_samples_leaf=60, l2_regularization=1.0,
        early_stopping=True, validation_fraction=0.15,
        random_state=RANDOM_SEED)
    model.fit(X, y, sample_weight=w)
    model._ai_features = cols
    model._ai_recency = bool(w is not None)
    if verbose:
        print(f"학습 완료 — 표본 {len(y):,} · 양성률 {y.mean()*100:.2f}% "
              f"· 피처 {len(cols)}개 · 반복 {model.n_iter_}"
              + (f" · 최근가중 반감기 {half_life_days:.0f}일" if w is not None else ""))
        if dropped:
            print(f"  제외된 피처(값이 하나뿐): {', '.join(dropped)}")
    return model


def compare_recency(tr: pd.DataFrame, te: pd.DataFrame,
                    half_lives=(10.0, 20.0, 40.0),
                    features: list[str] | None = None,
                    verbose: bool = True) -> pd.DataFrame:
    """
    최근 가중을 켤지 끌지 **재보고 정한다.**

    해외 데이터에서 도움이 됐다고 국내 분봉에서도 도움이 되리란 보장은 없다.
    데이터 성격이 다르고(4년 일봉 vs 60일 분봉), 표본 밀도도 다르다.
    """
    from sklearn.metrics import average_precision_score, roc_auc_score

    cols = usable_features(tr, features)
    Xte = te[cols].to_numpy(dtype=np.float32)
    yte = te["y"].to_numpy(dtype=int)

    rows = []
    for hl in (None, *half_lives):
        m = train(tr, features=features, recency=hl is not None,
                  half_life_days=hl or 20.0, verbose=False)
        p = m.predict_proba(Xte)[:, 1]
        rows.append({"설정": "가중없음" if hl is None else f"반감기 {hl:.0f}일",
                     "PR-AUC": round(float(average_precision_score(yte, p)), 4),
                     "ROC-AUC": round(float(roc_auc_score(yte, p)), 4)})
        if verbose:
            r = rows[-1]
            print(f"  {r['설정']:12s} PR-AUC {r['PR-AUC']:.4f}  "
                  f"ROC-AUC {r['ROC-AUC']:.4f}")
    out = pd.DataFrame(rows)
    if verbose:
        best = out.loc[out["PR-AUC"].idxmax()]
        base = out.loc[out["설정"] == "가중없음", "PR-AUC"].iloc[0]
        gain = best["PR-AUC"] - base
        print(f"  → 최고 {best['설정']} (PR-AUC {gain:+.4f})"
              + ("  ✅ 켠다" if gain > 0.002 else "  ❌ 차이 없음 — 끈다"))
    return out


def predict(model, cand: pd.DataFrame,
            features: list[str] | None = None) -> np.ndarray:
    """
    피처 목록은 **모델이 들고 있는 것**을 쓴다.

    모듈 전역 FEATURES를 쓰면 학습 때와 서빙 때가 어긋난다.
    2단 탐지기를 뺀 랭커를 배포하면서 전역 목록에는 deep_* 3개가 남아 있어
    "X has 21 features, but expecting 18"로 죽었다.
    모델과 함께 저장한 목록을 따라가면 이 종류의 실수가 구조적으로 불가능해진다.
    """
    cols = features or getattr(model, "_ai_features", None) or FEATURES
    X = cand.reindex(columns=cols).to_numpy(dtype=np.float32)
    return model.predict_proba(X)[:, 1]


def save(model, meta: dict, name: str = "ranker_5m"):
    import pickle

    # 피처 목록을 모델 객체에 붙여 저장한다. json과 따로 놀 수 없게 하기 위해서다.
    model._ai_features = list(meta.get("features") or FEATURES)
    with open(MODEL_DIR / f"{name}.pkl", "wb") as f:
        pickle.dump(model, f)
    (MODEL_DIR / f"{name}.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return MODEL_DIR / f"{name}.pkl"


_LOADED: dict = {}


def load(name: str = "ranker_5m"):
    if name in _LOADED:
        return _LOADED[name]
    import pickle

    pkl, js = MODEL_DIR / f"{name}.pkl", MODEL_DIR / f"{name}.json"
    if not pkl.exists():
        return None, None
    with open(pkl, "rb") as f:
        model = pickle.load(f)
    meta = json.loads(js.read_text(encoding="utf-8")) if js.exists() else {}
    if not hasattr(model, "_ai_features") and meta.get("features"):
        model._ai_features = list(meta["features"])
    _LOADED[name] = (model, meta)
    return model, meta


# --------------------------------------------------------------------------
# 평가 — 같은 알림 수에서 얼마나 나아졌는가
# --------------------------------------------------------------------------
def evaluate_at_budget(te: pd.DataFrame, prob: np.ndarray,
                       baseline_col: str = "max_abs_z",
                       n_days: int = 60, budgets=(0.25, 0.5, 1.0)) -> pd.DataFrame:
    """
    **알림 수를 고정하고** 랭커와 기존 방식의 정밀도를 비교한다.

    정밀도만 비교하면 "적게 알리면 당연히 정확하다"는 함정에 빠진다.
    같은 개수를 고를 때 누가 더 잘 고르는지가 유일하게 공정한 비교다.
    """
    n_stocks = te["code"].nunique()
    rows = []
    for b in budgets:
        k = max(1, int(round(b * n_days * n_stocks)))
        for label, s in (("랭커", prob),
                         ("기존(|z| 최대)", te[baseline_col].to_numpy()),
                         ("기존(절대크기)", te["move_sigma"].fillna(0).to_numpy())):
            idx = np.argsort(-np.nan_to_num(s, nan=-1e9))[:k]
            hit = te["y"].to_numpy()[idx]
            rows.append({
                "예산(종목·일)": b, "방식": label, "선택수": k,
                "정밀도": round(float(hit.mean()), 4),
                "리프트": round(float(hit.mean() / te["y"].mean()), 2),
            })
    return pd.DataFrame(rows).pivot(index="예산(종목·일)", columns="방식",
                                    values=["정밀도", "리프트"])


# --------------------------------------------------------------------------
# 실사용 — 예산에 맞춰 고르기
# --------------------------------------------------------------------------
def calibrate_probability(prob: np.ndarray, budget_per_day: float,
                          n_days: float, floor: float = 0.0) -> float:
    """
    확률 임계값을 **알림 예산**에서 역산한다.

    z-score 때와 같은 논리다. 상위 K개를 고르는 게 아니라 분위수를 쓴다 —
    실시간에서는 미래를 모르므로 "상위 K개"를 알 수 없기 때문이다.
    과거 분포에서 목표 발생률에 해당하는 확률을 임계값으로 삼고,
    그 값을 다음 봉부터 적용한다.
    """
    p = np.asarray(prob, dtype=float)
    p = p[np.isfinite(p)]
    if len(p) < 20:
        return max(floor, 0.5)
    target = max(budget_per_day * max(n_days, 1), 1)
    q = 1.0 - min(target / len(p), 0.99)
    return float(max(np.quantile(p, q), floor))


def grade_from_probability(p: float, hi: float, mid: float) -> str:
    """
    확률로 신뢰도 등급을 매긴다.

    손으로 짠 점수제를 대체한다. 점수제는 두 번 고쳐서 겨우 단조가 됐는데,
    확률은 정의상 단조다 — 높을수록 기회일 가능성이 높다.
    등급 경계는 학습 분포의 분위수에서 잡는다.
    """
    if p >= hi:
        return "A"
    if p >= mid:
        return "B"
    return "C"


CALIB_TAIL = 1560          # 5분봉 20거래일. 임계값 보정에 쓰는 과거 구간


_THR_CACHE: dict = {}


def select(feat: pd.DataFrame, horizon: str = "intraday",
           budget: float | None = None, model=None, meta=None,
           deep=None, start_ts: pd.Timestamp | None = None,
           calib_tail: int | None = CALIB_TAIL,
           scan_tail: int | None = None,
           cache_key: str | None = None) -> pd.DataFrame:
    """
    후보 → 랭커 점수 → 예산 임계값 통과분.

    반환 컬럼: prob, grade, 그리고 후보 피처 전부.
    모델이 없으면 **빈 결과가 아니라 None**을 준다 — 호출부가 규칙 경로로
    폴백해야 한다는 신호다. 오프라인·미학습 환경에서 알림이 0건이 되면 안 된다.
    """
    from .config import ALERT_BUDGET

    if model is None:
        model, meta = load()
    if model is None:
        return None

    # 실시간 루프에서는 **최근 구간만** 본다.
    # 전 구간(4,285봉)에서 후보를 다시 만들면 종목당 270ms가 든다.
    # 관심종목 30개면 8초 — 봉 주기(5분) 안에는 들어가지만, 화면을 못 보는 사용자에게
    # 8초 무응답은 길다. 최근 구간만 만들면 종목당 밀리초 단위가 된다.
    #
    # 임계값은 그 구간으로 못 잡는다(표본이 부족하다) → 캐시에서 가져오고,
    # 없으면 전 구간으로 한 번 잡아 캐시한다. 실사용에서는 장 시작 전 1회다.
    src = feat.tail(scan_tail) if scan_tail else feat
    cand = candidates(src, horizon, deep=deep)
    if not len(cand):
        return cand
    prob = predict(model, cand, (meta or {}).get("features"))
    cand = cand.copy()
    cand["prob"] = prob

    if scan_tail and cache_key is not None and cache_key in _THR_CACHE:
        thr, hi, mid = _THR_CACHE[cache_key]
        out = cand[cand["prob"] >= thr].copy()
        out["grade"] = [grade_from_probability(p, hi, mid) for p in out["prob"]]
        out.attrs["threshold"] = thr
        if start_ts is not None:
            out = out[out.index > start_ts]
        return out

    # 임계값은 **과거 분포에서만** 뽑는다.
    # 전 구간 분포로 잡으면 "오늘 알림을 정하는 데 다음 달 데이터를 쓴" 셈이 되어
    # 백테스트 성능이 실제보다 좋아 보인다. 실시간에서는 애초에 불가능한 일이기도 하다.
    calib = cand.iloc[:-1].tail(calib_tail) if calib_tail else cand
    pcal = prob[:-1][-calib_tail:] if calib_tail else prob
    if len(pcal) < 50:
        pcal = prob
        calib = cand
    n_days = max(pd.Series(calib.index.date).nunique(), 1) if isinstance(
        calib.index, pd.DatetimeIndex) else 1
    budget = budget if budget is not None else ALERT_BUDGET[horizon]
    thr = calibrate_probability(pcal, budget, n_days)
    hi = float(np.quantile(pcal, 0.97)) if len(pcal) > 30 else thr
    mid = float(np.quantile(pcal, 0.90)) if len(pcal) > 30 else thr

    if cache_key is not None:
        _THR_CACHE[cache_key] = (thr, hi, mid)

    out = cand[cand["prob"] >= thr].copy()
    out["grade"] = [grade_from_probability(p, hi, mid) for p in out["prob"]]
    out.attrs["threshold"] = thr
    if start_ts is not None:
        out = out[out.index > start_ts]
    return out


def feature_importance(model, te: pd.DataFrame, n_repeats: int = 5,
                       features: list[str] | None = None) -> pd.DataFrame:
    """순열 중요도. 어떤 피처가 실제로 값을 하는지 — 특히 2단 탐지기 기여도."""
    from sklearn.inspection import permutation_importance
    from sklearn.metrics import average_precision_score

    cols = features or getattr(model, "_ai_features", None) or FEATURES
    X = te[cols].to_numpy(dtype=np.float32)
    y = te["y"].to_numpy(dtype=int)
    r = permutation_importance(
        model, X, y, n_repeats=n_repeats, random_state=RANDOM_SEED,
        scoring=lambda m, Xv, yv: average_precision_score(
            yv, m.predict_proba(Xv)[:, 1]))
    return (pd.DataFrame({"피처": cols, "중요도": r.importances_mean.round(5)})
            .sort_values("중요도", ascending=False).reset_index(drop=True))
