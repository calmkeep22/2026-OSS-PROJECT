"""
결과 폴더 마무리 — 파트별 README 와 업로드 목록을 쓴다.

왜 코드를 복사하지 않게 바꿨나
==============================
처음에는 파트마다 `code/` 를 만들어 그 파트가 쓰는 `.py` 를 **복사**했다.
"심사자가 폴더 하나만 열어도 무슨 코드인지 보이게" 하려는 의도였는데,
실제로는 세 가지가 나빠졌다.

    1. 같은 파일이 여러 곳에 생겼다 (`segments.py` 가 두 파트에 중복)
    2. 원본과 복사본 중 어느 게 진짜인지 매번 설명해야 했다
    3. 원본을 고치고 `package` 를 안 돌리면 조용히 어긋났다

지금은 **원본 한 벌만 두고 README 에서 링크**한다. GitHub 에서 상대경로
링크는 그냥 눌리므로 "폴더 하나만 열어도 보인다"는 목적은 그대로 달성되고,
중복은 0이 된다.

구조
====
    results/
    ├── 01_anomaly/      이상감지 · 종목별 위험도
    ├── 02_similarity/   차트 유사도
    ├── 03_forecast/     다음날 예측 · 급락 반등
    │   ├── figures/     제출용 그림 (PNG)
    │   ├── data/        근거 표 (CSV/JSON)
    │   └── README.md    설명 + 코드 링크
    ├── index.html       전체 요약 한 장
    └── UPLOAD.md        GitHub 업로드 목록
"""

from __future__ import annotations

from pathlib import Path

from .config import PROJECT_ROOT

RESULTS_DIR = PROJECT_ROOT / "results"
PKG_NAME = "accessible_investor"

PARTS: dict[str, dict] = {
    "01_anomaly": {
        "title": "이상감지 · 종목별 위험도",
        "modules": ["anomaly.py", "ranker.py", "risk.py", "features.py",
                    "segments.py", "models.py", "training.py",
                    "validation.py", "tabpfn_bench.py"],
        "summary": "장중 이상 움직임을 찾고, 종목별로 얼마나 험한지를 낸다. "
                   "핵심 문제는 정확도가 아니라 알림 폭주였다 — 고정 임계값은 "
                   "종목당 하루 9.8건을 냈다.",
        "notes": """## 두 가지 다른 질문

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
**변동 폭과 진입·청산 난이도**만 말한다.""",
    },
    "02_similarity": {
        "title": "차트 유사도",
        "modules": ["similarity.py", "pairs.py", "segments.py"],
        "summary": "지금 모양과 닮은 과거 구간을 1·3·6·12개월 네 창에서 찾는다. "
                   "z-정규화하므로 가격대가 달라도 모양만 비교된다.",
        "notes": """## 창을 왜 넷으로 나눴나

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
상승 몇 건 · 하락 몇 건 · 흩어진 정도까지만 사실로 말한다.""",
    },
    "03_forecast": {
        "title": "다음날 예측 · 급락 반등",
        "modules": ["forecast.py", "pooled.py", "serving.py",
                    "registry.py", "reference.py",
                    "reversion.py", "universe.py",
                    "sentiment.py", "newsxfer.py", "news.py",
                    "lexicon.py", "lexicon_ko.py",
                    "overseas.py", "news_predict.py", "news_judge.py"],
        "summary": "방향·변동성 예측을 정직하게 측정하고, 검증을 통과한 "
                   "급락 반등 신호를 낸다.",
        "notes": """## 미래를 보지 않는다

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
그 그림에는 **동전 던지기였다면 나왔을 분포**를 겹쳐 두었다.""",
    },
}

COMMON = ["config.py", "data.py", "pipeline.py", "viz.py", "results.py",
          "report_ai.py", "package.py"]


