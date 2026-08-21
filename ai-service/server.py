"""
HTTP 서버 — 자바 앱이 부르는 창구.

`INTEGRATION.md` 가 보여 주는 FastAPI 예시를 실제로 띄울 수 있게 옮긴 것이다.
라이브러리에는 손대지 않는다. 여기서 하는 일은 셋뿐이다.

    1. 기동할 때 warm() 을 부른다
    2. brief() · predict() · similar() 를 HTTP 로 감싼다
    3. health() 를 그대로 내보낸다

왜 서버인가
-----------
warm() 이 10초 걸린다. 모델과 비교군 패널을 읽고 지수 넷을 네트워크에서
받는다. 호출마다 프로세스를 새로 띄우면 그 비용을 매번 치른다. 한 번 띄워
두면 이후 호출이 167ms 다.

    프로세스를 매번 띄움   호출당 4.5초
    서버로 재사용          기동 10초, 이후 167ms

띄우는 법
---------
    pip install -r requirements.txt
    python server.py                 # 기본 127.0.0.1:8765

앱이 자식 프로세스로 띄우므로 사용자가 직접 칠 일은 없다. 손으로 확인할 때만
쓴다.

바깥에 열지 않는다
------------------
기본 주소가 127.0.0.1 이다. 인증이 없고 같은 기계의 앱만 부르면 되므로
바깥에 열 이유가 없다.
"""

from __future__ import annotations

import argparse
import logging

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from accessible_investor import serving as SV
import chat as CHAT
from news_cache import CACHE as NEWS_CACHE

LOG = logging.getLogger("ai-service")

app = FastAPI(title="OpenStock Access AI", version="1.0")


class Bar(BaseModel):
    """일봉 한 개. 앱이 증권사에서 받은 것을 그대로 넘긴다."""

    date: str
    open: float
    high: float
    low: float
    close: float
    volume: int = 0


class NewsRequest(BaseModel):
    """뉴스는 종목명으로 찾는다. 코드로는 기사가 안 잡힌다."""

    code: str
    days: int = 7


class TrackRequest(BaseModel):
    """미리 받아 둘 종목. 앱이 보유·관심 목록을 알려 준다."""

    codes: list[str] = Field(default_factory=list)


class ChatRequest(BaseModel):
    """
    질문과, 앱이 이미 화면에 띄워 둔 분석.

    앱이 근거를 함께 보내는 이유는 화면 글자와 답이 어긋나지 않게 하기 위해서다.
    서버가 다시 계산하면 그새 값이 바뀌어 사용자가 보고 있는 것과 다른 답을 듣는다.
    """

    code: str
    question: str
    narration: str = ""
    forecast: str = ""
    direction: str = ""
    direction_meaningful: bool = False
    risk: str = ""
    anomaly: str = ""


class BriefRequest(BaseModel):
    code: str
    bars: list[Bar] = Field(default_factory=list)
    with_similar: bool = False
    # 뉴스를 켜면 1~3초가 되고 거의 전부 RSS 요청이다. 화면이 기다릴 시간이 아니다.
    with_news: bool = False
    target: str = "변동성"


@app.on_event("startup")
def _warm() -> None:
    """첫 사용자가 로딩 비용을 혼자 뒤집어쓰지 않게 미리 치른다."""
    try:
        result = SV.warm()
        LOG.info("warm 완료: %s", result)
    except Exception as error:  # 기동은 막지 않는다. health 가 상태를 알린다.
        LOG.warning("warm 실패: %s", error)


@app.get("/health")
def health() -> dict:
    """
    기동 점검.

    지수는 저장소에 없고 네트워크에서 받는다. 못 받아도 예측은 답을 내지만
    시장맥락 피처 여덟 개가 조용히 결측된다. 그 상태를 숨기지 않는다.

    점검 자체가 실패해도 오류를 던지지 않는다. 점검이 500 을 내면 앱은 서버가
    죽은 것과 구별할 수 없다. 무엇이 없는지 적어 돌려준다. 실제로 의존 패키지
    하나가 빠져도 여기서 걸린다.
    """
    try:
        report = SV.health()
        report["서버"] = "정상"
        return report
    except Exception as error:
        return {
            "서버": "점검 실패",
            "전체정상": False,
            "사유": f"{type(error).__name__}: {error}",
        }


