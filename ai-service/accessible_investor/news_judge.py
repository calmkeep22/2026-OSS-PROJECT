"""
관심종목 투자판단 — 뉴스 + 가격 + 이상신호를 한 문장으로.

이건 예측이 아니다
==================
이 모듈은 **다음날 주가를 맞히지 않는다.** 맞힐 수 없다는 걸 세 번 독립적으로
측정했고, 결과는 `outputs/validation/` 에 그대로 남아 있다.

    국내 뉴스 3-way (10_news_predict_gbm.json)   균형정확도 0.538 · AUC 0.511
    해외 xforecast 어블레이션 (11_overseas_*.csv) 최고 AUC 0.5105 · 무지성 미달
    사용자 원 실험 (xforecast/experiment/results)  HR 0.5133 vs 무지성 0.537

그래서 이 모듈이 하는 일은 다른 것이다.

무엇을 하는가
=============
일반 투자자는 종목 화면을 30초 훑고 대충 안다 — 뉴스 분위기가 어떤지,
지금 값이 평소보다 높은지, 오늘 뭔가 이상한 일이 있었는지. **그 30초를
저시력 사용자에게 귀로 돌려주는 것**이 이 모듈이다.

    "삼성전자, 관망입니다. 판단 점수 +12점.
     뉴스 분위기는 약간 긍정, 최근 기사 14건으로 평소의 2.1배입니다.
     주가는 20일선 위 3.2퍼센트로 다소 높은 자리입니다.
     오늘 거래량 이상 신호가 1건 있었습니다.
     근거가 엇갈려 확신도는 낮음입니다."

다섯 요인을 각각 점수화하고 합산해 5단계로 나눈다. 중요한 건 합계가 아니라
**요인별 근거를 그대로 읽어준다는 것**이다. 점수만 주면 사용자는 판단할 수
없고, 근거를 주면 판단할 수 있다. 접근성 도구의 목적은 대신 결정해 주는 게
아니라 **같은 정보에 닿게 하는 것**이다.

가중치는 어떻게 정했나
======================
예측력이 없으므로 "수익률에 최적화된 가중치"라는 건 존재하지 않는다.
대신 **사람이 화면에서 실제로 보는 것의 비중**으로 정했다. 뉴스 화면을 열면
제목과 건수가 먼저 보이고(감성·집중도), 차트에서 이동평균 대비 위치를 보고,
당일 특이사항을 본다. `WEIGHTS` 의 값은 그 순서를 반영한 것이며,
수익률로 튜닝한 값이 아니다 — 튜닝했다면 그게 곧 과적합이다.

`validate()` 로 이 판단이 실제 수익률과 관계있는지 재고, 결과를
숨기지 않고 리포트에 싣는다.
"""

from __future__ import annotations

import json

import numpy as np
import pandas as pd

from . import data as D
from . import features as F
from . import news as N
from .anomaly import josa
from .config import OUTPUT_DIR

# --------------------------------------------------------------------------
# 판단 구간과 가중치
# --------------------------------------------------------------------------
# 5단계. 경계는 ±40 / ±15 다. ±15 안쪽을 관망으로 넓게 잡은 이유는,
# 근거가 약할 때 단정적으로 말하면 접근성 도구가 아니라 투자권유가 되기 때문이다.
STANCES = [
    (40, "매수 우위", "buy"),
    (15, "약한 매수 우위", "weak_buy"),
    (-15, "관망", "hold"),
    (-40, "약한 매도 우위", "weak_sell"),
    (-999, "매도 우위", "sell"),
]

WEIGHTS = {
    "뉴스감성": 30.0,       # 뉴스 화면에서 가장 먼저 닿는 정보
    "뉴스집중도": 15.0,     # 기사가 몰리면 뭔가 있다. 방향은 감성이 정한다
    "추세위치": 25.0,       # 20일선·60일선 대비 위치
    "과열도": 20.0,         # 60일 고저 대비 위치. 극단이면 반대 방향 가점
    "이상신호": 10.0,       # 오늘 이상감지 알림
}

# 확신도를 깎는 조건. 근거가 엇갈리거나 표본이 적으면 낮춰야 한다.
MIN_ARTICLES_FOR_NEWS = 3