def _measured(key: str) -> str:
    """
    측정값을 **결과 파일에서 읽어** 표로 만든다.

    숫자를 문서에 손으로 적어 두면 반드시 어긋난다. 실제로 그랬다 —
    평가 구간을 7일에서 40일로 늘린 뒤에도 README 에는 옛 숫자가 남아
    있었고, 그림 파일 이름이 바뀐 뒤에도 옛 목록이 남아 있었다.
    그래서 이 부분은 `package` 를 돌 때마다 CSV 에서 다시 읽는다.
    """
    import pandas as pd

    d = RESULTS_DIR / key / "data"
    if key != "03_forecast" or not d.is_dir():
        return ""
    out = []

    f = d / "target_comparison.csv"
    if f.exists():
        t = pd.read_csv(f)
        rows = "\n".join(
            f"| {r['타깃']} | {r['모델']} | {r['균형정확도']:.2%} | "
            f"[{r['균형_신뢰하한']:.1%}, {r['균형_신뢰상한']:.1%}] | "
            f"{'**유의미**' if r['유의미'] else '미검증'} | "
            f"{r['50%초과종목']}/{r['종목수']} | "
            f"{r['적중률']:.2%} | {r['다수클래스비율']:.1%} |"
            for _i, r in t.iterrows())
        n = int(t["평가건수"].max()) if len(t) else 0
        out.append(
            "## 측정 결과 — 균형정확도\n\n"
            "| 타깃 | 모델 | 균형정확도 | 95% 신뢰구간 | 판정 |"
            " 50%초과 종목 | 단순 적중률 | 무지성 기준선 |\n"
            "|---|---|---|---|---|---|---|---|\n" + rows + "\n\n"
            f"종목별 워크포워드 {n:,}건. 기준선은 **50%** 다 (위 설명 참조).\n\n"
            "맨 오른쪽 두 열이 **왜 균형정확도를 쓰는지** 그대로 보여 준다.\n"
            "단순 적중률이 무지성 기준선보다 낮은데도 50%는 넘는 경우가 있다 —\n"
            "그 상태로 50% 선만 그으면 이겼다고 착각하게 된다.\n\n"
            "**왜 방향은 안 되고 변동성은 되는가.** 방향은 차익거래로 지워진다 —\n"
            "내일 오를 걸 알면 오늘 사버리기 때문이다. 반면 **변동성 군집**\n"
            '("오늘 크게 움직였으면 내일도 크게")은 알아도 돈이 되지 않아\n'
            "지워지지 않고 남는다.")

    f = d / "reversion_summary.csv"
    if f.exists():
        r = pd.read_csv(f)
        rows = "\n".join(
            f"| \\|z\\| > {x['임계값']} | {int(x['급락n']):,} | "
            f"{x['급락후반등률']:.2%} | {x['평상시상승률']:.2%} | "
            f"**{x['순초과']:+.2%}p** | {x['급등순초과']:+.2%}p |"
            for _i, x in r.iterrows())
        out.append(
            "## 급락 후 반등 — 검증을 통과한 신호\n\n"
            "| 임계값 | 표본 | 반등률 | 평상시 | 순초과 | 급등쪽(대조) |\n"
            "|---|---|---|---|---|---|\n" + rows + "\n\n"
            "임계값을 높일수록 효과가 커진다 — 우연이라면 이런 단조성이\n"
            "나오지 않는다. 그리고 **급등 쪽에서는 아무 효과도 없다.**\n"
            "이 비대칭이 이 발견의 신뢰도를 스스로 증명한다. 급락은 강제\n"
            "청산(마진콜·손절)이 밀어내는 것이라 되돌아오지만, 급등에는\n"
            "그런 강제력이 없기 때문이다.")
    return "\n\n".join(out)


