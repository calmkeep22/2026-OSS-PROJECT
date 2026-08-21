# 서드파티 고지

이 프로젝트가 쓰는 외부 구성요소와 각각의 라이선스 조건이다.
**GitHub 공개·배포 전에 이 문서를 확인할 것.**

이 저장소는 **AI 파트만** 담는다. 소리 변환·음성 안내·웹 데모는 다른 담당이
맡기로 해서 제외했다(`_local/sound/`).

---

## 모델

### snunlp/KR-FinBert-SC — 국내 뉴스 감성 분석
- 용도: 한글 기사 제목 → 긍정/중립/부정
- 실행: **로컬**. API 키도 비용도 필요 없다
- 배포: 가중치를 재배포하지 않는다. 최초 실행 시 HuggingFace에서 자동 내려받는다

### ProsusAI/finbert — 미국 뉴스 감성 분석
- 라이선스: Apache 2.0
- 용도: 영문 기사 제목 → 긍정/중립/부정
- 실행: **로컬**. 키 불필요. 역시 재배포하지 않는다
- ⚠️ **한글 모델과 반드시 갈라 써야 한다.** 한글 기사에 영문 FinBERT를
  물리면 토크나이저가 모르는 서브워드로 쪼개 전부 중립으로 나온다.
  `sentiment.py` 가 시장(`KR`/`US`)에 따라 라우팅한다

### TabPFN — 표 형식 파운데이션 모델

> **Built with PriorLabs-TabPFN**

이 표시는 장식이 아니라 **라이선스가 요구하는 의무**다. README와 웹 데모에도 같은
문구가 들어 있다. 지우면 라이선스 위반이다.

- 라이선스: **Prior Labs License** = Apache 2.0 + 추가 귀속 조항 (v1.2, 2025-12)
- ⚠️ 이 프로젝트 초기 문서에는 "비상업 라이선스라 오픈소스 배포 불가"라고 적혀
  있었으나 **그 판단은 현재 버전(8.1.0)에는 맞지 않는다.** Apache 2.0 기반으로 바뀌었다
- 조건 셋:
  1. 배포물에 **라이선스 사본을 동봉**할 것
  2. 웹사이트·UI·문서에 **"Built with PriorLabs-TabPFN"** 을 눈에 띄게 표시할 것
  3. TabPFN **출력으로 다른 AI 모델을 학습·파인튜닝·증류**해서 배포하면
     그 모델 이름 **앞에 "TabPFN"** 을 붙일 것
- ⚠️ 3번 때문에 **TabPFN 예측을 우리 랭커의 학습 입력으로 쓰지 않는다.**
  추론 시에만 쓰므로 이 조항에 걸리지 않는다

**가중치는 재배포하지 않는다.** gated 저장소(`Prior-Labs/tabpfn_3`)에 있고,
사용자가 각자 아래 절차로 받는다.

```bash
huggingface-cli login          # 1. HF 토큰 (gated repo 접근용)
python -c "from tabpfn import TabPFNRegressor; TabPFNRegressor()"
```

1. https://huggingface.co/Prior-Labs/tabpfn_3 에서 **Agree and access repository**
2. https://ux.priorlabs.ai/account/licenses 에서 라이선스 동의
3. 위 명령 실행 → `%APPDATA%\tabpfn\` (Windows) 에 캐시된다

⚠️ **가중치를 제3자 저장소·버킷에 재호스팅하고 그 경로를 안내하지 않는다.**
gated 저장소는 접근을 통제하려고 게이트를 둔 것이고, 우회 경로를 남에게
안내하면 그 통제를 무력화한다. 이 문서와 README는 공식 경로만 링크한다.

### 사용하지 않기로 한 것
- TabPFN v2.5 / v2.6 계열 — 당시 비상업 라이선스

---

## 데이터

| 출처 | 용도 | 조건 |
|---|---|---|
| FinanceDataReader | 국내 종목 목록·일봉 | MIT |
| yfinance | 국내 분봉, **미국 일봉** | Apache 2.0. **Yahoo 데이터는 개인·연구용**. 상업 재배포 금지 |
| pykrx | 일봉 폴백 | MIT |
| 구글 뉴스 RSS | 뉴스 수집 (KR·US 로케일, `after:`/`before:` 소급 포함) | 키 불필요. 제목·출처·링크만 저장하고 **본문은 저장하지 않는다** |
| DART OpenAPI | 공시 (선택) | 키 필요. 없어도 전부 동작한다 |
| xforecast 아카이브 | 미국 모델 학습·검증 | 대회 데이터. **연구·교육 사용 및 학습된 모델 배포 허용** 확인함 |

⚠️ **수집한 데이터(`data/`)는 저장소에 커밋하지 않는다.** 재생성 가능하고,
출처별 재배포 조건이 서로 다르다.

### xforecast 아카이브 — 해외(미국) 모델용

- 내용: 미국 100종목 × 2019~2022 일봉 + LLM 요약 뉴스 47만 건 (`text.parquet`)
- 크기: 임베딩 포함 65GB. **이 프로젝트는 임베딩을 쓰지 않는다** —
  사전 기반 극성(`lexicon.py`)으로 충분하고, 65GB를 요구하면 배포가 불가능하다
- 사용 범위: 연구·교육 목적 사용과 **학습된 모델의 배포**가 허용됨을 확인했다
- ⚠️ **원본 데이터는 저장소에 넣지 않는다.** 위치는 `XFORECAST_DIR` 환경변수로 준다
- 재현: `python cli.py overseas --ablate --rebuild`

이 데이터로 만든 모델의 결론은 [`results/index.html`](results/index.html) 에 있다 —
**다음날 방향 예측은 되지 않았다.** 불리한 결과지만 그대로 싣는다.

이 아카이브는 두 군데에 쓰인다.
1. 교차언어 전이 모델(`newsxfer.py`) **학습**
2. 겹치는 미국 종목(NVDA·TSLA·MU·BAC)의 **과거 뉴스 피처** — 우리 학습
   구간(2020~2026)과 2년 넘게 겹친다

---

## 주요 파이썬 패키지

Apache 2.0 / BSD / MIT 계열이며 재배포에 제약이 없다.

`numpy` `pandas` `scipy` `scikit-learn` `matplotlib` `pyarrow`
`torch` `transformers` `onnx` `onnxruntime`
`stumpy` `pyod` `ruptures` `statsmodels` `dtaidistance` `requests`

---

## API 키

**이 저장소에는 어떤 키도 들어 있지 않다.** 코드는 전부 환경변수로만 읽는다.

```
DART_API_KEY          선택. 공시 수집
ANTHROPIC_API_KEY     선택. 뉴스 사건 요약(LLM). 없으면 추출 요약으로 폴백
TABPFN_TOKEN          선택. TabPFN 클라우드 API
```

키를 코드·노트북·커밋 메시지에 절대 넣지 말 것.
실수로 넣었다면 **파일에서 지우는 것으로 끝나지 않는다** — 즉시 폐기하고 재발급할 것.
git 히스토리에 남으면 공개 즉시 스캐너가 수집한다.
