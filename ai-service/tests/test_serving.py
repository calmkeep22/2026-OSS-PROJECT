"""
서비스 계층 검사 — **"아무 종목이나 된다"를 말이 아니라 코드로 확인한다.**

이 저장소가 팀원에게 넘어가면 백엔드가 붙는다. 그쪽에서 처음 마주칠 실패는
정확도가 아니라 **입력 처리**다. 키움이 주는 열 이름이 다르거나, 코스닥
우량 종목이 목록에 없거나, 신규 상장이 예외로 죽거나 하는 것들이다.
전부 조용히 틀리거나 요란하게 죽어서 신뢰를 먼저 잃는 종류다.

여기서 막는 것
--------------
    1. 네 지수 전 종목이 코드로 찾아지는가        (KOSDAQ GLOBAL 회귀 포함)
    2. 키움이 줄 법한 봉 형식을 다 받아들이는가
    3. 넘긴 봉을 실제로 쓰는가 (캐시로 조용히 덮지 않는가)
    4. 자료가 짧은 종목이 거절이 아니라 저신뢰로 응답하는가
    5. 유사도 결과에 **자기 자신**이 섞이지 않는가
    6. 피처 순서가 저장된 순서와 일치하는가
    7. 장 마감 뒤에 캐시가 만료돼 두 함수가 같은 날을 말하는가
    8. `brief` 가 부분 실패를 전체 실패로 만들지 않는가
    9. 이중 클래스 주식(BRKB·BFB)의 티커 표기 차이를 넘기는가
   10. 조회 실패가 잡을 수 있는 예외로 떨어지는가

실행
----
    python -m pytest tests/test_serving.py -v
    python tests/test_serving.py          # pytest 없이도 돈다
"""

from __future__ import annotations

import sys
import time
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
warnings.filterwarnings("ignore")

from accessible_investor import pooled as PL          # noqa: E402
from accessible_investor import registry as REG       # noqa: E402
from accessible_investor import serving as SV         # noqa: E402


def _synthetic_bars(n: int = 400, seed: int = 0,
                    start: str = "2024-01-01") -> pd.DataFrame:
    """
    검사용 일봉. **실제 시세를 쓰지 않는다** — 네트워크와 그날의 시장 상황에
    따라 통과 여부가 달라지면 검사가 아니라 점괘가 된다.
    """
    rng = np.random.default_rng(seed)
    ret = rng.normal(0.0004, 0.018, n)
    close = 50_000 * np.exp(np.cumsum(ret))
    idx = pd.bdate_range(start, periods=n)
    high = close * (1 + np.abs(rng.normal(0, 0.006, n)))
    low = close * (1 - np.abs(rng.normal(0, 0.006, n)))
    return pd.DataFrame(
        {"open": close * (1 + rng.normal(0, 0.004, n)),
         "high": np.maximum(high, close), "low": np.minimum(low, close),
         "close": close,
         "volume": rng.integers(1e5, 5e6, n).astype(float)}, index=idx)


# ==========================================================================
def test_registry_covers_four_indices():
    """네 지수가 모두 들어 있고, 대표 종목이 코드로 찾아진다."""
    tb = REG.table()
    idx = set(tb["index"].unique())
    assert {"KOSPI", "KOSDAQ", "NASDAQ", "S&P500"} <= idx, idx
    assert len(tb) > 5000, f"종목이 너무 적다 ({len(tb)})"

    for q, want in [("005930", "KOSPI"), ("035720", "KOSPI"),
                    ("NVDA", "S&P500")]:
        e = REG.resolve(q)
        assert e["index"] == want, (q, e["index"])
        assert e["market"] in ("KR", "US")
    print(f"  [1] 레지스트리 {len(tb):,}종목 · 네 지수 모두 존재")


