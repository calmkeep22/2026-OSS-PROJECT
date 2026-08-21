"""
근거 있는 답만 하는 질의응답.

왜 말을 지어내지 않나
---------------------
이 도구의 사용자는 화면을 보기 어렵다. 지어낸 문장과 실제 분석을 구별할 방법이
없다. 그래서 여기서는 **우리가 실제로 계산한 값**만 근거로 답하고, 그 밖의 질문에는
모른다고 말한다. 모른다고 말하는 편이 그럴듯한 답보다 낫다.

LLM 키가 있으면 더 나아지나
---------------------------
문장이 자연스러워질 뿐 답할 수 있는 범위는 같다. 근거는 여전히 아래 `Facts` 에
담긴 값뿐이어야 한다. 키가 생겨도 모델에게 시세를 물어보게 해서는 안 된다.
학습 시점의 값을 오늘 가격으로 말하기 때문이다.

절대 답하지 않는 것
-------------------
사라, 팔아라, 얼마까지 오른다. 셋 다 우리가 가진 값으로 뒷받침되지 않고, 법적으로도
투자자문이다. 거절할 때는 대신 무엇을 알려 줄 수 있는지 함께 말한다. 막다른 길로
두면 사용자는 다른 곳에서 더 나쁜 답을 찾는다.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# 투자 판단을 대신해 달라는 말. 이건 값이 있어도 답하지 않는다.
_ADVICE_WORDS = ("사도", "살까", "매수해", "매도해", "팔까", "팔아도", "추천",
                 "들어가도", "손절", "익절", "얼마나 사", "비중")
# 미래 가격을 묻는 말. 우리가 가진 것은 확률이지 가격이 아니다.
_PRICE_WORDS = ("얼마까지", "목표가", "몇 원", "얼마가 될", "전망가", "적정가")

_NUMBER_WORDS = ("수치", "숫자", "확률", "몇 퍼센트", "지표")
_PLAIN_WORDS = ("쉽게", "쉬운 말", "무슨 뜻", "풀어서", "설명해")
_NEWS_WORDS = ("뉴스", "공시", "기사", "무슨 일", "왜 움직", "왜 올", "왜 내")
_RISK_WORDS = ("위험", "리스크", "변동")
_MOVE_WORDS = ("오를", "내릴", "방향", "상승", "하락", "예측")

# 뉴스가 주가를 어떻게 만들지 묻는 말. 뉴스와 주가의 인과는 우리가 재지 않았다.
# 감성 지수는 여론의 방향을 요약할 뿐 그것이 가격을 움직였는지는 말하지 않는다.
_CAUSAL_HINTS = ("뜻해", "뜻이", "의미", "때문", "영향", "탓", "덕")


@dataclass
class Facts:
    """답의 근거가 되는 값. 여기 없는 것은 답하지 않는다."""

    name: str
    narration: str = ""
    forecast: str = ""
    direction: str = ""
    direction_meaningful: bool = False
    risk: str = ""
    anomaly: str = ""
    news_brief: str = ""
    news_score: float | None = None
    news_events: list[str] = field(default_factory=list)

    def available(self) -> list[str]:
        """지금 답할 수 있는 것. 거절할 때 함께 알려 준다."""
        can = []
        if self.forecast or self.narration:
            can.append("다음 거래일 변동성 예측")
        if self.anomaly:
            can.append("오늘 움직임이 평소와 얼마나 다른지")
        if self.risk:
            can.append("이 종목의 위험도")
        if self.news_events or self.news_brief:
            can.append("최근 뉴스와 감성 지수")
        return can


def _refusal(facts: Facts, reason: str) -> dict:
    can = facts.available()
    tail = ("대신 " + ", ".join(can) + "은 알려 드릴 수 있습니다."
            if can else "지금은 근거로 삼을 분석이 없습니다.")
    return {"답변": reason + " " + tail, "근거": [], "거절": True}


def answer(question: str, facts: Facts) -> dict:
    """
    질문 하나에 답한다.

    의도를 키워드로 가른다. 뜻을 못 알아들으면 아는 척하지 않고 무엇을 물으면 되는지
    되돌려 준다.
    """
    q = (question or "").strip()
    if not q:
        return _refusal(facts, "질문을 입력해주세요.")

    if any(w in q for w in _ADVICE_WORDS):
        return _refusal(facts, "사고파는 판단은 알려 드리지 않습니다.")
    if any(w in q for w in _PRICE_WORDS):
        return _refusal(facts, "앞으로의 가격은 알려 드리지 않습니다. "
                               "저희가 가진 것은 가격이 아니라 확률입니다.")

    grounds: list[str] = []
    lines: list[str] = []

    news_asked = any(w in q for w in _NEWS_WORDS)
    move_asked = any(w in q for w in _MOVE_WORDS)
    if news_asked and (move_asked or any(w in q for w in _CAUSAL_HINTS)):
        # 뉴스가 주가를 올릴지는 우리가 재지 않은 것이다. 감성 지수는 여론의 방향을
        # 요약할 뿐, 그것이 가격을 움직였는지는 말하지 않는다.
        lines = ["단정할 수 없습니다. 뉴스가 주가를 어떻게 움직이는지는 "
                 "저희가 재지 않은 것입니다."]
        if facts.news_events:
            lines.append("확인된 사건은 이렇습니다. " + facts.news_events[0])
            grounds.append("뉴스 감성 분석")
        if facts.news_score is not None:
            lines.append(f"뉴스 감성 지수는 {facts.news_score:+.0f}점으로 "
                         "여론의 방향을 요약한 값입니다.")
        lines.append("투자 규모와 재무 상태를 함께 확인하세요. 투자 추천이 아닙니다.")
        return {"답변": " ".join(lines), "근거": grounds, "거절": False}

    if news_asked:
        if facts.news_events:
            lines.append(f"{facts.name}의 최근 사건입니다.")
            lines.extend(f"{i}. {e}" for i, e in enumerate(facts.news_events[:3], 1))
            grounds.append("뉴스 감성 분석")
        if facts.news_score is not None:
            lines.append(f"뉴스 감성 지수는 {facts.news_score:+.0f}점입니다. "
                         "여론의 방향을 요약한 것이며 주가 예측이 아닙니다.")
        if not lines:
            lines.append("최근 뉴스를 찾지 못했습니다.")

    elif any(w in q for w in _RISK_WORDS):  # noqa: SIM114
        if facts.risk:
            lines.append(facts.risk)
            grounds.append("비교군 대비 위험도 백분위")
        else:
            return _refusal(facts, "이 종목의 위험도를 아직 받지 못했습니다.")

    elif move_asked:
        if facts.forecast:
            lines.append(facts.forecast)
            grounds.append("변동성 예측 모델")
        if facts.direction:
            lines.append(facts.direction)
            if not facts.direction_meaningful:
                lines.append("다만 오를지 내릴지에 대한 예측은 검증에서 우연과 "
                             "구별되지 않았습니다. 판단 근거로 삼지 마세요.")
            grounds.append("방향 예측 모델")
        if not lines:
            return _refusal(facts, "예측을 아직 받지 못했습니다.")

    elif any(w in q for w in _NUMBER_WORDS):
        for value in (facts.forecast, facts.direction, facts.risk, facts.anomaly):
            if value:
                lines.append(value)
        if facts.news_score is not None:
            lines.append(f"뉴스 감성 지수 {facts.news_score:+.0f}점.")
        if not lines:
            return _refusal(facts, "보여 드릴 수치를 아직 받지 못했습니다.")
        grounds.append("이 종목의 최신 분석")

    elif any(w in q for w in _PLAIN_WORDS):
        if facts.narration:
            lines.append(facts.narration)
            lines.append("숫자는 확률입니다. 그렇게 된다는 뜻이 아니라 "
                         "비슷한 상황에서 그런 경우가 그만큼 있었다는 뜻입니다.")
            grounds.append("이 종목의 최신 분석")
        else:
            return _refusal(facts, "설명할 분석을 아직 받지 못했습니다.")

    else:
        return _refusal(facts, "그 질문은 이해하지 못했습니다.")

    lines.append("투자 추천이 아닙니다.")
    return {"답변": " ".join(lines), "근거": grounds, "거절": False}


def suggestions(facts: Facts) -> list[str]:
    """물어볼 만한 것. 무엇을 물어야 할지 모르면 아무것도 못 묻는다."""
    picks = []
    if facts.narration:
        picks.append("쉽게 설명해줘")
    if facts.forecast or facts.direction:
        picks.append("핵심 수치 알려줘")
    if facts.news_events:
        picks.append("무슨 일이 있었어?")
    if facts.risk:
        picks.append("위험도가 어때?")
    return picks
