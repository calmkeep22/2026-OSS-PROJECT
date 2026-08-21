# similarity — 차트 유사도

지금 모양과 닮은 과거 구간을 1·3·6·12개월 네 창에서 찾는다. z-정규화하므로 가격대가 달라도 모양만 비교된다.



## 창을 왜 넷으로 나눴나

처음엔 20봉 하나만 봤다. 그런데 20봉은 "지금 이 모양"만 답하고
"올해 전체 흐름"은 답하지 못한다. 두 질문의 답이 다른데 한 숫자로
합치면 둘 다 잃는다. 그래서 **1·3·6·12개월(20/60/120/250봉)** 을 각각 돌린다.

2년(500봉)은 넣지 않았다. 겹치지 않는 구간이 종목당 3~4개뿐이라
"닮은 구간 4개"를 뽑으면 사실상 전부가 뽑힌다 — 비교가 되지 않는다.

## 네 창에서 공통으로 올라오면 그게 진짜다

창마다 1등이 다르다는 것 자체가 결과다. `similar_consensus.csv` 는
몇 개 창에서 공통으로 올라왔는지를 센다. 한 창에서만 1등인 종목보다
네 창 모두에 있는 종목이 더 믿을 만하다.

## 평균으로 요약하지 않는다

닮은 구간 다음의 수익률을 평균 한 숫자로 쓰면 예측처럼 읽힌다.
상승 몇 건 · 하락 몇 건 · 흩어진 정도까지만 사실로 말한다.

## 폴더

| 폴더 | 내용 | 개수 |
|---|---|---|
| [`figures/`](figures/) | 제출용 그림 (PNG) | 6 |
| [`data/`](data/) | 근거 표 (CSV/JSON) | 3 |

전체 요약은 [`../index.html`](../index.html) 하나로 본다.

## 이 파트를 만드는 코드

**복사본이 아니라 원본 링크다.** 실행되는 것도 이것뿐이라 어긋날 일이 없다.

- [`similarity.py`](../../accessible_investor/similarity.py)
- [`pairs.py`](../../accessible_investor/pairs.py)
- [`segments.py`](../../accessible_investor/segments.py)

공통 모듈은 [`../../accessible_investor/`](../../accessible_investor/) 에 함께 있다
(`config.py` `data.py` `pipeline.py` `viz.py` …).

## 그림

| 파일 |
|---|
| [`01_windows_000660.png`](figures/01_windows_000660.png) |
| [`01_windows_005930.png`](figures/01_windows_005930.png) |
| [`01_windows_047050.png`](figures/01_windows_047050.png) |
| [`01_windows_086520.png`](figures/01_windows_086520.png) |
| [`01_windows_196170.png`](figures/01_windows_196170.png) |
| [`01_windows_402340.png`](figures/01_windows_402340.png) |

## 표

| 파일 |
|---|
| [`similar_consensus.csv`](data/similar_consensus.csv) |
| [`similar_matches.csv`](data/similar_matches.csv) |
| [`summary.json`](data/summary.json) |