def test_kosdaq_global_not_dropped():
    """
    ⚠️ 회귀 검사 — **KOSDAQ GLOBAL 50종목이 통째로 빠졌던 버그.**

    FDR 의 시장 값이 KOSPI / KOSDAQ / KONEX / **KOSDAQ GLOBAL** 넷이라,
    앞의 둘만 남기면 코스닥 우량 세그먼트가 전부 사라진다. 하필 알테오젠·
    에코프로처럼 관심종목에 가장 많이 담길 종목들이라 치명적이었다.
    """
    for code, name in [("196170", "알테오젠"), ("086520", "에코프로"),
                       ("247540", "에코프로비엠")]:
        e = REG.resolve(code)
        assert e["index"] == "KOSDAQ", (code, e["index"])
        assert name in e["label"], (code, e["label"])
    print("  [2] KOSDAQ GLOBAL 종목 정상 조회 (알테오젠·에코프로·에코프로비엠)")


def test_bars_normalization_is_liberal():
    """키움이 줄 법한 형식을 다 받아들인다."""
    base = _synthetic_bars(120)

    # ① 한글 열 이름 + 문자열 날짜 열 + 역순 정렬
    ko = pd.DataFrame({
        "일자": [d.strftime("%Y%m%d") for d in base.index],
        "시가": base["open"].to_numpy(), "고가": base["high"].to_numpy(),
        "저가": base["low"].to_numpy(), "종가": base["close"].to_numpy(),
        "거래량": base["volume"].to_numpy(),
    }).iloc[::-1]
    got = SV._normalize_bars(ko, "TEST")
    assert list(got.columns) == SV.OHLCV
    assert got.index.is_monotonic_increasing, "정렬이 안 됐다"
    assert len(got) == len(base)

    # ② 대문자 열 + 타임존 붙은 인덱스
    up = base.rename(columns=str.capitalize)
    up.index = pd.DatetimeIndex(up.index).tz_localize("Asia/Seoul")
    got2 = SV._normalize_bars(up, "TEST")
    assert got2.index.tz is None, "타임존이 안 떨어졌다"

    # ③ 거래량 없음 → 0 으로 채운다
    nov = base.drop(columns=["volume"])
    got3 = SV._normalize_bars(nov, "TEST")
    assert (got3["volume"] == 0).all()

    # ④ 중복 날짜는 마지막 것만
    dup = pd.concat([base, base.tail(3)])
    got4 = SV._normalize_bars(dup, "TEST")
    assert not got4.index.duplicated().any()
    print("  [3] 봉 정규화 — 한글열·문자열날짜·역순·타임존·무거래량·중복 모두 통과")


def test_supplied_bars_are_actually_used():
    """
    ⚠️ 넘긴 봉을 캐시가 덮지 않는지.

    `bars` 를 넘겼는데 캐시된 패널이 돌아오면 **오류 없이 틀린 답**이 나온다.
    가장 발견이 늦는 종류의 버그라 기계로 못 박아 둔다.
    """
    bars = _synthetic_bars(400, seed=7)
    df = SV.panel("005930", bars=bars, with_news=False)
    assert len(df) == len(bars), (len(df), len(bars))
    assert abs(float(df["close"].iloc[-1])
               - float(bars["close"].iloc[-1])) < 1e-6, "종가가 다르다"
    assert pd.Timestamp(df.index[-1]) == bars.index[-1], "마지막 날짜가 다르다"
    print("  [4] 넘긴 봉이 캐시에 덮이지 않는다")


def test_short_history_degrades_not_fails():
    """
    상장한 지 얼마 안 된 종목은 **거절이 아니라 저신뢰 응답**이어야 한다.

    종목별 재학습 구조에서는 250행 미만이면 답이 없었다. 파운데이션 모델은
    그 종목의 과거로 학습하지 않으므로, 오늘의 피처만 계산되면 답이 나온다.
    """
    if not PL.model_path("변동성").is_file():
        print("  [5] 건너뜀 — 파운데이션 모델이 아직 없음")
        return

    tiers = {}
    for n, want in [(400, "높음"), (150, "보통"), (80, "낮음"), (45, "매우낮음")]:
        r = SV.predict("005930", bars=_synthetic_bars(n, seed=n),
                       with_news=False)
        tiers[n] = r["신뢰도"]
        assert r["신뢰도"] == want, (n, r["신뢰도"], want)
        assert 0.0 <= r["확률"] <= 1.0
        assert r["예측"] in ("크게움직임", "잔잔함")

    # 자료가 짧을수록 대체값으로 메운 피처가 많아야 한다
    a = SV.predict("005930", bars=_synthetic_bars(400, seed=1),
                   with_news=False)["결측피처"]
    b = SV.predict("005930", bars=_synthetic_bars(45, seed=1),
                   with_news=False)["결측피처"]
    assert b > a, f"짧은 자료인데 결측이 늘지 않았다 ({a} → {b})"

    # 30봉 미만은 거절
    try:
        SV.predict("005930", bars=_synthetic_bars(20), with_news=False)
        raise AssertionError("20봉인데 예외가 안 났다")
    except SV.InsufficientData:
        pass
    print(f"  [5] 짧은 자료 → 저신뢰 응답 {tiers} · 결측 {a}→{b} · 30봉 미만 거절")


