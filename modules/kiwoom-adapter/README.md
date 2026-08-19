# kiwoom-adapter

Kiwoom REST and WebSocket adapter.

- Supported entry points: `KiwoomRestClient`, `KiwoomMarketDataStream`, `KiwoomProperties`, `KiwoomTr`
- Depends on: `broker-api`, `application`, `finance-domain`
- HTTP/WebSocket sessions, protocol parsing, and reconnect scheduling are internal details.

## REST 호출 방식

키움은 기능마다 경로를 나누지 않는다. 경로는 업무 카테고리 하나이고, 실제로 어떤 조회인지는
`api-id` 헤더의 TR 코드가 정한다. 모든 TR은 POST 이며 요청과 응답 모두 JSON 이다.

```text
POST /api/dostk/chart    api-id: ka10081    {"stk_cd":"005930","base_dt":"20260819","upd_stkpc_tp":"1"}
POST /api/dostk/chart    api-id: ka10080    {"stk_cd":"005930","tic_scope":"5","upd_stkpc_tp":"1"}
POST /api/dostk/ordr     api-id: kt10000    (매수)
POST /api/dostk/ordr     api-id: kt10001    (매도)
```

사용 중인 TR 목록은 `KiwoomTr` 에 정의되어 있다.

| 구분 | TR | 용도 |
|---|---|---|
| 인증 | `au10001` | 접근토큰 발급 |
| 시세 | `ka10001` | 주식기본정보요청 |
| 차트 | `ka10080` / `ka10081` / `ka10082` / `ka10083` | 분/일/주/월봉 |
| 계좌 | `kt00001` / `kt00018` | 예수금 / 평가잔고 |
| 주문 | `kt00009` | 주문체결현황 |
| 주문 | `kt10000` / `kt10001` / `kt10003` | 매수 / 매도 / 취소 |

## 도메인

```text
운영     https://api.kiwoom.com
모의투자 https://mockapi.kiwoom.com
```

`KiwoomProperties.mockTrading(...)` 과 `KiwoomProperties.liveTrading(...)` 으로 만든다. 기본은
모의투자다. 실거래 주문은 `-Dossproject.trading.live=true` 실행 인자가 있어야 전송된다.

## 응답 해석에서 주의할 점

키움 값 표기에는 세 가지 특징이 있어 그대로 파싱하면 틀린다. `KiwoomJsonMapper` 가 처리한다.

- 숫자에 부호가 붙는다. `"+70700"`, `"-600"`
- 계좌 응답은 좌측을 0으로 채운다. `"000000017598258"`
- 계좌 응답의 종목코드에는 접두어가 붙는다. `"A005930"` (A:주식, J:ELW, Q:ETN)

**업무 오류는 HTTP 상태가 아니라 본문의 `return_code` 로 온다.** HTTP 200 이라도 `return_code`
가 0이 아니면 실패다. 모든 조회는 `KiwoomErrorMapper.requireSuccessBody` 를 거친다.

차트 응답은 최신 봉을 먼저 준다. 도메인 계약은 오름차순이므로 매퍼가 뒤집는다. 봉의 종가는
`cur_prc` 다.

주문 상태 코드는 따로 오지 않는다. 주문수량(`ord_qty`)과 체결수량(`cntr_qty`), 정정·취소구분
(`mdfy_cncl_tp`) 으로 상태를 판단한다.

## 아직 확인하지 않은 것

WebSocket 실시간 규격은 REST 와 필드 이름이 다르다. 확인 전까지 `JsonStreamProtocol` 과
`KiwoomFieldMap` 은 자리표시자를 사용한다. 실시간 시세를 실제로 쓰기 전에 공식 문서로
교체해야 한다.

Test: `./gradlew :modules:kiwoom-adapter:test`
