# forecast — 다음날 예측 · 급락 반등

방향·변동성 예측을 정직하게 측정하고, 검증을 통과한 급락 반등 신호를 낸다.

## 측정 결과 — 균형정확도

| 타깃 | 모델 | 균형정확도 | 95% 신뢰구간 | 판정 | 50%초과 종목 | 단순 적중률 | 무지성 기준선 |
|---|---|---|---|---|---|---|---|
| 변동성 | 앙상블 | 54.73% | [52.2%, 57.2%] | **유의미** | 28/38 | 54.74% | 54.3% |
| 방향 | 앙상블 | 50.38% | [47.9%, 52.9%] | 미검증 | 16/38 | 50.26% | 56.2% |

종목별 워크포워드 1,520건. 기준선은 **50%** 다 (위 설명 참조).

맨 오른쪽 두 열이 **왜 균형정확도를 쓰는지** 그대로 보여 준다.
단순 적중률이 무지성 기준선보다 낮은데도 50%는 넘는 경우가 있다 —
그 상태로 50% 선만 그으면 이겼다고 착각하게 된다.

**왜 방향은 안 되고 변동성은 되는가.** 방향은 차익거래로 지워진다 —
내일 오를 걸 알면 오늘 사버리기 때문이다. 반면 **변동성 군집**
("오늘 크게 움직였으면 내일도 크게")은 알아도 돈이 되지 않아
지워지지 않고 남는다.

## 급락 후 반등 — 검증을 통과한 신호

| 임계값 | 표본 | 반등률 | 평상시 | 순초과 | 급등쪽(대조) |
|---|---|---|---|---|---|
| \|z\| > 2.0 | 2,691 | 51.36% | 47.52% | **+3.84%p** | -2.05%p |
| \|z\| > 2.5 | 1,501 | 53.50% | 47.52% | **+5.98%p** | -1.07%p |
| \|z\| > 3.0 | 875 | 54.40% | 47.52% | **+6.88%p** | -0.86%p |

임계값을 높일수록 효과가 커진다 — 우연이라면 이런 단조성이
나오지 않는다. 그리고 **급등 쪽에서는 아무 효과도 없다.**
이 비대칭이 이 발견의 신뢰도를 스스로 증명한다. 급락은 강제
청산(마진콜·손절)이 밀어내는 것이라 되돌아오지만, 급등에는
그런 강제력이 없기 때문이다.

## 미래를 보지 않는다

    D일 예측
     ├ 학습 데이터  라벨까지 확정된 행만 (t+1 ≤ D-1)
     ├ 입력 피처    D-1 종가 시점
     └ 정답         D일 결과

날짜마다 다시 학습한다. 한 번 학습해 전 구간을 평가하면 D+3의 정보로 D를
맞히게 되어 정확도가 실제보다 높게 나온다.

장중에 받은 미완성 일봉은 버린다 — 그걸 정답으로 쓰면 평가가 오염되고
"오늘 예측"이 "이미 본 값 되읽기"가 된다.

## 왜 적중률이 아니라 균형정확도인가

단순 적중률은 기준선이 50%가 아니다. 라벨이 한쪽으로 쏠려 있으면
**아무 생각 없이 다수쪽만 외쳐도** 그 쏠린 비율만큼 나온다 (위 표의
"무지성 기준선" 열). 55% 짜리 모델에 50% 선을 그으면 넘은 것처럼 보이지만
실제로는 무지성보다 나쁘다. 한 번 그렇게 읽고 결론이 뒤집힌 적이 있다.

균형정확도는 상승 적중률과 하락 적중률을 따로 재서 평균한다. 한쪽만
외치면 반대쪽이 0%가 되어 정확히 50%로 수렴한다. **쏠림이 얼마든
50%가 진짜 무작위선**이므로, 50%를 기준선으로 두고 비교하는 것이
그대로 정당해진다. 모델 선택 점수도 균형정확도로 매긴다.