def test_similar_excludes_itself():
    """
    ⚠️ 회귀 검사 — **질의 종목이 자기 결과에 나오던 버그.**

    질의를 `__query__005930` 같은 별도 키로 넣었더니 검색의 제외 처리가
    그 키만 지우고 풀 안의 진짜 005930 은 그대로 남았다. 삼성전자의 최근
    구간이 삼성전자 자신의 겹치는 구간과 매칭돼 1위가 자기 자신이 됐다.
    """
    from accessible_investor import reference as RF

    if not RF.has_panel("KR"):
        print("  [6] 건너뜀 — 참조 패널이 아직 없음")
        return
    code = "005930"
    res = SV.similar(code, bars=_synthetic_bars(300, seed=3),
                     window=120, top_k=5, forward=20)
    hits = [r["code"] for r in res["results"]]
    assert code not in hits, f"자기 자신이 결과에 있다: {hits}"
    assert len(hits) == len(set(hits)), f"같은 종목이 중복: {hits}"
    assert res["후보종목수"] > 100, res["후보종목수"]
    print(f"  [6] 유사도 자기 제외 확인 · 후보 {res['후보종목수']}종목 · "
          f"1위 {res['results'][0]['name'][:14]}")


def test_feature_order_is_pinned():
    """
    피처 순서가 어긋나면 **예외 없이 확률만 틀린다.**

    저장된 `cols` 순서대로 벡터를 만드는지, 열을 섞어 넣어도 같은 값이
    나오는지 확인한다.
    """
    if not PL.model_path("변동성").is_file():
        print("  [7] 건너뜀 — 파운데이션 모델이 아직 없음")
        return
    mdl = PL.load("변동성")
    assert mdl["cols"] == list(PL.FEATURES), "저장된 피처 목록이 코드와 다르다"
    assert len(mdl["medians"]) == len(mdl["cols"])

    df = SV.panel("005930", bars=_synthetic_bars(400, seed=11),
                  with_news=False)
    row = df.iloc[[-1]]
    x1, m1 = PL.vectorize(row, mdl)
    # 열 순서를 뒤집어도 결과가 같아야 한다
    x2, m2 = PL.vectorize(row[row.columns[::-1]], mdl)
    assert np.allclose(x1, x2), "열 순서에 따라 벡터가 달라진다"
    assert m1 == m2
    assert x1.shape == (1, len(PL.FEATURES))
    print(f"  [7] 피처 {len(mdl['cols'])}개 순서 고정 확인 (열을 섞어도 동일)")


