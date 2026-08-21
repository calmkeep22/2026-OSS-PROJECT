"""
시장별 뉴스 감성 — 언어에 맞는 모델로 갈라 보낸다.

왜 갈라야 하나
--------------
처음엔 한글 모델(KR-FinBert-SC) 하나로 다 처리하려 했다. 영문 헤드라인을
넣어 보니 **전부 중립**으로 나왔다. 당연하다 — 토크나이저가 영어를
모르는 서브워드로 쪼갠다. 반대도 마찬가지다.

    KR   snunlp/KR-FinBert-SC     한글 금융 뉴스로 파인튜닝
    US   ProsusAI/finbert         영문 금융 뉴스로 파인튜닝
    대량 lexicon.py               47만 건짜리 아카이브용 (BERT는 너무 느리다)

세 번째가 필요한 이유는 비용이다. xforecast 아카이브는 뉴스 47만 건이라
BERT로 돌리면 CPU에서 몇 시간이 걸린다. 사전 방식은 수십 초다.
**실시간 몇백 건은 BERT, 대량 과거 데이터는 사전** — 정확도와 비용을
쓰는 자리에 맞게 나눈 것이다.

⚠️ 두 모델의 점수는 같은 척도가 아니다
--------------------------------------
KR-FinBert와 FinBERT는 서로 다른 데이터로 학습됐다. `polarity` 값을
시장 간에 직접 비교하면 안 된다. 모델에 넣을 때는 **종목별로 표준화**해서
"이 종목 평소 대비 얼마나 긍정적인가"로 바꿔 쓴다.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .universe import MARKETS

_CACHE: dict[str, tuple] = {}
MAX_LEN = 64            # 헤드라인이라 짧다. 128로 늘려도 결과가 안 바뀌고 2배 느려진다
BATCH = 32


def _load(model_name: str):
    """
    모델을 한 번만 올린다. 두 개를 동시에 들고 있어도 CPU에서 700MB 정도다.

    ⚠️ **전역 스레드 수를 건드리지 않는다.**
    처음엔 여기서 `torch.set_num_threads(현재값 // 2)` 를 호출했다.
    캐시 덕에 모델당 한 번씩만 불리지만, KR·US 둘 다 쓰면 **4 → 2 → 1** 로
    떨어진다. 그리고 그 설정은 프로세스 전역이라 이후 TabPFN 까지 1스레드로
    돌게 된다 — 감성 모델을 올렸다는 이유로 무관한 모델이 느려지는 것이다.
    실측에서 이 한 줄 때문에 워크포워드가 10분 넘게 걸렸다.

    스레드 수를 정말 제한해야 한다면 `OMP_NUM_THREADS` 환경변수로 밖에서
    주는 게 맞다. 라이브러리 코드가 남의 실행 환경을 바꾸면 안 된다.
    """
    if model_name in _CACHE:
        return _CACHE[model_name]
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(model_name)
    mdl = AutoModelForSequenceClassification.from_pretrained(model_name)
    mdl.eval()
    _CACHE[model_name] = (tok, mdl)
    return tok, mdl


def _label_map(mdl) -> dict[int, str]:
    """
    라벨 순서가 모델마다 다르다.

    ProsusAI/finbert 는 {0: positive, 1: negative, 2: neutral} 이고
    KR-FinBert-SC 는 순서가 다르다. `id2label` 을 읽지 않고 인덱스를
    가정하면 **긍정과 부정이 뒤바뀐 채로 조용히 돌아간다.**
    """
    raw = getattr(mdl.config, "id2label", None) or {}
    out = {}
    for i, lab in raw.items():
        s = str(lab).lower()
        if "pos" in s or s in ("2", "긍정"):
            out[int(i)] = "positive"
        elif "neg" in s or s in ("0", "부정"):
            out[int(i)] = "negative"
        else:
            out[int(i)] = "neutral"
    return out or {0: "negative", 1: "neutral", 2: "positive"}


def score_titles(titles: list[str], market: str = "KR",
                 verbose: bool = False) -> pd.DataFrame:
    """
    헤드라인 목록 → polarity(-1~+1) · label · 확률.

    polarity = P(positive) - P(negative). 중립 확률은 빼지 않는다 —
    "확실히 중립"과 "긍정·부정이 반반"을 구분해야 하는데, 전자는 polarity 0
    이면서 neutral 이 높고 후자는 polarity 0 이면서 neutral 이 낮다.
    """
    titles = [str(t) for t in titles]
    if not titles:
        return pd.DataFrame(columns=["title", "polarity", "label",
                                     "p_pos", "p_neg", "p_neu"])

    import torch
    name = MARKETS.get(market, MARKETS["KR"])["sentiment"]
    tok, mdl = _load(name)
    lab = _label_map(mdl)

    probs = []
    for i in range(0, len(titles), BATCH):
        enc = tok(titles[i:i + BATCH], return_tensors="pt", padding=True,
                  truncation=True, max_length=MAX_LEN)
        with torch.no_grad():
            probs.append(torch.softmax(mdl(**enc).logits, dim=-1).numpy())
    p = np.vstack(probs)

    cols = {v: p[:, k] for k, v in lab.items()}
    pos = cols.get("positive", np.zeros(len(p)))
    neg = cols.get("negative", np.zeros(len(p)))
    neu = cols.get("neutral", np.zeros(len(p)))
    pol = pos - neg
    out = pd.DataFrame({
        "title": titles, "polarity": pol,
        "label": np.where(pol > 0.15, "positive",
                          np.where(pol < -0.15, "negative", "neutral")),
        "p_pos": pos, "p_neg": neg, "p_neu": neu})
    if verbose:
        print(f"  감성 {len(out)}건 ({market}/{name.split('/')[-1]}) "
              f"긍정 {(out['label']=='positive').sum()} "
              f"부정 {(out['label']=='negative').sum()}")
    return out


def score_titles_lex(titles: list[str], market: str = "KR",
                     verbose: bool = False) -> pd.DataFrame:
    """
    사전 방식 채점. **반환 열은 `score_titles` 와 같다.**

    왜 BERT 가 아니라 사전인가
    --------------------------
    교차언어 전이 모델(`newsxfer`)은 xforecast 미국 뉴스를 **사전**으로 채점한
    피처로 학습했다. 그 모델에 BERT 로 채점한 값을 넣으면 **학습 때와 다른
    척도의 숫자**를 주는 셈이다. 사전은 근거 단어 하나면 평활항 때문에 ±0.25
    근처에 머무는데 BERT 는 확신하면 0.99 를 뱉는다.

        학습 분포 ≠ 적용 분포  →  미국에서 배운 임계값이 한국에 안 맞는다

    그래서 전이 피처를 만들 때는 양쪽 다 사전으로 채점한다. 국문은
    `lexicon_ko` 가 **영문 사전의 어휘를 국내 표현으로 대응**시켜 둔 것이라
    같은 척도가 나온다.

    BERT 를 없애지는 않았다. 사용자에게 읽어 줄 문장 톤에는 그쪽이 낫다 —
    용도가 다르다.
    """
    titles = [str(t) for t in titles]
    if not titles:
        return pd.DataFrame(columns=["title", "polarity", "label",
                                     "p_pos", "p_neg", "p_neu"])
    if market == "KR":
        from . import lexicon_ko as LX
    else:
        from . import lexicon as LX

    rows = LX.score_many(titles)
    pol = np.array([r["polarity"] for r in rows], dtype=float)
    pr = np.array([r["pos_ratio"] for r in rows], dtype=float)
    nr = np.array([r["neg_ratio"] for r in rows], dtype=float)
    tot = np.clip(pr + nr, 1e-9, None)
    out = pd.DataFrame({
        "title": titles, "polarity": pol,
        "label": np.where(pol > 0.15, "positive",
                          np.where(pol < -0.15, "negative", "neutral")),
        # 확률 자리에는 근거 비율을 정규화해 넣는다. 사전에는 확률이 없다.
        "p_pos": pr / tot, "p_neg": nr / tot,
        "p_neu": np.where(pr + nr > 0, 0.0, 1.0)})
    if verbose:
        hit = int((pr + nr > 0).sum())
        print(f"  사전 채점 {len(out)}건 ({market}) · 근거어 잡힌 건 {hit}")
    return out


# --------------------------------------------------------------------------
# 일자별 뉴스 피처
# --------------------------------------------------------------------------
FEATURES = ["news_n", "news_pol_mean", "news_pol_max", "news_pol_min",
            "news_pol_std", "news_pos_ratio", "news_neg_ratio",
            "news_n_z", "news_pol_z"]


def daily_features(scored: pd.DataFrame, ts_col: str = "ts",
                   session_cut: str = "15:30") -> pd.DataFrame:
    """
    기사 단위 감성 → **거래일 단위** 피처.

    거래일 귀속이 중요하다
    ----------------------
    장 마감 후 기사는 그날이 아니라 **다음 거래일**에 반영된다. 이걸 안 하면
    "실적 발표 후 급등" 기사가 당일 수익률과 상관 있어 보이는데, 사실은
    주가가 움직여서 기사가 난 것이다 — 인과가 거꾸로다.

    `news_n_z` · `news_pol_z` 는 종목 자기 기준 표준화값이다. 종목마다
    커버리지가 다르고(삼성전자 하루 30건 vs 중소형주 1건), 두 감성 모델의
    점수 척도도 달라서, 원값을 그대로 쓰면 종목·시장 간 비교가 깨진다.
    """
    if not len(scored):
        return pd.DataFrame(columns=["tday", *FEATURES])
    d = scored.copy()
    ts = pd.to_datetime(d[ts_col])
    if getattr(ts.dt, "tz", None) is not None:
        ts = ts.dt.tz_localize(None)
    cut_h, cut_m = (int(x) for x in session_cut.split(":"))
    after = (ts.dt.hour > cut_h) | ((ts.dt.hour == cut_h) & (ts.dt.minute >= cut_m))
    tday = ts.dt.normalize() + pd.to_timedelta(after.astype(int), unit="D")
    # 토·일에 난 기사는 월요일로 민다
    wd = tday.dt.weekday
    tday = tday + pd.to_timedelta(np.where(wd == 5, 2, np.where(wd == 6, 1, 0)),
                                  unit="D")
    d["tday"] = tday

    g = d.groupby("tday")["polarity"]
    out = pd.DataFrame({
        "news_n": g.size(),
        "news_pol_mean": g.mean(),
        "news_pol_max": g.max(),
        "news_pol_min": g.min(),
        # 하루 기사가 1건이면 표준편차가 NaN 이다. 0으로 두면 "의견이 일치"라는
        # 뜻이 되는데 사실은 "잴 수 없음"이다. NaN 을 그대로 남긴다 —
        # HistGradientBoosting 은 NaN 을 결측으로 알아서 처리한다.
        "news_pol_std": g.std(),
        "news_pos_ratio": d.groupby("tday")["label"].apply(
            lambda s: float((s == "positive").mean())),
        "news_neg_ratio": d.groupby("tday")["label"].apply(
            lambda s: float((s == "negative").mean())),
    })
    for src, dst in (("news_n", "news_n_z"), ("news_pol_mean", "news_pol_z")):
        sd = out[src].std()
        out[dst] = (out[src] - out[src].mean()) / (sd if sd and sd > 1e-9 else 1.0)
    return out.reset_index()