@app.post("/brief")
def brief(request: BriefRequest) -> dict:
    """
    한 종목 요약. 예측과 이상감지와 유사종목을 합친다.

    셋을 따로 부르고 앱에서 합치면 합치는 방식이 화면마다 달라진다. 부분
    실패를 전체 실패로 만들지 않는 규칙도 여기 한 곳에 둔다.
    """
    bars = _to_frame(request.bars)
    try:
        return SV.brief(
            request.code,
            bars=bars,
            with_similar=request.with_similar,
            with_news=request.with_news,
            target=request.target,
        )
    except SV.UnknownSymbol:
        raise HTTPException(404, "그런 종목이 없습니다")
    except SV.InsufficientData as error:
        raise HTTPException(422, f"자료가 부족합니다 ({error})")
    except SV.ServiceError as error:
        raise HTTPException(400, str(error))


@app.post("/predict")
def predict(request: BriefRequest) -> dict:
    """
    한 가지 예측만 받는다.

    `brief` 는 기본값인 변동성만 담는다. 방향(오를까 내릴까)까지 보여 주려면 따로 부른다.
    방향은 검증에서 우연과 구별되지 않아 응답에 `유의미: false` 가 실려 나간다. 부르는
    쪽이 그 사실을 함께 보여야 한다.
    """
    bars = _to_frame(request.bars)
    try:
        return SV.predict(
            request.code,
            bars=bars,
            target=request.target,
            with_news=request.with_news,
        )
    except SV.UnknownSymbol:
        raise HTTPException(404, "그런 종목이 없습니다")
    except SV.InsufficientData as error:
        raise HTTPException(422, f"자료가 부족합니다 ({error})")
    except SV.ServiceError as error:
        raise HTTPException(400, str(error))


@app.post("/similar")
def similar(request: BriefRequest) -> dict:
    """
    닮은 차트를 통째로 받는다.

    `brief` 도 유사종목을 담지만 종목명·코드·유사도만 추린 요약이다. 거기서 빠지는 것이
    셋 있고 셋 다 UI 가 반드시 써야 하는 값이다.

        forward     닮은 구간 다음에 상승 몇 건 · 하락 몇 건이었나
        disclaimer  이건 예측이 아니라는 서비스가 쓴 문장
        동조도      봉마다 실제로 함께 움직였나

    특히 `forward` 는 유사도 기능이 사실로 말할 수 있는 거의 전부다. 평균 수익률로
    요약하지 않는 이유는 요약하는 순간 예측처럼 읽히기 때문이다.

    `동조도` 없이 유사도만 보여 주면 오해한다. 모양이 0.98 로 닮았는데 동조가 0.21 인
    짝이 흔하다 — 다른 시기의 다른 종목이 우연히 같은 곡선을 그린 경우다.
    """
    bars = _to_frame(request.bars)
    try:
        return SV.similar(request.code, bars=bars)
    except SV.UnknownSymbol:
        raise HTTPException(404, "그런 종목이 없습니다")
    except SV.InsufficientData as error:
        raise HTTPException(422, f"자료가 부족합니다 ({error})")
    except SV.ServiceError as error:
        raise HTTPException(400, str(error))


