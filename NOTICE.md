# 서드파티 고지

이 프로젝트가 쓰는 외부 구성요소와 각각의 라이선스입니다. 이 저장소의 코드는
[`LICENSE`](LICENSE)(MIT)를 따르고, 아래 구성요소는 각자의 조건을 따릅니다.

각 패키지에 포함된 원문 라이선스와 저작권 고지가 이 문서보다 우선합니다.

## 먼저 볼 것 — 조건이 붙은 넷

대부분은 MIT·Apache-2.0·BSD 계열이라 재배포에 제약이 없습니다. 아래 넷만 다릅니다.

**OpenJDK 런타임 · OpenJFX** — `GPL-2.0 with Classpath Exception`
GPL 이지만 **Classpath Exception** 이 붙어 있어, 이것과 링크한다는 이유로 우리 코드를
공개할 의무는 생기지 않습니다. 예외 조항을 빼고 "GPL" 로만 적으면 오해를 삽니다.

**PyInstaller 부트로더** — `GPL-2.0-or-later with Bootloader Exception`
배포물에 들어가는 것은 부트로더뿐이고, 여기에도 예외 조항이 있어 우리 앱을 GPL 로
만들지 않습니다.

**Pretendard 글꼴** — `SIL OFL 1.1`
글꼴 파일 두 벌(`Pretendard-Regular.otf`, `Pretendard-Bold.otf`)을 저장소에 동봉하고
앱에서 직접 읽습니다. OFL 은 번들과 재배포를 허용하지만 **글꼴을 단독 상품으로 판매하는
것은 금지**하고, **저작권 고지를 함께 배포**할 것을 요구합니다. 글꼴 파일을 고쳐 쓸
경우에는 이름에 "Pretendard" 를 쓸 수 없습니다. 지금은 원본을 그대로 씁니다.
저작권: Copyright (c) 2021 Kil Hyung-jin, with Reserved Font Name Pretendard.

**yfinance** — 소프트웨어는 `Apache-2.0`, **데이터는 별개**
Yahoo 금융 데이터의 이용 조건은 라이브러리 라이선스와 다릅니다. 개인·연구 목적이며
상업적 재배포는 허용되지 않습니다. 앱은 키움 봉 데이터를 우선 쓰고 yfinance 는 국내
시세 폴백 경로에만 씁니다.

## 데스크톱 앱 (Java)

| 구성요소 | 버전 | 라이선스 | 저장소 | 쓰는 곳 |
|---|---|---|---|---|
| OpenJDK 런타임 | 17 | GPL-2.0 with Classpath Exception | https://github.com/openjdk/jdk | 실행 환경 (jpackage 로 동봉) |
| OpenJFX | 17.0.12 | GPL-2.0 with Classpath Exception | https://github.com/openjdk/jfx | 데스크톱 화면 |
| Jackson Databind | 2.18.2 | Apache-2.0 | https://github.com/FasterXML/jackson-databind | AI·음성 서비스 JSON |
| JNA (jna-platform) | 5.15.0 | Apache-2.0 또는 LGPL-2.1-or-later | https://github.com/java-native-access/jna | Windows DPAPI 비밀 저장 |
| Xerial SQLite JDBC | 3.47.1.0 | Apache-2.0 (번들 SQLite 는 Public Domain) | https://github.com/xerial/sqlite-jdbc | 주문·이상 감지 이력 |
| Pretendard | 1.3.9 | SIL OFL 1.1 | https://github.com/orioncactus/pretendard | 화면 글꼴 (Regular·Bold 를 저장소에 동봉) |

검사에만 쓰는 것 (배포물에 들어가지 않음)

| 구성요소 | 버전 | 라이선스 | 저장소 |
|---|---|---|---|
| JUnit Jupiter | 5.11.4 | EPL-2.0 | https://github.com/junit-team/junit5 |
| TestFX Monocle | 17.0.10 | GPL-2.0 with Classpath Exception | https://github.com/TestFX/Monocle |

## AI·음성 서비스 (Python)

`ai-service/requirements.txt` 가 배포에 필요한 전부입니다. 버전은 선언한 하한과
개발 환경에서 실제로 쓴 값을 함께 적습니다.

