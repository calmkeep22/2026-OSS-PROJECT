"""
근거 있는 답만 하는지 검사 — `chat.py`.

이 도구의 사용자는 화면을 보기 어렵다. 지어낸 문장과 실제 분석을 구별할 방법이
없다. 그래서 여기서 지키는 것은 문장이 자연스러운지가 아니라 **없는 근거로 말하지
않는지**다.

아래 질문은 전부 실제로 나올 만한 것이고, 몇 개는 답하면 안 되는 것이다.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import chat as C  # noqa: E402


@pytest.fixture
def facts() -> C.Facts:
    return C.Facts(
        name="A전자",
        narration="A전자는 평소 범위 안에서 움직였습니다.",
        forecast="다음 거래일 크게 움직일 확률 52퍼센트입니다.",
        direction="다음 거래일 방향 예측은 하락입니다. 확률 50.7퍼센트.",
        direction_meaningful=False,
        risk="이 종목의 위험도는 높음입니다.",
        news_score=12.0,
        news_events=["A전자가 신규 시설 투자를 공시했습니다."],
    )


@pytest.mark.parametrize("question", [
    "지금 사도 돼?", "매수해도 될까", "이거 추천해?", "손절해야 하나", "비중 얼마나 가져가"])
def test_사고파는_판단은_거절한다(facts, question):
    """우리가 가진 값으로 뒷받침되지 않고, 법적으로도 투자자문이다."""
    result = C.answer(question, facts)
    assert result["거절"]


@pytest.mark.parametrize("question", ["얼마까지 올라?", "목표가 알려줘", "적정가가 몇 원이야"])
def test_미래_가격은_거절한다(facts, question):
    """우리가 가진 것은 가격이 아니라 확률이다."""
    result = C.answer(question, facts)
    assert result["거절"]
    assert "확률" in result["답변"]


def test_거절할_때_할_수_있는_것을_함께_말한다(facts):
    """막다른 길로 두면 사용자는 다른 곳에서 더 나쁜 답을 찾는다."""
    result = C.answer("지금 사도 돼?", facts)
    assert "알려 드릴 수 있습니다" in result["답변"]


def test_뉴스가_주가를_뜻하냐는_물음에_단정하지_않는다(facts):
    """감성 지수는 여론의 방향을 요약할 뿐 가격을 움직였는지는 말하지 않는다."""
    result = C.answer("이 공시가 주가 상승을 뜻해?", facts)
    assert "단정할 수 없습니다" in result["답변"]
    assert not result["거절"]


def test_검증되지_않은_방향_예측에는_그_사실을_붙인다(facts):
    result = C.answer("오를까 내릴까?", facts)
    assert "우연과 구별되지 않았습니다" in result["답변"]


def test_검증된_방향_예측에는_경고를_붙이지_않는다(facts):
    """검증을 통과한 것까지 싸잡아 경고하면 경고가 무뎌진다."""
    facts.direction_meaningful = True
    result = C.answer("오를까 내릴까?", facts)
    assert "우연과 구별되지 않았습니다" not in result["답변"]


def test_근거가_없으면_답하지_않는다():
    """분석을 못 받았는데 답이 나오면 그건 지어낸 것이다."""
    empty = C.Facts(name="A전자")
    result = C.answer("핵심 수치 알려줘", empty)
    assert result["거절"]


def test_알아듣지_못하면_아는_척하지_않는다(facts):
    result = C.answer("오늘 점심 뭐 먹지", facts)
    assert result["거절"]


def test_모든_답에_투자_추천이_아님을_붙인다(facts):
    for question in ["핵심 수치 알려줘", "쉽게 설명해줘", "무슨 일이 있었어?", "위험도가 어때?"]:
        assert "투자 추천이 아닙니다" in C.answer(question, facts)["답변"]


def test_추천_질문은_답할_수_있는_것만_준다():
    """물어봤자 모른다고 할 질문을 권하면 사용자를 두 번 헛걸음시킨다."""
    only_news = C.Facts(name="A전자", news_events=["공시가 있었습니다."])
    assert C.suggestions(only_news) == ["무슨 일이 있었어?"]