@app.post("/news")
def news(request: NewsRequest) -> dict:
    """
    한 종목의 최근 뉴스와 감성 지수.

    기사 목록·사건 요약·지수를 함께 준다. 화면은 목록을 보여 주고 `브리핑` 을 읽어
    주기만 하면 된다. 문장을 화면이 새로 지으면 지수가 무엇을 뜻하는지가 빠진다.

    감성 지수는 **여론의 방향을 요약한 것이지 주가 예측이 아니다.** 그 사실이
    `브리핑` 마지막 줄에 들어 있다. 잘라 내면 안 된다.
    """
    from accessible_investor import news as N

    try:
        meta = SV.resolve(request.code)
    except SV.UnknownSymbol:
        raise HTTPException(404, "그런 종목이 없습니다")

    # 이 종목을 미리받기 목록에 넣는다. 사용자가 실제로 보는 종목이 곧 쌓아야 할 종목이다.
    NEWS_CACHE.track([meta["label"]])

    # 들고 있는 것이 있으면 그대로 준다. 매번 새로 받으면 화면을 열 때마다 11초다.
    result = NEWS_CACHE.digest(meta["label"])
    if not result:
        # 기사가 없는 것과 조회 실패는 다르다. 빈 목록으로 뭉개면 구별되지 않는다.
        return {"종목코드": meta["code"], "종목명": meta["label"],
                "기사": [], "사건": [], "지수": None,
                "브리핑": "관련 뉴스를 찾지 못했습니다."}

    return {
        "종목코드": meta["code"],
        "종목명": meta["label"],
        "지수": result["score"],
        "지수해석": result["label"],
        "기사수": result["n_articles"],
        "사건수": result["n_events"],
        "긍정": result["n_pos"], "중립": result["n_neu"], "부정": result["n_neg"],
        "사건": result["summaries"],
        "시황": result.get("market_line"),
        "기사": [
            {"제목": a["title"], "출처": a["source"],
             "시각": str(a["ts"]), "주소": a["url"], "감성": a["polarity"]}
            for a in result["articles"][:20]
        ],
        "브리핑": N.briefing_text(result),
    }


@app.post("/news/track")
def news_track(request: TrackRequest) -> dict:
    """
    미리 받아 둘 종목을 알려 준다.

    구글 뉴스 RSS 는 최근 7일까지만 준다. 오늘 안 받으면 그날치는 영영 없다. 앱이
    보유·관심 목록을 넘겨 주면 하루 한 번 훑어 아카이브에 쌓는다.
    """
    names = []
    unknown = []
    for code in request.codes:
        try:
            names.append(SV.resolve(code)["label"])
        except Exception:
            unknown.append(code)
    result = NEWS_CACHE.track(names)
    result["모르는종목"] = unknown
    return result


@app.get("/news/status")
def news_status() -> dict:
    """적립이 실제로 돌고 있는지. 조용히 멈추면 예측만 서서히 무뎌진다."""
    return NEWS_CACHE.status()


@app.post("/chat")
def chat(request: ChatRequest) -> dict:
    """
    근거 있는 답만 한다.

    사고팔라는 말과 미래 가격은 답하지 않는다. 우리가 가진 값으로 뒷받침되지 않고,
    법적으로도 투자자문이다. 거절할 때는 대신 무엇을 알려 줄 수 있는지 함께 말한다.
    막다른 길로 두면 사용자는 다른 곳에서 더 나쁜 답을 찾는다.
    """
    from accessible_investor import news as N

    try:
        meta = SV.resolve(request.code)
    except SV.UnknownSymbol:
        raise HTTPException(404, "그런 종목이 없습니다")

    facts = CHAT.Facts(
        name=meta["label"],
        narration=request.narration,
        forecast=request.forecast,
        direction=request.direction,
        direction_meaningful=request.direction_meaningful,
        risk=request.risk,
        anomaly=request.anomaly,
    )
    # 뉴스는 앱이 안 보낸다. 이미 받아 둔 것이 있으면 쓰고, 없으면 없는 대로 답한다.
    try:
        result = NEWS_CACHE.digest(meta["label"])
        if result:
            facts.news_score = result["score"]
            facts.news_events = list(result["summaries"])
            facts.news_brief = N.briefing_text(result)
    except Exception:
        pass

    answer = CHAT.answer(request.question, facts)
    answer["추천질문"] = CHAT.suggestions(facts)
    return answer


def _to_frame(bars: list[Bar]):
    """봉을 넘기면 네트워크 요청이 나가지 않는다. 비어 있으면 자체 조회에 맡긴다."""
    if not bars:
        return None
    import pandas as pd

    return pd.DataFrame(
        {
            "date": [b.date for b in bars],
            "open": [b.open for b in bars],
            "high": [b.high for b in bars],
            "low": [b.low for b in bars],
            "close": [b.close for b in bars],
            "volume": [b.volume for b in bars],
        }
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="OpenStock Access AI 서버")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    import uvicorn

    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