| 구성요소 | 선언 / 실사용 | 라이선스 | 저장소 | 쓰는 곳 |
|---|---|---|---|---|
| NumPy | ≥2.0 / 2.2.6 | BSD-3-Clause | https://github.com/numpy/numpy | 수치 계산 |
| pandas | ≥2.2 / 2.3.3 | BSD-3-Clause | https://github.com/pandas-dev/pandas | 봉 데이터 처리 |
| SciPy | ≥1.14 / 1.15.2 | BSD-3-Clause | https://github.com/scipy/scipy | 통계 검정 |
| scikit-learn | ≥1.5 / 1.9.0 | BSD-3-Clause | https://github.com/scikit-learn/scikit-learn | 예측·랭킹 모델 학습과 적재 |
| joblib | ≥1.4 / 1.5.0 | BSD-3-Clause | https://github.com/joblib/joblib | `models/*.pkl` 직렬화 |
| PyArrow | ≥17.0 / 25.0.1 | Apache-2.0 | https://github.com/apache/arrow | `models/*.parquet` |
| FinanceDataReader | ≥0.9.96 | MIT | https://github.com/FinanceData/FinanceDataReader | 종목 목록·일봉 |
| yfinance | ≥1.5 / 1.6.0 | Apache-2.0 (데이터 조건은 위 참고) | https://github.com/ranaroussi/yfinance | 시세 폴백 |
| Requests | ≥2.32 / 2.34.2 | Apache-2.0 | https://github.com/psf/requests | 뉴스 RSS 수집 |
| FastAPI | ≥0.115 / 0.115.12 | MIT | https://github.com/fastapi/fastapi | 앱이 부르는 HTTP 창구 |
| Uvicorn | ≥0.32 / 0.34.3 | BSD-3-Clause | https://github.com/encode/uvicorn | 위 서버 구동 |
| faster-whisper | ≥1.2 / 1.2.1 | MIT | https://github.com/SYSTRAN/faster-whisper | 음성 인식 |
| CTranslate2 | 4.8.1 (전이 의존) | MIT | https://github.com/OpenNMT/CTranslate2 | 위 모델 추론 |
| PyAV | 18.1.0 (전이 의존) | BSD-3-Clause | https://github.com/PyAV-Org/PyAV | 음성 디코딩 |
| PyInstaller | 배포 도구 | GPL-2.0-or-later with Bootloader Exception | https://github.com/pyinstaller/pyinstaller | 서비스 EXE 패키징 |
| pytest | 검사 전용 | MIT | https://github.com/pytest-dev/pytest | 검사 |

재학습·재측정에만 쓰는 것은 `ai-service/requirements-dev.txt` 에 있고 배포물에
들어가지 않습니다. 무엇을 왜 뺐는지는 [`ai-service/RESEARCH.md`](ai-service/RESEARCH.md)
에 있습니다.

## 음성 인식 모델

`faster-whisper` 가 쓰는 가중치는 **소스 저장소에 넣지 않습니다.** 공식 배포 빌드가
HuggingFace의 고정 리비전을 받아 배포본에 포함합니다. 따라서 배포본 사용자의 첫 음성
인식에서도 네트워크 다운로드가 일어나지 않습니다. 모델 카드와 MIT 라이선스 사본도
배포물의 `app/ai-service/legal/`에 함께 둡니다.

| 모델 | 라이선스 | 출처 |
|---|---|---|
| `Systran/faster-whisper-base` (`ebe41f70d5b6dfa9166e2c581c45c9c0cfc57b66`) | MIT | https://huggingface.co/Systran/faster-whisper-base |

원본 Whisper(OpenAI)도 MIT 입니다. 모델 파일이 손상되거나 빠져도 앱은 그대로 돌아가고
음성 기능만 명시적으로 꺼집니다. 배포본에서 조용히 인터넷 다운로드로 우회하지 않습니다.

## AI 분석 모델과 학습 데이터

감성 분석 모델, TabPFN 의 귀속 표시 의무, 학습 데이터의 재배포 조건은 양이 많아
따로 두었습니다.

→ [`ai-service/NOTICE.md`](ai-service/NOTICE.md)

특히 **TabPFN 은 "Built with PriorLabs-TabPFN" 표시가 라이선스 의무**입니다. 지우면
위반입니다.

## 확인해야 할 것

NumPy·SciPy·PyArrow 같은 바이너리 휠은 OpenBLAS, LAPACK, 컴파일러 런타임 같은 추가
구성요소를 포함할 수 있습니다. **실제 배포 전에 생성된 패키지 안의 라이선스 파일을
함께 보존하고, 최종 산출물 기준으로 다시 점검해야 합니다.**

이 문서의 버전은 `build.gradle.kts` 와 `ai-service/requirements.txt` 에서 뽑은 것입니다.
의존성을 바꾸면 여기도 함께 고칩니다.

## API 키

**이 저장소에는 어떤 키도 들어 있지 않습니다.** 키움 자격증명은 환경변수 또는 연결
화면을 통해서만 받고, Windows DPAPI 로 보호해 저장합니다. 평문으로 SQLite 나 설정
파일에 쓰지 않습니다.