# 감성이 이만큼은 돼야 "방향이 있다"고 본다.
# ⚠️ 처음엔 5로 뒀다가 문제를 발견했다. 삼성전자 실측에서 감성 +6.5점
# (사실상 중립)인데 기사량이 평소의 2.4배라는 이유로 집중도가 +6.4점을
# 받았다. 거의 중립인 감성에서 부호를 빌려 온 것이다. 15로 올려서
# "분위기가 실제로 한쪽으로 기울었을 때만" 집중도가 부호를 갖게 했다.
SENT_SIGN_MIN = 15.0


def _clip(x: float, lo: float = -1.0, hi: float = 1.0) -> float:
    return float(np.clip(x, lo, hi)) if np.isfinite(x) else 0.0


def _stance(score: float) -> tuple[str, str]:
    for thr, label, key in STANCES:
        if score >= thr:
            return label, key
    return STANCES[-1][1], STANCES[-1][2]


# --------------------------------------------------------------------------
# 요인별 점수
# --------------------------------------------------------------------------
def _factor_news(res: dict | None) -> tuple[float, str]:
    """
    뉴스 극성 → -1~+1 과 설명 문장.

    ⚠️ `res["score"]`(감성 지수)를 그대로 쓰면 안 된다.
    그 값은 **전 사건의 가중 평균**이다. 브리핑("오늘 분위기 어때?")에는 맞지만
    매수/매도 판단에는 틀린 집계다 — 기사가 많을수록 0으로 수렴하기 때문이다.

    실측: 5종목 판단에서 기사 7~91건인데 감성 기여가 전부 ±6점 안이었다.
    가중치를 30으로 줬는데 실제로는 2점짜리로 동작했다. 평균이 신호를 지운 것이다.

    투자판단에서 중요한 건 평균이 아니라 **가장 무거운 사건이 어느 쪽인가**다.
    악재 하나가 잔잔한 기사 50건보다 중요하다. 그래서 영향도(impact) 상위
    사건들만 impact 가중으로 평균한다.
    """
    if not res or res["n_articles"] < MIN_ARTICLES_FOR_NEWS:
        n = res["n_articles"] if res else 0
        return 0.0, f"최근 기사가 {n}건뿐이라 뉴스 분위기는 판단하지 않았습니다"

    ev = [e for e in (res.get("events") or []) if np.isfinite(e.get("polarity", np.nan))]
    if not ev:
        return _clip(res["score"] / 100.0), f"뉴스 분위기는 {res['label']}입니다"

    pol = np.array([e["polarity"] for e in ev[:5]], dtype=float)
    imp = np.array([max(e.get("impact", 0.0), 0.0) for e in ev[:5]], dtype=float)
    s = float(np.average(pol, weights=imp) if imp.sum() > 1e-9 else pol.mean())

    top = ev[0]
    if pol[0] <= -0.5:
        # 강한 악재는 평균에 묻히면 안 된다. 하한을 눌러 둔다.
        s = min(s, -0.5)
    tone = ("긍정적인" if s > 0.15 else "부정적인" if s < -0.15 else "중립적인")
    return _clip(s), (f"주요 뉴스는 {tone} 쪽입니다. "
                      f"가장 큰 사건은 {top['title']}")


def _factor_velocity(res: dict | None, baseline: float,
                     news_factor: float) -> tuple[float, str]:
    """
    기사량이 평소보다 많은가.

    방향은 없다 — 기사가 몰린다는 건 "뭔가 있다"까지만 말한다. 그래서
    **뉴스 극성의 부호를 빌려 쓴다.** 극성이 0이면 이 요인도 0이 된다.
    """
    if not res or baseline <= 0:
        return 0.0, ""
    mult = res["n_articles"] / baseline
    if mult < 1.5:
        return 0.0, "기사량은 평소 수준입니다"
    text = f"최근 기사 {res['n_articles']}건으로 평소의 {mult:.1f}배입니다"
    if abs(news_factor) < SENT_SIGN_MIN / 100.0:
        # 기사는 몰렸는데 분위기가 한쪽이 아니다. 사용자에게 알리되 점수는 0.
        return 0.0, text + ", 다만 분위기는 한쪽으로 기울지 않았습니다"
    # log 로 눌러야 20배짜리 이벤트가 판단 전체를 삼키지 않는다
    mag = _clip(np.log2(mult) / 3.0, 0.0, 1.0)
    return float(np.sign(news_factor) * mag), text


