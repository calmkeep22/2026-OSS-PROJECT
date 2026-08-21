"""
누설 검사 — **"미래를 보지 않는다"를 말이 아니라 코드로 확인한다.**

이 저장소의 결론은 전부 "워크포워드로 측정했다"에 기대고 있다. 그런데
워크포워드는 조용히 깨진다. 인덱싱을 한 칸 잘못 잡거나, 롤링 통계를 자르기
전에 걸거나, 이웃 종목을 전 구간 상관으로 고르면 — 예외는 안 나고 정확도만
올라간다. **틀린 걸 잘한 걸로 착각하게 되는 유일한 방식**이다.

그래서 세 가지를 기계적으로 확인한다.

    1. 평가일 이후를 망가뜨려도 그 날 예측이 그대로인가   (walk_forward)
    2. 테마 이웃이 뒷구간과 무관하게 정해지는가            (peer_features)
    3. 임계값이 평가일 데이터를 보지 않는가                (_pick_threshold)

실행
----
    python -m pytest tests/test_no_lookahead.py -v
    python tests/test_no_lookahead.py          # pytest 없이도 돈다
"""

from __future__ import annotations

import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
warnings.filterwarnings("ignore")

from accessible_investor import forecast as FC  # noqa: E402

STOCK = "삼성전자"
MODEL = "로지스틱회귀"
N_DAYS = 3


def _panel() -> pd.DataFrame:
    return FC.build(STOCK, verbose=False)


# ==========================================================================
def test_future_rows_do_not_change_predictions():
    """
    평가일 **이후** 행을 전부 망가뜨려도 그 날 예측이 바뀌지 않아야 한다.

    바뀐다면 어딘가에서 미래를 읽고 있다는 뜻이다. 이 검사가 강한 이유는
    누설 경로를 미리 알 필요가 없다는 데 있다 — 피처든 라벨이든 스케일러든,
    뒷구간을 건드렸는데 앞이 흔들리면 그것 자체가 증거다.
    """
    df = _panel()
    base, _ = FC.walk_forward(STOCK, n_days=N_DAYS, df=df, target="방향",
                              models=[MODEL], refit_every=1, verbose=False)
    assert len(base), "평가 결과가 비었다 — 검사를 할 수 없다"

    first_day = base["날짜"].min()

    # 평가 첫날 **다음** 행부터 전부 난수로 덮는다. 라벨도 함께 망가뜨린다.
    #
    # ⚠️ NaN 패턴은 그대로 둬야 한다.
    # 처음엔 라벨 칸까지 난수로 덮었더니 `y_up` 의 결측이 사라졌고,
    # `walk_forward` 가 쓰는 `usable = df[y_up.notna()]` 가 달라져 **평가일
    # 집합 자체가 바뀌었다.** 그러면 "같은 날 예측이 같은가"를 물을 수 없다.
    # 값만 흔들고 어디가 비었는지는 건드리지 않는다.
    corrupt = df.copy()
    after = corrupt.index > first_day
    rng = np.random.default_rng(0)
    num = list(corrupt.select_dtypes("number").columns)
    # 거래량처럼 정수인 열에 실수 난수를 넣는다. pandas 2.x 는 조용히 실수로
    # 올려 줬지만 3.0 부터는 TypeError 를 내므로 먼저 명시적으로 올린다.
    corrupt[num] = corrupt[num].astype("float64")
    block = pd.DataFrame(rng.normal(size=(int(after.sum()), len(num))) * 100,
                         index=corrupt.index[after], columns=num)
    block = block.where(corrupt.loc[after, num].notna())
    corrupt.loc[after, num] = block

    got, _ = FC.walk_forward(STOCK, n_days=N_DAYS, df=corrupt, target="방향",
                             models=[MODEL], refit_every=1, verbose=False)
    a = base[base["날짜"] == first_day].iloc[0]
    b = got[got["날짜"] == first_day].iloc[0]

    assert a["상승확률"] == b["상승확률"], (
        f"평가 첫날({first_day:%Y-%m-%d}) 예측이 뒷구간 훼손에 반응했다: "
        f"{a['상승확률']} → {b['상승확률']} — 미래 누설")
    assert a["임계값"] == b["임계값"], (
        f"임계값이 뒷구간 훼손에 반응했다: {a['임계값']} → {b['임계값']}")
    print(f"  ✅ 평가 첫날 예측 {a['상승확률']:.4f} · 임계값 {a['임계값']:.2f} 불변")


