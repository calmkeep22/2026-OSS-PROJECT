# anomaly — 이상감지 · 종목별 위험도

장중 이상 움직임을 찾고, 종목별로 얼마나 험한지를 낸다. 핵심 문제는 정확도가 아니라 알림 폭주였다 — 고정 임계값은 종목당 하루 9.8건을 냈다.



## 두 가지 다른 질문

    이상감지  오늘 09:35 거래량이 평소의 8배다        ← 시점
    위험도    이 종목은 이런 일이 자주 나는 종목이다   ← 성질

관심종목을 고를 때 필요한 건 후자다. 이상 신호가 하루 3번 뜨는 종목과
2주에 한 번 뜨는 종목은 같은 방식으로 다룰 수 없다.

## 다섯 축

변동성 · 이상빈도 · 갭 · 꼬리 · 유동성을 **비교군 안에서의 백분위**로
환산해 가중 평균한다. 절대 기준을 쓰지 않는 이유는 시장 국면마다
"높은 변동성"의 값이 달라지기 때문이다 — 2020년 3월엔 대형주도 5%를 찍었다.

## robust z 는 후행 지표다

`|z| > 2.5` 는 **이미 움직인 뒤에** 뜬다. 그래서 이 신호를 "지금 사라"로
읽으면 안 된다. 다만 급락 쪽에서는 그 후행성이 오히려 쓸모가 있다 —
평균 회귀 때문이다. 그 검증은 [`../03_forecast/`](../03_forecast/) 에 있다.

## ⚠️ 수익 예측이 아니다

위험도가 높다고 떨어진다는 뜻이 아니고, 낮다고 오른다는 뜻도 아니다.
**변동 폭과 진입·청산 난이도**만 말한다.

## 폴더

| 폴더 | 내용 | 개수 |
|---|---|---|
| [`figures/`](figures/) | 제출용 그림 (PNG) | 40 |
| [`data/`](data/) | 근거 표 (CSV/JSON) | 3 |
| [`validation/`](validation/) | 엔진 검증 근거 (어블레이션·랭커·벤치마크) | 21 |

전체 요약은 [`../index.html`](../index.html) 하나로 본다.

## 이 파트를 만드는 코드

**복사본이 아니라 원본 링크다.** 실행되는 것도 이것뿐이라 어긋날 일이 없다.

- [`anomaly.py`](../../accessible_investor/anomaly.py)
- [`ranker.py`](../../accessible_investor/ranker.py)
- [`risk.py`](../../accessible_investor/risk.py)
- [`features.py`](../../accessible_investor/features.py)
- [`segments.py`](../../accessible_investor/segments.py)
- [`models.py`](../../accessible_investor/models.py)
- [`training.py`](../../accessible_investor/training.py)
- [`validation.py`](../../accessible_investor/validation.py)
- [`tabpfn_bench.py`](../../accessible_investor/tabpfn_bench.py)

공통 모듈은 [`../../accessible_investor/`](../../accessible_investor/) 에 함께 있다
(`config.py` `data.py` `pipeline.py` `viz.py` …).

## 그림

| 파일 |
|---|
| [`01_risk_profile.png`](figures/01_risk_profile.png) |
| [`02_risk_ranking.png`](figures/02_risk_ranking.png) |
| [`03_timeline_000660.png`](figures/03_timeline_000660.png) |
| [`03_timeline_005930.png`](figures/03_timeline_005930.png) |
| [`03_timeline_006570.png`](figures/03_timeline_006570.png) |
| [`03_timeline_010690.png`](figures/03_timeline_010690.png) |
| [`03_timeline_012030.png`](figures/03_timeline_012030.png) |
| [`03_timeline_025750.png`](figures/03_timeline_025750.png) |
| [`03_timeline_037710.png`](figures/03_timeline_037710.png) |
| [`03_timeline_041960.png`](figures/03_timeline_041960.png) |
| [`03_timeline_047050.png`](figures/03_timeline_047050.png) |
| [`03_timeline_049950.png`](figures/03_timeline_049950.png) |
| [`03_timeline_054920.png`](figures/03_timeline_054920.png) |
| [`03_timeline_086520.png`](figures/03_timeline_086520.png) |
| [`03_timeline_118000.png`](figures/03_timeline_118000.png) |
| [`03_timeline_129920.png`](figures/03_timeline_129920.png) |
| [`03_timeline_196170.png`](figures/03_timeline_196170.png) |
| [`03_timeline_247540.png`](figures/03_timeline_247540.png) |
| [`03_timeline_288620.png`](figures/03_timeline_288620.png) |
| [`03_timeline_376180.png`](figures/03_timeline_376180.png) |

## 표

| 파일 |
|---|
| [`anomaly_counts.csv`](data/anomaly_counts.csv) |
| [`risk_scores.csv`](data/risk_scores.csv) |
| [`summary.json`](data/summary.json) |