## 피처 네 묶음 (32개)

    기술적   19  수익률 · 이동평균 이격 · 변동성 · RSI · 연속일수 + 드리프트
    시장맥락  8  지수 수익률 · 지수 대비 초과 · 베타 · 상관
    테마      4  이웃 종목들의 수익률 · 분산 · 나와의 격차
    뉴스      1  news_xfer (미국 뉴스로 학습한 전이 모델의 출력)

**드리프트**(`drift_250` `drift_60` `up_ratio_60`)는 "이 종목이 원래
우상향하는가"를 종목마다 다른 숫자로 준다. 주식이 평균적으로 오르는 건
맞지만 그 정도가 종목마다 다르고, 그 차이가 정보다.

**테마**는 지수보다 좁다. 지수는 반도체가 오르고 은행이 빠지는 날 서로
상쇄돼 0에 가까워지는데, 그런 날 실제로 쓸모 있는 건 "내 이웃들이 어제
어땠나"다. 이웃은 상관 상위 6개이고, **앞쪽 55% 구간에서만 골라 고정**한다 —
전 구간에서 고르면 "평가 구간에서 나와 닮게 움직인 종목"을 뽑는 셈이라
그대로 누설이다.

## 뉴스는 기본으로 켠다

`DEFAULT_FEATURES = "all"`.

한동안 국내 종목의 뉴스는 학습 구간의 **0.3%** 만 차 있었다. 열이 사실상
상수라 배울 것이 없었다 — "뉴스를 쓴다"고 적어 놓고 실제로는 아무것도
안 쓰는 상태였다. 원인은 `when:7d` 밖에 안 썼기 때문이고, 구글 뉴스 RSS 는
`after:`/`before:` 를 받는다. 1년치를 소급 수집해 평가 구간을 덮었다
(`python cli.py backfill-news --days 365`).

전이 피처는 **양쪽 시장 모두 사전으로** 채점한다. 전이 모델이 미국 뉴스를
사전으로 채점한 값으로 학습했기 때문이다. BERT 값을 넣으면 학습 때와 척도가
달라(사전은 근거 1개면 ±0.25, BERT 는 확신하면 0.99) 미국에서 배운 임계값이
한국에 안 맞는다. `lexicon_ko.py` 가 영문 사전 어휘를 국내 표현으로
대응시켜 둔 것이라 같은 척도가 나온다.

남은 결측은 **중립 0.5**. NaN으로 두고 대치기에 맡겼더니 "값이 있는 행 =
최근 며칠"이라는 사실 자체를 모델이 외워 확률이 0.0%/100.0%로 포화했다.
0.5로 채우면 열이 100% 차서 그 누수가 구조적으로 불가능해진다.

커버리지는 종목마다 크게 다르다. 평균만 적으면 그 편차가 사라지므로
`news_coverage.csv` 에 종목별 수치를 그대로 싣는다.

## 판정 임계값도 학습한다

확률을 0.5로 자르면 쏠린 쪽만 잘 맞히는 모델이 된다. 학습 구간을 시간순
8:2로 갈라 **뒤 20%에서만** 임계값을 고르고, 고른 뒤 전체 학습 구간으로
다시 적합한다. 평가일 데이터는 어느 단계에서도 쓰지 않는다.

    [────────── 학습 구간 ──────────][평가일]
    [── 적합 80% ──][임계값 고르기 20%]   ↑ 여기는 안 본다

## ⚠️ 상위 종목은 선별 결과다

`demo_top_stocks.csv` 는 **성적순으로 고른 8종목**이다. 시연용이며,
이것만 보고 전체 성능을 판단하면 안 된다. 전체 분포는
`accuracy_by_stock.csv` 와 `03_accuracy_distribution.png` 에 있고,
그 그림에는 **동전 던지기였다면 나왔을 분포**를 겹쳐 두었다.

## 폴더