def test_cache_expires_at_market_close():
    """
    ⚠️ 회귀 검사 — **장 마감 직후 캐시가 살아남던 버그.**

    캐시 만료를 나이(12시간)로만 봤더니, 오후 2시에 만든 캐시가 3시 50분
    (마감 확정) 이후에도 "두 시간밖에 안 됐으니 신선"으로 통과했다. 결과는
    `predict` 가 어제 종가를, `anomaly` 가 오늘 종가를 말하는 상태였다 —
    같은 화면의 두 패널이 다른 날을 가리킨다.
    """
    import os
    import tempfile

    from accessible_investor import news as N

    now = time.time()
    # ⚠️ 만료 규칙은 **둘**이다 — 장 마감과 뉴스 아카이브 갱신.
    # 아카이브 규칙을 빼놓고 재면 검사가 엉뚱하게 실패한다. 실제로 그랬다:
    # 미국 확정 시각(한국 새벽)보다 아카이브가 나중에 갱신돼 있어서
    # "마감 후 캐시"가 정당하게 만료됐는데 검사는 버그로 신고했다.
    # 여기서 보려는 건 **마감 규칙**이므로 아카이브 쪽은 고정해 둔다.
    arch = (N.NEWS_ARCHIVE.stat().st_mtime
            if N.NEWS_ARCHIVE.is_file() else 0.0)

    for market in ("KR", "US"):
        settle = SV._last_settle_epoch(market)
        assert settle <= now, f"{market} 확정 시각이 미래다"
        assert now - settle < 3 * 86400, f"{market} 확정 시각이 너무 오래됐다"

        with tempfile.NamedTemporaryFile(suffix=".parquet",
                                         delete=False) as fh:
            p = Path(fh.name)
        try:
            # 확정 시각 **직전**에 만든 캐시 → 낡음
            os.utime(p, (settle - 60, settle - 60))
            assert not SV._cache_fresh(p, market), \
                f"{market}: 마감 전 캐시가 신선으로 통과했다"
            # 확정 시각과 아카이브 갱신 **둘 다 지난 뒤** → 신선
            fresh = max(settle, arch) + 60
            if fresh <= now:
                os.utime(p, (fresh, fresh))
                assert SV._cache_fresh(p, market), \
                    f"{market}: 마감·아카이브 이후 캐시가 낡음으로 버려졌다"
        finally:
            p.unlink(missing_ok=True)

    # 실제 응답에서 두 함수의 기준일이 같아야 한다
    for q in ("005930", "NVDA"):
        a = SV.predict(q, with_news=False)["기준일"]
        b = SV.anomaly(q)["기준일"]
        assert a == b, f"{q}: predict {a} vs anomaly {b}"
    print("  [8] 캐시가 장 마감에 맞춰 만료 · predict/anomaly 기준일 일치")


def test_brief_survives_partial_failure():
    """
    `brief` 는 **부분 실패를 전체 실패로 만들지 않아야** 한다.

    관심종목 화면에서 유사도 하나가 안 된다고 예측·이상감지까지 사라지면,
    사용자는 아무 이유도 모른 채 빈 줄을 보게 된다. 실패한 것만 `오류` 에
    적고 나머지는 그대로 돌려주는 게 맞다.
    """
    if not PL.model_path("변동성").is_file():
        print("  [9] 건너뜀 — 파운데이션 모델이 아직 없음")
        return
    bars = _synthetic_bars(400, seed=21)

    r = SV.brief("005930", bars=bars, with_news=False, with_similar=False)
    assert r["예측"] is not None and r["이상감지"] is not None
    assert r["문안"] and r["종목명"]
    # 이름을 두 번 말하지 않는다 (이상감지 문장 + 예측 문장을 이을 때)
    assert r["문안"].count(r["종목명"]) == 1, r["문안"]

    # 유사도만 강제로 깨뜨려도 나머지는 살아 있어야 한다
    from accessible_investor import reference as RF
    orig, RF.panel_path = RF.panel_path, lambda m: Path("__none__.parquet")
    RF._PANEL.clear()
    SV._SIM_POOL.clear()
    SV._SIM_INDEX.clear()
    try:
        r2 = SV.brief("005930", bars=bars, with_news=False, with_similar=True)
        assert r2["예측"] is not None, "유사도 실패가 예측까지 죽였다"
        assert r2["이상감지"] is not None
        assert "유사도" in r2["오류"], r2["오류"]
    finally:
        RF.panel_path = orig
        RF._PANEL.clear()
        SV._SIM_POOL.clear()
        SV._SIM_INDEX.clear()
    print("  [9] brief 합산 · 이름 중복 없음 · 부분 실패 격리 확인")