def _factor_trend(feat: pd.DataFrame) -> tuple[float, str]:
    """20일선·60일선 대비 위치. 추세추종이 아니라 '지금 어디 있나'를 말한다."""
    last = feat.iloc[-1]
    d20 = last.get("ma_dev_20", np.nan)
    d60 = last.get("ma_dev_60", np.nan)
    if not np.isfinite(d20):
        return 0.0, ""
    # ±10%를 만점으로 본다. 그 이상은 과열도 요인이 받는다
    s = _clip((d20 * 0.6 + (d60 if np.isfinite(d60) else d20) * 0.4) / 0.10)
    where = "위" if d20 >= 0 else "아래"
    return s, f"주가는 20일선 {where} {abs(d20)*100:.1f}퍼센트 자리입니다"


def _factor_overheat(feat: pd.DataFrame) -> tuple[float, str]:
    """
    60일 고저 대비 위치. **부호가 반대다.**

    고점에 붙어 있으면 추세 요인이 이미 가점을 줬다. 여기서 같은 방향으로
    또 주면 한 사실을 두 번 세는 것이다. 이 요인은 "그래서 살 자리인가"를
    묻고, 고점 근처면 감점한다.
    """
    last = feat.iloc[-1]
    dh = last.get("dist_high_60", np.nan)
    dl = last.get("dist_low_60", np.nan)
    if not np.isfinite(dh) or not np.isfinite(dl):
        # 일봉 피처에 없으면 직접 계산
        c = feat["close"]
        dh = float(c.iloc[-1] / c.tail(60).max() - 1)
        dl = float(c.iloc[-1] / c.tail(60).min() - 1)
    if dh > -0.02:
        return -0.7, "60일 최고가 부근으로 부담스러운 자리입니다"
    if dl < 0.02:
        return 0.7, "60일 최저가 부근입니다"
    # 중간 구간은 완만하게. 위쪽일수록 감점
    span = dl - dh                       # 항상 양수
    pos = dl / span if span > 1e-9 else 0.5      # 0=저점 1=고점
    where = ("위쪽" if pos > 0.66 else "아래쪽" if pos < 0.33 else "중간")
    return _clip((0.5 - pos) * 1.2), f"60일 구간의 {where}에 있습니다"


def _alert_direction(a) -> float:
    """
    알림 하나의 방향. `anomaly.Alert` 는 dataclass 이고 `direction` 이
    "상승"/"하락" 문자열이다. dict 로 넘어오는 경우도 받아 준다.
    """
    d = a.get("direction") if isinstance(a, dict) else getattr(a, "direction", "")
    if d == "상승":
        return 1.0
    if d == "하락":
        return -1.0
    # direction 이 비어 있으면 수익률 부호로 폴백
    r = (a.get("ret_pct", 0.0) if isinstance(a, dict)
         else getattr(a, "ret_pct", 0.0))
    return float(np.sign(r)) if np.isfinite(r) else 0.0


def _factor_alert(alerts: list | None) -> tuple[float, str]:
    """
    오늘 이상감지 알림.

    개수가 아니라 **방향의 평균**이 부호를 정한다. 상승 알림과 하락 알림이
    같이 있으면 상쇄돼 0에 가까워지는데, 그게 맞다 — 양방향으로 튀는 날은
    방향을 말할 수 없다. 세기는 3건에서 포화시킨다.
    """
    if not alerts:
        return 0.0, "오늘 이상 신호는 없습니다"
    n = len(alerts)
    s = float(np.mean([_alert_direction(a) for a in alerts]))
    up = sum(1 for a in alerts if _alert_direction(a) > 0)
    if up and up < n:
        text = f"오늘 이상 신호가 {n}건 있습니다. 상승 {up}건, 하락 {n - up}건"
    else:
        text = (f"오늘 {'상승' if up else '하락'} 쪽 이상 신호가 {n}건 있습니다")
    return _clip(s * min(n, 3) / 3.0), text


