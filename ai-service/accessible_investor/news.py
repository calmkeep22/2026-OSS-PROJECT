"""
뉴스 감성 지수 + 장중 뉴스 감시.

표현에 대한 결정 (심사·법적 리스크를 함께 줄이기 위한 것)
    쓰지 않음                  대신 씀
    ─────────────────────────────────────────────
    매매 적합성 점수      →   뉴스 감성 지수
    페어 트레이딩 전략    →   관계 이탈 탐지
    "호재/악재"           →   "긍정적/부정적 내용"

수집원
------
**API 키가 필요 없다.** 구글 뉴스 RSS를 쓴다.
네이버 뉴스 검색 API를 쓰려 했으나 2026-08 기준 네이버 개발자센터의 '사용 API'
목록에 검색이 없다. 결과적으로 RSS 쪽이 오픈소스 제출물에 더 낫다 —
API 키 발급은 진입 장벽인데 RSS는 `pip install` 후 바로 돌아간다.
DART 공시는 선택이며 키가 없어도 전부 동작한다.

05 노트북 대비 새로 넣은 것
--------------------------
1. 장중 신규 기사 감지 (NewsWatcher) — 이상 움직임과 뉴스를 시각으로 잇는다
2. 사건별 요약 — 제목 여러 개를 한 문장으로. LLM 키가 있으면 더 좋게, 없어도 동작
3. 이상 알림 ↔ 뉴스 연결 — "왜 움직였는지"를 같이 읽어준다
4. 예측력 검증 — 감성 지수가 실제로 다음날 수익률과 상관이 있는지.
   **없으면 없다고 명시한다.** 있는 척하는 게 이 프로젝트에서 제일 위험하다.
"""

from __future__ import annotations

import hashlib
import os
import re
import urllib.parse
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta

import numpy as np
import pandas as pd

from .config import (
    DATA_DIR,
    NEWS_DEDUP_THRESHOLD,
    NEWS_HALF_LIFE_DAYS,
    NEWS_LOOKBACK_DAYS,
    MARKET_TZ,
)

# 실제 수집에서 확인된 오염 두 가지를 막는다.
#  1) 블로그·프리미엄콘텐츠가 뉴스로 섞인다 (NAVER 검색 시 상위 출처가 Naver Blog 61건)
#  2) 주가와 무관한 마케팅 기사가 들어온다 ("삼성전자 에어컨 청.정.확.인!")
BLOCK_SOURCES = {"Naver Blog", "네이버 블로그", "네이버 프리미엄콘텐츠",
                 "Tistory", "브런치", "Brunch", "네이트", "네이버 포스트"}
BLOCK_KEYWORDS = ["에어컨", "냉장고", "세탁기", "구독 서비스", "이벤트 응모",
                  "체험단", "할인 행사", "채용 설명회", "공모전 개최"]

# 검색어에 금융 맥락을 더한다. 종목명만 넣으면 생활·마케팅 기사가 대량으로 섞인다.
QUERY_CONTEXT = "(주가 OR 실적 OR 증권 OR 공시 OR 목표주가 OR 영업이익)"

SOURCE_WEIGHT = {"DART": 2.0}      # 공시는 기사보다 무겁게. 나머지는 1.0

# 주가 움직임을 **사후 보도**한 기사. 감성 지수에서 순환 참조를 만든다.
#
# "[특징주] 삼성전자 2%대 상승" 같은 기사는 주가가 올랐기 때문에 쓰인 것이다.
# 이걸 그대로 넣으면 "주가가 올라서 → 긍정 기사가 나고 → 감성 지수가 오르고 →
# 사용자는 긍정 뉴스 때문에 오른 줄 안다"는 고리가 생긴다.
# 실측에서 삼성전자 3일치 91건 중 상당수가 이 유형이었다.
# 완전히 버리지는 않는다(시황 자체는 정보다). 가중치를 크게 낮춘다.
REACTIVE_PATTERNS = re.compile(
    r"\[?특징주\]?|장 ?초반|장중|오전 ?장|오후 ?장|시황|상승 ?마감|하락 ?마감|"
    r"강세|약세 ?마감|[0-9]+%대 ?(상승|하락|강세|약세)")
REACTIVE_WEIGHT = 0.3

# 종목명이 제목에 없는 기사. 검색이 느슨해 관련 종목 기사가 섞여 들어온다.
# (삼성전자 검색에 "원익IPS 수주 잔고 급증"이 잡혔다 — 협력사 기사다)
# 업계 전체를 다룬 진짜 관련 기사도 있으므로 버리지 않고 낮춘다.
OFFTOPIC_WEIGHT = 0.4


# --------------------------------------------------------------------------
# 1. 수집
# --------------------------------------------------------------------------
# 시장별 검색 로케일과 문맥어.
#
# 왜 필요한가
# -----------
# 처음엔 hl=ko&gl=KR 이 하드코딩돼 있었다. 그 상태로 "NVIDIA stock earnings"를
# 물으면 **한국어 기사가 섞여 온다** — 실측에서 'Tesla stock' 9건 중 3건이
# 한국 매체였고, 심지어 종목이 다른 기사(에어비앤비)까지 올라왔다.
# 감성 분석은 언어별로 다른 모델을 쓰므로(한글 KR-FinBert / 영문 사전) 섞이면
# 둘 다 틀린다. 로케일을 시장에 맞춰 분리한다.
LOCALES = {
    "KR": {"hl": "ko", "gl": "KR", "ceid": "KR:ko",
           "context": "(주가 OR 실적 OR 증권 OR 공시 OR 목표주가 OR 영업이익)"},
    "US": {"hl": "en-US", "gl": "US", "ceid": "US:en",
           "context": "(stock OR shares OR earnings OR revenue OR guidance)"},
}


