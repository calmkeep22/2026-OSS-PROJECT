"""
유사 차트 패턴 검색 + 페어 관계 이탈.

03 노트북에서 실험으로 확정한 것만 남겼다.

1. **정규화는 필수다.** 원가격 유클리드는 가격대가 다르면 완전히 실패한다.
       방식                같은 가격대   1/50 가격대
       원가격 + 유클리드      0.985        0.000  ❌
       수익률 + 유클리드      0.585        0.585
       z-정규화 + 코사인      0.815        0.815  ✅

2. **DTW는 쓰지 않는다.** 모든 난이도에서 상관계수에 졌고 2.6배 느렸다.
       노이즈 0.30 → 상관 0.830 vs DTW 0.525
       노이즈 0.50 → 상관 0.405 vs DTW 0.155
   2단계 필터링 자체가 불필요하다.

3. **윈도우는 120봉이 60봉보다 낫다.** "60봉이 스윗스팟"이라던 앞선 추정을
   실험이 뒤집었다 (0.894 vs 0.836, 표준오차 0.037 대비 3.5 SE).
   다만 긴 서명일수록 변별력이 높다는 일반 성질이기도 해서, 사용자에게
   의미 있는 기간인지는 별개다 → 기본 120봉, 20/60/120 선택 가능.

4. **설명이 순위보다 중요하다.** 첫 시도에서 상위 4개 종목이 완전히 같은 문장을
   받았다. 소리로 순차 청취하는 사용자에게 똑같은 문장 4번은 정보가 0이다.
   → 질의 대비 차이를 말하도록 고쳤다.

5. z-정규화는 형태만 보므로 유사도 96%인데 상승폭이 +20%와 +100%로 5배 차이날 수 있다.
   **반드시 실제 변화폭을 함께 읽어줘야 한다.**

단타 확장
--------
분봉 윈도우 검색을 추가했다. "지금 이 모양과 닮은 과거 장중 구간"은 단타에서
실제로 쓰는 질문이다. 다만 결과는 **과거 사례의 분포**로만 제시하고
평균 수익률 같은 예측형 수치는 내지 않는다.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .config import DISCLAIMER_SIMILAR

WINDOW_CHOICES = (20, 60, 120, 250)
DEFAULT_WINDOW = 120


# --------------------------------------------------------------------------
# 윈도우 · 정규화
# --------------------------------------------------------------------------
def make_windows(series, W: int) -> np.ndarray:
    x = np.asarray(series, dtype=np.float64)
    if len(x) < W:
        return np.empty((0, W))
    return np.lib.stride_tricks.sliding_window_view(x, W).copy()


def normalize(win: np.ndarray, method: str = "zscore") -> np.ndarray:
    """raw / ret / zscore. 기본은 zscore — 형태만 남는다."""
    if method == "raw":
        return win
    if method == "ret":
        return win / win[:, [0]] - 1.0
    mu = win.mean(axis=1, keepdims=True)
    sd = win.std(axis=1, keepdims=True) + 1e-12
    return (win - mu) / sd


# --------------------------------------------------------------------------
# 후보 풀
# --------------------------------------------------------------------------
def _zrows(a: np.ndarray) -> np.ndarray:
    """행마다 z-정규화. 상수 행은 0으로 둔다."""
    mu = a.mean(axis=1, keepdims=True)
    sd = a.std(axis=1, keepdims=True)
    return np.divide(a - mu, sd, out=np.zeros_like(a), where=sd > 1e-12)


# 순위는 **눈에 보이는 닮음**이 정한다
# ====================================
# `차트 유사도` 라는 이름이 약속하는 것은 "이 차트와 모양이 닮은 구간"이다.
# 그러니 순위도 그걸 따라가야 한다. `형태상관`(z-정규화 종가 경로의 상관)이
# 정확히 그 값이다.
#
#     형태상관   0.70  **눈에 보이는 닮음.** 이게 순위를 정한다
#     진폭일치   0.20  하루 0.5% 움직인 구간과 5% 구간은 다른 차트다
#     수익률상관 0.10  같은 모양이면 실제로 함께 움직인 쪽을 앞에
#
# ⚠️ 한 번 크게 돌아갔다. 그 기록을 남긴다.
#
# "유사도 0.97 인데 겹쳐 그리면 달라 보인다"는 문제가 있었다. 원인을
# **추세가 상관을 지배해서**라고 짚고 일간 수익률 상관을 주축(0.45)으로
# 올렸다. 그런데 진짜 원인은 따로 있었다 — **그림이 매칭과 20봉 어긋난
# 구간을 그리고 있었다**(`viz.similarity_windows` 주석 참조). 형태상관은
# 처음부터 멀쩡했고, 그림을 고치자 0.98 짜리는 실제로 0.98 처럼 겹쳤다.
#
# 그 사이에 얹은 재순위가 오히려 순서를 망가뜨렸다. 실측:
#
#     NVDA 질의   Extra Space  형태 0.884(최고)  동조 0.214  종합 0.569  5위
#                 Take-Two     형태 0.739(최저)  동조 0.407  종합 0.575  1위
#
# **가장 닮아 보이는 것이 꼴찌, 가장 안 닮은 것이 1등**이 됐다. 25개 사례
# 전체에서 종합점수와 형태상관의 상관은 0.556 인데 수익률상관과는 0.926
# 이었다 — 이름은 유사도인데 실제로 재는 것은 동조도였다.
#
# 시장 간 비교도 깨졌다. 국내 매치는 같은 기간이라 시장 베타를 공유해
# 동조가 높고(0.85~0.87), 미국 매치는 다른 시기라 낮다(0.57~0.63).
# 같은 0.7 이 시장에 따라 다른 뜻이 됐다. 형태 주도로 되돌리자 국내
# 0.93~0.95 · 미국 0.82~0.87 로 같은 척도가 됐다.
#
# `추세일치` 는 순위에서 뺐다. 그림을 z-정규화해서 그리므로 총변화율 차이는
# **이미 지워진 뒤**라 눈에 보이지도 않는다(종합과의 상관 0.039 — 잡음이었다).
# 계산은 계속해서 `components` 로 실어 보낸다.
#
# ⚠️ 그래도 거리를 따로 넣지는 않는다. z-정규화된 두 벡터는
#     ||a - b||² = 2n(1 - r)
# 이라 **유클리드 거리가 상관의 단조 함수**다. 새 정보가 하나도 없다.
SIM_WEIGHTS = {"형태상관": 0.70, "진폭일치": 0.20, "수익률상관": 0.10}
# 회수 단계도 형태 쪽에 무게를 둔다. 순위를 형태로 매기면서 후보를
# 수익률로 추리면, 정작 모양이 닮은 구간이 재순위에 도달하지 못한다.
RETRIEVE_MIX = 0.75        # 회수 단계에서 형태상관에 주는 비중
RERANK_POOL = 300          # 재순위에 넘길 후보 수


class PatternIndex:
    """
    검색 인덱스. 상관계수는 z-정규화 후 **행렬곱 한 번**이라 별도 자료구조가 필요 없다.

    실측: 전 종목 55,575개 윈도우 검색 2.5ms. 2,600종목 환산 24ms.
    이 속도 덕분에 화면을 못 보는 사용자가 기다리지 않는다.

    **두 벌을 만든다** — 종가 경로(`pool_z`)와 일간 수익률(`pool_r`).
    회수도 재순위도 둘을 섞어 쓴다 (`SIM_WEIGHTS` 주석 참조).
    """

    def __init__(self, W: int = DEFAULT_WINDOW):
        self.W = W
        self.pool_raw: np.ndarray | None = None
        self.pool_z: np.ndarray | None = None
        self.pool_r: np.ndarray | None = None     # z-정규화한 일간 수익률
        self.pool_vol: np.ndarray | None = None   # 구간 일간 변동성
        self.pool_chg: np.ndarray | None = None   # 구간 총변화율(%)
        self.meta: pd.DataFrame | None = None
        self.forward: np.ndarray | None = None

    def build(self, data: dict[str, pd.DataFrame], stride: int = 5,
              max_per_stock: int = 200, forward_bars: int = 0) -> "PatternIndex":
        """
        forward_bars > 0 이면 각 윈도우 **직후** 구간의 변화율을 함께 저장한다.
        "닮은 구간 다음에 무슨 일이 있었나"를 사실로 보여주기 위한 것이지 예측이 아니다.
        """
        rows, meta, fwd = [], [], []
        for code, df in data.items():
            close = df["close"].to_numpy(dtype=np.float64)
            wv = make_windows(close, self.W)
            if len(wv) == 0:
                continue
            idx = np.arange(0, len(wv), stride)
            if forward_bars:
                idx = idx[idx + self.W - 1 + forward_bars < len(close)]
            if len(idx) > max_per_stock:
                idx = idx[np.linspace(0, len(idx) - 1, max_per_stock).astype(int)]
            if not len(idx):
                continue
            rows.append(wv[idx])
            for i in idx:
                end = i + self.W - 1
                meta.append((code, df.index[end]))
                if forward_bars:
                    fwd.append(close[end + forward_bars] / close[end] - 1.0)
        if not rows:
            raise ValueError("후보 윈도우를 만들 수 없습니다 (데이터가 짧습니다).")

        self.pool_raw = np.concatenate(rows, axis=0)
        self.pool_z = normalize(self.pool_raw, "zscore")

        # 수익률 쪽 재료. 여기서 한 번 만들어 두면 검색 때는 행렬곱뿐이다.
        rets = np.diff(self.pool_raw, axis=1) / self.pool_raw[:, :-1]
        rets = np.nan_to_num(rets, nan=0.0, posinf=0.0, neginf=0.0)
        self.pool_r = _zrows(rets)
        self.pool_vol = rets.std(axis=1)
        self.pool_chg = (self.pool_raw[:, -1] / self.pool_raw[:, 0] - 1) * 100

        self.meta = pd.DataFrame(meta, columns=["code", "end"])
        self.forward = np.asarray(fwd) if forward_bars else None
        return self

    # ------------------------------------------------------------------
    def components(self, i: np.ndarray, qz: np.ndarray, qr: np.ndarray,
                   qvol: float, qchg: float) -> dict[str, np.ndarray]:
        """후보 `i` 들의 유사도 성분. 전부 0~1 이고 클수록 닮았다."""
        form = self.pool_z[i] @ qz / len(qz)
        ret = self.pool_r[i] @ qr / max(len(qr), 1)

        # 진폭 — 변동성 비를 로그로 재고 1배(=0) 에서 멀수록 깎는다.
        # 하루 0.5% 움직인 구간과 5% 움직인 구간은 모양이 같아도 다른 차트다.
        lv = np.log((self.pool_vol[i] + 1e-9) / (qvol + 1e-9))
        amp = np.clip(1.0 - np.abs(lv), 0.0, 1.0)

        # 추세 — 구간 총변화율의 차이. 기준을 질의 변화폭과 10%p 중 큰 쪽으로
        # 둔다. 질의가 거의 안 움직인 구간이면 분모가 0에 붙어 폭발한다.
        scale = max(abs(qchg), 10.0)
        trend = np.clip(1.0 - np.abs(self.pool_chg[i] - qchg) / scale, 0.0, 1.0)
        return {"형태상관": form, "수익률상관": ret,
                "진폭일치": amp, "추세일치": trend}

    def search(self, query: np.ndarray, top_k: int = 10,
               exclude_code: str | None = None,
               min_gap_bars: int = 0) -> tuple[np.ndarray, np.ndarray, dict]:
        """
        **2단계 검색.**

            ① 회수   형태상관과 수익률상관을 섞어 상위 `RERANK_POOL` 개
            ② 재순위 진폭까지 넣은 종합 점수로 다시 정렬

        회수도 **형태 주도**(`RETRIEVE_MIX = 0.75`)로 한다. 순위를 형태로
        매기면서 후보를 수익률로 추리면, 정작 모양이 닮은 구간이 재순위에
        도달하지 못한다 — 실제로 그렇게 뒤집혔던 기록이 `SIM_WEIGHTS`
        주석에 있다.

        수익률상관을 회수에 조금(0.25) 섞어 두는 이유는, 같은 모양이면
        실제로 함께 움직인 쪽을 앞에 두는 0.10 가중이 작동할 후보를
        확보하기 위해서다.
        """
        q = np.asarray(query, dtype=np.float64).ravel()
        qz = normalize(q.reshape(1, -1), "zscore")[0]
        qret = np.nan_to_num(np.diff(q) / q[:-1], nan=0.0,
                             posinf=0.0, neginf=0.0)
        qr = _zrows(qret.reshape(1, -1))[0]
        qvol = float(qret.std())
        qchg = float((q[-1] / q[0] - 1) * 100)

        base = (RETRIEVE_MIX * (self.pool_z @ qz / len(qz))
                + (1 - RETRIEVE_MIX) * (self.pool_r @ qr / max(len(qr), 1)))
        if exclude_code is not None:
            base = np.where(self.meta["code"].to_numpy() == exclude_code,
                            -np.inf, base)

        k = min(max(RERANK_POOL, top_k * 8), len(base) - 1)
        cand = np.argpartition(-base, k)[:k]

        comp = self.components(cand, qz, qr, qvol, qchg)
        score = sum(SIM_WEIGHTS[c] * comp[c] for c in SIM_WEIGHTS)
        order = np.argsort(-score)
        cand, score = cand[order], score[order]
        comp = {c: v[order] for c, v in comp.items()}

        # 같은 종목의 이웃 윈도우가 상위를 도배하는 걸 막는다.
        # 안 하면 상위 10개가 전부 같은 종목의 1일씩 밀린 구간이 된다 — 정보량 0이다.
        codes = self.meta["code"].to_numpy()
        ends = self.meta["end"].to_numpy()
        chosen, used = [], {}
        for j, i in enumerate(cand):
            code, end = codes[i], pd.Timestamp(ends[i])
            prev = used.get(code)
            if prev is not None and abs((end - prev).days) < min_gap_bars:
                continue
            used[code] = end
            chosen.append(j)
            if len(chosen) >= top_k:
                break
        sel = np.array(chosen, dtype=int)
        return (cand[sel], score[sel],
                {c: v[sel] for c, v in comp.items()})


# --------------------------------------------------------------------------
# 형태 설명
# --------------------------------------------------------------------------
def shape_features(w: np.ndarray) -> dict:
    z = (w - w.mean()) / (w.std() + 1e-12)
    n = len(z)
    return {
        "방향": "상승" if z[-1] > z[0] else "하락",
        "총변화": float((w[-1] / w[0] - 1) * 100),
        "고점위치": int(np.argmax(z)) / n,
        "저점위치": int(np.argmin(z)) / n,
        "변동폭": float(z.max() - z.min()),
        "전환횟수": int((np.diff(np.sign(np.diff(
            np.convolve(z, np.ones(5) / 5, mode="valid")))) != 0).sum()),
    }


def explain(q: np.ndarray, c: np.ndarray, sim: float,
            unit: str = "일", comp: dict | None = None) -> str:
    """
    왜 닮았는지 + **무엇이 다른지**.

    항목마다 다른 문장이 나와야 한다. 공통점만 나열하면 상위 4개가
    똑같은 문장을 받는다 (실제로 그 버그가 났다).

    `comp` 를 주면 **어느 축에서 닮고 어느 축에서 어긋났는지**를 앞세운다.
    종합 점수 하나만 읽어 주면 "0.72가 무슨 뜻이냐"에 답할 수 없다.
    """
    fq, fc = shape_features(q), shape_features(c)
    n = len(c)
    parts = [f"{n}{unit}간 {fc['총변화']:+.1f}퍼센트"]
    if comp:
        r = comp.get("수익률상관")
        if r is not None:
            grade = ("봉마다 함께 움직임" if r >= 0.5 else
                     "봉 단위로는 절반쯤 겹침" if r >= 0.3 else
                     "모양만 닮고 봉 단위 움직임은 다름")
            parts.insert(0, f"일간 동조 {r * 100:.0f}퍼센트로 {grade}")
        a = comp.get("진폭일치")
        if a is not None and a < 0.6:
            parts.append("다만 하루 변동폭이 꽤 다름")

    gap = fc["총변화"] - fq["총변화"]
    if abs(gap) < 3:
        parts.append("질의 종목과 거의 같은 폭")
    elif gap > 0:
        parts.append(f"질의 종목보다 {abs(gap):.0f}퍼센트포인트 큰 폭")
    else:
        parts.append(f"질의 종목보다 {abs(gap):.0f}퍼센트포인트 완만")

    peak = int(fc["고점위치"] * n)
    if abs(fc["고점위치"] - fq["고점위치"]) * n < n * 0.1:
        parts.append(f"고점이 {peak}{unit}째로 거의 일치")
    else:
        parts.append(f"고점은 {peak}{unit}째")

    if abs(fq["전환횟수"] - fc["전환횟수"]) <= 1:
        parts.append(f"방향 전환 {fc['전환횟수']}회로 유사")
    else:
        more = "잦음" if fc["전환횟수"] > fq["전환횟수"] else "적음"
        parts.append(f"방향 전환 {fc['전환횟수']}회로 더 {more}")

    return f"유사도 {sim * 100:.0f}퍼센트. " + ", ".join(parts) + "."


# --------------------------------------------------------------------------
# 공개 진입점
# --------------------------------------------------------------------------
def find_similar(query_code: str, data: dict[str, pd.DataFrame],
                 W: int = DEFAULT_WINDOW, top_k: int = 5,
                 name_map: dict[str, str] | None = None,
                 forward_bars: int = 0, unit: str = "일",
                 index: PatternIndex | None = None,
                 query_offset: int = 0) -> dict:
    """
    `query_code`의 최근 W봉과 닮은 구간을 찾는다.

    forward_bars > 0 이면 "닮은 구간 다음 구간이 실제로 어땠는지"의 **분포**를
    함께 준다. 평균 수익률로 요약하지 않는다 — 그건 예측처럼 읽힌다.
    상승 몇 건 / 하락 몇 건까지만 사실로 말한다.

    query_offset
        질의 구간을 **과거로 몇 봉 물릴지**. 0이면 가장 최근 구간이다.

        실사용(오늘 뭐랑 닮았나)에서는 0 이 맞다. 그런데 **그림으로 비교를
        보여 줄 때는 0 이면 안 된다** — 질의는 정의상 마지막 구간이라
        "그 다음 20일"이 존재하지 않고, 매치 쪽만 이후 구간이 그려져
        정작 비교가 되지 않는다.

            offset=0    질의 [.....지금]              이후 없음
                        매치 [.....][이후 20일]        ← 한쪽만 있다

            offset=20   질의 [.....][이후 20일]  ← 양쪽 다 있다 = 비교 가능
                        매치 [.....][이후 20일]

        `query_offset=forward_bars` 로 두면 질의도 결과를 아는 구간이 되어
        "닮은 모양이 실제로 닮은 결과로 이어졌나"를 눈으로 확인할 수 있다.
    """
    name_map = name_map or {}
    if query_code not in data:
        raise KeyError(f"{query_code}의 데이터가 없습니다.")

    idx = index or PatternIndex(W).build(data, forward_bars=forward_bars)
    close_q = data[query_code]["close"].to_numpy(dtype=np.float64)
    end = len(close_q) - query_offset
    q = close_q[max(0, end - W):end]
    if len(q) < W:
        raise ValueError(f"질의 구간이 {W}봉보다 짧습니다.")

    hits, sims, comp = idx.search(q, top_k=top_k, exclude_code=query_code,
                                  min_gap_bars=W // 2)
    fq = shape_features(q)

    # 질의 구간의 z-정규화. 매치 구간도 **각자의** 평균·표준편차로 정규화해야
    # 모양만 비교된다 (가격대가 달라도 같은 자리에 놓인다).
    qz = (q - q.mean()) / (q.std() or 1.0)

    results = []
    for rank, (i, s) in enumerate(zip(hits, sims), 1):
        m = idx.meta.iloc[i]
        seg = np.asarray(idx.pool_raw[i], dtype=np.float64)
        mu, sd = seg.mean(), (seg.std() or 1.0)
        parts = {c: round(float(v[rank - 1]), 4) for c, v in comp.items()}
        row = {
            "rank": rank, "code": m["code"],
            "name": name_map.get(m["code"], m["code"]),
            "end": str(m["end"]), "similarity": round(float(s), 4),
            # 성분을 그대로 실어 보낸다. "0.7인데 왜 닮았다는 거냐"는 물음에
            # 답할 수 있어야 한다 — 어느 축에서 닮고 어느 축에서 어긋났는지.
            "components": parts,
            "explain": explain(q, idx.pool_raw[i], float(s), unit, parts),
            # ⚠️ 닮은 구간의 **실제 모양**을 같이 실어야 한다.
            # 전에는 유사도 숫자만 돌려줬고, 그림 쪽에서는 그릴 것이 없어
            # 질의 곡선을 네 번 반복해 그렸다. 네 패널이 전부 똑같아 보여
            # "닮았다"를 눈으로 확인할 방법이 아예 없었다.
            "segment": [round(float(v), 4) for v in (seg - mu) / sd],
        }
        if idx.forward is not None:
            row["forward_pct"] = round(float(idx.forward[i]) * 100, 2)
        # 구간 **직후** 경로. 같은 mu·sd 로 이어 붙여야 선이 끊기지 않는다.
        if forward_bars and m["code"] in data:
            df = data[m["code"]]
            try:
                end_i = int(df.index.get_loc(m["end"]))
                nxt = df["close"].to_numpy(float)[end_i + 1:
                                                  end_i + 1 + forward_bars]
                if len(nxt):
                    row["forward_path"] = [round(float(v), 4)
                                           for v in (nxt - mu) / sd]
            except (KeyError, TypeError):
                pass
        results.append(row)

    out = {
        "query": {"code": query_code, "name": name_map.get(query_code, query_code),
                  "window": W, "unit": unit,
                  "change_pct": round(fq["총변화"], 2),
                  "peak_at": int(fq["고점위치"] * W),
                  "turns": fq["전환횟수"],
                  "offset": int(query_offset),
                  "segment": [round(float(v), 4) for v in qz],
                  "forward_path": ([round(float(v), 4) for v in
                                    (close_q[end:end + forward_bars]
                                     - q.mean()) / (q.std() or 1.0)]
                                   if query_offset and forward_bars else [])},
        "results": results,
        "pool_size": int(len(idx.pool_z)),
        "disclaimer": DISCLAIMER_SIMILAR,
    }
    if idx.forward is not None and results:
        f = [r["forward_pct"] for r in results if "forward_pct" in r]
        out["forward_summary"] = {
            "n": len(f), "up": int(sum(1 for v in f if v > 0)),
            "down": int(sum(1 for v in f if v < 0)),
            "median_pct": round(float(np.median(f)), 2),
            "note": "닮은 구간 다음에 실제로 무슨 일이 있었는지의 분포입니다. "
                    "표본이 적고 미래를 보장하지 않습니다.",
        }
    return out


# --------------------------------------------------------------------------
# 다중 윈도우 — "최근 모양"과 "전체 추세"는 다른 질문이다
# --------------------------------------------------------------------------
#
# 세 가지가 서로 다른 것을 잰다. 하나로 합치면 안 된다.
#
#   20봉  (약 1개월)  "지금 이 모양"      — 단기 형태. 진입 타이밍 참고
#   60봉  (약 3개월)  "이번 분기 흐름"    — 중기 형태
#   120봉 (약 6개월)  "전체 추세"        — 03에서 변별력 1위로 확정된 기본값
#   페어(공적분)                          — 형태가 아니라 **레벨 동조**. pairs.py
#
# 페어와 유사도는 원리가 다르다. 페어는 두 종목의 로그가격 차이가 평균회귀하는지를
# 통계 검정으로 본다(장기 관계). 유사도는 z-정규화 후 모양만 본다(레벨 무관).
# 그래서 "삼성전자와 SK하이닉스는 페어"이면서 동시에
# "지금 모양은 전혀 다른 종목과 닮았다"가 얼마든지 성립한다.
#
# 여러 윈도우에서 **공통으로** 올라오는 종목이 진짜 닮은 것이다.
# 한 윈도우에서만 1등인 종목은 그 구간이 우연히 맞은 것일 수 있다.
# 1·3·6·12개월. 창마다 다른 질문에 답한다.
#
#    20봉  ≈ 1개월   "지금 이 모양"        진입 타이밍 참고
#    60봉  ≈ 3개월   "이번 분기 흐름"
#   120봉  ≈ 6개월   "전체 추세"           변별력 1위로 확정된 기본값
#   250봉  ≈ 12개월  "올해 전체"
#
# 2년(500봉)은 넣지 않았다. 종목당 일봉이 1,800개 남짓이라 겹치지 않는
# 구간이 3~4개뿐이고, 그걸로는 "닮았다"를 말할 표본이 안 된다.
MULTI_WINDOWS = (20, 60, 120, 250)


def find_similar_multi(query_code: str, data: dict[str, pd.DataFrame],
                       windows: tuple[int, ...] = MULTI_WINDOWS, top_k: int = 5,
                       name_map: dict[str, str] | None = None,
                       forward_bars: int = 0, unit: str = "일") -> dict:
    """
    여러 윈도우로 동시에 검색하고 **합의**를 계산한다.

    합의 점수 = 그 종목이 등장한 윈도우 수 + 평균 유사도.
    "최근 모양도 닮았고 6개월 추세도 닮았다"가 "한쪽만 닮았다"보다 강한 근거다.
    """
    name_map = name_map or {}
    per_window, agree = {}, {}
    for W in windows:
        usable = {c: df for c, df in data.items() if len(df) > W + 20}
        if query_code not in usable or len(usable) < 3:
            continue
        try:
            res = find_similar(query_code, usable, W=W, top_k=top_k,
                               name_map=name_map, forward_bars=forward_bars,
                               unit=unit)
        except Exception:
            continue
        per_window[W] = res
        for r in res["results"]:
            a = agree.setdefault(r["code"], {"code": r["code"], "name": r["name"],
                                             "best": {}})
            # 한 윈도우 안에서 같은 종목이 여러 구간으로 잡힐 수 있다
            # (min_gap_bars가 떨어진 구간은 허용한다). 그걸 세면 합의가 부풀려지고
            # 안내도 "최근 석 달, 최근 석 달 모두에서"처럼 같은 말을 반복한다.
            # → 윈도우당 한 번, 가장 높은 유사도만 남긴다.
            a["best"][W] = max(a["best"].get(W, 0.0), r["similarity"])

    consensus = sorted(
        ({"code": v["code"], "name": v["name"],
          "windows": sorted(v["best"]), "n_windows": len(v["best"]),
          "sims": [round(v["best"][w], 4) for w in sorted(v["best"])],
          "mean_sim": round(float(np.mean(list(v["best"].values()))), 4)}
         for v in agree.values()),
        key=lambda x: (-x["n_windows"], -x["mean_sim"]))

    return {"query": {"code": query_code,
                      "name": name_map.get(query_code, query_code),
                      "windows": list(per_window)},
            "per_window": per_window, "consensus": consensus[:top_k],
            "disclaimer": DISCLAIMER_SIMILAR}


def speak_multi(res: dict) -> str:
    """다중 윈도우 결과 → TTS 문안. 합의부터 읽고 그다음 창별로."""
    q = res["query"]
    lines = [f"{q['name']} 유사 종목 검색 결과입니다."]
    if not res["consensus"]:
        lines.append("닮은 종목을 찾지 못했습니다.")
        lines.append(res["disclaimer"])
        return "\n".join(lines)

    labels = {20: "최근 한 달 모양", 60: "최근 석 달 흐름", 120: "최근 여섯 달 추세"}
    top = res["consensus"][0]
    if top["n_windows"] >= 2:
        which = ", ".join(labels.get(w, f"{w}일") for w in top["windows"])
        lines.append(f"가장 닮은 종목은 {top['name']}입니다. "
                     f"{which} 모두에서 상위로 나왔고, 평균 유사도는 "
                     f"{top['mean_sim']*100:.0f}퍼센트입니다.")
    else:
        lines.append(f"여러 구간에서 함께 닮은 종목은 없었습니다. "
                     f"구간마다 다른 종목이 올라왔습니다.")

    for W, r in res["per_window"].items():
        if not r["results"]:
            continue
        best = r["results"][0]
        lines.append(f"{labels.get(W, str(W) + '일')} 기준 1위는 "
                     f"{best['name']}. {best['explain']}")
    lines.append(res["disclaimer"])
    return "\n".join(lines)


def speak(res: dict) -> str:
    """검색 결과 → TTS 문안."""
    q = res["query"]
    lines = [
        f"{q['name']} 최근 {q['window']}{q['unit']}과 닮은 구간을 찾았습니다.",
        f"질의 구간은 {q['change_pct']:+.1f}퍼센트, 고점은 {q['peak_at']}{q['unit']}째, "
        f"방향 전환 {q['turns']}회입니다.",
    ]
    for r in res["results"]:
        lines.append(f"{r['rank']}번, {r['name']}. {r['explain']}")
    fs = res.get("forward_summary")
    if fs:
        lines.append(f"닮은 {fs['n']}개 구간의 직후를 보면 상승 {fs['up']}건, "
                     f"하락 {fs['down']}건이었습니다. {fs['note']}")
    lines.append(res["disclaimer"])
    return "\n".join(lines)