# --------------------------------------------------------------------------
# 판단
# --------------------------------------------------------------------------
def judge(corp_name: str, code: str | None = None, days: int = 3,
          alerts: list | None = None, news_res: dict | None = None,
          verbose: bool = False) -> dict:
    """
    관심종목 하나 → 투자판단.

    days
        뉴스 조회 기간. 단타 사용자는 1~3일, 스윙은 7일이 맞다.
    alerts
        오늘 이상감지 결과. 없으면 이 요인은 0점 처리한다.
    """
    if code is None:
        hit = D.resolve(corp_name)
        if hit is None:
            return {"corp": corp_name, "code": None, "error": "종목을 찾을 수 없음",
                    "stance": "판단 보류", "score": 0.0}
        code, corp_name = hit
    if news_res is None:
        news_res = N.analyze(corp_name, days=days, verbose=verbose)

    daily = D.load_daily(code, auto_download=True) if code else None
    if daily is None or len(daily) < 60:
        return {"corp": corp_name, "code": code, "error": "일봉 데이터 부족",
                "stance": "판단 보류", "score": 0.0}
    feat = F.add_daily_features(daily, index=D.load_market_index())

    # 평소 하루 기사량. 아카이브가 있으면 그걸로, 없으면 보수적으로 3건.
    arch = N.archive_load(corp_name)
    baseline = (len(arch) / max(arch["ts"].dt.date.nunique(), 1)
                if len(arch) else 3.0) * days

    factors = {}
    fn, tn = _factor_news(news_res)
    fv, tv = _factor_velocity(news_res, baseline, fn)
    ft, tt = _factor_trend(feat)
    fo, to = _factor_overheat(feat)
    fa, ta = _factor_alert(alerts)
    for name, val, text in (("뉴스감성", fn, tn), ("뉴스집중도", fv, tv),
                            ("추세위치", ft, tt), ("과열도", fo, to),
                            ("이상신호", fa, ta)):
        factors[name] = {"값": round(val, 3),
                         "기여": round(val * WEIGHTS[name], 1),
                         "설명": text}

    score = float(sum(f["기여"] for f in factors.values()))
    label, key = _stance(score)

    # --- 확신도 --------------------------------------------------------
    # 요인들이 서로 반대를 가리키면 낮춰야 한다. 합계만 보면 +30과 -30이
    # 상쇄돼 0이 되는데, 그건 "중립"이 아니라 "모른다"다.
    vals = [f["기여"] for f in factors.values() if abs(f["기여"]) > 1]
    agree = (abs(sum(vals)) / sum(abs(v) for v in vals)) if vals else 0.0
    n_art = news_res["n_articles"] if news_res else 0

    # 방향 일치만으로는 부족하다. 약한 요인 셋이 같은 쪽을 가리켜도
    # 일치도는 1.0이 나오는데, 그건 확신이 아니라 그냥 조용한 상태다.
    # 가장 센 요인의 절대 세기를 같이 곱해서 그 착시를 없앤다.
    strength = _clip(max((abs(f["값"]) for f in factors.values()), default=0.0),
                     0.0, 1.0)
    # ⚠️ 근거 개수도 봐야 한다. 실측에서 NAVER 가 기사 7건 · 실질 요인 1개
    # (20일선 위)뿐인데 확신도 "높음"이 나왔다. 요인 하나로 내린 판단을
    # 확신한다고 말하면 안 된다. 2개 미만이면 절반으로 깎는다.
    breadth = 1.0 if len(vals) >= 3 else 0.8 if len(vals) == 2 else 0.5
    sample = (1.0 if n_art >= 10 else 0.75 if n_art >= 5
              else 0.6 if n_art >= MIN_ARTICLES_FOR_NEWS else 0.35)
    conf_score = agree * sample * breadth * (0.4 + 0.6 * strength)
    conf = "높음" if conf_score >= 0.6 else "보통" if conf_score >= 0.35 else "낮음"

    out = {
        "corp": corp_name, "code": code, "score": round(score, 1),
        "stance": label, "stance_key": key,
        "confidence": conf, "confidence_score": round(conf_score, 3),
        "agreement": round(agree, 3), "strength": round(strength, 3),
        "n_articles": n_art, "factors": factors,
        "as_of": str(feat.index[-1].date()),
        "close": float(feat["close"].iloc[-1]),
    }
    out["speech"] = speak(out)
    return out


