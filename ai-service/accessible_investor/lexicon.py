"""
영문 금융 감성 사전 (Loughran-McDonald 축약판).

왜 사전인가
-----------
xforecast 아카이브의 뉴스는 **47만 건**이다. 여기에 BERT를 돌리면
CPU로 몇 시간이 걸리고, exe에서는 아예 못 쓴다. 사전 방식은
집합 조회 한 번이라 47만 건을 수십 초에 끝낸다.

금융 텍스트에 범용 감성 사전(예: VADER)을 쓰면 안 된다. 회계·공시
어휘에서는 일반적 어감과 극성이 다르기 때문이다.

    "liability"   일반: 부담·골칫거리(부정)   회계: 부채(중립)
    "tax"         일반: 부정                  재무: 중립
    "vice"        일반: 악덕(부정)            직함: vice president(중립)

Loughran & McDonald (2011, Journal of Finance) 가 10-K 공시를 세어
이 문제를 지적하고 재무 전용 사전을 만들었다. 아래는 그 사전에서
**뉴스 요약문에 실제로 자주 나오는 어간만 추린 축약판**이다.

⚠️ 원본이 아니다
----------------
원본은 극성별 수백~2천 단어다. 아래는 각 극성 100~180개 수준으로,
재현성을 위해 저장소에 그대로 담을 수 있게 줄인 것이다. 원본을 쓰려면
`LM_MASTER_PATH` 환경변수에 마스터 사전 CSV 경로를 주면 그걸 읽는다.

극성 계산
---------
    polarity = (pos - neg) / (pos + neg + k)

k(=`SMOOTH`)는 평활항이다. 없으면 단어 하나만 걸린 문서가 ±1.0으로
튀어서, 근거가 빈약한 문서와 확실한 문서를 구별할 수 없다.

부정어 처리
-----------
"not strong", "no growth" 처럼 앞 두 단어 안에 부정어가 있으면 극성을
뒤집는다. 금융 뉴스에서 "failed to beat", "did not grow" 는 흔하다.
"""

from __future__ import annotations

import os
import re
from functools import lru_cache
from pathlib import Path

SMOOTH = 3.0

# 부정어. 앞 NEG_SCOPE 단어 안에 있으면 극성을 뒤집는다.
NEGATORS = frozenset("""
not no never none nor cannot cant couldnt didnt doesnt dont hadnt hasnt
havent isnt wasnt werent wont wouldnt without lacks lacking failed fails
fail unable neither barely hardly scarcely
""".split())
NEG_SCOPE = 3

# ---------------------------------------------------------------------------
# 축약 사전 — 어간(stem) 단위. 접미사는 _stemize 가 잘라낸다.
# ---------------------------------------------------------------------------
POSITIVE = frozenset("""
able abundant accomplish achiev advanc advantag alliance attain attract
beat benefici benefit best better boom boost breakthrough brilliant
collabor complement conclusiv confid constructive courteous creativ
delight depend desirable despite dream durable ease easi effici empower
enabl encourag enhanc enjoy enthusiasm excel except excit exclusiv
exemplary expand exceed favor favorit friendly gain good great greater
happi highest honor ideal improv incredibl influential informative
ingenuity innovat insight inspir integrity invent lead leadership
loyal lucrative meritorious momentum optimis outperform outstanding
perfect pleasant pleas plentiful popular positiv premier premium
prestig proactive proficien profit progress prosper record regain
rebound resolv revolution reward robust satisf smooth solv spectacular
stabiliz streamlin strength strong succeed success superior surpass
sustainab thrive top transparen tremendous unmatched unparalleled
upgrad upside upturn valuable versatile vibrant win won worthy
""".split())

NEGATIVE = frozenset("""
abandon abnormal absence accident adverse adversely against aggravat
alarming allegation alleged annul anomal antitrust bad bail bankrupt
barrier breach bribery burden cancel careless caution cease challeng
closure collaps collusion complain complic concern confess confront
conspir contempt contamina contradict controvers convict corrupt crime
criminal crisis critical crucial cut damag danger deadlock decay
decept declin decreas default defect deficien delay delinquen demolish
denial deny depress deprecat deteriorat detriment devalu difficult
diminish disagree disappoint disaster disciplinary disclos discontinu
discourag dismiss disput disrupt dissatisf distress divert downgrad
downturn drag drop dubious dump eras erod erron escalat evict exagger
exceedingly excessive exploit expropriat fail fall false fault fear
felony fine flaw forbid forc fraud frustrat futile grievance guilty
halt hamper harass hard harm hazard hinder hurt idle illegal illicit
impair imped improper inability inaccurat inadequat inadvertent
incapab incident incompeten inconsisten incorrect indict ineffect
inefficien inferior infring injunction injur insolven instabilit
insufficien interfer interrupt investigat lack lag lapse late lawsuit
layoff lien limitation liquidat litigat lose loss lost malfunction
manipulat misappropriat misconduct misdemeanor mislead misstat
mistake misus negligen nonperform nullif obsolet obstacl offense
opposition outage overcharg overdue overrun overstat penalt peril
persist plaintiff plummet poor postpon precipitous predatory pressure
prevent problem prolong prone prosecut protest question recall recession
redress reduc refus reject relinquish renegotiat repossess restat
restructur retaliat risk sacrific scandal sever shortag shortfall
shut slow sluggish solvenc stagnat stolen stopp strain stress strike
subpoena substandard sue suffer suspect suspend terminat testify
threat tighten tragic troubl unable unauthoriz uncertain uncollect
undesirab undetermin uneconomic unemploy unethical unexpect unfair
unfavor unfit unforeseen unfortunate unfulfill uninsur unjust unlawful
unnecessar unpaid unprofitab unreasonab unrest unsatisf unsold
unstable unsuccessful unsuit untrust unwarrant urgent violat volatil
vulnerab warn weak worse worst writeoff writedown wrong
""".split())

