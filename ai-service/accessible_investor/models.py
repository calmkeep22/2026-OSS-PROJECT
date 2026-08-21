"""
2단 딥러닝 탐지기 — 계획에만 있고 구현되지 않았던 부분.

02 어블레이션에서 Transformer-Forecast가 단일 1위(PR-AUC 0.1727)였고
규칙 계층(0.1412)보다 1.22배 정확했다. 다만 **2,088배 느려서**
1단(항상 동작)에는 못 넣고 2단(선택 동작)으로 미뤄뒀는데, 그 2단이 없었다.

여기서 만든다. 장중 축(5분봉)에 맞춰 다시 학습하고, 가중치를 저장하고,
ONNX로 내보내 파이썬 없이도 추론할 수 있게 한다.

설계
----
    과거 W-1봉 → 다음 봉의 피처 예측.  잔차 = 이상 점수.

재구성(AE) 대신 예측(Forecast)을 쓰는 이유는 02에서 측정됐다 —
AE는 이상치를 포함한 윈도우를 통째로 재구성하려 하므로 이상치 자신을
학습해버린다. 예측은 이상치를 **볼 수 없는 위치**에 두므로 그게 안 된다.

    Transformer-Forecast  0.1727
    Transformer-AE        0.1582
    LSTM-AE               0.1561

일반화 검증
----------
02와 같은 방식으로 **종목 단위로 학습/평가를 나눈다.** 같은 종목의 다른 구간으로
나누면 종목 고유 성질을 외워서 성능이 부풀려진다. 배포 시나리오는
"처음 보는 종목에 바로 쓰는 것"이므로 평가도 그래야 한다.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np
import pandas as pd

from .config import HORIZONS, PROJECT_ROOT, RANDOM_SEED

MODEL_DIR = PROJECT_ROOT / "models"
MODEL_DIR.mkdir(parents=True, exist_ok=True)

# 딥러닝 입력 피처. **시간대 정규화를 거친 값**을 쓴다 —
# 원시 수익률을 넣으면 모델이 "9시에는 크다"를 외우느라 용량을 낭비한다.
# 그건 프로파일이 이미 처리했다.
MODEL_FEATURES = ["excess_n", "vol_n", "rvol_n", "vwap_n", "thrust_n", "hilo"]
WINDOW = 36                 # 5분봉 36개 = 3시간. 당일 맥락을 담되 전날까지 끌지 않는다


# --------------------------------------------------------------------------
# 윈도우 만들기
# --------------------------------------------------------------------------
def stock_sequences(feat: pd.DataFrame, window: int = WINDOW,
                    features: list[str] | None = None):
    """
    종목별 표준화 후 슬라이딩 윈도우. (windows, end_indices) 반환.

    표준화를 종목 안에서 하는 게 중요하다. 전체 풀 기준으로 하면
    대형주 스케일이 소형주를 덮는다.
    """
    features = features or MODEL_FEATURES
    cols = [c for c in features if c in feat.columns]
    if len(cols) < 3:
        return None, None
    sub = feat[cols].replace([np.inf, -np.inf], np.nan)
    valid = sub.notna().all(axis=1).to_numpy()
    X = sub[valid].to_numpy(dtype=np.float32)
    if len(X) < window + 5:
        return None, None
    mu, sd = X.mean(0), X.std(0) + 1e-8
    Xs = np.clip((X - mu) / sd, -10, 10).astype(np.float32)
    idx_map = np.flatnonzero(valid)
    wins = np.lib.stride_tricks.sliding_window_view(Xs, window, axis=0)
    wins = np.ascontiguousarray(np.transpose(wins, (0, 2, 1)), dtype=np.float32)
    return wins, idx_map[window - 1:]


def collect_windows(panel: dict, max_per_stock: int = 900,
                    window: int = WINDOW) -> np.ndarray:
    rng = np.random.default_rng(RANDOM_SEED)
    chunks = []
    for item in panel.values():
        feat = item[1] if isinstance(item, tuple) else item
        w, _ = stock_sequences(feat, window)
        if w is None:
            continue
        if len(w) > max_per_stock:
            w = w[rng.choice(len(w), max_per_stock, replace=False)]
        chunks.append(w)
    if not chunks:
        raise ValueError("학습 윈도우를 만들 수 없습니다.")
    return np.concatenate(chunks, axis=0)


# --------------------------------------------------------------------------
# 모델
# --------------------------------------------------------------------------
def _build(n_feat: int, window: int = WINDOW, d_model: int = 48,
           nhead: int = 4, layers: int = 2):
    import torch
    import torch.nn as nn

    class TransformerForecaster(nn.Module):
        """과거 W-1봉 → 다음 봉 피처 예측. 잔차가 이상 점수."""

        def __init__(self):
            super().__init__()
            self.inp = nn.Linear(n_feat, d_model)
            self.pos = nn.Parameter(torch.randn(1, window - 1, d_model) * 0.02)
            layer = nn.TransformerEncoderLayer(
                d_model, nhead, dim_feedforward=d_model * 2, dropout=0.1,
                batch_first=True, norm_first=True)
            self.encoder = nn.TransformerEncoder(layer, layers)
            self.head = nn.Linear(d_model, n_feat)

        def forward(self, x):
            h = self.inp(x[:, :-1]) + self.pos
            h = self.encoder(h)
            return self.head(h[:, -1])

    return TransformerForecaster()


def train(panel: dict, epochs: int = 6, batch: int = 512, lr: float = 1e-3,
          window: int = WINDOW, max_per_stock: int = 900,
          verbose: bool = True):
    """장중 축 Transformer-Forecast 학습."""
    import torch
    import torch.nn as nn

    torch.manual_seed(RANDOM_SEED)
    device = "cuda" if torch.cuda.is_available() else "cpu"

    W = collect_windows(panel, max_per_stock, window)
    n_feat = W.shape[2]
    model = _build(n_feat, window).to(device).train()
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    lossf = nn.MSELoss()
    X = torch.from_numpy(W)
    n = len(X)
    if verbose:
        print(f"학습 윈도우 {W.shape} · device {device}")

    t0 = time.time()
    for ep in range(epochs):
        perm = torch.randperm(n)
        tot = 0.0
        for s in range(0, n, batch):
            xb = X[perm[s:s + batch]].to(device)
            opt.zero_grad()
            loss = lossf(model(xb), xb[:, -1])
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            tot += loss.item() * len(xb)
        if verbose:
            print(f"  epoch {ep+1}/{epochs}  loss {tot/n:.5f}  ({time.time()-t0:.0f}s)")
    return model.eval(), {"n_feat": n_feat, "window": window,
                          "features": MODEL_FEATURES[:n_feat],
                          "n_windows": int(n), "epochs": epochs,
                          "trained_at": pd.Timestamp.now().isoformat()}


def save(model, meta: dict, name: str = "tf_forecast_5m"):
    import torch

    torch.save(model.state_dict(), MODEL_DIR / f"{name}.pt")
    (MODEL_DIR / f"{name}.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return MODEL_DIR / f"{name}.pt"


_LOADED: dict = {}


def load(name: str = "tf_forecast_5m"):
    """학습된 모델을 프로세스당 한 번만 올린다."""
    if name in _LOADED:
        return _LOADED[name]
    import torch

    pt, js = MODEL_DIR / f"{name}.pt", MODEL_DIR / f"{name}.json"
    if not (pt.exists() and js.exists()):
        return None, None
    meta = json.loads(js.read_text(encoding="utf-8"))
    model = _build(meta["n_feat"], meta["window"])
    model.load_state_dict(torch.load(pt, map_location="cpu"))
    model.eval()
    _LOADED[name] = (model, meta)
    return model, meta


# --------------------------------------------------------------------------
# 점수
# --------------------------------------------------------------------------
def score(feat: pd.DataFrame, model=None, meta=None,
          name: str = "tf_forecast_5m", batch: int = 1024) -> np.ndarray:
    """
    예측 잔차 = 이상 점수. 길이는 feat와 같고, 계산 불가 구간은 NaN.

    2단 탐지기이므로 **관심종목 심층 분석에서만 호출한다.**
    1단(규칙 계층)은 이것 없이도 완전히 동작해야 한다 — 오프라인 보장이 그 이유다.
    """
    import torch

    if model is None:
        model, meta = load(name)
    if model is None:
        return np.full(len(feat), np.nan)

    out = np.full(len(feat), np.nan)
    wins, ends = stock_sequences(feat, meta["window"], meta["features"])
    if wins is None:
        return out
    with torch.no_grad():
        preds = []
        X = torch.from_numpy(wins)
        for s in range(0, len(X), batch):
            preds.append(model(X[s:s + batch]).numpy())
    P = np.concatenate(preds, axis=0)
    out[ends] = ((P - wins[:, -1]) ** 2).mean(axis=1)
    return out


def score_detail(feat: pd.DataFrame, model=None, meta=None,
                 name: str = "tf_forecast_5m", batch: int = 1024) -> dict:
    """
    피처별 예측 잔차.

    `score()`는 잔차를 평균 내 하나의 숫자로 만든다. 그런데 **어느 축이 놀랐는지**가
    정보다 — 가격이 예상을 벗어난 것과 거래량이 벗어난 것은 다른 사건이다.
    평균을 내면 그 구분이 사라지고, 랭커가 이미 갖고 있는 z-score들과 중복이 된다.

    반환: {"mean": 전체 평균, "<피처명>": 축별 잔차}
    """
    import torch

    if model is None:
        model, meta = load(name)
    n = len(feat)
    empty = {"mean": np.full(n, np.nan)}
    if model is None:
        return empty
    wins, ends = stock_sequences(feat, meta["window"], meta["features"])
    if wins is None:
        return empty
    with torch.no_grad():
        preds = []
        X = torch.from_numpy(wins)
        for s in range(0, len(X), batch):
            preds.append(model(X[s:s + batch]).numpy())
    P = np.concatenate(preds, axis=0)
    resid = (P - wins[:, -1]) ** 2

    out = {"mean": np.full(n, np.nan)}
    out["mean"][ends] = resid.mean(axis=1)
    for j, fname in enumerate(meta["features"]):
        col = np.full(n, np.nan)
        col[ends] = resid[:, j]
        out[fname] = col
    return out


def to_onnx(name: str = "tf_forecast_5m") -> Path | None:
    """
    ONNX 변환 — Java에서 파이썬 없이 로드하기 위한 것.

    배포 계획상 데스크톱 클라이언트가 Java라, 2단 탐지기를 쓰려면
    파이썬 런타임을 같이 배포하거나 ONNX로 빼야 한다. 후자가 맞다.
    """
    import torch

    model, meta = load(name)
    if model is None:
        return None
    dummy = torch.randn(1, meta["window"], meta["n_feat"])
    path = MODEL_DIR / f"{name}.onnx"

    # torch 2.6+ 기본 내보내기(dynamo)는 `onnxscript`를 요구한다.
    # 그게 없다고 **학습된 모델을 버리면 안 된다** — ONNX는 Java 배포용 편의이지
    # 파이썬 쪽 동작의 전제가 아니다. 실패하면 None을 주고 조용히 넘어간다.
    for kwargs in ({"dynamo": False}, {}):
        try:
            torch.onnx.export(
                model, dummy, str(path), input_names=["window"],
                output_names=["pred"],
                dynamic_axes={"window": {0: "batch"}, "pred": {0: "batch"}},
                opset_version=17, **kwargs)
            return path
        except Exception:
            continue
    return None


_ORT: dict = {}


def _ort_session(name: str):
    """
    ONNX Runtime 세션을 프로세스당 한 번만 만든다.

    처음엔 호출할 때마다 `InferenceSession`을 새로 만들었다. 그래서 측정했더니
    ONNX(1,161ms)가 파이썬(646ms)보다 **느렸다.** 모델이 느린 게 아니라
    세션 생성 비용을 매번 낸 것이다. 감성 모델을 프로세스당 한 번만 올리는 것과 같은 문제다.
    """
    if name in _ORT:
        return _ORT[name]
    try:
        import onnxruntime as ort
    except ImportError:
        _ORT[name] = (None, None)
        return _ORT[name]
    path = MODEL_DIR / f"{name}.onnx"
    js = MODEL_DIR / f"{name}.json"
    if not (path.exists() and js.exists()):
        _ORT[name] = (None, None)
        return _ORT[name]
    opt = ort.SessionOptions()
    opt.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess = ort.InferenceSession(str(path), opt, providers=["CPUExecutionProvider"])
    _ORT[name] = (sess, json.loads(js.read_text(encoding="utf-8")))
    return _ORT[name]


def onnx_score(feat: pd.DataFrame, name: str = "tf_forecast_5m") -> np.ndarray:
    """ONNX Runtime 경로. 설치돼 있지 않으면 NaN을 준다."""
    sess, meta = _ort_session(name)
    if sess is None:
        return np.full(len(feat), np.nan)
    out = np.full(len(feat), np.nan)
    wins, ends = stock_sequences(feat, meta["window"], meta["features"])
    if wins is None:
        return out
    P = sess.run(["pred"], {"window": wins})[0]
    out[ends] = ((P - wins[:, -1]) ** 2).mean(axis=1)
    return out


# --------------------------------------------------------------------------
# 앙상블 — 결합 방식이 전부다
# --------------------------------------------------------------------------
def rank_normalize(x: np.ndarray) -> np.ndarray:
    """순위를 0~1로. NaN은 그대로 둔다."""
    from scipy.stats import rankdata

    x = np.asarray(x, dtype=float)
    out = np.full(len(x), np.nan)
    m = np.isfinite(x)
    if m.sum() < 2:
        return out
    out[m] = (rankdata(x[m]) - 1) / (m.sum() - 1)
    return out


def ensemble(scores: list[np.ndarray]) -> np.ndarray:
    """
    **상위 2개의 순위 평균.** 02에서 결합 방식 6가지를 비교해 나온 결론이다.

        상위2·순위 평균   0.1746  ✅
        상위2·가중 평균   0.1722
        유형1위·가중      0.1599
        유형1위·평균      0.1094
        유형1위·순위      0.0539  ❌

    "유형별 1위를 모으면 된다"는 예상은 틀렸다. 약한 멤버(거래량 z, PR-AUC 0.03)를
    넣으면 오히려 **최고 단일보다 나빠진다.** 강한 멤버 2개를 순위 평균해야 이긴다.
    그래서 이 함수는 멤버를 늘려서 부르면 안 된다.
    """
    ranks = [rank_normalize(s) for s in scores]
    stack = np.vstack(ranks)
    with np.errstate(invalid="ignore"):
        return np.nanmean(stack, axis=0)