def test_class_share_tickers_resolve():
    """
    ⚠️ 회귀 검사 — **이중 클래스 주식의 티커 표기 차이.**

    FinanceDataReader 의 S&P500 목록은 버크셔 B 를 `BRKB`, 브라운포맨 B 를
    `BFB` 로 주는데 야후는 `BRK-B` · `BF-B` 다. 그대로 물으면 빈 응답이 와서
    세 기능이 전부 죽었다(스트레스 검사에서 RuntimeError 6건). 버크셔는
    관심종목에 확실히 담길 종목이라 그냥 둘 수 없다.
    """
    for code in ("BRKB", "BFB"):
        e = REG.resolve(code)
        cand = REG.yahoo_candidates(code, e["index"])
        assert cand[0] == code, cand
        assert f"{code[:-1]}-{code[-1]}" in cand, cand
    # 그대로 통하는 티커는 첫 후보에서 끝나야 한다 (추가 요청 없음)
    for code in ("AAPL", "GOOGL", "NVDA"):
        e = REG.resolve(code)
        assert REG.yahoo_candidates(code, e["index"])[0] == code
    # 국내는 접미사만 붙고 후보가 하나다
    assert REG.yahoo_candidates("005930", "KOSPI") == ["005930.KS"]
    assert REG.yahoo_candidates("196170", "KOSDAQ") == ["196170.KQ"]
    print("  [10] 클래스주 티커 후보 (BRKB→BRK-B · BFB→BF-B) 확인")


def test_fetch_failure_is_typed():
    """
    시세를 못 받으면 **`ServiceError` 하위**로 떨어져야 한다.

    예전엔 `RuntimeError` 가 그대로 올라와, 백엔드가 `except ServiceError`
    로 감싸 놓으면 안 잡히고 500 이 됐다. "그 종목 시세를 못 받았습니다"는
    서버 장애가 아니라 정상적인 실패다.
    """
    assert issubclass(SV.FetchFailed, SV.ServiceError)
    assert issubclass(SV.InsufficientData, SV.ServiceError)
    assert issubclass(SV.ReferenceMissing, SV.ServiceError)
    print("  [11] 조회 실패가 잡을 수 있는 예외 (FetchFailed ⊂ ServiceError)")


def test_unknown_symbol_raises():
    try:
        SV.predict("ZZZZ9999", with_news=False)
        raise AssertionError("없는 종목인데 예외가 안 났다")
    except REG.UnknownSymbol:
        pass
    print("  [12] 없는 종목 → UnknownSymbol")


def test_health_always_reports_verdict():
    """
    `check_index=False` 는 네트워크를 건드리지 않는 빠른 기동 점검이다.
    예전에는 이때 `전체정상` 자체가 빠져서, 백엔드가 `.get("전체정상")` 으로
    받으면 None → 거짓으로 읽혀 **멀쩡한 기동을 실패로 봤다.**
    """
    for check in (False, True):
        h = SV.health(check_index=check)
        assert "전체정상" in h, f"check_index={check} 인데 판정이 없다"
        assert isinstance(h["전체정상"], bool), \
            f"check_index={check}: {type(h['전체정상'])} 가 왔다"

    빠름 = SV.health(check_index=False)
    assert 빠름["전체정상"] is True, f"배포 파일이 모자란다: {빠름}"
    assert "지수" not in 빠름, "네트워크를 건드리지 않기로 했는데 지수를 봤다"


TESTS = [test_registry_covers_four_indices, test_kosdaq_global_not_dropped,
         test_bars_normalization_is_liberal, test_supplied_bars_are_actually_used,
         test_short_history_degrades_not_fails, test_similar_excludes_itself,
         test_feature_order_is_pinned, test_cache_expires_at_market_close,
         test_brief_survives_partial_failure,
         test_class_share_tickers_resolve, test_fetch_failure_is_typed,
         test_unknown_symbol_raises, test_health_always_reports_verdict]


if __name__ == "__main__":
    print("서비스 계층 검사")
    bad = 0
    for t in TESTS:
        try:
            t()
        except Exception as e:
            bad += 1
            print(f"  ✗ {t.__name__}: {type(e).__name__}: {e}")
    print(f"\n{len(TESTS) - bad}/{len(TESTS)} 통과")
    sys.exit(1 if bad else 0)