def fetch_google_news(query: str, days: int = NEWS_LOOKBACK_DAYS,
                      verbose: bool = False, market: str = "KR",
                      after: str | None = None,
                      before: str | None = None) -> list[dict]:
    """
    구글 뉴스 RSS. 키 불필요. 출처·발행시각이 함께 와서 '근거 기사 표시' 요건도 충족한다.

    market
        "KR" 국내 · "US" 미국. 검색 로케일과 문맥어가 함께 바뀐다.
    after, before
        "YYYY-MM-DD". 주면 `when:Nd` 대신 **날짜 구간**으로 검색한다.

    왜 날짜 구간이 필요한가
    -----------------------
    `when:7d` 밖에 못 쓰면 최근 7일치만 모인다. 학습 구간은 6년인데 뉴스가
    7일치뿐이면 그 열은 사실상 상수가 되고, "뉴스를 쓴다"고 적어 놓고 실제로는
    아무것도 안 쓰는 상태가 된다. 실측에서 국내 종목의 뉴스값이 있는 행은
    **0.3%** 였다.

    구글 뉴스 RSS 는 `after:` / `before:` 를 받아 준다(확인함 — 국내·미국 모두
    구간당 100건까지). 이걸 쪼개서 과거로 걸어 내려가면 평가 구간을 덮을
    만큼은 모인다. 국내에 과거 아카이브가 없던 것은 성능 문제가 아니라
    **수집을 안 해서**였다.
    """
    import requests

    loc = LOCALES.get(market, LOCALES["KR"])
    if after or before:
        rng = " ".join(x for x in (f"after:{after}" if after else "",
                                   f"before:{before}" if before else "") if x)
        q = f"{query} {loc['context']} {rng}"
    else:
        q = f"{query} {loc['context']} when:{days}d"
    url = ("https://news.google.com/rss/search?"
           f"q={urllib.parse.quote(q)}"
           f"&hl={loc['hl']}&gl={loc['gl']}&ceid={loc['ceid']}")
    try:
        r = requests.get(url, headers={"User-Agent": "Mozilla/5.0"}, timeout=15)
        root = ET.fromstring(r.content)
    except Exception as e:
        if verbose:
            print(f"  구글 뉴스 RSS 실패: {type(e).__name__}: {e}")
        return []

    # 날짜 구간 검색에서는 그 구간 자체가 조건이라 상대 컷오프를 걸지 않는다.
    cutoff = (pd.Timestamp(after, tz="UTC") if after
              else pd.Timestamp.now(tz="UTC") - pd.Timedelta(days=days))
    out, dropped = [], 0
    for it in root.iter("item"):
        title = it.findtext("title", "")
        try:
            pub = pd.to_datetime(it.findtext("pubDate", ""), utc=True)
        except Exception:
            continue
        if pub < cutoff:
            continue
        src_el = it.find("source")
        src = src_el.text if src_el is not None else "뉴스"
        if src in BLOCK_SOURCES:
            dropped += 1
            continue
        # 구글 RSS 제목은 "제목 - 매체명" 형태라 뒤쪽 매체명을 떼어낸다.
        # source 태그와 제목의 매체 표기가 다른 경우가 있어(source="Chosunbiz",
        # 제목 끝은 "- 조선비즈") 이름 매칭만으로는 안 떨어진다. 짧은 꼬리는 일반 규칙으로 자른다.
        title = re.sub(rf"\s*-\s*{re.escape(src)}\s*$", "", title).strip()
        title = re.sub(r"\s+-\s+[^\-]{2,12}$", "", title).strip()
        if any(k in title for k in BLOCK_KEYWORDS):
            dropped += 1
            continue
        ts = pub.tz_convert(MARKET_TZ)
        out.append({
            "title": title, "source": src, "corp": query,
            "ts": ts, "date": ts.strftime("%Y%m%d"),
            "url": it.findtext("link", ""),
            "guid": it.findtext("guid", "") or title,
        })
    if verbose and dropped:
        print(f"  필터로 제외: {dropped}건 (블로그·비금융)")
    return out


def fetch_dart(corp_name: str, days: int = NEWS_LOOKBACK_DAYS) -> list[dict]:
    """DART 공시. DART_API_KEY가 없으면 조용히 빈 목록을 준다 (기능이 죽지 않는다)."""
    key = os.getenv("DART_API_KEY")
    if not key:
        return []
    import requests

    end = datetime.now()
    start = end - timedelta(days=days)
    try:
        r = requests.get("https://opendart.fss.or.kr/api/list.json",
                         params={"crtfc_key": key,
                                 "bgn_de": start.strftime("%Y%m%d"),
                                 "end_de": end.strftime("%Y%m%d"),
                                 "page_count": 100}, timeout=10)
        items = r.json().get("list", [])
    except Exception:
        return []
    out = []
    for it in items:
        if corp_name not in it.get("corp_name", ""):
            continue
        ts = pd.Timestamp(it["rcept_dt"], tz=MARKET_TZ)
        out.append({
            "title": it["report_nm"], "source": "DART", "corp": corp_name,
            "ts": ts, "date": it["rcept_dt"],
            "url": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={it['rcept_no']}",
            "guid": it["rcept_no"],
        })
    return out


def collect(corp_name: str, days: int = NEWS_LOOKBACK_DAYS,
            verbose: bool = False) -> pd.DataFrame:
    items = fetch_dart(corp_name, days) + fetch_google_news(corp_name, days, verbose)
    if not items:
        return pd.DataFrame(columns=["title", "source", "corp", "ts", "date",
                                     "url", "guid"])
    df = pd.DataFrame(items).sort_values("ts", ascending=False).reset_index(drop=True)
    return df


