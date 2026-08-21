"""
사건 요약 검사 — `news.summarize_event`.

`_NUM_PAT` 이 정의되지 않은 채 쓰이고 있었다. 요약을 만들 때마다 NameError 가 나서
`analyze()` 가 통째로 실패했고, 그 결과 뉴스 기능이 한 번도 동작하지 않았다. 예외가
`analyze` 밖으로 나가 버려 "뉴스 없음" 이 아니라 500 으로 떨어졌다.

숫자만 뽑으면 "3분기" 의 3 과 "3퍼센트" 의 3 이 구별되지 않는다. 단위를 붙여 잡는다.
"""

import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from accessible_investor import news as N  # noqa: E402


def _row(title: str, polarity: float = 0.5, n_articles: int = 1) -> pd.Series:
    return pd.Series({"title": title, "polarity": polarity,
                      "n_articles": n_articles, "sources": ["경제 신문", "거래소"]})


def test_요약이_예외_없이_만들어진다():
    """이게 죽으면 analyze() 가 통째로 실패해 뉴스 기능이 전부 멈춘다."""
    text = N.summarize_event(_row("A전자, 3분기 영업이익 12조원 기록"))
    assert text.startswith("A전자, 3분기 영업이익 12조원 기록")


@pytest.mark.parametrize("title,expected", [
    ("영업이익 12조원, 8.5퍼센트 증가", ("12", "조원")),
    ("목표주가 95,000원으로 상향", ("95,000", "원")),
    ("주가 -3.2% 하락", ("-3.2", "%")),
])
def test_수치를_단위와_함께_뽑는다(title, expected):
    assert expected in N._NUM_PAT.findall(title)


def test_긴_단위가_짧은_단위로_잘리지_않는다():
    """'억원' 이 '억' 으로 잘리면 '100억원' 이 '100억' 으로 읽힌다."""
    assert ("100", "억원") in N._NUM_PAT.findall("100억원 투자")


def test_수치가_하나뿐이면_굳이_다시_말하지_않는다():
    """제목에 이미 있는 숫자를 되풀이하면 듣는 사람에게는 같은 말이 두 번이다."""
    assert "언급된 수치" not in N.summarize_event(_row("A전자, 12조원 투자"))


def test_여러_매체가_보도했으면_몇_곳인지_말한다():
    text = N.summarize_event(_row("A전자 신규 투자", n_articles=14))
    assert "14개 매체" in text
