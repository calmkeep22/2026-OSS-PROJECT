# 연구 코드와 앱 실행 코드의 경계

데스크톱 앱은 `server.py`에서 제공하는 국내주식 API만 사용한다. 앱 배포물에는
이 서버가 실제로 import하는 모듈과 저장된 모델만 포함한다.

다음 모듈은 모델 비교, 재학습, 결과 보고서 생성에 쓰는 연구 도구다. 데스크톱
PyInstaller 번들에서는 명시적으로 제외한다.

- `cli`, `training`, `tabpfn_bench`
- `news_judge`, `news_predict`
- `pairs`, `reversion`, `segments`
- `report`, `report_ai`, `results`, `viz`

연구 도구를 실행할 때만 다음 명령으로 추가 의존성을 설치한다.

```powershell
python -m pip install -r requirements-dev.txt
```

과거 미국시장 데이터와 실험은 저장된 모델의 재현 근거로만 남아 있다. 현재 앱의
종목 레지스트리와 HTTP 서비스에서는 미국 종목을 조회하거나 노출하지 않는다.
