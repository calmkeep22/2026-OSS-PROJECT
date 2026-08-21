"""
이상 탐지 엔진 — 단타 시간축 대응.

이 파일이 답하는 질문
--------------------
"이상 탐지 60일 기준을 단타에 맞추면 성능·신뢰가 떨어지지 않나?"

떨어진다. 아무 대책 없이 봉만 잘게 쓰면 그렇다. 실측(005930, 5분봉 60일):

    채널        |z|>3 비율     종목당 하루 알림
    가격            1.64%          1.16건
    거래량          0.82%          0.58건
    VWAP이탈        2.72%          1.93건
    추세가속        1.88%          1.34건
    일중신고가      2.93%          2.08건
    변동성          3.75%          2.66건
    ------------------------------------------
    합계           13.7%          9.8건 / 종목 / 일

관심종목 20개면 **하루 195건**이다. 음성으로 읽으면 장중 내내 말이 끊기지 않는다.
일봉 기준(06 실측 종목당 5주에 1건)에서 이쪽으로 그냥 옮기면 도구가 죽는다.

그래서 네 겹을 둔다
------------------
1. **시간대 정규화** (features.py) — 개장 30분 변동성이 장중의 2.41배, 거래량은 3.36배다.
   보정 없이는 알림이 09:00~09:30에 몰려 "장이 열렸습니다" 알림이 된다.
2. **알림 예산 임계값** (이 파일) — 고정 |z|>3을 버리고, 원하는 알림 빈도에 해당하는
   **분위수**를 임계값으로 쓴다. 시끄러움이 상수로 고정된다.
3. **봉 단위 병합** — 큰 움직임은 여러 채널을 동시에 켠다. 채널별로 따로 읽지 않고
   한 봉 = 한 알림으로 묶는다. 이것만으로 건수가 절반 이하로 준다.
4. **확인 단계와 신뢰도 등급** — 거래량 동반 여부 + 다음 봉 지속 여부로 A/B/C를 나눈다.
   음성으로 읽는 건 A·B뿐이고 C는 경고음만 낸다. 놓치지도, 시끄럽지도 않게 하는 장치다.

정확도를 포기하지 않는 이유
--------------------------
예산 임계값은 **점수 순위를 바꾸지 않는다.** 같은 탐지기의 상위 N개를 고를 뿐이라
PR-AUC·랭킹 성능은 그대로다. 바뀌는 건 "몇 개를 알릴 것인가"뿐이다.
02 어블레이션에서 정한 규칙 계층(수익률 z, PR-AUC 0.1412, 0.10ms)의 성질을 그대로 쓴다.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from .config import (
    ALERT_BUDGET,
    CEILING_Z,
    CHANNELS,
    FLOOR_Z,
    HORIZONS,
    MARKET_BREADTH,
    MARKET_MIN,
    MARKET_SIGMA,
    MIN_MOVE_SIGMA,
    PRICE_CHANNELS,
    VOICE_MAX_PRIORITY,
    WATCHLIST_BUDGET,
)

# --------------------------------------------------------------------------
# 예산 배분 — 어느 채널에 얼마를 줄 것인가
# --------------------------------------------------------------------------
#
# 균등 배분하지 않는다. 02 어블레이션에서 채널별 변별력이 크게 갈렸다
# (수익률 z PR-AUC 0.1412 vs 거래량 z 0.0304 — 4.6배 차이).
# 약한 채널에 같은 예산을 주면 알림 절반이 정보량 낮은 소리가 된다.
#
# 단타 축의 배분은 02 결과 + 실전 용도를 함께 본 값이다.
# 실증되지 않은 부분이 있어 사용자가 조정할 수 있게 노출한다.
CHANNEL_WEIGHTS = {
    "가격": 0.28,
    "추세가속": 0.18,      # 봉 단위로는 안 걸리는 누적 이동. 단타에서 실제로 중요하다
    "VWAP이탈": 0.16,
    "일중신고가": 0.14,
    "거래량": 0.14,
    "갭": 0.05,            # 하루 한 번뿐이라 예산이 적어도 된다
    "변동성": 0.05,        # 06 결론: 상태이지 사건이 아니다. 우선순위 3
}


@dataclass
class Alert:
    """하나의 알림. UI는 `text`만 읽으면 되고, 나머지는 상세 화면용이다."""
    ts: pd.Timestamp
    code: str
    name: str
    horizon: str
    channels: list[dict] = field(default_factory=list)
    priority: int = 3
    confidence: str = "C"
    direction: str = ""
    score: float = 0.0
    ret_pct: float = 0.0
    day_ret_pct: float = 0.0
    price: float = 0.0
    text: str = ""
    confirmed: bool | None = None
    bar_index: int = -1
    market_event: bool = False

    @property
    def channel_names(self) -> list[str]:
        return [c["name"] for c in self.channels]

    @property
    def voice(self) -> bool:
        """음성으로 읽을 알림인가. C등급과 우선순위 3은 경고음만 낸다."""
        return self.priority <= VOICE_MAX_PRIORITY and self.confidence in ("A", "B")

    def to_dict(self) -> dict:
        return {
            "ts": self.ts.strftime("%Y-%m-%d %H:%M"),
            "code": self.code, "name": self.name, "horizon": self.horizon,
            "channels": self.channels, "priority": self.priority,
            "confidence": self.confidence, "direction": self.direction,
            "score": round(self.score, 2),
            "ret_pct": round(self.ret_pct, 2),
            "day_ret_pct": round(self.day_ret_pct, 2),
            "price": self.price, "text": self.text,
            "confirmed": self.confirmed, "voice": self.voice,
            "market_event": self.market_event,
        }


# --------------------------------------------------------------------------
# 1. 임계값 보정 — 이 엔진의 핵심
# --------------------------------------------------------------------------
def calibrate(feat: pd.DataFrame, horizon: str = "intraday",
              budget: float | None = None,
              weights: dict[str, float] | None = None,
              tail: int | None = None) -> dict[str, float]:
    """
    채널별 임계값을 **알림 예산**에서 역산한다.

    budget : 종목당 하루 목표 알림 수 (채널 병합 전 기준)
    tail   : 분포 추정에 쓸 최근 봉 수. None이면 전체.

    왜 고정 임계값을 안 쓰는가
    -------------------------
    06에서 확인한 사실: 005930 일간 표준편차가 2024년 2.01% → 2026년 5.22%로 2.6배가 됐다.
    같은 |z|>3이 국면에 따라 전혀 다른 빈도를 만든다. 사용자 입장에서
    "어떤 달은 조용하고 어떤 달은 하루 종일 울리는" 도구는 신뢰할 수 없다.

    분위수로 잡으면 **시끄러움이 상수**가 된다. 조용한 장에서는 임계값이 내려가
    작은 이상도 잡고, 급변장에서는 올라가 폭주를 막는다.

    하한(FLOOR_Z)과 상한(CEILING_Z)
    -------------------------------
    분위수만 쓰면 "아무 일도 없는 날"에도 억지로 예산을 채운다. 그래서 FLOOR_Z 밑으로는
    내려가지 않는다. 반대로 급변장에 임계값이 위로 밀려 진짜 사건을 놓치는 것도 막아야
    하므로 CEILING_Z 위는 무조건 알린다.
    """
    cfg = HORIZONS[horizon]
    budget = budget if budget is not None else ALERT_BUDGET[horizon]
    weights = weights or CHANNEL_WEIGHTS
    bars_per_day = cfg["bars_per_day"]

    sub = feat.tail(tail) if tail else feat
    out = {}
    for name, (col, *_rest) in CHANNELS.items():
        if not _channel_enabled(name, horizon):
            continue
        if col not in sub:
            continue
        s = sub[col].replace([np.inf, -np.inf], np.nan).abs().dropna()
        if len(s) < 30:
            out[name] = 3.0
            continue

        w = weights.get(name, 0.1)
        if name == "갭":
            # 갭은 하루 한 봉에서만 평가되므로 '봉당 비율'이 아니라 '일당 비율'이다
            rate = min(max(budget * w, 1e-4), 0.9)
        else:
            rate = budget * w / bars_per_day
            rate = min(max(rate, 1e-4), 0.5)

        thr = float(np.quantile(s, 1.0 - rate))
        out[name] = float(np.clip(thr, FLOOR_Z, CEILING_Z))
    return out


def _channel_enabled(name: str, horizon: str) -> bool:
    _col, _prio, use_intra, use_swing, _dir = CHANNELS[name]
    return use_swing if HORIZONS[horizon]["interval"] == "1d" else use_intra


def _channel_direction(name: str) -> str:
    return CHANNELS[name][4]


def per_stock_budget(horizon: str, n_watch: int,
                     watchlist_budget: float | None = None) -> float:
    """
    관심종목 수에 따라 종목당 예산을 나눈다.

    종목당으로만 정하면 관심종목 40개일 때 하루 35건이 되어 못 듣는다.
    사용자가 실제로 정하고 싶은 건 "내가 하루에 몇 번 들을 수 있나"다.
    """
    base = ALERT_BUDGET[horizon]
    total = watchlist_budget if watchlist_budget is not None else WATCHLIST_BUDGET[horizon]
    return float(min(base, total / max(n_watch, 1)))


# --------------------------------------------------------------------------
# 2. 봉 단위 스캔
# --------------------------------------------------------------------------
def scan_bar(feat: pd.DataFrame, i: int, thresholds: dict[str, float],
             horizon: str = "intraday",
             min_move_sigma: float = MIN_MOVE_SIGMA) -> list[dict]:
    """
    한 봉에서 켜진 채널 목록. 실시간 경로와 백테스트가 같은 코드를 쓴다.

    절대 크기 게이트
    ---------------
    z-score만 보면 "조용한 점심시간에 이례적이지만 0.2%밖에 안 움직인" 봉이 걸린다.
    단타 사용자에게는 수수료도 안 나오는 크기다.
    → 가격군 채널은 그 종목 일간 변동성의 `min_move_sigma` 배 이상 움직여야 통과한다.
      거래량·변동성 채널은 게이트를 적용하지 않는다. 가격이 아직 안 움직인
      물량 유입을 잡는 게 그 채널의 존재 이유이기 때문이다.
    """
    row = feat.iloc[i]
    move = float(row.get("move_sigma", np.nan))
    weak = (min_move_sigma > 0 and np.isfinite(move) and move < min_move_sigma)

    hits = []
    for name, thr in thresholds.items():
        col = CHANNELS[name][0]
        v = row.get(col, np.nan)
        if not np.isfinite(v):
            continue
        if abs(v) <= thr and abs(v) <= CEILING_Z:
            continue
        if _channel_direction(name) == "up" and v < 0:
            continue
        if weak and name in PRICE_CHANNELS and abs(v) < CEILING_Z:
            continue
        hits.append({"name": name, "z": round(float(v), 2),
                     "threshold": round(thr, 2)})
    return hits


def _confirm(feat: pd.DataFrame, i: int, direction: int, horizon: str) -> bool | None:
    """
    다음 봉에서 움직임이 유지되는가.

    단타에서 가장 흔한 오경보는 **한 봉짜리 스파이크**다 —
    큰 매물이 한 번 나가고 곧바로 되돌린다. 이걸 알리면 사용자는
    "울렸는데 가보니 아무것도 아니더라"를 반복하고 도구를 끈다.

    반환 None = 아직 다음 봉이 없다(장중 최신 봉). 이 경우 '잠정'으로 다룬다.
    """
    n = HORIZONS[horizon]["confirm_bars"]
    if n <= 0:
        return True
    if i + n >= len(feat):
        return None
    base = feat["close"].iloc[i]
    prev = feat["close"].iloc[i - 1] if i > 0 else base
    move = base - prev
    if move == 0:
        return True
    after = feat["close"].iloc[i + n] - prev
    return bool(after / move > 0.5 and np.sign(after) == np.sign(move))


def _grade(hits: list[dict], vol_z: float, confirmed: bool | None,
           move_sigma: float) -> str:
    """
    신뢰도 등급 — **측정으로 두 번 고친 부분이다.**

    처음 설계는 "거래량 동반 + 다음 봉 지속 확인"으로 A/B/C를 나눴다.
    30종목 60일로 재보니 **역방향이었다**:

        신뢰도 A  802건  정밀도 0.064
        신뢰도 B   53건  정밀도 0.170   ← B가 A보다 정확하다
        신뢰도 C    2건  —

    두 가지가 잘못됐다.
      1. 조건이 너무 흔해서 94%가 A로 몰렸다. 나누지 못하는 등급은 등급이 아니다.
      2. **지속 확인이 단타에서는 역효과다.** 다음 봉까지 움직임이 유지됐다는 건
         이미 그만큼 갔다는 뜻이라, 남은 폭이 오히려 작다. 확인을 기다리는 사이
         기회가 사라진다. 스윙에서는 맞는 장치지만 5분 축에서는 아니다.

    그래서 실제로 정밀도를 나누는 것만 남겼다 (같은 표본에서 측정):

        절대 크기(move_sigma)      <0.6σ 0.038 → 0.6~1σ 0.065 → 1~1.6σ 0.112 → >1.6σ 0.333
        채널 우선순위              1군 0.089 → 2군 0.013 → 3군 0.025
        동시에 켜진 채널 수         1개 0.066 → 2개 0.083 → 3개 0.333
        갭 채널 포함               0.240 (단일 채널 중 최고)
        거래량 동반(vol_z>1)       0.043 → 0.079 (약하지만 단조)

    반면 **|z| 점수 자체는 정밀도를 나누지 못했다** (0.205 / 0.108 / 0.029 / 0.047 / 0.162,
    비단조). 임계값을 분위수로 잡은 게 옳았다는 증거이기도 하다 —
    z의 절대 크기에는 의미가 없고 순위에만 의미가 있다.

    `confirmed`는 등급에서 뺐지만 출력에는 남긴다. 사용자가 "확인된 신호인지"를
    알고 싶어하는 것과, 그게 수익 기회를 예측하는지는 다른 문제다.
    """
    pts = 0
    if np.isfinite(move_sigma):
        pts += 2 if move_sigma >= 1.0 else (1 if move_sigma >= 0.6 else 0)
    names = [h["name"] for h in hits]
    top_priority = min(CHANNELS[n][1] for n in names)
    if top_priority == 1:
        pts += 2
    if "갭" in names:
        pts += 1
    if len(hits) >= 2:
        pts += 1
    if np.isfinite(vol_z) and vol_z > 1.0:
        pts += 1
    if any(abs(h["z"]) >= CEILING_Z for h in hits):
        pts += 1
    return "A" if pts >= 5 else ("B" if pts >= 3 else "C")


# --------------------------------------------------------------------------
# 3. 문안 생성 — UI는 이 문자열만 읽는다
# --------------------------------------------------------------------------
def josa(word: str, pair: tuple[str, str]) -> str:
    """받침에 따라 조사 선택. 음성으로 읽히므로 틀리면 바로 어색하다."""
    if not word:
        return pair[1]
    ch = word[-1]
    if "가" <= ch <= "힣":
        return pair[0] if (ord(ch) - 0xAC00) % 28 else pair[1]
    return pair[1]


def _clock(ts: pd.Timestamp, horizon: str) -> str:
    if HORIZONS[horizon]["interval"] == "1d":
        return ts.strftime("%m월 %d일")
    return f"{ts.hour}시 {ts.minute:02d}분"


def describe(feat: pd.DataFrame, i: int, hits: list[dict], name: str,
             horizon: str = "intraday", confidence: str = "B") -> str:
    """
    알림 → TTS 한 덩어리.

    원칙 세 가지
      1. **숫자를 먼저.** 화면을 못 보는 사용자는 "급등했습니다" 뒤에 숫자가 나오면
         앞부분을 다시 들어야 한다.
      2. **전문용어를 풀어서.** VWAP은 "평균 체결가"로 읽는다.
      3. **오늘 누적을 항상 붙인다.** 5분 움직임만 들으면 지금이 어디인지 모른다.
    """
    row = feat.iloc[i]
    when = _clock(feat.index[i], horizon)
    ret = float(row.get("ret", np.nan))
    up = ret > 0 if np.isfinite(ret) else float(row.get("excess_z", 0)) > 0

    parts = []
    names = [h["name"] for h in hits]

    if "갭" in names:
        g = float(row.get("gap", np.nan)) * 100
        parts.append(f"전일 종가 대비 {abs(g):.1f}퍼센트 갭 {'상승' if g > 0 else '하락'} 출발")
    if "가격" in names and np.isfinite(ret):
        parts.append(f"{abs(ret) * 100:.1f}퍼센트 {'상승' if up else '하락'}")
    if "추세가속" in names:
        th = float(row.get("thrust", np.nan)) * 100
        mins = 3 * (5 if HORIZONS[horizon]["interval"] == "5m" else 1)
        if np.isfinite(th):
            parts.append(f"{mins}분간 시장 대비 {abs(th):.1f}퍼센트 "
                         f"연속 {'상승' if th > 0 else '하락'}")
    if "일중신고가" in names:
        hl = float(row.get("hilo", 0.0)) * 100
        edge = f" {abs(hl):.1f}퍼센트 차이로" if abs(hl) >= 0.05 else ""
        parts.append(f"오늘 고가를{edge} 새로 썼습니다" if hl > 0
                     else f"오늘 저가를{edge} 새로 썼습니다")
    if "VWAP이탈" in names:
        vd = float(row.get("vwap_dev", np.nan)) * 100
        if np.isfinite(vd):
            parts.append(f"평균 체결가보다 {abs(vd):.1f}퍼센트 "
                         f"{'위' if vd > 0 else '아래'}")
    if "거래량" in names:
        m = float(row.get("vol_mult", np.nan))
        if np.isfinite(m) and m > 0:
            parts.append(f"거래량이 같은 시간대 평소의 {m:.1f}배")
    if "변동성" in names and len(parts) == 0:
        parts.append("변동성이 커지고 있습니다")

    if not parts:
        parts.append("평소와 다른 움직임")

    body = ", ".join(p for p in parts if not p.endswith("니다"))
    tail_sentences = [p for p in parts if p.endswith("니다")]

    head = f"{when}, {name}."
    if body:
        head += f" {body}입니다."
    for s in tail_sentences:
        head += f" {s}."

    day = float(row.get("day_ret", np.nan))
    if np.isfinite(day) and HORIZONS[horizon]["interval"] != "1d":
        head += f" 오늘 시가 대비 {day * 100:+.1f}퍼센트."
    if confidence == "B" and HORIZONS[horizon]["interval"] != "1d":
        head += " 아직 확인 중인 신호입니다."
    return head


# --------------------------------------------------------------------------
# 4. 전체 스캔 (백테스트 + 장중 공용)
# --------------------------------------------------------------------------
def scan(feat: pd.DataFrame, code: str, name: str, horizon: str = "intraday",
         thresholds: dict[str, float] | None = None,
         start: int | None = None, budget: float | None = None,
         cooldown_min: int | None = None, min_confidence: str = "C",
         min_move_sigma: float = MIN_MOVE_SIGMA) -> list[Alert]:
    """
    구간 스캔 → 알림 목록.

    같은 봉에서 여러 채널이 켜지면 **하나의 알림으로 병합**한다.
    큰 움직임은 가격·추세·신고가·VWAP을 동시에 켜므로, 병합하지 않으면
    같은 사건을 네 번 읽는다. 06에서 "시장 급락일을 8건으로 쪼개 읽는다"고
    잡은 문제의 장중 판이다.
    """
    if thresholds is None:
        thresholds = calibrate(feat, horizon, budget=budget)
    cd = pd.Timedelta(minutes=cooldown_min if cooldown_min is not None
                      else HORIZONS[horizon]["cooldown_min"])
    order = {"A": 0, "B": 1, "C": 2}
    min_rank = order[min_confidence]

    out: list[Alert] = []
    last_fire: dict[str, pd.Timestamp] = {}
    n = len(feat)
    start = start if start is not None else 0

    for i in range(max(start, 1), n):
        hits = scan_bar(feat, i, thresholds, horizon, min_move_sigma)
        if not hits:
            continue

        ts = feat.index[i]
        kept = []
        for h in hits:
            prev = last_fire.get(h["name"])
            if prev is not None and (ts - prev) < cd:
                continue
            kept.append(h)
        if not kept:
            continue
        for h in kept:
            last_fire[h["name"]] = ts

        ret = float(feat["ret"].iloc[i]) if "ret" in feat else np.nan
        direction = 1 if (ret > 0 if np.isfinite(ret) else True) else -1
        confirmed = _confirm(feat, i, direction, horizon)
        conf = _grade(kept,
                      float(feat["vol_z"].iloc[i]) if "vol_z" in feat else np.nan,
                      confirmed,
                      float(feat["move_sigma"].iloc[i]) if "move_sigma" in feat else np.nan)
        if order[conf] > min_rank:
            continue

        prio = min(CHANNELS[h["name"]][1] for h in kept)
        out.append(Alert(
            ts=ts, code=code, name=name, horizon=horizon,
            channels=kept, priority=prio, confidence=conf,
            direction="상승" if direction > 0 else "하락",
            score=max(abs(h["z"]) for h in kept),
            ret_pct=round(ret * 100, 2) if np.isfinite(ret) else 0.0,
            day_ret_pct=round(float(feat["day_ret"].iloc[i]) * 100, 2)
            if "day_ret" in feat and np.isfinite(feat["day_ret"].iloc[i]) else 0.0,
            price=float(feat["close"].iloc[i]),
            text=describe(feat, i, kept, name, horizon, conf),
            confirmed=confirmed, bar_index=i,
        ))
    return out


# --------------------------------------------------------------------------
# 4-2. 학습 랭커 경로 (2단)
# --------------------------------------------------------------------------
def scan_ranked(feat: pd.DataFrame, code: str, name: str,
                horizon: str = "intraday", budget: float | None = None,
                start: int | None = None, deep=None,
                cooldown_min: int | None = None, min_confidence: str = "C",
                scan_tail: int | None = None) -> list[Alert] | None:
    """
    규칙 대신 **학습 랭커**가 고르는 경로.

    규칙 경로와 같은 Alert를 만들고 같은 문안 생성기를 쓴다.
    바뀌는 건 "무엇을 알릴까"를 정하는 방법 하나뿐이다.

    모델이 없으면 **None**을 반환한다. 호출부는 규칙 경로로 폴백해야 한다 —
    학습 산출물 없이도 도구가 완전히 동작하는 것이 이 프로젝트의 전제다
    (오프라인 보장, 02에서 트랜스포머를 1단에서 뺀 것과 같은 이유).
    """
    from . import ranker as R

    sel = R.select(feat, horizon, budget=budget, deep=deep,
                   scan_tail=scan_tail, cache_key=f"{code}:{horizon}")
    if sel is None:
        return None
    if not len(sel):
        return []

    fcols = [c for c in sel.columns if c.startswith(R.FIRED_PREFIX)]
    cd = pd.Timedelta(minutes=cooldown_min if cooldown_min is not None
                      else HORIZONS[horizon]["cooldown_min"])
    order = {"A": 0, "B": 1, "C": 2}
    min_rank = order[min_confidence]
    start_ts = feat.index[start] if start is not None and start < len(feat) else None

    out: list[Alert] = []
    last_fire: dict[str, pd.Timestamp] = {}
    # 위치 조회는 인덱서로 한 번에. 호출마다 4,285개짜리 dict를 만들던 게
    # 실시간 경로에서 종목당 28ms를 먹고 있었다.
    positions = dict(zip(sel.index, feat.index.get_indexer(sel.index)))

    for ts, row in sel.iterrows():
        if start_ts is not None and ts <= start_ts:
            continue
        if order[row["grade"]] > min_rank:
            continue

        names = [c[len(R.FIRED_PREFIX):] for c in fcols if bool(row[c])]
        if not names:
            continue
        kept = []
        for n in names:
            prev = last_fire.get(n)
            if prev is not None and (ts - prev) < cd:
                continue
            kept.append({"name": n,
                         "z": round(float(feat[CHANNELS[n][0]].get(ts, np.nan)), 2),
                         "threshold": None})
        if not kept:
            continue
        for h in kept:
            last_fire[h["name"]] = ts

        i = int(positions[ts])
        if i < 0:
            continue
        ret = float(feat["ret"].iloc[i]) if "ret" in feat else np.nan
        direction = 1 if (ret > 0 if np.isfinite(ret) else True) else -1
        prio = min(CHANNELS[h["name"]][1] for h in kept)
        out.append(Alert(
            ts=ts, code=code, name=name, horizon=horizon, channels=kept,
            priority=prio, confidence=row["grade"],
            direction="상승" if direction > 0 else "하락",
            score=round(float(row["prob"]), 4),
            ret_pct=round(ret * 100, 2) if np.isfinite(ret) else 0.0,
            day_ret_pct=round(float(feat["day_ret"].iloc[i]) * 100, 2)
            if "day_ret" in feat and np.isfinite(feat["day_ret"].iloc[i]) else 0.0,
            price=float(feat["close"].iloc[i]),
            text=describe(feat, i, kept, name, horizon, row["grade"]),
            confirmed=_confirm(feat, i, direction, horizon), bar_index=i,
        ))
    return out


# --------------------------------------------------------------------------
# 5. 시장 이벤트 — 같은 얘기를 N번 읽지 않기 위해
# --------------------------------------------------------------------------
def find_market_bars(panel: dict, horizon: str = "intraday",
                     lookback: int = 400) -> dict[pd.Timestamp, tuple[str, int]]:
    """
    관심종목의 40% 이상이 **같은 방향으로** 평소보다 크게 움직인 봉.

    06에서 세 번 만에 맞춘 정의를 장중으로 옮긴 것이다.
      1차: 지수 z-score > 3 → 129일 중 56일이 시장일. 무의미했다
      2차: 알림 목록에서 판정 → 필터를 거친 뒤라 0일. 판정 대상이 틀렸다
      3차: 가격 데이터에서 직접, 같은 방향 breadth 40% ← 이것

    방향 조건이 없으면 뒤죽박죽 움직인 봉도 "함께 상승했습니다"가 된다.
    """
    n_watch = len(panel)
    if n_watch < MARKET_MIN:
        return {}
    need = max(MARKET_MIN, int(np.ceil(n_watch * MARKET_BREADTH)))

    frames = []
    for code, item in panel.items():
        # pipeline.build_panel은 {코드: (이름, 피처)} 형식을 준다. 둘 다 받는다 —
        # 형식이 갈리면 장중 루프에서만 터진다 (실제로 그 버그를 냈다).
        feat = item[1] if isinstance(item, tuple) else item
        if "ret" not in feat.columns:
            continue
        r = feat["ret"].tail(lookback)
        sd = feat["ret"].rolling(HORIZONS[horizon]["baseline_bars"],
                                 min_periods=20).std().tail(lookback)
        sig = pd.Series(0, index=r.index, dtype=int)
        sig[r > MARKET_SIGMA * sd] = 1
        sig[r < -MARKET_SIGMA * sd] = -1
        frames.append(sig.rename(code))
    if not frames:
        return {}

    m = pd.concat(frames, axis=1).fillna(0)
    up = (m > 0).sum(axis=1)
    dn = (m < 0).sum(axis=1)
    out = {}
    for ts in m.index:
        u, d = int(up.loc[ts]), int(dn.loc[ts])
        if u >= need and u > d:
            out[ts] = ("상승", u)
        elif d >= need and d > u:
            out[ts] = ("하락", d)
    return out


def merge_market_events(alerts: list[Alert],
                        market_bars: dict[pd.Timestamp, tuple[str, int]],
                        n_watch: int) -> list[str]:
    """
    같은 봉의 알림들을 시장 이벤트 한 문장 + 예외 종목으로 줄인다.

    시장 방향과 **반대로 간 종목**은 따로 짚어준다. 시장이 빠지는데 혼자 오른
    종목이야말로 개별 이슈가 있는 종목이라, 오히려 그게 알릴 값어치가 있다.
    """
    if not alerts:
        return []
    by_ts: dict[pd.Timestamp, list[Alert]] = {}
    for a in alerts:
        by_ts.setdefault(a.ts, []).append(a)

    msgs = []
    for ts in sorted(by_ts):
        group = by_ts[ts]
        info = market_bars.get(ts)
        if info is None or len(group) < 2:
            msgs.extend(a.text for a in group if a.voice)
            continue

        direction, breadth = info
        for a in group:
            a.market_event = True
        same = [a for a in group if a.direction == direction]
        opp = [a for a in group if a.direction != direction]

        when = _clock(ts, group[0].horizon)
        msgs.append(f"{when}, 관심종목 {n_watch}개 중 {breadth}개가 함께 {direction}했습니다. "
                    f"개별 종목 문제가 아니라 시장 전반의 움직임으로 보입니다.")
        same.sort(key=lambda a: -abs(a.ret_pct))
        for k, a in enumerate(same[:2]):
            head = "그중" if k == 0 else "그다음으로"
            msgs.append(f"{head} {a.name}{josa(a.name, ('이', '가'))} 크게 움직였습니다. {a.text}")
        for a in opp:
            msgs.append(f"반면 {a.name}{josa(a.name, ('은', '는'))} 시장과 반대로 갔습니다. {a.text}")
    return msgs


# --------------------------------------------------------------------------
# 6. 관심종목 일괄 (실시간 경로)
# --------------------------------------------------------------------------
def scan_panel(panel: dict[str, tuple[str, pd.DataFrame]], horizon: str = "intraday",
               budget: float | None = None, since: pd.Timestamp | None = None,
               min_confidence: str = "C",
               thresholds: dict[str, dict] | None = None,
               watchlist_budget: float | None = None,
               min_move_sigma: float = MIN_MOVE_SIGMA,
               use_ranker: bool = True,
               scan_tail: int | None = None) -> list[Alert]:
    """
    관심종목 전체 스캔. `since` 이후 봉만 본다 (장중 폴링용).

    임계값은 종목마다 따로 보정한다. 같은 |z|가 대형주와 소형주에서
    전혀 다른 의미인 것과 같은 이유로, 종목별 분포에서 뽑아야 한다.
    """
    if budget is None:
        budget = per_stock_budget(horizon, len(panel), watchlist_budget)
    out: list[Alert] = []
    for code, (name, feat) in panel.items():
        start = 1
        if since is not None:
            pos = feat.index.searchsorted(since, side="right")
            start = max(1, int(pos))

        got = None
        if use_ranker:
            # 학습 랭커가 있으면 그쪽이 고른다. 없으면 조용히 규칙 경로로 내려간다 —
            # 학습 산출물 없이도 도구가 완전히 동작해야 한다.
            got = scan_ranked(feat, code, name, horizon, budget=budget,
                              start=start if since is not None else None,
                              min_confidence=min_confidence, scan_tail=scan_tail)
        if got is None:
            thr = (thresholds or {}).get(code) or calibrate(feat, horizon, budget=budget)
            got = scan(feat, code, name, horizon, thresholds=thr, start=start,
                       min_confidence=min_confidence, min_move_sigma=min_move_sigma)
        out.extend(got)
    out.sort(key=lambda a: (a.ts, a.priority, -a.score))
    return out