# ==========================================================================
def test_peer_selection_ignores_recent_data():
    """
    테마 이웃은 **앞쪽 구간에서만** 골라야 한다.

    전 구간 상관으로 고르면 "평가 구간에서 나와 가장 닮게 움직인 종목"을
    이웃으로 뽑는 셈이고, 그 이웃의 수익률이 곧 내 수익률의 누설이 된다.
    뒷부분을 잘라내도 같은 이웃이 나오는지로 확인한다.
    """
    px = FC.load_prices(STOCK)
    full = FC.peer_features(STOCK, px)
    if full["peer_ret_1"].notna().sum() == 0:
        print("  ⚠️  이웃 풀이 비어 검사를 건너뛴다")
        return

    # 뒤 20% 를 잘라도 **앞 55% 는 그대로**이므로 이웃이 같아야 한다.
    cut = px.iloc[:int(len(px) * 0.8)]
    FC._PEER_POOL.clear()
    part = FC.peer_features(STOCK, cut)
    FC._PEER_POOL.clear()

    common = full.index.intersection(part.index)
    a = full.loc[common, "peer_ret_1"].dropna()
    b = part.loc[common, "peer_ret_1"].dropna()
    both = a.index.intersection(b.index)
    assert len(both) > 100, "비교 구간이 너무 짧다"
    assert np.allclose(a.loc[both], b.loc[both], equal_nan=True), (
        "뒷구간을 잘랐더니 이웃 수익률이 달라졌다 — 이웃 선택이 미래를 본다")
    print(f"  ✅ 이웃 수익률 {len(both)}행 일치 — 이웃 선택은 앞구간에만 의존")


# ==========================================================================
def test_threshold_uses_only_training_rows():
    """
    임계값은 학습 구간 안에서만 정해져야 한다.

    `_pick_threshold` 에 같은 학습 행렬을 주면 항상 같은 값이 나오고,
    학습 행렬 자체가 평가일 이전 행으로만 구성되는지는 위 첫 검사가 덮는다.
    여기서는 **결정성**과 **범위**를 본다 — 0.5 에서 지나치게 멀면 사실상
    한쪽만 찍는 것이라 균형정확도가 도로 0.5 로 내려간다.
    """
    df = _panel()
    lab = df[df["y_up"].notna()]
    use = FC._cols(lab, "all")
    X = lab[use].to_numpy(np.float32)
    y = lab["y_up"].to_numpy(int)

    t1 = FC._pick_threshold(MODEL, X, y)
    t2 = FC._pick_threshold(MODEL, X, y)
    assert t1 == t2, f"임계값이 실행마다 달라진다: {t1} vs {t2}"
    assert FC.THR_GRID.min() <= t1 <= FC.THR_GRID.max(), (
        f"임계값 {t1} 이 허용 범위 밖이다")
    print(f"  ✅ 임계값 {t1:.2f} — 결정적이고 범위 안 "
          f"[{FC.THR_GRID.min()}, {FC.THR_GRID.max()}]")


# ==========================================================================
def test_partial_daily_bar_is_dropped():
    """
    장중에 받은 미완성 일봉은 버려야 한다.

    그걸 정답으로 쓰면 평가가 오염되고, 입력으로 쓰면 "오늘 예측"이
    "이미 본 값 되읽기"가 된다.
    """
    raw = FC.load_prices(STOCK, keep_partial=True)
    used = FC.load_prices(STOCK)
    assert len(used) <= len(raw)
    tz = FC.MARKETS["KR"]["tz"]
    now = pd.Timestamp.now(tz=tz)
    if pd.Timestamp(raw.index[-1]).date() == now.date():
        hh, mm = FC._CLOSE_HHMM["KR"]
        settled = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
        if now < settled + pd.Timedelta(minutes=FC._SETTLE_MIN):
            assert len(used) == len(raw) - 1, "장중인데 미완성 봉이 남아 있다"
            print("  ✅ 장중 미완성 봉 1개 제거 확인")
            return
    print(f"  ✅ 장 마감 이후 — 버릴 미완성 봉 없음 (봉 {len(used)}개)")


# ==========================================================================
if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    fails = 0
    for fn in fns:
        print(f"\n▶ {fn.__name__}")
        try:
            fn()
        except AssertionError as e:
            fails += 1
            print(f"  ❌ {e}")
        except Exception as e:
            fails += 1
            print(f"  ❌ {type(e).__name__}: {e}")
    print(f"\n{len(fns) - fails}/{len(fns)} 통과")
    sys.exit(1 if fails else 0)
