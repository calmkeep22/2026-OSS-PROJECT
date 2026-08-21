# GitHub 업로드 목록

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