# --------------------------------------------------------------------------
# 2. 중복 제거
# --------------------------------------------------------------------------
def normalize_title(t: str) -> str:
    t = re.sub(r"\[[^\]]*\]|\([^)]*\)", "", t)      # [속보] (종합) 제거
    t = re.sub(r"[^가-힣A-Za-z0-9 ]", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def dedup(df: pd.DataFrame, threshold: float = NEWS_DEDUP_THRESHOLD,
          corp_name: str | None = None) -> pd.DataFrame:
    """
    같은 사건을 여러 매체가 쓴 것을 하나로 묶는다.

    임계값 주의 — 처음 0.55로 잡았더니 명백한 중복이 전혀 안 묶였다.
    실측 분포는 중복 0.31~0.52 / 무관 0.06~0.09로 사이가 크게 벌어져 있어 0.30이 맞다.
    자동 판정을 두 번 시도해 두 번 다 틀렸다(크기 기준·안정구간 기준). 실측으로 고정한다.

    종목명은 모든 기사에 들어가 유사도를 일괄 상승시키므로 미리 제거한다.

    왜 중요한가: 삼성전자 zHBM 공개 보도자료 하나를 **49개 매체가 받아썼다.**
    중복 제거 없이는 이 한 사건이 감성 점수에 49번 계상된다.
    """
    if not len(df):
        return df.assign(cluster=[])
    df = df.copy()
    df["norm"] = df["title"].map(normalize_title)
    df["hash"] = df["norm"].map(lambda s: hashlib.md5(s.encode()).hexdigest())
    df = df.drop_duplicates("hash").reset_index(drop=True)
    if len(df) < 2:
        df["cluster"] = range(len(df))
        return df

    corp = corp_name or (df["corp"].iloc[0] if "corp" in df else None)
    text = df["norm"]
    if corp:
        text = text.str.replace(corp, " ", regex=False).str.strip()

    from scipy.sparse import csr_matrix
    from scipy.sparse.csgraph import connected_components
    from sklearn.feature_extraction.text import TfidfVectorizer

    tfidf = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4)).fit_transform(text)
    sim = (tfidf @ tfidf.T).toarray()
    np.fill_diagonal(sim, 0)
    # 연결 요소로 묶는다: A~B, B~C면 A~C 유사도가 낮아도 같은 사건이다
    _n, labels = connected_components(csr_matrix(sim > threshold), directed=False)
    df["cluster"] = labels
    return df


# --------------------------------------------------------------------------
# 3. 감성 — KR-FinBert-SC (로컬 실행, 키 불필요)
# --------------------------------------------------------------------------
MODEL_NAME = "snunlp/KR-FinBert-SC"
_MODEL = None

POS_WORDS = ["상회", "상향", "호조", "강세", "순매수", "수혜", "개선", "반등",
             "매입", "체결", "기대", "최대", "돌파", "흑자"]
NEG_WORDS = ["하회", "하향", "부진", "약세", "순매도", "우려", "지연", "급락",
             "손실", "결렬", "둔화", "하락", "적자", "리콜"]


def _load_model():
    """
    프로세스당 한 번만 올린다.

    종목마다 새로 로드하면 첫 종목 18.2초 / 이후 4.3초로 갈린다.
    관심종목 10개면 로드 오버헤드만 2분이 넘는다.
    """
    global _MODEL
    if _MODEL is None:
        try:
            import torch  # noqa: F401
            from transformers import (AutoModelForSequenceClassification,
                                      AutoTokenizer)
            tok = AutoTokenizer.from_pretrained(MODEL_NAME)
            mdl = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME).eval()
            _MODEL = (tok, mdl)
        except Exception:
            _MODEL = (None, None)
    return _MODEL


def sentiment(texts) -> pd.DataFrame:
    """반환: DataFrame[negative, neutral, positive]. 모델이 없으면 사전 기반 폴백."""
    texts = list(texts)
    if not texts:
        return pd.DataFrame(columns=["negative", "neutral", "positive"])
    tok, mdl = _load_model()
    if mdl is not None:
        import torch

        with torch.no_grad():
            enc = tok(texts, return_tensors="pt", padding=True,
                      truncation=True, max_length=128)
            probs = torch.softmax(mdl(**enc).logits, dim=-1).numpy()
        cols = [mdl.config.id2label[i] for i in range(probs.shape[1])]
        out = pd.DataFrame(probs, columns=cols)
        for c in ("negative", "neutral", "positive"):
            if c not in out:
                out[c] = 0.0
        return out[["negative", "neutral", "positive"]]

    rows = []
    for t in texts:
        p = sum(w in t for w in POS_WORDS)
        n = sum(w in t for w in NEG_WORDS)
        tot = p + n
        rows.append([0.15, 0.7, 0.15] if tot == 0
                    else [n / tot * 0.85 + 0.05, 0.1, p / tot * 0.85 + 0.05])
    return pd.DataFrame(rows, columns=["negative", "neutral", "positive"])


# KR-FinBert-SC가 놓치는 유형. 실측 실패 사례:
#   "'사상 최대 실적' 삼성전자·SK하이닉스 대표, 경찰 수사 왜?" → **긍정**으로 분류됐다.
# 모델이 '사상 최대 실적'에 반응하고 '경찰 수사'를 흘렸다. 금융 코퍼스로 학습된 모델이라
# 사법·규제 리스크 어휘가 약하다.
#
# 모델을 덮어쓰지는 않는다. 이 목록에 걸리면 **상한만 씌운다** —
# 긍정으로 나갔던 것을 약한 부정으로 끌어내리되, 이미 부정이면 건드리지 않는다.
SEVERE_NEG = re.compile(
    r"수사|기소|구속|압수 ?수색|횡령|배임|분식|리콜|집단 ?소송|과징금|제재|"
    r"영업 ?정지|상장 ?폐지|거래 ?정지|불성실 ?공시|감자|회생 ?절차|파산")
SEVERE_CAP = -0.4


def score_articles(df: pd.DataFrame) -> pd.DataFrame:
    if not len(df):
        return df
    s = sentiment(df["title"])
    out = pd.concat([df.reset_index(drop=True), s], axis=1)
    out["polarity"] = out["positive"] - out["negative"]

    severe = out["title"].str.contains(SEVERE_NEG, na=False)
    out["severe_flag"] = severe
    out.loc[severe & (out["polarity"] > SEVERE_CAP), "polarity"] = SEVERE_CAP

    out["label"] = np.where(out["polarity"] > 0.2, "positive",
                            np.where(out["polarity"] < -0.2, "negative", "neutral"))
    return out