def _part_readme(key: str, spec: dict, n_fig: int, n_data: int) -> str:
    """파트 설명. 코드는 복사하지 않고 원본을 상대경로로 링크한다."""
    # 파트에 딸린 추가 폴더(예: 이상감지 엔진 검증 근거)를 표에 넣는다.
    # 전에는 `results/_anomaly_validation/` 이 어디서도 참조되지 않는 고아
    # 폴더였다 — 21개 파일이 있는데 심사자는 그게 뭔지 알 길이 없었다.
    vdir = RESULTS_DIR / key / "validation"
    extra_dir = ""
    if vdir.is_dir():
        n_v = sum(1 for f in vdir.iterdir() if f.is_file())
        extra_dir = ("\n| [`validation/`](validation/) | "
                     f"엔진 검증 근거 (어블레이션·랭커·벤치마크) | {n_v} |")
    mods = "\n".join(f"- [`{m}`](../../{PKG_NAME}/{m})" for m in spec["modules"])
    figs = sorted((RESULTS_DIR / key / "figures").glob("*.png"))
    datas = sorted(f for f in (RESULTS_DIR / key / "data").iterdir()
                   if f.suffix in (".csv", ".json")) \
        if (RESULTS_DIR / key / "data").is_dir() else []
    fig_rows = "\n".join(f"| [`{f.name}`](figures/{f.name}) |" for f in figs[:20])
    data_rows = "\n".join(f"| [`{f.name}`](data/{f.name}) |" for f in datas[:20])
    return f"""# {key.split('_', 1)[1]} — {spec['title']}

{spec['summary']}

{_measured(key)}

{spec.get('notes', '')}

## 폴더

| 폴더 | 내용 | 개수 |
|---|---|---|
| [`figures/`](figures/) | 제출용 그림 (PNG) | {n_fig} |
| [`data/`](data/) | 근거 표 (CSV/JSON) | {n_data} |{extra_dir}

전체 요약은 [`../index.html`](../index.html) 하나로 본다.

## 이 파트를 만드는 코드

**복사본이 아니라 원본 링크다.** 실행되는 것도 이것뿐이라 어긋날 일이 없다.

{mods}

공통 모듈은 [`../../{PKG_NAME}/`](../../{PKG_NAME}/) 에 함께 있다
(`config.py` `data.py` `pipeline.py` `viz.py` …).

## 그림

| 파일 |
|---|
{fig_rows}

## 표

| 파일 |
|---|
{data_rows}
"""


ROOT_README = """# results — AI 결과물

AI 파트의 결과를 세 갈래로 나눠 담았다.

| 폴더 | 내용 |
|---|---|
| [`01_anomaly/`](01_anomaly/) | 이상감지 · 종목별 위험도 |
| [`02_similarity/`](02_similarity/) | 차트 유사도 |
| [`03_forecast/`](03_forecast/) | 다음날 예측 · 급락 반등 |

**전체 요약은 [`index.html`](index.html) 하나면 된다.** 브라우저로 열면
세 파트의 그림과 표가 한 장에 나온다. 서버가 필요 없다.

## 다시 만들기

```bash
python cli.py results     # 전부 재생성
python cli.py package     # README 갱신
```

## 코드는 어디에

`../accessible_investor/` 한 벌뿐이다. 각 파트 README 가 그리로 링크한다.
전에는 파트마다 코드를 복사했는데, 원본과 어긋나고 중복만 늘어서 없앴다.
"""

