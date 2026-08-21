"""
국문 금융 사전 검사 — `lexicon_ko.py`.

이 사전은 **교차언어 전이의 척도를 맞추려고** 만든 것이라, 부호가 틀리면
전이 모델이 미국에서 배운 임계값이 그대로 어긋난다. 그래서 실제로 틀렸던
문장을 사례로 박아 둔다. 아래 목록은 전부 **개발 중에 오답이 났던 것**이다.

    "실적 개선 차질 없이"      → '없'이 12글자 안에 있어 긍정이 뒤집혔다
    "비용만 증가"              → '증가'가 무조건 긍정이었다
    "기대치 상회하지 않았다"   → '기대'가 긍정으로 잡혀 상쇄됐다
    "회복 못 했다"             → 부정어 목록에 '못' 단독이 없었다

실행
----
    python -m pytest tests/test_lexicon_ko.py -v
    python tests/test_lexicon_ko.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from accessible_investor import lexicon as LX_EN  # noqa: E402
from accessible_investor import lexicon_ko as LX  # noqa: E402

# (문장, 기대 부호). "+" 긍정 · "-" 부정
CASES = [
    ("삼성전자, 3분기 영업이익 시장 기대치 상회…목표주가 상향", "+"),
    ("SK하이닉스 급락, 실적 부진 우려에 외국인 매도", "-"),
    ("현대차 리콜 결정…품질 논란 확산", "-"),
    ("LG엔솔 수주 확대 기대감에 강세", "+"),
    ("영업이익 증가로 흑자전환 성공", "+"),
    # --- 부정 범위 ---
    ("테슬라 주가 회복 못 했다", "-"),
    ("영업이익 기대치 상회하지 않았다", "-"),
    ("실적 개선 차질 없이 진행…생산 정상화", "+"),
    # --- 모호한 단일어 ---
    ("매출 감소에 비용 증가까지", "-"),
    ("적자 확대…유상증자 결정에 급락", "-"),
    ("코스피 혼조세, 방향성 관망…실적 개선 가능성 전망", "+"),
]


def _sign(x: float) -> str:
    return "+" if x > 0 else ("-" if x < 0 else "0")


def test_polarity_sign():
    """부호가 기대와 맞아야 한다."""
    bad = []
    for text, want in CASES:
        got = _sign(LX.score(text)["polarity"])
        if got != want:
            bad.append(f"{text!r} → {got} (기대 {want})")
    assert not bad, "부호 불일치:\n  " + "\n  ".join(bad)
    print(f"  ✅ {len(CASES)}개 문장 부호 일치")


def test_scale_matches_english_lexicon():
    """
    영문 사전과 **같은 척도**여야 한다.

    이 모듈의 존재 이유가 척도 정합이다. 평활항이 다르면 근거 단어 하나짜리
    문서의 극성이 서로 달라지고, 미국에서 배운 임계값이 안 맞는다.
    """
    assert LX.SMOOTH == LX_EN.SMOOTH, (
        f"평활항이 다르다: 국문 {LX.SMOOTH} vs 영문 {LX_EN.SMOOTH}")
    assert set(LX.score("")) == set(LX_EN.score("")), "반환 스키마가 다르다"

    # 근거 단어 **1개**짜리 문서는 양쪽 모두 정확히 ±0.25 여야 한다.
    #   (pos - neg) / (pos + neg + SMOOTH) = 1 / (1 + 3) = 0.25
    # 이 값이 같다는 것이 "같은 척도"의 구체적 의미다. 전이 모델은 이
    # 분포 위에서 임계값을 배웠다.
    #
    # ⚠️ 영문 예시는 사전에 실제로 있는 어간으로 골라야 한다.
    # 처음엔 "shares rise" 를 썼는데 LM 축약판에는 방향어(rise/fall)가 없다 —
    # 재무 감성 사전이라 감성어 위주다. 검사가 사전이 아니라 예시 탓에
    # 실패했다.
    ko = LX.score("삼성전자 주가 상승")["polarity"]
    en = LX_EN.score("profit surged")["polarity"]
    assert abs(ko - 0.25) < 1e-9, f"국문 단일 근거 극성 {ko} (기대 0.25)"
    assert abs(en - 0.25) < 1e-9, f"영문 단일 근거 극성 {en} (기대 0.25)"
    print(f"  ✅ 평활항 {LX.SMOOTH} 공유 · 단일 근거 극성 "
          f"국문 {ko:+.2f} = 영문 {en:+.2f}")


def test_no_double_counting():
    """긴 표현이 짧은 표현으로 두 번 세이지 않아야 한다."""
    r = LX.score("목표주가 상향")
    # "목표주가 상향"과 "상향"이 둘 다 세이면 pos=2 라 극성이 0.4 가 된다.
    assert abs(r["polarity"] - 0.25) < 1e-9, (
        f"이중 계수 의심 — 극성 {r['polarity']} (1건이면 0.25)")
    print("  ✅ '목표주가 상향' 1건으로 계수")


def test_neutral_text_is_zero():
    """근거어가 없으면 정확히 0 이어야 한다 (중립 0.5 대치와 구분된다)."""
    for t in ("삼성전자 정기 주주총회 개최", "이사회 일정 공고"):
        assert LX.score(t)["polarity"] == 0.0, t
    print("  ✅ 근거 없는 문장은 극성 0")


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    fails = 0
    for fn in fns:
        print(f"\n▶ {fn.__name__}")
        try:
            fn()
        except AssertionError as e:
            fails += 1
            print(f"  ❌ {e}")
    print(f"\n{len(fns) - fails}/{len(fns)} 통과")
    sys.exit(1 if fails else 0)