# --------------------------------------------------------------------------
# 4. 감성 지수
# --------------------------------------------------------------------------
def sentiment_index(df: pd.DataFrame,
                    half_life_days: float = NEWS_HALF_LIFE_DAYS
                    ) -> tuple[float, pd.DataFrame]:
    """사건 단위로 축약한 뒤 최신성·출처 가중 평균. 범위 -100 ~ +100."""
    if not len(df):
        return 0.0, pd.DataFrame()
    d = df.copy()
    ts = pd.to_datetime(d["ts"])
    age = (ts.max() - ts).dt.total_seconds() / 86400.0
    d["w_recency"] = 0.5 ** (age / max(half_life_days, 1e-6))
    d["w_source"] = d["source"].map(SOURCE_WEIGHT).fillna(1.0)
    d["w_reactive"] = np.where(d["title"].str.contains(REACTIVE_PATTERNS, na=False),
                               REACTIVE_WEIGHT, 1.0)
    corp = d["corp"].iloc[0] if "corp" in d else None
    d["w_topic"] = (1.0 if corp is None
                    else np.where(d["title"].str.contains(str(corp), regex=False,
                                                          na=False),
                                  1.0, OFFTOPIC_WEIGHT))
    d["w_article"] = d["w_source"] * d["w_reactive"] * d["w_topic"]

    rows = []
    for cid, g in d.groupby("cluster"):
        w = g["w_article"].to_numpy()
        w = w if w.sum() > 0 else np.ones(len(g))
        rows.append({
            "cluster": cid,
            "polarity": float(np.average(g["polarity"], weights=w)),
            "weight": float((g["w_recency"] * g["w_article"]).max()),
            "n_articles": len(g),
            "title": _representative_title(g),
            "sources": sorted(set(g["source"]))[:5],
            "ts": g["ts"].max(),
            "url": g.iloc[0]["url"],
            "reactive": bool(g["w_reactive"].mean() < 1.0),
        })
    ev = pd.DataFrame(rows)
    score = float(np.clip(np.average(ev["polarity"], weights=ev["weight"]) * 100,
                          -100, 100))

    # 중요도 = 세기 × 신선도 × **보도 폭**.
    # 보도 폭을 빼면 한 매체의 단독 논평(|polarity|=1.0)이
    # 10개 매체가 함께 쓴 사건보다 위로 올라온다. 실제로 그 버그가 났다.
    ev["impact"] = ev["polarity"].abs() * ev["weight"] * np.log1p(ev["n_articles"])
    ev = ev.sort_values("impact", ascending=False).reset_index(drop=True)
    return score, ev


def _representative_title(g: pd.DataFrame) -> str:
    """
    묶음의 대표 제목.

    첫 기사를 그냥 쓰면 "[특징주] 삼성전자 2%대 상승" 같은 정보량 낮은 제목이
    대표가 되는 일이 잦다. 숫자·고유명사가 많은 제목을 고른다.
    """
    def informativeness(t: str) -> float:
        digits = len(re.findall(r"\d", t))
        words = len(t.split())
        bracket_penalty = 3 if t.startswith("[") else 0
        return digits * 1.5 + words - bracket_penalty
    return max(g["title"], key=informativeness)


def score_to_text(s: float) -> str:
    if s >= 50:
        return "매우 긍정"
    if s >= 20:
        return "긍정"
    if s > -20:
        return "중립"
    if s > -50:
        return "부정"
    return "매우 부정"


