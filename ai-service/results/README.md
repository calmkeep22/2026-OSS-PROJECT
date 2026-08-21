# results — AI 결과물

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