def speak(j: dict) -> str:
    """TTS가 그대로 읽을 문안. UI는 이 문자열만 넘기면 된다."""
    if j.get("error"):
        return f"{j['corp']}은 데이터가 부족해 판단할 수 없습니다."

    # 조사를 안 맞추면 "셀트리온은" 이 "셀트리온는" 으로 읽힌다.
    lines = [f"{j['corp']}{josa(j['corp'], ('은', '는'))} "
             f"{j['stance']}입니다. 판단 점수 {j['score']:+.0f}점."]
    # 기여도 큰 순으로 근거를 읽는다. 설명이 없는 요인은 건너뛴다.
    ranked = sorted(j["factors"].items(), key=lambda kv: -abs(kv[1]["기여"]))
    for _name, f in ranked:
        if f["설명"]:
            lines.append(f["설명"] + ".")
    lines.append(f"확신도는 {j['confidence']}입니다.")
    if j["confidence"] == "낮음":
        lines.append("근거가 엇갈리거나 부족합니다.")
    lines.append("이 판단은 예측이 아니라 현재 상태 요약이며, "
                 "투자 결정은 본인이 하셔야 합니다.")
    return " ".join(lines)


def scan_alerts(codes: list[str], horizon: str = "intraday",
                verbose: bool = False) -> dict[str, list]:
    """
    관심종목의 오늘 이상 신호를 모아 `{코드: [Alert, ...]}` 로 준다.

    이게 없으면 `_factor_alert` 가 항상 0점이 되어 다섯 요인 중 하나가
    죽는다. 실제로 그 상태로 한동안 돌고 있었다.
    """
    from . import anomaly as A
    from . import pipeline as P

    try:
        panel = P.build_panel(codes, horizon, verbose=verbose)
        if not panel:
            return {}
        # 오늘(마지막 세션)분만 본다 — 판단은 "지금 상태" 요약이다
        last = max(f.index[-1] for _n, f in panel.values())
        since = pd.Timestamp(last).normalize()
        alerts = A.scan_panel(panel, horizon, since=since)
    except Exception as e:
        if verbose:
            print(f"  이상감지 건너뜀 — {type(e).__name__}: {e}")
        return {}

    out: dict[str, list] = {}
    for a in alerts:
        out.setdefault(a.code, []).append(a)
    return out


def judge_many(names: list[str], days: int = 3, alerts_by_code: dict | None = None,
               with_alerts: bool = True, verbose: bool = True) -> pd.DataFrame:
    """
    관심종목 여러 개. 점수 높은 순으로 정렬해 돌려준다.

    alerts_by_code 를 안 넘기면 `with_alerts` 일 때 직접 스캔한다.
    """
    if alerts_by_code is None and with_alerts:
        codes = [h[0] for h in (D.resolve(n) for n in names) if h]
        alerts_by_code = scan_alerts(codes, verbose=verbose)
        if verbose:
            n_hit = sum(len(v) for v in alerts_by_code.values())
            print(f"  오늘 이상 신호 {n_hit}건 / {len(alerts_by_code)}종목")
    alerts_by_code = alerts_by_code or {}
    rows = []
    for nm in names:
        try:
            hit = D.resolve(nm)
            if hit is None:
                if verbose:
                    print(f"  {nm}: 종목을 찾을 수 없습니다")
                continue
            code, name = hit
            j = judge(name, code, days=days, alerts=alerts_by_code.get(code))
        except Exception as e:
            if verbose:
                print(f"  {nm}: {type(e).__name__} {e}")
            continue
        rows.append({"종목": j["corp"], "판단": j["stance"], "점수": j["score"],
                     "확신도": j["confidence"], "기사": j.get("n_articles", 0),
                     **{k: v["기여"] for k, v in j.get("factors", {}).items()},
                     "_speech": j.get("speech", "")})
        if verbose:
            print(f"  {j['corp']:10s} {j['stance']:12s} {j['score']:+6.1f} "
                  f"({j['confidence']})")
    df = pd.DataFrame(rows)
    return df.sort_values("점수", ascending=False).reset_index(drop=True) if len(df) else df