UPLOAD_MD = """# GitHub 업로드 목록

이 저장소는 **AI 파트만** 담는다. 소리 변환·음성 안내·웹 데모는 다른 담당이
맡아 `_local/sound/` 로 뺐다(업로드 제외).

## 올리는 것

| 경로 | 내용 |
|---|---|
| `accessible_investor/` | 실행되는 코드 **원본 한 벌** |
| `results/` | 그림·표·`index.html` — 심사 근거 |
| `models/` | 학습된 모델 + **서비스 배포 자산** (9.6MB, 아래 표) |
| `tests/` | 누설 검사 · 국문 사전 검사 · **서비스 계층 검사** |
| `cli.py` `requirements.txt` | 실행 진입점 |
| `README.md` `NOTICE.md` | 설명 · 라이선스 고지 |
| **`INTEGRATION.md`** | **팀원용 연동 가이드** (API 계약) |

### `models/` 를 반드시 포함해야 하는 이유

이게 없으면 `pip install` 을 해도 서비스가 **기동조차 안 된다.**

| 파일 | 크기 | 없으면 |
|---|---|---|
| `registry.parquet` | 0.2MB | 종목을 하나도 못 찾는다 |
| `refpanel_KR/US.parquet` | 5.6MB | 테마 이웃·유사도 후보가 없다 |
| `risk_reference.json` | 0.03MB | 위험도 백분위를 못 낸다 |
| `pooled_변동성/방향.pkl` | 2.7MB | 예측이 안 나온다 |

⚠️ `refpanel_*.parquet` 만은 요약 통계가 아니라 **시세 파생 데이터**다 —
794종목의 6년치 종가가 그대로 들어 있다(FinanceDataReader · yfinance).
이 저장소가 **비상업 오픈소스 공모전 제출물**이라 포함했다(NOTICE.md 의
"개인·연구용" 범위). 상업적으로 쓰려면 빼고
`python cli.py reference --build` 로 각자 만들어야 한다.

나머지 셋은 종목 목록·분위수 격자·학습된 모델이라 그런 제약이 없다.

## 올리지 않는 것

| 경로 | 이유 |
|---|---|
| `data/` | 재생성 가능하고 크다. 출처별 재배포 조건이 다르다 |
| `_local/` | 다른 담당의 소리 모듈, 옛 노트북·산출물 |
| TabPFN 가중치 | gated 저장소 소유. 사용자가 각자 받는다 (NOTICE.md) |
| xforecast 원본 | 대회 데이터. **학습된 모델만** 배포 |

## 올리기 전 확인

```bash
python tests/test_no_lookahead.py   # 미래 누설 4/4
python tests/test_lexicon_ko.py     # 국문 사전 4/4
python tests/test_serving.py        # 서비스 계층 8/8
python cli.py serve health          # 배포 파일이 다 있는지
python cli.py results               # 결과가 최신인지
python cli.py package               # README 가 최신인지
```

누설 검사를 먼저 돌리는 이유는, 워크포워드가 **조용히 깨지기** 때문이다.
인덱싱을 한 칸 잘못 잡으면 예외는 안 나고 정확도만 올라간다 — 틀린 걸
잘한 걸로 착각하게 되는 유일한 방식이다.

비밀이 섞이지 않았는지는 `gitleaks` 나 GitHub secret scanning 으로 본다.
이 저장소의 코드는 전부 `os.getenv` 로만 키를 읽는다.

## 처음 받은 사람이 할 일

```bash
pip install -r requirements.txt
python cli.py serve health               # 배포 파일 점검
python cli.py serve predict 카카오        # 바로 써 본다
```

연구 결과를 **다시 만들어 보려면** 뉴스부터 모아야 한다.

```bash
python cli.py backfill-news --days 365   # 뉴스를 먼저 모은다
python cli.py results
```

`backfill-news` 를 건너뛰면 뉴스 열이 최근 며칠만 차서 사실상 상수가 되고,
모델이 뉴스에서 배울 것이 없어진다.
"""


def run(verbose: bool = True) -> Path:
    """파트별 README 와 업로드 목록을 갱신한다."""
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    for key, spec in PARTS.items():
        d = RESULTS_DIR / key
        for sub in ("figures", "data"):
            (d / sub).mkdir(parents=True, exist_ok=True)
        # 옛 구조의 잔재를 지운다 — code/ 복사본은 더 이상 만들지 않는다
        old_code = d / "code"
        if old_code.is_dir():
            for f in old_code.glob("*"):
                f.unlink()
            old_code.rmdir()
        n_fig = len(list((d / "figures").glob("*.png")))
        n_data = sum(1 for f in (d / "data").iterdir()
                     if f.suffix in (".csv", ".json"))
        (d / "README.md").write_text(
            _part_readme(key, spec, n_fig, n_data), encoding="utf-8")
        if verbose:
            print(f"  {key:16s} 그림 {n_fig:2d} · 표 {n_data:2d}")

    (RESULTS_DIR / "README.md").write_text(ROOT_README, encoding="utf-8")
    (RESULTS_DIR / "UPLOAD.md").write_text(UPLOAD_MD, encoding="utf-8")
    if verbose:
        print(f"\n→ {RESULTS_DIR}")
    return RESULTS_DIR