# 불확실성 어휘. 극성과 별개로 "확신 없음"의 신호다.
# 이게 많으면 판단 강도를 낮춰야 한다.
UNCERTAIN = frozenset("""
almost ambigu anticipat appear approxim assum believ cautious conceivab
depend doubt exposure fluctuat hidden imprecis improbab indefinit
indetermin instabilit likelihood may maybe might nearly occasional
pending perhaps possib precaution predict preliminar presum probab
random reassess recalculat reconsider revis risk rough seldom
seem sometim somewhat speculat sporadic sudden suggest tentativ
turbul uncertain unclear unconfirm undecid undefin undetermin
unexpect unforeseen unguarante unknown unobserv unplan unpredict
unproven unquantif unseasonal unsettl untest unusual vari volatil
""".split())

_WORD = re.compile(r"[a-z]+")
# 어간 추출용 접미사. 긴 것부터 잘라야 "ingly" 가 "ing" 보다 먼저 걸린다.
_SUFFIX = ("ingly", "edly", "ions", "ing", "ies", "ied", "ely", "est",
           "ers", "ed", "es", "er", "ly", "s")


def _stemize(w: str) -> str:
    """아주 가벼운 어간 추출. Porter stemmer 를 쓸 만큼의 정밀도는 필요 없다."""
    for suf in _SUFFIX:
        if len(w) > len(suf) + 3 and w.endswith(suf):
            return w[: -len(suf)]
    return w


@lru_cache(maxsize=1)
def _tables() -> tuple[frozenset, frozenset, frozenset]:
    """
    사전을 돌려준다. `LM_MASTER_PATH` 가 있으면 원본 마스터 사전을 읽는다.

    마스터 사전은 Loughran-McDonald 공식 배포본(CSV)이며, 라이선스상
    저장소에 동봉하지 않는다. 없으면 위의 축약판으로 동작한다.
    """
    path = os.getenv("LM_MASTER_PATH", "").strip()
    if path and Path(path).is_file():
        try:
            import pandas as pd
            m = pd.read_csv(path)
            col = {c.lower(): c for c in m.columns}
            w = m[col["word"]].astype(str).str.lower()
            pos = frozenset(w[m[col["positive"]] > 0].map(_stemize))
            neg = frozenset(w[m[col["negative"]] > 0].map(_stemize))
            unc = frozenset(w[m[col["uncertainty"]] > 0].map(_stemize))
            if pos and neg:
                return pos, neg, unc
        except Exception:
            pass                        # 형식이 다르면 조용히 축약판으로 폴백
    return POSITIVE, NEGATIVE, UNCERTAIN


def score(text: str) -> dict[str, float]:
    """
    영문 금융 텍스트 → 극성·불확실성.

    Returns
    -------
    polarity      -1 ~ +1. 평활항 때문에 극단값은 잘 안 나온다
    pos_ratio     긍정어 비율
    neg_ratio     부정어 비율
    uncertainty   불확실성 어휘 비율
    n_words       단어 수. 짧은 문서를 걸러낼 때 쓴다
    """
    if not text:
        return {"polarity": 0.0, "pos_ratio": 0.0, "neg_ratio": 0.0,
                "uncertainty": 0.0, "n_words": 0.0}

    pos_t, neg_t, unc_t = _tables()
    words = _WORD.findall(text.lower())
    n = len(words)
    if not n:
        return {"polarity": 0.0, "pos_ratio": 0.0, "neg_ratio": 0.0,
                "uncertainty": 0.0, "n_words": 0.0}

    stems = [_stemize(w) for w in words]
    pos = neg = unc = 0

    for i, st in enumerate(stems):
        is_pos, is_neg = st in pos_t, st in neg_t
        if not (is_pos or is_neg):
            if st in unc_t:
                unc += 1
            continue
        # 앞 NEG_SCOPE 단어 안에 부정어가 있으면 극성을 뒤집는다.
        flip = any(words[j] in NEGATORS
                   for j in range(max(0, i - NEG_SCOPE), i))
        if is_pos:
            neg += 1 if flip else 0
            pos += 0 if flip else 1
        else:
            pos += 1 if flip else 0
            neg += 0 if flip else 1

    return {"polarity": (pos - neg) / (pos + neg + SMOOTH),
            "pos_ratio": pos / n,
            "neg_ratio": neg / n,
            "uncertainty": unc / n,
            "n_words": float(n)}


def score_many(texts) -> "list[dict[str, float]]":
    """여러 건. 사전 조회라 병렬화 없이도 충분히 빠르다."""
    return [score(t if isinstance(t, str) else "") for t in texts]