| 폴더 | 내용 | 개수 |
|---|---|---|
| [`figures/`](figures/) | 제출용 그림 (PNG) | 10 |
| [`data/`](data/) | 근거 표 (CSV/JSON) | 24 |

전체 요약은 [`../index.html`](../index.html) 하나로 본다.

## 이 파트를 만드는 코드

**복사본이 아니라 원본 링크다.** 실행되는 것도 이것뿐이라 어긋날 일이 없다.

- [`forecast.py`](../../accessible_investor/forecast.py)
- [`pooled.py`](../../accessible_investor/pooled.py)
- [`serving.py`](../../accessible_investor/serving.py)
- [`registry.py`](../../accessible_investor/registry.py)
- [`reference.py`](../../accessible_investor/reference.py)
- [`reversion.py`](../../accessible_investor/reversion.py)
- [`universe.py`](../../accessible_investor/universe.py)
- [`sentiment.py`](../../accessible_investor/sentiment.py)
- [`newsxfer.py`](../../accessible_investor/newsxfer.py)
- [`news.py`](../../accessible_investor/news.py)
- [`lexicon.py`](../../accessible_investor/lexicon.py)
- [`lexicon_ko.py`](../../accessible_investor/lexicon_ko.py)
- [`overseas.py`](../../accessible_investor/overseas.py)
- [`news_predict.py`](../../accessible_investor/news_predict.py)
- [`news_judge.py`](../../accessible_investor/news_judge.py)

공통 모듈은 [`../../accessible_investor/`](../../accessible_investor/) 에 함께 있다
(`config.py` `data.py` `pipeline.py` `viz.py` …).

## 그림

| 파일 |
|---|
| [`01_reversion.png`](figures/01_reversion.png) |
| [`02_target_comparison.png`](figures/02_target_comparison.png) |
| [`03_accuracy_distribution.png`](figures/03_accuracy_distribution.png) |
| [`04_by_group.png`](figures/04_by_group.png) |
| [`05_accuracy_top.png`](figures/05_accuracy_top.png) |
| [`06_daily_hits.png`](figures/06_daily_hits.png) |
| [`07_model_tradeoff.png`](figures/07_model_tradeoff.png) |
| [`08_next_day.png`](figures/08_next_day.png) |
| [`09_ablation.png`](figures/09_ablation.png) |
| [`10_pooled_vs_perstock.png`](figures/10_pooled_vs_perstock.png) |

## 표

| 파일 |
|---|
| [`accuracy_by_index.csv`](data/accuracy_by_index.csv) |
| [`accuracy_by_stock.csv`](data/accuracy_by_stock.csv) |
| [`accuracy_by_tier.csv`](data/accuracy_by_tier.csv) |
| [`demo_top_stocks.csv`](data/demo_top_stocks.csv) |
| [`feature_ablation.csv`](data/feature_ablation.csv) |
| [`model_by_target.csv`](data/model_by_target.csv) |
| [`model_selection.csv`](data/model_selection.csv) |
| [`news_coverage.csv`](data/news_coverage.csv) |
| [`next_day_prediction.csv`](data/next_day_prediction.csv) |
| [`pooled_decision.json`](data/pooled_decision.json) |
| [`pooled_vs_perstock.csv`](data/pooled_vs_perstock.csv) |
| [`pooled_walkforward_방향.csv`](data/pooled_walkforward_방향.csv) |
| [`pooled_walkforward_변동성.csv`](data/pooled_walkforward_변동성.csv) |
| [`pooled_walkforward_변동성_미학습.csv`](data/pooled_walkforward_변동성_미학습.csv) |
| [`reversion.json`](data/reversion.json) |
| [`reversion_by_stock.csv`](data/reversion_by_stock.csv) |
| [`reversion_clustered.csv`](data/reversion_clustered.csv) |
| [`reversion_holdout.csv`](data/reversion_holdout.csv) |
| [`reversion_signals.csv`](data/reversion_signals.csv) |
| [`reversion_summary.csv`](data/reversion_summary.csv) |