# --------------------------------------------------------------------------
# 정직한 검증
# --------------------------------------------------------------------------
def validate(codes: list[str] | None = None, horizon: int = 1,
             save: bool = True, verbose: bool = True) -> dict:
    """
    판단 점수가 이후 수익률과 관계있는가.

    ⚠️ 이 검증은 **뉴스 아카이브가 쌓인 날짜만큼만** 유효하다.

    실측으로 부딪힌 제약
    --------------------
    아카이브가 8일치(뉴스 거래일 4개)일 때 `horizon=5` 로 돌리면 **표본이 0**이
    나온다. 뉴스가 난 날로부터 5거래일 뒤 종가가 아직 존재하지 않기 때문이다.
    쓸 수 있는 뉴스일 수 = (아카이브 거래일) − horizon 이다.

    그래서 기본값을 1로 둔다. `cli.py collect-news` 를 매일 돌려 아카이브가
    자라면 그때 horizon 을 늘려서 다시 재면 된다.

    측정 지표
        스피어만 상관   점수 순위 ↔ 이후 **초과**수익 순위
        상하위 스프레드 상위 20% 평균 - 하위 20% 평균
    """
    from scipy.stats import spearmanr

    from . import pipeline as P
    codes = codes or P.watchlist_from_cache(60)
    arch = N.archive_load()
    if not len(arch):
        return {"verdict": "뉴스 아카이브가 비어 있어 검증할 수 없습니다"}

    rows = []
    idx = D.load_market_index()
    for code in codes:
        name = D.name_of(code)
        sub = arch[arch["corp"] == name]
        if len(sub) < MIN_ARTICLES_FOR_NEWS:
            continue
        daily = D.load_daily(code)
        if daily is None or len(daily) < 120:
            continue
        feat = F.add_daily_features(daily, index=idx)
        feat.index = pd.to_datetime(feat.index)
        scored = N.score_articles(N.dedup(sub.copy(), corp_name=name))
        nf = N.daily_features(scored, name,
                              baseline_per_day=len(scored) /
                              max(scored["ts"].dt.date.nunique(), 1))
        if not len(nf):
            continue
        # 이후 horizon 거래일 **초과**수익.
        # 원시 수익률을 쓰면 "그날 시장이 올랐나"를 재게 된다 — 우리가 묻는 건
        # 종목별 판단이 종목별 성과와 관계있는가다.
        # (⚠️ 처음엔 시장 성분을 빼는 항에 실수로 * 0 이 붙어 있어
        #  사실상 원시 수익률을 재고 있었다.)
        fwd = (feat["excess_ret"].rolling(horizon, min_periods=horizon).sum()
               .shift(-horizon))

        for _i, r in nf.iterrows():
            tday = pd.Timestamp(r["tday"])
            if tday not in feat.index:
                continue
            y = fwd.get(tday, np.nan)
            if not np.isfinite(y):
                continue
            # 그날 시점의 피처만으로 점수를 재구성한다 (미래 미사용)
            hist = feat.loc[:tday]
            if len(hist) < 60:
                continue
            # 그날의 뉴스 피처만으로 재구성한다. `events` 가 없으므로
            # `_factor_news` 는 감성 지수 경로로 폴백한다.
            fake_news = {"score": float(r.get("sent_mean", 0.0) * 100),
                         "label": "", "n_articles": int(r.get("n_articles", 0)),
                         "events": []}
            fn, _ = _factor_news(fake_news)
            # article_velocity = 그날 기사수 / 평소 하루 기사수. 역산해 평소치를 얻는다.
            base = (max(int(r.get("n_articles", 1)), 1)
                    / max(float(r.get("article_velocity", 1.0)), 0.1))
            fv, _ = _factor_velocity(fake_news, base, fn)
            ft, _ = _factor_trend(hist)
            fo, _ = _factor_overheat(hist)
            s = (fn * WEIGHTS["뉴스감성"] + fv * WEIGHTS["뉴스집중도"]
                 + ft * WEIGHTS["추세위치"] + fo * WEIGHTS["과열도"])
            rows.append({"code": code, "tday": tday, "score": s, "fwd": float(y)})

    df = pd.DataFrame(rows)
    if len(df) < 30:
        n_arch_days = int(arch["ts"].dt.date.nunique())
        out = {"n": int(len(df)), "horizon": horizon,
               "아카이브_거래일": n_arch_days,
               "verdict": "표본 부족으로 판단 불가",
               "원인": (f"아카이브가 {n_arch_days}일치인데 horizon={horizon}이라 "
                        f"뉴스일 이후 {horizon}거래일 종가가 아직 없습니다. "
                        "`python cli.py collect-news` 를 매일 돌려 아카이브를 "
                        "늘리거나 --horizon-days 를 줄이세요.")}
        if verbose:
            print(f"표본 {len(df)}행 — 검증할 수 없습니다.")
            print(f"  {out['원인']}")
        if save:
            d = OUTPUT_DIR / "validation"
            d.mkdir(parents=True, exist_ok=True)
            (d / "12_judge_validation.json").write_text(
                json.dumps(out, ensure_ascii=False, indent=2, default=str),
                encoding="utf-8")
        return out

    rho, p = spearmanr(df["score"], df["fwd"])
    q = df["score"].quantile([0.2, 0.8])
    top = df[df["score"] >= q.iloc[1]]["fwd"].mean()
    bot = df[df["score"] <= q.iloc[0]]["fwd"].mean()
    out = {"n": int(len(df)), "n_days": int(df["tday"].nunique()),
           "n_stocks": int(df["code"].nunique()), "horizon": horizon,
           "스피어만": round(float(rho), 4), "p값": round(float(p), 4),
           "상위20%평균": round(float(top), 5),
           "하위20%평균": round(float(bot), 5),
           "스프레드": round(float(top - bot), 5)}
    # ⚠️ p값을 액면가로 읽으면 안 된다.
    # 거래일이 며칠뿐이면 60종목이 **같은 날 같은 시장**을 공유한다.
    # 독립 표본은 169개가 아니라 사실상 거래일 수만큼이고, 그러면
    # 어떤 p값이든 신뢰할 수 없다. 실측에서 rho=-0.286, p=0.0002 가 나왔지만
    # 거래일이 3개였다 — 그 3일 동안 상승 추세 종목이 되돌린 것일 뿐이다.
    # 이걸 "점수를 뒤집으면 돈을 번다"로 읽는 것이 전형적인 과적합 함정이다.
    n_days = out["n_days"]
    if n_days < 10:
        out["verdict"] = (
            f"판단 불가 — 거래일이 {n_days}개뿐이라 {out['n']}행이 "
            f"사실상 {n_days}개의 독립 표본이다. "
            f"(참고 수치 rho={rho:+.3f}, p={p:.4f} — 신뢰할 수 없음)")
        out["신뢰가능"] = False
    elif p > 0.05:
        out["verdict"] = "점수와 이후 수익률 사이에 통계적으로 유의한 관계가 없다"
        out["신뢰가능"] = True
    else:
        direction = "같은" if rho > 0 else "반대"
        out["verdict"] = (f"점수와 이후 수익률이 {direction} 방향으로 움직였다 "
                          f"(rho={rho:+.3f}, p={p:.4f})")
        out["신뢰가능"] = True

    if verbose:
        print("\n" + "=" * 70)
        print(f"투자판단 점수의 사후 검증 — 이후 {horizon}거래일 수익률")
        print("=" * 70)
        for k, v in out.items():
            print(f"  {k:12s} {v}")
        if out["n_days"] < 10:
            print(f"  ⚠️ 거래일 {out['n_days']}개. 표본이 사실상 며칠뿐이다.")

    if save:
        d = OUTPUT_DIR / "validation"
        d.mkdir(parents=True, exist_ok=True)
        (d / "12_judge_validation.json").write_text(
            json.dumps(out, ensure_ascii=False, indent=2, default=str),
            encoding="utf-8")
    return out