def text_bar(s: float, width: int = 40) -> str:
    """스크린리더가 읽을 수 있는 문자 막대. 색상에 의존하지 않는다."""
    pos = int((s + 100) / 200 * width)
    bar = ["─"] * width
    bar[width // 2] = "┼"
    bar[min(max(pos, 0), width - 1)] = "█"
    return "".join(bar)


# 제목에서 뽑을 수치. `summarize_event` 가 쓰는데 정의가 빠져 있어서 요약을 만들 때마다
# NameError 로 죽었다 — `analyze()` 가 통째로 실패해 뉴스 기능이 한 번도 동작하지 않았다.
#
# 단위를 붙여 잡는 이유는 숫자만 뽑으면 "3분기" 의 3 과 "3퍼센트" 의 3 이 구별되지 않기
# 때문이다. 긴 단위를 앞에 두어야 "억원" 이 "억" 으로 잘리지 않는다.
_NUM_PAT = re.compile(
    r"([-+]?\d[\d,]*(?:\.\d+)?)\s*"
    r"(조원|억원|만원|퍼센트|포인트|달러|조|억|만|원|배|%|bp)")


def summarize_event(row: pd.Series, titles: list[str] | None = None) -> str:
    """
    사건 하나를 한 문장으로.

    RSS는 **제목만** 준다(description 필드는 링크뿐이다). 본문이 없으니
    추상 요약은 불가능하고, 할 수 있는 건 묶음 안에서 가장 정보량이 많은 제목을
    고르고 숫자를 뽑아 붙이는 것이다. 없는 정보를 지어내는 것보다 낫다.

    ANTHROPIC_API_KEY가 있으면 `llm_summarize`가 더 나은 문장을 만든다.
    없어도 전부 동작한다 — 오픈소스 제출물에서 키를 요구하지 않는 게 원칙이다.
    """
    title = row["title"]
    tone = ("긍정적" if row["polarity"] > 0.2
            else "부정적" if row["polarity"] < -0.2 else "중립적")
    parts = [f"{title}. {tone} 내용입니다"]

    nums = _NUM_PAT.findall(title)
    for t in (titles or []):
        nums += _NUM_PAT.findall(t)
    seen, keys = set(), []
    for v, u in nums:
        k = f"{v}{u}"
        if k not in seen:
            seen.add(k)
            keys.append(k)
    if len(keys) > 1:
        parts.append(f"언급된 수치는 {', '.join(keys[:3])}입니다")
    if row["n_articles"] > 1:
        srcs = ", ".join(row["sources"][:2])
        parts.append(f"{srcs} 등 {int(row['n_articles'])}개 매체가 함께 보도했습니다")
    return ". ".join(parts) + "."


def llm_summarize(events: pd.DataFrame, corp: str, max_events: int = 3) -> list[str] | None:
    """
    선택 기능. ANTHROPIC_API_KEY가 있을 때만 동작하고, 없으면 None을 준다.

    호출부는 반드시 None을 처리해야 한다 — 키 없는 환경이 기본이다.
    """
    key = os.getenv("ANTHROPIC_API_KEY")
    if not key or not len(events):
        return None
    try:
        import anthropic
    except ImportError:
        return None

    lines = []
    for _i, r in events.head(max_events).iterrows():
        lines.append(f"- {r['title']} (관련 기사 {int(r['n_articles'])}건)")
    prompt = (
        f"다음은 {corp} 관련 뉴스 제목이다. 각 항목을 시각장애인 사용자가 "
        "음성으로 들을 문장 2개 이내로 요약하라.\n"
        "규칙: 제목에 없는 사실을 지어내지 말 것. 투자 권유·전망 표현을 쓰지 말 것. "
        "한 줄에 하나씩, 번호 없이 출력할 것.\n\n" + "\n".join(lines)
    )
    try:
        client = anthropic.Anthropic(api_key=key)
        msg = client.messages.create(
            model="claude-sonnet-5", max_tokens=500,
            messages=[{"role": "user", "content": prompt}])
        return [ln.strip("-· ").strip()
                for ln in msg.content[0].text.strip().split("\n") if ln.strip()]
    except Exception:
        return None


# --------------------------------------------------------------------------
# 6. 종목 하나 분석 (공개 진입점)
# --------------------------------------------------------------------------
def analyze(corp_name: str, days: int = NEWS_LOOKBACK_DAYS,
            use_llm: bool = False, verbose: bool = False) -> dict | None:
    raw = collect(corp_name, days, verbose)
    if not len(raw):
        return None
    dd = dedup(raw, corp_name=corp_name)
    dd = score_articles(dd)
    score, events = sentiment_index(dd)

    # 주요 사건에서 **사후보도(시황·특징주)를 뺀다.**
    # "삼전 2.5% 상승" 류는 사건이 아니라 결과다. 사용자에게 "무슨 일이 있었나"를
    # 물으면 이런 걸 원하는 게 아니다. 감성 지수 계산에는 낮은 가중치로 남긴다.
    # (실측: 이걸 안 빼면 16개 매체가 받아쓴 시황 기사가 항상 1번으로 올라온다.
    #  게다가 그 기사는 '상승' 내용인데 KR-FinBert가 "기관 팔았다"에 반응해
    #  부정으로 분류했다 — 잘못된 라벨이 1번 자리를 차지한다)
    real = events[~events["reactive"]] if "reactive" in events else events
    if len(real) < 2:
        real = events
    summaries = llm_summarize(real, corp_name) if use_llm else None
    if summaries is None:
        summaries = [summarize_event(r, list(dd[dd["cluster"] == r["cluster"]]["title"]))
                     for _i, r in real.head(3).iterrows()]

    # 시황은 따로 한 줄. 사건이 아니라 배경이라는 걸 위치로 알린다.
    market_line = None
    if "reactive" in events and events["reactive"].any():
        top_r = events[events["reactive"]].iloc[0]
        if top_r["n_articles"] >= 3:
            market_line = f"오늘 시황 보도입니다. {top_r['title']}"

    return {
        "corp": corp_name,
        "score": round(score, 1),
        "label": score_to_text(score),
        "bar": text_bar(score),
        "n_articles": int(len(dd)),
        "n_events": int(dd["cluster"].nunique()),
        "n_pos": int((dd["label"] == "positive").sum()),
        "n_neu": int((dd["label"] == "neutral").sum()),
        "n_neg": int((dd["label"] == "negative").sum()),
        "events": real.head(5).to_dict("records"),
        "all_events": events.head(8).to_dict("records"),
        "market_line": market_line,
        "summaries": summaries,
        "articles": dd[["title", "source", "ts", "url", "polarity", "cluster"]]
        .to_dict("records"),
        "llm_used": bool(use_llm and os.getenv("ANTHROPIC_API_KEY")),
    }


def briefing_text(res: dict) -> str:
    """장 전 브리핑 문안. UI는 이 문자열을 TTS로 읽기만 하면 된다."""
    if not res:
        return "관련 뉴스를 찾지 못했습니다."
    lines = [
        f"{res['corp']} 뉴스 브리핑입니다.",
        f"최근 기사 {res['n_articles']}건, 사건 {res['n_events']}개입니다.",
        f"긍정 {res['n_pos']}건, 중립 {res['n_neu']}건, 부정 {res['n_neg']}건.",
        f"뉴스 감성 지수는 {res['score']:+.0f}점으로 {res['label']}입니다.",
        "주요 사건입니다.",
    ]
    for i, s in enumerate(res["summaries"], 1):
        lines.append(f"{i}. {s}")
    if res.get("market_line"):
        lines.append(res["market_line"])
    lines.append("뉴스 감성 지수는 여론의 방향을 요약한 것이며 주가 예측이 아닙니다.")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# 7. 장중 신규 기사 감지
# --------------------------------------------------------------------------
class NewsWatcher:
    """
    관심종목의 신규 기사를 폴링한다.

    이상 움직임이 감지됐을 때 "왜"를 답하려면 뉴스가 **가격과 같은 시간축** 위에
    있어야 한다. 장 전에 한 번 훑는 것만으로는 10시 30분 급등의 이유를 알 수 없다.

    RSS 폴링 간격은 3분 이상을 권한다. 구글 뉴스는 초당 요청 제한이 명시돼 있지 않지만
    관심종목 20개를 1분마다 치면 하루 9,600회다. 예의 문제이기도 하고,
    RSS 반영 자체가 실시간이 아니라서 더 자주 쳐도 얻는 게 없다.
    """

    def __init__(self, names: list[str], days: int = 1):
        self.names = names
        self.days = days
        self.seen: dict[str, set[str]] = {n: set() for n in names}
        self.primed = False

    def prime(self):
        """첫 호출에서 기존 기사를 '이미 본 것'으로 등록한다. 안 하면 시작하자마자 폭주한다."""
        for n in self.names:
            df = collect(n, self.days)
            self.seen[n] = set(df["guid"]) if len(df) else set()
        self.primed = True
        return self

    def poll(self) -> list[dict]:
        """새 기사만 반환. 감성 점수와 함께."""
        if not self.primed:
            return self.prime() and []
        fresh = []
        for n in self.names:
            df = collect(n, self.days)
            if not len(df):
                continue
            new = df[~df["guid"].isin(self.seen[n])]
            self.seen[n] |= set(df["guid"])
            if not len(new):
                continue
            new = score_articles(new)
            for _i, r in new.iterrows():
                tone = ("긍정적" if r["polarity"] > 0.2
                        else "부정적" if r["polarity"] < -0.2 else "중립적")
                fresh.append({
                    "corp": n, "title": r["title"], "source": r["source"],
                    "ts": r["ts"], "polarity": round(float(r["polarity"]), 3),
                    "url": r["url"],
                    "text": f"{n} 새 기사입니다. {r['title']}. {tone} 내용입니다.",
                })
        fresh.sort(key=lambda x: x["ts"])
        return fresh


# --------------------------------------------------------------------------
# 8. 이상 알림 ↔ 뉴스 연결
# --------------------------------------------------------------------------
def link_alerts(alerts, articles: pd.DataFrame,
                window_min: int = 45) -> dict:
    """
    알림 시각 주변의 기사를 찾는다. 반환: {알림 인덱스: [기사, ...]}

    ±45분을 기본으로 잡은 이유: RSS 반영이 실시간이 아니고(길게는 30분 지연),
    반대로 기사가 먼저 나고 주가가 뒤따르는 경우도 있다. 양쪽을 다 보려면 넓어야 한다.

    **인과를 주장하지 않는다.** "같은 시간대에 이런 기사가 있었습니다"까지만 말한다.
    뉴스가 원인인지 결과인지는 이 도구가 판단할 수 없다.
    """
    if not len(articles) or not alerts:
        return {}
    ts = pd.to_datetime(articles["ts"])
    out = {}
    for i, a in enumerate(alerts):
        lo = a.ts - pd.Timedelta(minutes=window_min)
        hi = a.ts + pd.Timedelta(minutes=window_min)
        m = articles[(ts >= lo) & (ts <= hi)]
        if len(m):
            out[i] = m.sort_values("ts").to_dict("records")
    return out


def alert_with_news(alert, matched: list[dict]) -> str:
    if not matched:
        return alert.text
    top = matched[0]
    extra = f" 외 {len(matched) - 1}건" if len(matched) > 1 else ""
    return (f"{alert.text} 비슷한 시각에 나온 기사가 있습니다. "
            f"{top['title']}{extra}. 기사와 주가 움직임의 인과는 확인되지 않았습니다.")


# --------------------------------------------------------------------------
# 8-2. 뉴스 아카이브 — RSS 7일 한도를 넘기는 유일한 방법
# --------------------------------------------------------------------------
#
# `when:Nd` 만 쓰면 최근 7일치밖에 안 온다. 그래서 한동안 "지난 6개월 뉴스로
# 학습"이 불가능하다고 보고 매일 쌓는 방법만 썼다 — 그런데 그건 틀렸다.
# 구글 뉴스 RSS 는 `after:` / `before:` 를 받는다. 구간을 쪼개 걸어 내려가면
# **과거도 모을 수 있다** (`backfill`). 매일 쌓기는 그 위에 오늘치를 얹는 용도다.
#
# exe 배포 시나리오에서도 이 아카이브가 필요하다 — 사용자가 앱을 처음 켠 날
# 학습 데이터가 0건이면 예측이 불가능하기 때문이다. 개발자가 모아둔 아카이브를
# 같이 배포하고, 앱은 거기에 오늘치를 덧붙인다.
NEWS_ARCHIVE = DATA_DIR / "news_archive.parquet"


def archive_append(df: pd.DataFrame) -> int:
    """수집분을 아카이브에 병합. guid로 중복을 막는다. 반환: 새로 추가된 건수."""
    if df is None or not len(df):
        return 0
    keep = ["title", "source", "corp", "ts", "date", "url", "guid"]
    new = df[[c for c in keep if c in df.columns]].copy()
    if NEWS_ARCHIVE.exists():
        old = pd.read_parquet(NEWS_ARCHIVE)
        before = len(old)
        merged = pd.concat([old, new], ignore_index=True)
    else:
        before = 0
        merged = new
    merged["ts"] = pd.to_datetime(merged["ts"], utc=True).dt.tz_convert(MARKET_TZ)

    # ⚠️ guid 만으로는 중복이 안 걸러진다.
    # 구글 뉴스는 **같은 기사에도 질의마다 다른 guid** 를 준다. `when:7d` 로
    # 받은 것과 `after:/before:` 로 받은 것이 서로 다른 guid 를 달고 와서,
    # 소급 수집을 돌린 뒤 제목이 같은 행이 1,832건(4.7%) 쌓였다.
    # 같은 기사가 여러 번 세이면 그날의 뉴스 건수와 극성 평균이 함께 흔들린다.
    #
    # 그래서 **(종목, 제목, 날짜)** 로도 접는다. 날짜를 넣는 이유는 같은
    # 제목이 다른 날 다시 나오는 일이 실제로 있기 때문이다(정기 코멘트 등) —
    # 제목만으로 접으면 그런 날의 뉴스가 통째로 사라진다.
    merged["date"] = merged["ts"].dt.strftime("%Y%m%d")
    merged = (merged.drop_duplicates(subset=["corp", "guid"])
                    .drop_duplicates(subset=["corp", "title", "date"])
                    .sort_values("ts"))
    merged.reset_index(drop=True).to_parquet(NEWS_ARCHIVE, index=False)
    return len(merged) - before


def archive_load(corp: str | None = None) -> pd.DataFrame:
    if not NEWS_ARCHIVE.exists():
        return pd.DataFrame(columns=["title", "source", "corp", "ts", "date",
                                     "url", "guid"])
    df = pd.read_parquet(NEWS_ARCHIVE)
    df["ts"] = pd.to_datetime(df["ts"], utc=True).dt.tz_convert(MARKET_TZ)
    return df[df["corp"] == corp] if corp else df


BACKFILL_CHUNK = 12      # 한 번에 훑는 날짜 폭. 구간당 100건 상한을 고려해 좁게 잡는다


def backfill(name: str, days_back: int = 180, chunk: int = BACKFILL_CHUNK,
             market: str = "KR", verbose: bool = True) -> int:
    """
    **과거 뉴스를 날짜 구간으로 걸어 내려가며 모은다.**

    구글 뉴스 RSS 는 구간당 100건까지만 준다. 그래서 `chunk` 를 좁게 잡아야
    한다 — 두 달을 한 번에 요청하면 그 두 달에서 가장 큰 기사 100건만 오고
    조용한 날은 통째로 빠진다. 12일이면 종목당 하루 3~8건 수준으로 들어온다.

    반환값은 아카이브에 **새로 추가된** 건수다.
    """
    from .universe import MARKETS, entry

    try:
        e = entry(name)
        query, market = e["query"], MARKETS[e["market"]]["news_locale"]
    except Exception:
        query = name

    end = pd.Timestamp.now(tz=MARKET_TZ).normalize()
    start = end - pd.Timedelta(days=days_back)
    total, cur = 0, start
    while cur < end:
        nxt = min(cur + pd.Timedelta(days=chunk), end)
        try:
            rows = fetch_google_news(query, market=market,
                                     after=cur.strftime("%Y-%m-%d"),
                                     before=nxt.strftime("%Y-%m-%d"))
            if rows:
                total += archive_append(pd.DataFrame(rows))
        except Exception as ex:
            if verbose:
                print(f"    {cur:%Y-%m-%d} 실패 {type(ex).__name__}")
        cur = nxt
    if verbose:
        print(f"  {name[:16]:16s} 과거 {days_back}일 → 신규 {total}건")
    return total


def backfill_all(names: list[str], days_back: int = 180,
                 verbose: bool = True) -> dict:
    """유니버스 전체 과거 수집. 한 번 돌려 두면 평가 구간이 덮인다."""
    out = {}
    for i, n in enumerate(names, 1):
        if verbose:
            print(f"  [{i}/{len(names)}]", end=" ")
        try:
            out[n] = backfill(n, days_back=days_back, verbose=verbose)
        except Exception as ex:
            print(f"  {n} 실패: {type(ex).__name__}")
            out[n] = 0
    if verbose:
        print(f"총 신규 {sum(out.values()):,}건")
    return out


def archive_collect(names: list[str], days: int = 7, verbose: bool = True) -> dict:
    """관심 종목명 목록을 훑어 아카이브에 쌓는다. 매일 한 번 돌리면 된다."""
    total_new, failed = 0, []
    for i, n in enumerate(names, 1):
        try:
            got = collect(n, days=days)
            total_new += archive_append(got)
        except Exception as e:
            failed.append((n, f"{type(e).__name__}"))
        if verbose and i % 20 == 0:
            print(f"  {i}/{len(names)} … 누적 신규 {total_new}건")
    size = len(archive_load())
    if verbose:
        print(f"뉴스 아카이브: 신규 {total_new}건 · 총 {size:,}건 "
              f"· 실패 {len(failed)}")
    return {"new": total_new, "total": size, "failed": failed}


# --------------------------------------------------------------------------
# 8-3. 뉴스 피처 — TabPFN/GBM에 넣을 수 있는 숫자로
# --------------------------------------------------------------------------
#
# TabPFN은 **표(tabular) 모델이지 텍스트 모델이 아니다.** 기사 본문을 못 읽는다.
# KR-FinBert로 감성을 뽑아 종목·날짜 단위 숫자 벡터로 만든 뒤에야 넣을 수 있다.
NEWS_FEATURES = [
    "n_articles", "n_events", "sent_mean", "sent_max", "sent_min",
    "n_pos", "n_neg", "reactive_ratio", "offtopic_ratio", "severe_ratio",
    "max_cluster", "dup_ratio", "hours_since_last", "article_velocity",
]


def daily_features(scored: pd.DataFrame, corp: str,
                   baseline_per_day: float | None = None) -> pd.DataFrame:
    """
    점수가 매겨진 기사 → **거래일 단위** 피처.

    귀속 규칙은 `_trading_day_of`와 같다: 15:30 이후 기사는 다음 거래일 것이다.
    이걸 안 하면 "장 마감 후 실적 기사"가 당일 수익률과 상관 있는 것처럼 보인다.
    사실은 주가가 움직여서 기사가 난 것이라 인과가 거꾸로다.
    """
    if not len(scored):
        return pd.DataFrame(columns=["corp", "tday", *NEWS_FEATURES])
    d = scored.copy()
    d["tday"] = d["ts"].map(_trading_day_of)
    if "reactive" not in d:
        d["reactive"] = d["title"].str.contains(REACTIVE_PATTERNS, na=False)
    d["offtopic"] = ~d["title"].str.contains(str(corp), regex=False, na=False)
    if "severe_flag" not in d:
        d["severe_flag"] = d["title"].str.contains(SEVERE_NEG, na=False)

    rows = []
    for tday, g in d.groupby("tday"):
        sizes = g.groupby("cluster").size() if "cluster" in g else pd.Series([len(g)])
        last_ts = g["ts"].max()
        ref = pd.Timestamp(tday).tz_localize(MARKET_TZ) + pd.Timedelta(hours=9)
        rows.append({
            "corp": corp, "tday": tday,
            "n_articles": len(g),
            "n_events": int(g["cluster"].nunique()) if "cluster" in g else len(g),
            "sent_mean": float(g["polarity"].mean()),
            "sent_max": float(g["polarity"].max()),
            "sent_min": float(g["polarity"].min()),
            "n_pos": int((g["polarity"] > 0.2).sum()),
            "n_neg": int((g["polarity"] < -0.2).sum()),
            "reactive_ratio": float(g["reactive"].mean()),
            "offtopic_ratio": float(g["offtopic"].mean()),
            "severe_ratio": float(g["severe_flag"].mean()),
            "max_cluster": int(sizes.max()),
            "dup_ratio": float(1 - (sizes.size / max(len(g), 1))),
            "hours_since_last": float((ref - last_ts).total_seconds() / 3600),
            # 기사 수가 평소보다 몇 배인가. 감성보다 이쪽이 신호가 있을 수 있다 —
            # "관심이 몰렸다"는 사실 자체가 정보다.
            "article_velocity": (len(g) / baseline_per_day
                                 if baseline_per_day else np.nan),
        })
    return pd.DataFrame(rows)


# --------------------------------------------------------------------------
# 9. 예측력 검증 — 있으면 있다고, 없으면 없다고
# --------------------------------------------------------------------------
def _trading_day_of(ts: pd.Timestamp) -> pd.Timestamp:
    """
    기사를 어느 거래일에 귀속시킬 것인가.

    15:30 이후 기사는 그날 주가에 반영될 수 없다 → 다음 거래일에 귀속한다.
    이 처리를 빼면 "장 마감 후 나온 실적 기사"가 당일 수익률과 상관이 있는 것처럼
    보인다. 사실은 주가가 움직여서 기사가 난 것이다 — 인과가 거꾸로다.
    """
    d = pd.Timestamp(ts).tz_convert(MARKET_TZ) if ts.tzinfo else pd.Timestamp(ts)
    if d.strftime("%H:%M") >= "15:30":
        d = d + pd.Timedelta(days=1)
    while d.weekday() >= 5:
        d = d + pd.Timedelta(days=1)
    return pd.Timestamp(d.date())


def validate_predictive_power(codes: list[str], days: int = 7,
                              verbose: bool = True) -> dict:
    """
    감성 지수와 **다음 거래일 수익률**의 상관을 측정한다.

    한계를 먼저 밝힌다.
      - 구글 뉴스 RSS는 최근 7일까지만 준다. 표본이 종목수 × 며칠 수준으로 작다.
      - 따라서 이 결과는 **참고치이지 결론이 아니다.** 유의성을 주장할 수 없다.
      - 그럼에도 하는 이유: "감성 지수가 주가를 예측한다"는 인상을 주지 않기 위해서다.
        숫자를 내보이고 약하면 약하다고 쓰는 게 안 재는 것보다 정직하다.
    """
    from . import data as D
    from scipy import stats

    rows = []
    for code in codes:
        name = D.name_of(code)
        raw = collect(name, days)
        if not len(raw):
            continue
        dd = score_articles(dedup(raw, corp_name=name))
        dd["tday"] = dd["ts"].map(_trading_day_of)
        daily = D.load_daily(code)
        if daily is None or len(daily) < 30:
            continue
        ret = daily["close"].pct_change().shift(-1)   # 그날의 '다음날' 수익률
        ret.index = pd.to_datetime(ret.index)

        for tday, g in dd.groupby("tday"):
            # 사건 단위로 축약해야 중복 보도가 점수를 부풀리지 않는다
            ev = g.groupby("cluster")["polarity"].mean()
            s = float(np.clip(ev.mean() * 100, -100, 100))
            if tday not in ret.index:
                continue
            r0 = daily["close"].pct_change()
            rows.append({
                "code": code, "name": name, "date": tday,
                "score": s, "n_articles": len(g), "n_events": int(len(ev)),
                "ret_same": float(r0.get(tday, np.nan)) * 100,
                "ret_next": float(ret.get(tday, np.nan)) * 100,
            })

    # 수집이 하나도 안 되면 rows가 비고, 그러면 컬럼조차 없어
    # `dropna(subset=["ret_next"])`가 KeyError로 죽는다.
    # 검증이 못 돌아가는 것과 프로그램이 죽는 것은 다르다 — 여기선 전자여야 한다.
    df = pd.DataFrame(rows)
    df = df.dropna(subset=["ret_next"]) if "ret_next" in df.columns else df
    if len(df) < 8:
        out = {"n": len(df), "verdict": "표본 부족으로 판단 불가",
               "detail": df.to_dict("records")}
        if verbose:
            print(f"뉴스 예측력 검증: 표본 {len(df)}건 — 판단할 수 없습니다.")
        return out

    r_next, p_next = stats.pearsonr(df["score"], df["ret_next"])
    r_same, p_same = stats.pearsonr(df["score"].fillna(0),
                                    df["ret_same"].fillna(0))
    hi = df[df["score"] > 20]["ret_next"].mean()
    lo = df[df["score"] < -20]["ret_next"].mean()

    strong = abs(r_next) > 0.2 and p_next < 0.05
    verdict = ("약한 양의 상관이 관측됨 (표본이 작아 확정할 수 없음)" if strong and r_next > 0
               else "약한 음의 상관이 관측됨 (표본이 작아 확정할 수 없음)" if strong
               else "다음날 수익률과의 상관은 확인되지 않음")

    out = {
        "n": int(len(df)),
        "corr_next_day": round(float(r_next), 3), "p_next_day": round(float(p_next), 4),
        "corr_same_day": round(float(r_same), 3), "p_same_day": round(float(p_same), 4),
        "mean_ret_next_when_positive": round(float(hi), 3) if np.isfinite(hi) else None,
        "mean_ret_next_when_negative": round(float(lo), 3) if np.isfinite(lo) else None,
        "verdict": verdict,
        "detail": df.to_dict("records"),
    }
    if verbose:
        print("=" * 70)
        print("뉴스 감성 지수의 예측력")
        print("=" * 70)
        print(f"  표본                 {out['n']}건 (종목 {df['code'].nunique()}개 × 최대 {days}일)")
        print(f"  다음날 수익률 상관    r={out['corr_next_day']:+.3f}  p={out['p_next_day']:.3f}")
        print(f"  당일 수익률 상관      r={out['corr_same_day']:+.3f}  p={out['p_same_day']:.3f}")
        if out["mean_ret_next_when_positive"] is not None:
            print(f"  감성 +20 초과일 때 다음날 평균 {out['mean_ret_next_when_positive']:+.2f}%")
        if out["mean_ret_next_when_negative"] is not None:
            print(f"  감성 -20 미만일 때 다음날 평균 {out['mean_ret_next_when_negative']:+.2f}%")
        print(f"  판정: {verdict}")
        print("  주의: 당일 상관이 다음날보다 크면 '주가가 움직여서 기사가 난 것'이다.")
        print("        그건 예측력이 아니다.")
    return out
