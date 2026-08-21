"""
시각 자료 — 제출용 그림을 PNG로 저장한다.

원칙
====
1. **한글이 깨지지 않게** 폰트를 먼저 잡는다. 윈도우는 맑은 고딕이 있다.
2. **색만으로 구분하지 않는다.** 마커 모양·해칭·직접 라벨을 같이 쓴다.
   저시력·색각 이상 사용자를 위한 프로젝트가 색으로만 말하면 앞뒤가 안 맞고,
   심사자가 흑백으로 인쇄해도 읽혀야 한다.
3. **신뢰구간을 그린다.** 7일 평가의 막대만 그리면 85.7%가 대단해 보인다.
   오차막대를 얹으면 그 숫자가 무슨 뜻인지 그림이 스스로 말한다.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

_READY = False


def setup(dpi: int = 130):
    """matplotlib 초기화. 한글 폰트와 기본 스타일."""
    global _READY
    import matplotlib
    matplotlib.use("Agg")            # 화면 없는 환경에서도 그린다
    import matplotlib.pyplot as plt
    from matplotlib import font_manager

    if not _READY:
        for cand in ("Malgun Gothic", "AppleGothic", "NanumGothic", "Noto Sans CJK KR"):
            if any(f.name == cand for f in font_manager.fontManager.ttflist):
                plt.rcParams["font.family"] = cand
                break
        plt.rcParams.update({
            "axes.unicode_minus": False,      # 한글 폰트에서 마이너스가 깨진다
            "figure.dpi": dpi, "savefig.dpi": dpi,
            "savefig.bbox": "tight", "figure.facecolor": "white",
            "axes.grid": True, "grid.alpha": 0.25, "grid.linestyle": ":",
            "axes.spines.top": False, "axes.spines.right": False,
            "font.size": 10,
        })
        _READY = True
    return plt


def _save(fig, path: Path, verbose: bool = True) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path)
    import matplotlib.pyplot as plt
    plt.close(fig)
    if verbose:
        print(f"    → {path.name}")
    return path


# ==========================================================================
# 01 예측
# ==========================================================================
def forecast_accuracy(summary: pd.DataFrame, out: Path,
                      verbose: bool = True) -> Path:
    """
    종목 × 모델 **균형정확도** + 윌슨 신뢰구간.

    **오차막대가 이 그림의 요점이다.** 막대 높이만 보면 성능을 오해한다 —
    종목당 평가 횟수가 수십 회라 구간이 여전히 ±10%p 씩 벌어진다.

    단순 적중률은 흰 마름모로 참고만 찍는다. 판정은 균형정확도로만 한다.
    쏠린 라벨에서는 단순 적중률의 기준선이 50%가 아니기 때문이다.
    """
    plt = setup()
    stocks = list(dict.fromkeys(summary["종목"]))
    models = list(dict.fromkeys(summary["모델"]))
    fig, axes = plt.subplots(1, len(stocks), figsize=(3.5 * len(stocks), 4.6),
                             sharey=True)
    axes = np.atleast_1d(axes)
    hatches = ["", "//", "\\\\", "xx", ".."]
    has_bal = "균형정확도" in summary.columns

    for ax, st in zip(axes, stocks):
        sub = summary[summary["종목"] == st].set_index("모델").reindex(models)
        x = np.arange(len(models))
        val = (sub["균형정확도"] if has_bal else sub["적중률"]).to_numpy(float)

        # 균형정확도의 구간은 **적은 쪽 클래스**가 좌우한다.
        if has_bal and "소수클래스일" in sub.columns:
            nmin = sub["소수클래스일"].fillna(1).to_numpy(int).clip(min=1)
            pairs = [_wilson_pair(int(round(v * n * 2)), int(n * 2))
                     if np.isfinite(v) else (np.nan, np.nan)
                     for v, n in zip(val, nmin)]
        else:
            pairs = list(zip(sub["신뢰구간하한"], sub["신뢰구간상한"]))
        lo = val - np.array([a for a, _ in pairs], dtype=float)
        hi = np.array([b for _, b in pairs], dtype=float) - val
        sig = np.array([a for a, _ in pairs], dtype=float) > 0.5

        ax.bar(x, val, width=0.62,
               color=np.where(sig, "#cfe3d4", "#dfe5ec"),
               edgecolor=np.where(sig, "#0a6b3d", "#20344a"), linewidth=1.1)
        for b, h in zip(ax.patches, hatches):
            b.set_hatch(h)
        ax.errorbar(x, val, yerr=[lo, hi], fmt="none", ecolor="#20344a",
                    elinewidth=1.4, capsize=5)
        # 균형정확도에서는 50%가 진짜 무작위선이다 — 종목마다 다르지 않다.
        ax.axhline(0.5, color="#a3122a", linestyle="--", linewidth=1.4)
        if has_bal:
            ax.scatter(x, sub["적중률"].to_numpy(float), marker="D", s=26,
                       facecolor="white", edgecolor="#5b6270", linewidth=1.2,
                       zorder=5)
        # 값은 막대 **안쪽**에 쓴다. 막대 위에 쓰면 오차막대 캡과 겹치고,
        # 90% 짜리 막대에서는 ylim 밖으로 잘려 나간다. 실제로 둘 다 났다.
        for xi, a in zip(x, val):
            if np.isfinite(a):
                ax.text(xi, a - 0.035, f"{a:.0%}", ha="center", va="top",
                        fontsize=9, fontweight="bold", color="#16181d")
        ax.set_xlim(-0.62, len(models) - 0.38)
        ax.set_xticks(x)
        ax.set_xticklabels(models, rotation=28, ha="right", fontsize=8.5)
        ax.set_title(st[:16], fontsize=10.5, fontweight="bold")
        ax.set_ylim(0.15, 0.95)
    axes[0].set_ylabel("균형정확도")
    n_eval = int(summary["평가일"].max()) if "평가일" in summary.columns else 0
    fig.suptitle(
        f"시연용 상위 종목 — 최근 {n_eval}거래일 워크포워드 (성적순 선별)\n"
        "빨간 점선이 무작위 50%. 흰 마름모는 단순 적중률(참고)",
        fontsize=11.5, y=1.04)
    return _save(fig, out, verbose)


def feature_ablation(abl: pd.DataFrame, out: Path,
                     verbose: bool = True) -> Path:
    """
    피처 묶음을 하나씩 얹으며 잰 결과 — **무엇이 실제로 기여했는가.**

    "뉴스를 넣었다"는 말은 증거가 아니다. 넣기 전과 후를 같은 조건에서 재고,
    그 차이를 그려야 기여를 주장할 수 있다. 여기서는 세 단계로 얹는다.

        기술+시장  →  +테마  →  +뉴스(전체)

    막대는 균형정확도, 아래 숫자는 **50%를 넘은 종목 수**다. 둘을 같이 보는
    이유는 평균만 오르고 종목 수는 그대로인 경우가 있기 때문이다 — 그건
    "몇 종목에서만 좋아졌다"는 뜻이라 일반화 근거가 약하다.
    """
    plt = setup()
    order = ["tech+mkt", "tech+mkt+peer", "all"]
    label = {"tech+mkt": "기술+시장", "tech+mkt+peer": "+테마",
             "all": "+뉴스 (전체)"}
    targets = [t for t in ("변동성", "방향") if t in set(abl["타깃"])]
    fig, axs = plt.subplots(1, len(targets),
                            figsize=(6.2 * len(targets), 4.8), squeeze=False)

    for ax, tgt in zip(axs[0], targets):
        d = abl[abl["타깃"] == tgt]
        models = list(dict.fromkeys(d["모델"]))
        sets = [s for s in order if s in set(d["피처"])]
        w = 0.8 / max(len(models), 1)
        x = np.arange(len(sets))
        hatches = ["", "//", "\\\\", "xx", ".."]
        for i, (m, h) in enumerate(zip(models, hatches)):
            sub = d[d["모델"] == m].set_index("피처").reindex(sets)
            v = sub["균형정확도"].to_numpy(float)
            ax.bar(x + i * w - 0.4 + w / 2, v, width=w * 0.92, hatch=h,
                   label=m, edgecolor="#20344a", linewidth=1.0,
                   color=["#dfe5ec", "#c7d6e6", "#9dbdd8", "#cfe3d4"][i % 4])
        ax.axhline(0.5, color="#a3122a", linestyle="--", linewidth=1.5)

        # 종목 수는 축 라벨로 내린다 — 막대 안에 쓰면 해칭과 겹쳐 안 읽힌다.
        best = d.loc[d.groupby("피처")["균형정확도"].idxmax()].set_index("피처")
        ax.set_xticks(x)
        ax.set_xticklabels(
            [f"{label.get(s, s)}\n최고 {best.loc[s, '균형정확도']:.1%}"
             f"\n50%초과 {int(best.loc[s, '50%초과'])}/{int(best.loc[s, '종목수'])}"
             for s in sets], fontsize=9.2)
        ax.set_ylim(0.46, max(0.58, float(d["균형정확도"].max()) + 0.02))
        ax.set_title(f"타깃 = {tgt}", fontsize=11.5, fontweight="bold")
        if ax is axs[0][0]:
            ax.set_ylabel("균형정확도")
            ax.legend(fontsize=8.4, ncol=2, loc="upper left")
    fig.suptitle("피처를 얹을수록 좋아지는가 — 어블레이션\n"
                 "빨간 점선이 무작위 50%. 아래 숫자는 50%를 넘은 종목 수",
                 fontsize=11.5, y=1.02)
    fig.tight_layout()
    return _save(fig, out, verbose)


def forecast_timeline(wf: pd.DataFrame, best_model: str, out: Path,
                      verbose: bool = True) -> Path:
    """
    날짜별 적중/오답을 종목마다 한 줄로. 어떤 날 틀렸는지가 보인다.

    ⚠️ 날짜 수에 따라 표시를 바꾼다.
    7일용으로 만들어 둔 그림을 40일에 그렸더니 **완전히 뭉개졌다** —
    칸마다 수익률을 적고 날짜를 전부 찍으니 글자가 서로 겹쳐 아무것도
    읽히지 않았다. 12일이 넘으면 칸 글자를 빼고 날짜 눈금을 솎는다.
    """
    plt = setup()
    sub = wf[wf["모델"] == best_model]
    stocks = list(dict.fromkeys(sub["종목"]))
    dates = sorted(sub["날짜"].unique())
    dense = len(dates) > 12
    step = max(1, len(dates) // 10)
    fig, ax = plt.subplots(
        figsize=(max(9.5, 0.33 * len(dates) + 3.0),
                 (0.72 if dense else 1.05) * len(stocks) + 2.0))

    xmap = {d: i for i, d in enumerate(dates)}
    msize = 130 if dense else 360
    for yi, st in enumerate(stocks):
        s = sub[sub["종목"] == st]
        for _i, r in s.iterrows():
            x = xmap[r["날짜"]]
            ok = r["적중"] == 1
            # ⚠️ 마커 위에 글자를 겹쳐 쓰지 않는다.
            # 처음엔 "○"/"✕" 를 얹었는데 맑은 고딕에 ✕(U+2715) 글리프가 없어
            # 두부(□)로 렌더링됐다. 마커 모양(동그라미 / X)만으로 이미
            # 색 없이도 구분되므로 글자는 뺀다.
            ax.scatter(x, yi, s=msize,
                       marker="o" if ok else "X",
                       facecolor="#cfe3d4" if ok else "#f6d4da",
                       edgecolor="#0a6b3d" if ok else "#a3122a",
                       linewidth=1.2 if dense else 1.8, zorder=3)
            if not dense:
                ax.text(x, yi - 0.34, f"{r['실제등락률']:+.1f}%", ha="center",
                        fontsize=7.5, color="#555")
    ax.set_yticks(range(len(stocks)))
    ax.set_yticklabels([s[:18] for s in stocks], fontsize=9.5)
    tick_ix = list(range(0, len(dates), step))
    ax.set_xticks(tick_ix)
    ax.set_xticklabels([pd.Timestamp(dates[i]).strftime("%m-%d")
                        for i in tick_ix], fontsize=9)
    ax.set_ylim(-0.75, len(stocks) - 0.25)
    ax.set_xlim(-0.6, len(dates) - 0.4)
    ax.grid(axis="x", alpha=0.2)
    ax.scatter([], [], marker="o", s=180, facecolor="#cfe3d4",
               edgecolor="#0a6b3d", linewidth=1.8, label="맞힘")
    ax.scatter([], [], marker="X", s=180, facecolor="#f6d4da",
               edgecolor="#a3122a", linewidth=1.8, label="틀림")
    ax.legend(loc="upper right", bbox_to_anchor=(1.0, 1.14), ncol=2,
              frameon=False, fontsize=9)
    ax.set_title(f"날짜별 적중 — {best_model}"
                 + ("" if dense else "\n아래 숫자는 그날 실제 수익률"),
                 fontsize=11, fontweight="bold")
    return _save(fig, out, verbose)


def model_tradeoff(sel: pd.DataFrame, out: Path, verbose: bool = True) -> Path:
    """정확도 vs 소요시간. 모델 선택의 근거를 한 장으로."""
    plt = setup()
    fig, ax = plt.subplots(figsize=(7.2, 5.0))
    x = sel["건당ms"].to_numpy(float).clip(min=0.5)
    y = (sel["균형정확도"] if "균형정확도" in sel.columns
         else sel["적중률"]).to_numpy(float)
    ax.scatter(x, y, s=190, facecolor="#9dbdd8", edgecolor="#20344a",
               linewidth=1.4, zorder=3)
    for xi, yi, nm, sc in zip(x, y, sel["모델"], sel["점수"]):
        ax.annotate(f"{nm}\n(점수 {sc:.3f})", (xi, yi),
                    textcoords="offset points", xytext=(9, 7), fontsize=8.6)
    ax.set_xscale("log")
    ax.set_xlabel("예측 1건당 소요 시간 (ms, 로그 축)")
    ax.set_ylabel("균형정확도")
    ax.axhline(0.5, color="#a3122a", linewidth=1.2, linestyle="--")
    # 벌점이 붙기 시작하는 지점을 선으로 그린다 — 왼쪽은 전부 벌점 0이다.
    try:
        from .forecast import LATENCY_FREE_MS as _free
        ax.axvline(_free, color="#c98a00", linewidth=1.4, linestyle=":")
        ax.text(_free * 1.05, ax.get_ylim()[0], f" {_free}ms부터 벌점",
                va="bottom", fontsize=8.4, color="#c98a00")
    except Exception:
        pass
    # ⚠️ 그림 문자열에 U+2212(−)를 쓰지 않는다.
    # 맑은 고딕에 그 글리프가 없어 **두부(□)로 렌더링된다.** 실제로 제목의
    # 빼기 기호가 네모로 나갔다. `axes.unicode_minus=False` 는 축 눈금에만
    # 적용되고 임의 텍스트에는 적용되지 않는다. ASCII 하이픈을 쓴다.
    ax.set_title("모델 선택 - 정확도와 속도를 함께 본다\n"
                 "점수 = 균형정확도 - 0.02 x log10(1 + 초과ms / 기준ms)",
                 fontsize=11, fontweight="bold")
    return _save(fig, out, verbose)


def prediction_card(preds: pd.DataFrame, out: Path,
                    verbose: bool = True) -> Path:
    """
    다음 거래일 예측 요약 카드. **그 종목의 임계값 기준** 양방향 막대로.

    ⚠️ 0.5 를 기준선으로 그리면 안 된다.
    임계값은 종목마다 학습되므로(`forecast._pick_threshold`) 0.5 가 아니다.
    0.5 기준으로 그렸더니 **그림과 라벨이 대놓고 어긋났다** — SK하이닉스는
    확률 47.1% 라 막대가 왼쪽으로 뻗었는데 라벨은 "크게움직임"이었다.
    그 종목의 임계값이 0.40 이라 판정 자체는 맞았지만, 그림만 보면 정반대로
    읽힌다. 심사자가 제일 먼저 의심할 종류의 어긋남이다.

    그래서 가로축을 **임계값 대비 편차**로 바꾼다. 0 이 그 종목의 판정선이고,
    오른쪽이면 "그렇다", 왼쪽이면 "아니다"가 그림과 라벨에서 항상 같아진다.

    타깃이 둘이라 열 이름을 `확률` 로 통일했다. 예전 이름(`상승확률`)도
    받아 준다 — 저장해 둔 옛 CSV 로 그림만 다시 그릴 때가 있다.
    """
    plt = setup()
    col = "확률" if "확률" in preds.columns else "상승확률"
    fig, ax = plt.subplots(figsize=(9.0, 0.85 * len(preds) + 2.2))
    y = np.arange(len(preds))
    p = preds[col].to_numpy(float)
    thr = (preds["임계값"].to_numpy(float) if "임계값" in preds.columns
           else np.full(len(preds), 0.5))
    dev = p - thr
    ax.barh(y, dev, left=0.0, height=0.52,
            color=np.where(dev >= 0, "#cfe3d4", "#f6d4da"),
            edgecolor=np.where(dev >= 0, "#0a6b3d", "#a3122a"), linewidth=1.4)
    ax.axvline(0.0, color="#333", linewidth=1.4)
    for yi, (d, (_i, r)) in zip(y, zip(dev, preds.iterrows())):
        v = float(r[col])
        ax.text(0.006 if d >= 0 else -0.006, yi,
                f"{r['예측']} {v:.1%}",
                va="center", ha="left" if d >= 0 else "right",
                fontsize=9.5, fontweight="bold")
    ax.set_yticks(y)
    labels = []
    for _i, r in preds.iterrows():
        extra = (f" · 평소 ±{r['평소변동폭']:.1f}%"
                 if "평소변동폭" in preds.columns else "")
        t = (f" · 임계값 {r['임계값']:.2f}"
             if "임계값" in preds.columns else "")
        labels.append(f"{str(r['종목'])[:18]}\n{r['기준일']} 기준{extra}{t}")
    ax.set_yticklabels(labels, fontsize=8.4)
    # 축 범위를 실제 데이터에 맞춘다. 고정하면 확률이 포화됐을 때
    # 막대가 축을 뚫고 나간다.
    span = max(float(np.abs(dev).max()) * 1.35, 0.06)
    ax.set_xlim(-span, span)
    tgt = str(preds["타깃"].iloc[0]) if "타깃" in preds.columns else "방향"
    ax.set_xlabel("그 종목의 판정 임계값 대비 (0 = 판정선, 종목마다 다르다)")
    ax.invert_yaxis()
    ax.grid(axis="y", alpha=0)
    ax.set_title(f"다음 거래일 {tgt} 예측 — 확정된 마지막 종가 시점 정보만 사용",
                 fontsize=11, fontweight="bold")
    return _save(fig, out, verbose)


# ==========================================================================
# 02 이상감지 · 위험도
# ==========================================================================
def risk_profile(risk: pd.DataFrame, out: Path, verbose: bool = True) -> Path:
    """다섯 축 백분위를 종목별 레이더로. 어느 축이 험한지가 모양으로 보인다."""
    plt = setup()
    from .risk import AXES

    axes_names = list(AXES)
    n = len(axes_names)
    ang = np.linspace(0, 2 * np.pi, n, endpoint=False).tolist()
    ang += ang[:1]

    cols = min(len(risk), 4)
    rows = int(np.ceil(len(risk) / cols))
    # 제목 자리를 **높이로** 확보한다.
    # `subplots_adjust(top=...)` 만 쓰면 savefig(bbox="tight") 가 다시 잘라내
    # 서브플롯 제목과 suptitle 이 겹친다. 실측에서 두 번 겹쳤다.
    # 극좌표 축은 제목이 위로 길게 나오므로 여유를 실제 인치로 준다.
    head_in = 1.05
    fig, axs = plt.subplots(rows, cols,
                            figsize=(3.5 * cols, 3.8 * rows + head_in),
                            subplot_kw={"projection": "polar"})
    axs = np.atleast_1d(axs).ravel()

    for ax, (_i, r) in zip(axs, risk.iterrows()):
        v = [float(r[f"pct_{a}"]) for a in axes_names]
        v += v[:1]
        ax.plot(ang, v, color="#20344a", linewidth=1.8)
        ax.fill(ang, v, color="#9dbdd8", alpha=0.55)
        ax.set_xticks(ang[:-1])
        ax.set_xticklabels(axes_names, fontsize=8.5)
        ax.set_ylim(0, 100)
        ax.set_yticks([25, 50, 75])
        ax.set_yticklabels(["25", "50", "75"], fontsize=7, color="#777")
        ax.set_title(f"{r['표시이름']}\n위험도 {r['위험도']:.0f} · {r['등급']}",
                     fontsize=10, fontweight="bold", pad=14)
    for ax in axs[len(risk):]:
        ax.axis("off")
    total_h = 3.8 * rows + head_in
    fig.subplots_adjust(top=1 - head_in / total_h, hspace=0.5)
    fig.suptitle("종목별 위험 프로파일 — 비교군 내 백분위 (100에 가까울수록 험함)",
                 fontsize=11.5, y=1 - 0.28 / total_h, va="top")
    return _save(fig, out, verbose)


def risk_ranking(risk: pd.DataFrame, out: Path, verbose: bool = True) -> Path:
    """위험도 순위 막대 + 등급 라벨."""
    plt = setup()
    fig, ax = plt.subplots(figsize=(7.6, 0.72 * len(risk) + 2.0))
    y = np.arange(len(risk))
    v = risk["위험도"].to_numpy(float)
    ax.barh(y, v, height=0.55, color="#9dbdd8", edgecolor="#20344a",
            linewidth=1.2)
    for yi, (_i, r) in zip(y, risk.iterrows()):
        ax.text(r["위험도"] + 1.5, yi, f"{r['등급']}", va="center", fontsize=9)
    for thr, lab in ((20, "낮음"), (40, "보통"), (60, "높음"), (80, "매우 높음")):
        ax.axvline(thr, color="#bbb", linewidth=0.8, linestyle=":")
        ax.text(thr, len(risk) - 0.35, lab, fontsize=7.5, color="#888",
                ha="center")
    ax.set_yticks(y)
    ax.set_yticklabels(risk["표시이름"], fontsize=10)
    ax.invert_yaxis()
    ax.set_xlim(0, 105)
    ax.set_xlabel("위험도 (0~100, 비교군 백분위 가중합)")
    ax.grid(axis="y", alpha=0)
    ax.set_title("진입 난이도 — 높을수록 변동이 크고 빠져나오기 어렵다\n"
                 "주가 방향 예측이 아니다",
                 fontsize=11, fontweight="bold")
    return _save(fig, out, verbose)


def anomaly_timeline(px: pd.DataFrame, z: pd.Series, name: str, out: Path,
                     thr: float = 2.5, verbose: bool = True) -> Path:
    """가격 위에 이상 신호 지점을 찍는다. 위·아래 서브플롯."""
    plt = setup()
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(10.5, 6.0), sharex=True,
                                 gridspec_kw={"height_ratios": [2.2, 1]})
    a1.plot(px.index, px["close"], color="#20344a", linewidth=1.3)
    hit = z.abs() > thr
    up = hit & (px["close"].pct_change() > 0)
    dn = hit & ~up
    a1.scatter(px.index[up], px["close"][up], marker="^", s=95,
               facecolor="#cfe3d4", edgecolor="#0a6b3d", linewidth=1.4,
               zorder=3, label=f"이상 상승 {int(up.sum())}건")
    a1.scatter(px.index[dn], px["close"][dn], marker="v", s=95,
               facecolor="#f6d4da", edgecolor="#a3122a", linewidth=1.4,
               zorder=3, label=f"이상 하락 {int(dn.sum())}건")
    a1.set_ylabel("종가")
    a1.legend(fontsize=8.5, loc="upper left")
    a1.set_title(f"{name} — 이상 신호 (|robust z| > {thr})",
                 fontsize=11, fontweight="bold")

    a2.plot(px.index, z, color="#5b6270", linewidth=1.0)
    a2.axhline(thr, color="#a3122a", linestyle="--", linewidth=1.0)
    a2.axhline(-thr, color="#a3122a", linestyle="--", linewidth=1.0)
    a2.fill_between(px.index, -thr, thr, color="#eee", alpha=0.7)
    a2.set_ylabel("robust z")
    a2.set_xlabel("날짜")
    return _save(fig, out, verbose)


# ==========================================================================
# 03 차트 유사도
# ==========================================================================
def similarity_matches(query: np.ndarray, matches: list[dict], name: str,
                       out: Path, verbose: bool = True) -> Path:
    """
    질의 구간과 닮은 구간들을 겹쳐 그린다.

    z-정규화된 모양을 비교하는 것이므로 세로축은 가격이 아니라 **표준편차**다.
    그래야 5만원짜리와 50만원짜리를 같은 자리에서 비교할 수 있다.
    """
    plt = setup()
    k = len(matches)
    fig, axs = plt.subplots(1, k + 1, figsize=(2.7 * (k + 1), 3.4), sharey=True)
    axs = np.atleast_1d(axs)

    axs[0].plot(query, color="#20344a", linewidth=2.2)
    axs[0].set_title(f"{name}\n(질의 구간)", fontsize=10, fontweight="bold")
    axs[0].set_ylabel("z-정규화 가격 (표준편차)")

    for ax, m in zip(axs[1:], matches):
        ax.plot(query, color="#c8cdd6", linewidth=1.3, linestyle="--")
        ax.plot(m["shape"], color="#0b5cad", linewidth=1.9)
        fwd = m.get("forward_ret")
        sub = f"\n이후 {m.get('forward_days', 20)}일 {fwd:+.1f}%" if fwd is not None else ""
        ax.set_title(f"{m['name']}\n{m['period']}  거리 {m['dist']:.3f}{sub}",
                     fontsize=8.8)
    fig.suptitle("차트 유사도 — 회색 점선이 질의 구간, 파란 선이 닮은 구간",
                 fontsize=11, y=1.06, fontweight="bold")
    return _save(fig, out, verbose)


def similarity_forward(matches: list[dict], name: str, out: Path,
                       verbose: bool = True) -> Path:
    """
    닮은 구간들의 **이후 수익률 분포**.

    평균 한 숫자로 요약하지 않는다 — 그건 예측처럼 읽힌다.
    분포를 그대로 보여 주고 흩어진 정도를 같이 적는다.
    """
    plt = setup()
    vals = [m["forward_ret"] for m in matches if m.get("forward_ret") is not None]
    fig, ax = plt.subplots(figsize=(7.4, 4.2))
    if not vals:
        ax.text(0.5, 0.5, "이후 수익률 정보 없음", ha="center", va="center")
        ax.axis("off")
        return _save(fig, out, verbose)
    ax.axvline(0, color="#333", linewidth=1.2)
    ax.scatter(vals, np.arange(len(vals)), s=140, facecolor="#9dbdd8",
               edgecolor="#20344a", linewidth=1.3, zorder=3)
    for i, (v, m) in enumerate(zip(vals, matches)):
        ax.text(v, i + 0.22, f"{m['name']} {v:+.1f}%", fontsize=8.3,
                ha="center")
    up = sum(1 for v in vals if v > 0)
    ax.set_yticks([])
    ax.set_xlabel("닮은 구간 직후 수익률 (%)")
    ax.set_title(f"{name} — 닮은 구간 {len(vals)}개의 이후 결과\n"
                 f"상승 {up}건 · 하락 {len(vals)-up}건 · "
                 f"흩어짐 {np.std(vals):.1f}%p — 평균으로 요약하지 않는다",
                 fontsize=10.5, fontweight="bold")
    return _save(fig, out, verbose)


# ==========================================================================
# 01 예측 — 40종목 규모에서 추가된 그림들
# ==========================================================================
def accuracy_hist(by_stock: pd.DataFrame, out: Path,
                  verbose: bool = True) -> Path:
    """
    종목별 **균형정확도**의 분포.

    막대 하나로 "전체 55%"라고 쓰면 종목마다 30%~75%로 흩어진 사실이 사라진다.
    분포를 그리면 "잘 맞는 종목이 있다"와 "우연히 그런 종목이 나온다"가
    같은 그림 안에서 보인다.

    무작위선을 왜 시뮬레이션으로 그리나
    ----------------------------------
    단순 적중률이라면 이항분포를 겹쳐 그리면 됐다. 균형정확도는 **상승일
    적중률과 하락일 적중률의 평균**이라 이항분포 하나가 아니다. 종목마다
    상승일·하락일 수가 다르므로, 각 종목의 실제 클래스 수를 그대로 써서
    동전을 던져 보는 편이 정확하다. 공식을 유도하는 것보다 이쪽이 틀릴
    여지가 적다.
    """
    plt = setup()
    if "균형정확도" not in by_stock.columns:
        return forecast_accuracy(by_stock, out, verbose)

    d = by_stock.dropna(subset=["균형정확도"])
    acc = d["균형정확도"].to_numpy(float)
    fig, ax = plt.subplots(figsize=(8.6, 4.9))

    bins = np.linspace(0.0, 1.0, 21)
    ax.hist(acc, bins=bins, color="#9dbdd8", edgecolor="#20344a",
            linewidth=1.2, label=f"실제 종목 분포 (n={len(acc)})")

    # 동전 던지기였다면 어떤 모양이 나올까 — 각 종목의 실제 클래스 수 그대로.
    if {"다수클래스일", "소수클래스일"} <= set(d.columns):
        nu = d["다수클래스일"].to_numpy(int).clip(min=1)
        nd = d["소수클래스일"].to_numpy(int).clip(min=1)
        rng = np.random.default_rng(42)
        R = 400
        sims = ((rng.binomial(np.tile(nu, R), 0.5) / np.tile(nu, R)
                 + rng.binomial(np.tile(nd, R), 0.5) / np.tile(nd, R)) / 2)
        cnt, _ = np.histogram(sims, bins=bins)
        ctr = (bins[:-1] + bins[1:]) / 2
        ax.plot(ctr, cnt / R, "o--", color="#a3122a", linewidth=1.6,
                markersize=4.5, label="동전 던지기였다면 (같은 표본으로 모의)")
        exp_over = float((sims > 0.5).mean() * len(acc))
        ax.text(0.015, 0.965,
                f"50% 초과 종목  실제 {int((acc > 0.5).sum())}개 · "
                f"우연이라면 {exp_over:.1f}개",
                transform=ax.transAxes, va="top", fontsize=9,
                bbox=dict(boxstyle="round,pad=0.35", fc="#fffbe8",
                          ec="#c98a00", lw=1.0))

    m = float(np.mean(acc))
    ax.axvline(m, color="#0a6b3d", linewidth=1.8)
    ax.text(m, ax.get_ylim()[1] * 0.55, f" 평균 {m:.1%}", color="#0a6b3d",
            fontsize=9.5, fontweight="bold",
            bbox=dict(boxstyle="round,pad=0.18", fc="white", ec="none",
                      alpha=0.85))
    ax.axvline(0.5, color="#a3122a", linestyle="--", linewidth=1.4)
    ax.set_xlim(0.15, 0.85)
    ax.set_xlabel("종목별 균형정확도 (상승 적중률과 하락 적중률의 평균)")
    ax.set_ylabel("종목 수")
    ax.legend(fontsize=8.8, loc="upper right")
    ax.set_title("성능이 종목마다 얼마나 흩어지는가\n"
                 "빨간 점선과 겹치면 '잘 맞는 종목'은 우연으로 설명된다",
                 fontsize=11, fontweight="bold")
    return _save(fig, out, verbose)


def group_accuracy(by_index: pd.DataFrame, by_tier: pd.DataFrame, out: Path,
                   verbose: bool = True) -> Path:
    """
    지수별·규모별 **균형정확도**. 어디서 잘 맞고 어디서 안 맞는지.

    ⚠️ 넘어오는 표는 **선택된 모델 한 개로 이미 걸러져 있어야** 한다.
    전에는 여기서 모델 구분 없이 `적중` 을 통째로 합쳤다. 그러면 5개 모델의
    평균이 그려지는데, 정작 실제로 쓰는 건 그중 하나뿐이라 그림과 결론이
    서로 다른 것을 말하게 된다.
    """
    plt = setup()
    fig, axs = plt.subplots(1, 2, figsize=(11.5, 4.6))
    for ax, df, key, title in ((axs[0], by_index, "지수", "지수별"),
                               (axs[1], by_tier, "구분", "규모 구간별")):
        if not len(df) or "균형정확도" not in df.columns:
            ax.axis("off")
            continue
        d = df.dropna(subset=["균형정확도"]).copy()
        if not len(d):
            ax.axis("off")
            continue
        # 그룹당 한 행이 되도록 평가일 기준 가중평균 (이미 한 행이면 그대로)
        d = (d.assign(_w=d["평가일"])
               .groupby(key)
               .apply(lambda s: pd.Series({
                   "균형정확도": float(np.average(s["균형정확도"],
                                              weights=s["_w"])),
                   "평가일": int(s["평가일"].sum()),
                   "n_min": int(s["소수클래스일"].sum())
                   if "소수클래스일" in s.columns
                   else int(s["평가일"].sum() // 2)}))
               .reset_index())
        bal = d["균형정확도"].to_numpy(float)
        x = np.arange(len(d))
        lo_hi = [_wilson_pair(int(round(b * n * 2)), int(n * 2))
                 for b, n in zip(bal, d["n_min"].clip(lower=1))]
        lo = bal - np.array([a for a, _ in lo_hi])
        hi = np.array([b for _, b in lo_hi]) - bal
        sig = np.array([a for a, _ in lo_hi]) > 0.5
        ax.bar(x, bal, width=0.55,
               color=np.where(sig, "#cfe3d4", "#e8eaee"),
               edgecolor=np.where(sig, "#0a6b3d", "#5b6270"), linewidth=1.3)
        ax.errorbar(x, bal, yerr=[lo, hi], fmt="none",
                    ecolor="#20344a", elinewidth=1.4, capsize=5)
        ax.axhline(0.5, color="#a3122a", linestyle="--", linewidth=1.4)
        # 라벨은 **오차막대 위**에 둔다. 막대 바로 위면 캡과 겹친다.
        # n 은 정수로 — groupby 를 거치며 float 이 되어 "n=400.0" 이 찍혔다.
        for xi, (a, n, h) in enumerate(zip(bal, d["평가일"], hi)):
            ax.text(xi, a + h + 0.014, f"{a:.1%}\n(n={int(n):,})",
                    ha="center", fontsize=8.5)
        ax.set_xticks(x)
        ax.set_xticklabels(d[key], rotation=12, ha="right", fontsize=9)
        ax.set_ylim(0.30, float(np.max(bal + hi)) + 0.06)
        ax.set_title(title, fontsize=11, fontweight="bold")
    axs[0].set_ylabel("균형정확도")
    fig.suptitle("어디서 잘 맞는가 — 빨간 점선이 동전 던지기(50%)",
                 fontsize=11.5, y=1.02)
    return _save(fig, out, verbose)


def _wilson_pair(k: int, n: int, z: float = 1.96):
    if n <= 0:
        return (0.0, 1.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * np.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def magnitude_scatter(wf: pd.DataFrame, out: Path,
                      verbose: bool = True) -> Path:
    """
    예상 등락률 대 실제 등락률.

    방향만 맞히는 것과 크기까지 맞히는 것은 다른 문제다. 점들이 대각선에
    몰리면 크기도 맞은 것이고, 가로로 눕는다면 **모델이 늘 비슷한 크기만
    말하고 있다**는 뜻이다. 후자가 흔하다.
    """
    plt = setup()
    x = wf["예상등락률"].to_numpy(float)
    y = wf["실제등락률"].to_numpy(float)
    ok = np.isfinite(x) & np.isfinite(y)
    x, y = x[ok], y[ok]
    fig, ax = plt.subplots(figsize=(6.6, 6.0))
    lim = float(np.nanpercentile(np.abs(y), 98)) * 1.1 if len(y) else 5
    ax.axhline(0, color="#888", linewidth=0.9)
    ax.axvline(0, color="#888", linewidth=0.9)
    ax.plot([-lim, lim], [-lim, lim], "--", color="#a3122a", linewidth=1.3,
            label="완벽 예측선")
    same = np.sign(x) == np.sign(y)
    ax.scatter(x[same], y[same], s=26, alpha=0.55, facecolor="#cfe3d4",
               edgecolor="#0a6b3d", linewidth=0.7, label=f"방향 적중 {same.sum()}")
    ax.scatter(x[~same], y[~same], s=26, alpha=0.55, facecolor="#f6d4da",
               edgecolor="#a3122a", linewidth=0.7,
               label=f"방향 오답 {(~same).sum()}")
    ax.set_xlim(-lim, lim)
    ax.set_ylim(-lim, lim)
    ax.set_xlabel("예상 등락률 (%)")
    ax.set_ylabel("실제 등락률 (%)")
    ax.legend(fontsize=8.6, loc="upper left")
    mae = float(np.mean(np.abs(x - y))) if len(x) else float("nan")
    corr = float(np.corrcoef(x, y)[0, 1]) if len(x) > 2 else float("nan")
    ax.set_title(f"등락률 예측 — MAE {mae:.2f}%p · 상관 {corr:+.3f}\n"
                 "가로로 누우면 '늘 비슷한 크기만 말한다'는 뜻이다",
                 fontsize=10.8, fontweight="bold")
    return _save(fig, out, verbose)


def target_comparison(cmp_df: pd.DataFrame, out: Path,
                      verbose: bool = True) -> Path:
    """
    타깃별 **균형정확도** — 이 파트에서 제일 중요한 그림.

    왜 단순 적중률이 아니라 균형정확도인가
    --------------------------------------
    단순 적중률로 그리면 기준선이 50%가 아니다. 라벨이 상승 67% 로 쏠린
    구간에서는 "무조건 상승"만 외쳐도 67% 가 나오므로, 55% 막대에 50% 선을
    그으면 **넘은 것처럼 보이는 착시**가 생긴다. 실제로 그렇게 그렸다가
    결론이 한 번 뒤집혔다.

    균형정확도는 상승 적중률과 하락 적중률을 각각 재서 평균한다. 한쪽만
    외치면 다른 쪽이 0% 가 되어 정확히 50% 로 수렴한다. 즉 **쏠림이 얼마든
    50%가 진짜 무작위선**이고, 50% 기준선을 긋는 것이 그대로 정당해진다.

    비교를 위해 단순 적중률과 다수 클래스 비율도 함께 찍되, 판정은
    균형정확도 신뢰구간이 50%를 넘느냐로만 한다.
    """
    plt = setup()
    fig, ax = plt.subplots(figsize=(7.8, 5.2))
    x = np.arange(len(cmp_df))
    acc = cmp_df["균형정확도"].to_numpy(float)
    lo = acc - cmp_df["균형_신뢰하한"].to_numpy(float)
    hi = cmp_df["균형_신뢰상한"].to_numpy(float) - acc
    sig = cmp_df["유의미"].to_numpy(bool)

    ax.bar(x, acc, width=0.46,
           color=np.where(sig, "#cfe3d4", "#e8eaee"),
           edgecolor=np.where(sig, "#0a6b3d", "#5b6270"), linewidth=1.6,
           zorder=2)
    for b, s in zip(ax.patches, sig):
        b.set_hatch("" if s else "//")
    ax.errorbar(x, acc, yerr=[lo, hi], fmt="none", ecolor="#20344a",
                elinewidth=1.7, capsize=7, zorder=3)

    # 기준선 50% — 균형정확도에서는 이것이 진짜 무작위선이다.
    ax.axhline(0.5, color="#a3122a", linestyle="--", linewidth=2.0, zorder=4)
    # ⚠️ 라벨을 축 왼쪽 밖(-0.46)에 두면 y축 제목과 겹친다. 실제로 겹쳤다.
    # 막대 사이 빈 공간이 제일 안전하다.
    ax.text(len(cmp_df) - 0.55, 0.503, "무작위 50%", va="bottom", ha="right",
            color="#a3122a", fontsize=9.2, fontweight="bold", zorder=7,
            bbox=dict(boxstyle="round,pad=0.2", fc="white", ec="none",
                      alpha=0.85))

    # 참고값 — 단순 적중률과 "무조건 다수쪽" 비율. 판정에는 쓰지 않는다.
    if "다수클래스비율" in cmp_df.columns:
        ax.scatter(x, cmp_df["다수클래스비율"].to_numpy(float), marker="_",
                   s=520, color="#c98a00", linewidth=2.2, zorder=5,
                   label="무조건 다수쪽 (단순 적중률 기준선)")
    ax.scatter(x, cmp_df["적중률"].to_numpy(float), marker="D", s=34,
               facecolor="white", edgecolor="#5b6270", linewidth=1.4,
               zorder=6, label="단순 적중률 (참고)")
    ax.legend(fontsize=8.4, loc="upper right", framealpha=0.92)

    top = float(max(np.max(acc + hi),
                    cmp_df.get("다수클래스비율", pd.Series([0])).max()))
    # itertuples 는 '50%초과종목' 처럼 숫자로 시작하는 열을 `_9` 같은 이름으로
    # 바꿔 버린다. 위치 기반 접근은 열 순서가 바뀌면 조용히 틀리므로 iterrows 를 쓴다.
    for xi, (_i, r) in zip(x, cmp_df.iterrows()):
        # 라벨은 **오차막대 위쪽과 무지성 기준선 중 더 높은 것** 위에 둔다.
        # 신뢰상한만 기준으로 삼았더니 방향 타깃에서 기준선 마커(0.562)와
        # 글자가 겹쳤다 — 둘 다 같은 x 자리에 있기 때문이다.
        top_here = max(float(r["균형_신뢰상한"]),
                       float(r.get("다수클래스비율", 0)))
        ax.text(xi, top_here + 0.008,
                f"{r['균형정확도']:.2%}\n{'유의미' if r['유의미'] else '미검증'}",
                ha="center", fontsize=9.8, fontweight="bold",
                color="#0a6b3d" if r["유의미"] else "#5b6270", zorder=6)
    ax.set_xticks(x)
    # 표본 수는 막대 **안**에 쓰지 않는다 — 해칭과 겹쳐 읽히지 않았다.
    # 축 라벨로 내리면 배경이 흰색이라 그대로 읽힌다.
    ax.set_xticklabels(
        [f"{r['타깃']}\n({r['모델']})\n{r['평가건수']:,}건 · "
         f"50%초과 {r['50%초과종목']}/{r['종목수']}종목"
         for _i, r in cmp_df.iterrows()], fontsize=9.4)
    ax.set_ylim(0.40, max(top, 0.70) + 0.05)
    ax.set_ylabel("균형정확도")
    ax.set_title("무엇을 맞힐 수 있는가 — 방향 대 변동성\n"
                 "균형정확도에서는 50%가 진짜 무작위선이다",
                 fontsize=11.5, fontweight="bold")
    return _save(fig, out, verbose)


def pooled_comparison(cmp_df: pd.DataFrame, out: Path,
                      verbose: bool = True) -> Path:
    """
    **파운데이션(풀드) 대 종목별 재학습** — 서비스 구조를 정하는 그림.

    같은 38종목 × 40거래일을 두 방식이 각각 맞힌 결과다. 평가 행이 완전히
    같으므로 막대 높이 차이는 그대로 방식의 차이다.

    막대 안이 아니라 축 라벨에 학습 표본 수를 적는다 — 이 그림의 논점이
    "표본을 30배로 늘린 것이 실제로 이득이었나"이기 때문이다.
    """
    plt = setup()
    targets = list(dict.fromkeys(cmp_df["타깃"]))
    # 방식 수를 **데이터에서 읽는다.** 처음엔 둘로 못 박아 뒀는데, 그러면
    # "처음 보는 종목" 행을 추가해도 그림에는 안 나온다 — 조용히 빠진다.
    order = ["종목별 재학습", "파운데이션(풀드)", "파운데이션(미학습 종목)"]
    present = set(cmp_df["방식"])
    ways = [w for w in order if w in present] + \
           [w for w in dict.fromkeys(cmp_df["방식"]) if w not in order]
    fig, ax = plt.subplots(figsize=(3.0 + 2.9 * len(ways), 5.4))
    w = 0.8 / max(len(ways), 1)
    base = np.arange(len(targets))
    face = {"종목별 재학습": "#e8eaee", "파운데이션(풀드)": "#cfe3d4",
            "파운데이션(미학습 종목)": "#bcd9e8"}
    edge = {"종목별 재학습": "#5b6270", "파운데이션(풀드)": "#0a6b3d",
            "파운데이션(미학습 종목)": "#14567d"}

    # ⚠️ 자리를 **타깃마다 따로** 잡는다.
    # 방식 전체 목록으로 위치를 정하면, 어떤 타깃에만 한 방식이 없을 때
    # 그 자리가 빈 채로 남아 그룹이 한쪽으로 쏠려 보인다. 실제로 방향
    # 타깃에는 미학습 종목 측정이 없어서 그렇게 나왔다.
    slot: dict[tuple, float] = {}
    for i, t in enumerate(targets):
        here = [w_ for w_ in ways
                if len(cmp_df[(cmp_df["타깃"] == t) & (cmp_df["방식"] == w_)])]
        for k, w_ in enumerate(here):
            slot[(t, w_)] = base[i] + (k - (len(here) - 1) / 2) * w

    top = 0.5
    for j, way in enumerate(ways):
        xs, acc, lo, hi, sig, labels = [], [], [], [], [], []
        for i, t in enumerate(targets):
            r = cmp_df[(cmp_df["타깃"] == t) & (cmp_df["방식"] == way)]
            if not len(r):
                continue
            r = r.iloc[0]
            xs.append(slot[(t, way)])
            acc.append(float(r["균형정확도"]))
            lo.append(float(r["균형정확도"]) - float(r["균형_신뢰하한"]))
            hi.append(float(r["균형_신뢰상한"]) - float(r["균형정확도"]))
            sig.append(bool(r["유의미"]))
            labels.append((float(r["균형_신뢰상한"]),
                           f"{r['균형정확도']:.2%}",
                           "유의미" if r["유의미"] else "미검증",
                           int(r["50%초과종목"]), int(r["종목수"])))
        if not xs:
            continue
        bars = ax.bar(xs, acc, width=w * 0.92,
                      color=face.get(way, "#dfe5ec"),
                      edgecolor=edge.get(way, "#20344a"),
                      linewidth=1.6, zorder=2, label=way)
        for b, s in zip(bars, sig):
            if not s:
                b.set_hatch("//")
        ax.errorbar(xs, acc, yerr=[lo, hi], fmt="none", ecolor="#20344a",
                    elinewidth=1.6, capsize=6, zorder=3)
        for x, (hi_, pct, verdict, k, n) in zip(xs, labels):
            ax.text(x, hi_ + 0.006, f"{pct}\n{verdict}\n50%↑ {k}/{n}",
                    ha="center", fontsize=8.8, fontweight="bold",
                    color="#0a6b3d" if verdict == "유의미" else "#5b6270",
                    zorder=6)
            top = max(top, hi_)

    ax.axhline(0.5, color="#a3122a", linestyle="--", linewidth=2.0, zorder=4)
    ax.text(len(targets) - 0.52, 0.503, "무작위 50%", va="bottom", ha="right",
            color="#a3122a", fontsize=9.2, fontweight="bold", zorder=7,
            bbox=dict(boxstyle="round,pad=0.2", fc="white", ec="none",
                      alpha=0.85))

    # 축 라벨에 **학습 표본 수**를 적는다 — 이 비교의 논점이다.
    ticks = []
    for t in targets:
        s = cmp_df[cmp_df["타깃"] == t]
        n_per = s[s["방식"] == "종목별 재학습"]["학습표본중앙값"]
        n_pool = s[s["방식"] == "파운데이션(풀드)"]["학습표본중앙값"]
        bits = [t]
        if len(n_per) and len(n_pool):
            bits.append(f"학습표본 {int(n_per.iloc[0]):,}행 → "
                        f"{int(n_pool.iloc[0]):,}행")
        ticks.append("\n".join(bits))
    ax.set_xticks(base)
    ax.set_xticklabels(ticks, fontsize=9.4)
    ax.set_ylim(0.40, max(top, 0.62) + 0.085)
    ax.set_ylabel("균형정확도")
    ax.legend(fontsize=8.6, loc="upper left", framealpha=0.92,
              ncol=1 if len(ways) < 3 else 2)
    sub = ("학습 표본을 179배로 늘리면 이득인가 · "
           "한 번도 안 배운 종목도 맞히는가" if len(ways) > 2 else
           "학습 표본을 179배로 늘리면 실제로 이득인가")
    ax.set_title("파운데이션 모델 대 종목별 재학습 — 같은 38종목 × 40거래일\n"
                 + sub, fontsize=11.5, fontweight="bold")
    return _save(fig, out, verbose)


def similarity_windows(px: pd.DataFrame, per_window: dict, name: str,
                       windows: list, out: Path,
                       verbose: bool = True) -> Path:
    """
    창별로 닮은 구간을 한 줄씩, **질의와 매치를 겹쳐서** 그린다.

    창마다 답이 다르다는 것 자체가 이 그림의 요점이다. 20봉에서 1등인 종목이
    250봉에서는 안 보이는 게 정상이고, **네 창에 모두 나오는 종목**이 있다면
    그게 진짜 닮은 것이다. 하나로 합쳐 평균 내면 그 정보가 사라진다.

    한 패널의 구성
    --------------
        ┌─────────────── 빨간 세로선 ───────────────┐
        │  왼쪽: 질의 구간 vs 매치 구간 (겹쳐 그림)  │  오른쪽: 매치의 이후 경로
        │  "얼마나 닮았나"를 눈으로 확인하는 자리     │  "그 다음 무슨 일이 있었나"

    질의 구간의 오른쪽(미래)은 **비워 둔다.** 질의는 정의상 가장 최근 구간이라
    아직 일어나지 않았다. 없는 선을 그리면 예측을 그린 것처럼 읽힌다.

    세로축은 가격이 아니라 **z-정규화된 표준편차**다. 그래야 5만원짜리와
    50만원짜리를 같은 자리에서 비교할 수 있다. 매치 구간은 **자기 구간의**
    평균·표준편차로 정규화하므로, 겹쳐 놓으면 모양만 비교된다.

    색만으로 구분하지 않는다 — 질의는 굵은 실선, 매치는 파선이다.
    """
    plt = setup()

    # ⚠️ 창 키를 **두 가지 표기 모두** 받는다.
    # `results.run_similarity` 는 `"120봉"` 문자열로 넣고
    # `similarity.find_similar_multi` 는 정수 `120` 으로 넣는다. 한쪽만
    # 받으면 다른 쪽에서 `hits` 가 빈 리스트가 되어 **매치 패널이 통째로
    # 사라진다** — 예외는 안 나고 질의 곡선 하나만 덩그러니 남는다.
    # 실제로 그 그림이 한 번 나갔다.
    def _res(W):
        for k in (f"{W}봉", W, str(W)):
            if k in per_window:
                r = per_window[k]
                return r if isinstance(r, dict) else {}
        return {}

    rows = [W for W in windows if _res(W)]
    if not rows:
        rows = windows[:1]
    ncol = 4
    fig, axs = plt.subplots(len(rows), ncol + 1,
                            figsize=(3.05 * (ncol + 1), 2.7 * len(rows)),
                            squeeze=False)
    close = px["close"].to_numpy(float)
    Q_COLOR, M_COLOR, F_COLOR = "#20344a", "#0b5cad", "#a3122a"

    for ri, W in enumerate(rows):
        res = _res(W)
        hits = res.get("results", []) if isinstance(res, dict) else []
        months = round(W / 21)

        # 질의 구간도 **이후 구간을 아는 자리**로 물려 두었으면 같이 그린다.
        # 그래야 "닮은 모양이 닮은 결과로 이어졌나"를 눈으로 볼 수 있다.
        qinfo = res.get("query", {}) if isinstance(res, dict) else {}
        q_fwd = np.asarray(qinfo.get("forward_path", []), dtype=float)
        q_off = int(qinfo.get("offset", 0))

        # ⚠️ 검색이 **실제로 쓴** 질의 곡선을 그린다.
        #
        # 여기가 한 번 크게 틀렸다. 예전엔 `close[-W:]`, 즉 **가장 최근 W봉**을
        # 그렸는데, 검색은 `query_offset=20` 으로 20봉 물린 구간을 쓴다.
        # 그림과 매칭이 **서로 다른 구간**이었던 것이다.
        #
        # 증상이 고약했다. 유사도 0.98 이라고 적힌 패널에서 검은 실선과 파란
        # 파선이 눈에 띄게 어긋나 보였다 — 20봉만큼 위상이 밀렸으니 당연했다.
        # 숫자는 맞고 그림만 틀린 경우라, "유사도 계산이 잘못됐나" 하고
        # 엉뚱한 곳을 오래 뒤졌다.
        #
        # 그래서 이제는 결과에 실려 온 `query.segment` 를 **그대로** 쓴다.
        # 검색이 쓴 배열 자체이므로 어긋날 여지가 없다.
        qseg = np.asarray(qinfo.get("segment", []), dtype=float)
        if len(qseg) == W:
            qz = qseg
        else:
            end = len(close) - q_off
            q = close[max(0, end - W):end]
            qz = (q - q.mean()) / (q.std() or 1.0)

        ax0 = axs[ri][0]
        ax0.plot(np.arange(W), qz, color=Q_COLOR, linewidth=2.0)
        if len(q_fwd):
            ax0.plot(np.arange(W - 1, W + len(q_fwd)),
                     np.concatenate([[qz[-1]], q_fwd]),
                     color=F_COLOR, linewidth=1.9)
            ax0.axvline(W - 0.5, color=F_COLOR, linestyle=":", linewidth=1.8)
            ax0.axvspan(W - 0.5, W + len(q_fwd), color=F_COLOR, alpha=0.06)
        ax0.set_ylabel(f"{W}봉\n(약 {months}개월)", fontsize=9,
                       fontweight="bold")
        ax0.set_xticks([])
        if ri == 0:
            sub_t = (f"질의 구간 ({q_off}봉 전 기준)" if q_off
                     else "질의 구간 (가장 최근)")
            ax0.set_title(f"{name}\n{sub_t}", fontsize=9.5, fontweight="bold")

        for ci in range(ncol):
            ax = axs[ri][ci + 1]
            ax.set_xticks([])
            ax.set_yticks([])
            if ci >= len(hits):
                ax.axis("off")
                continue
            r = hits[ci]
            seg = np.asarray(r.get("segment", []), dtype=float)
            fwd_path = np.asarray(r.get("forward_path", []), dtype=float)

            ax.plot(np.arange(W), qz, color=Q_COLOR, linewidth=1.9,
                    label=f"질의 ({name})", zorder=3)
            if len(seg) == W:
                ax.plot(np.arange(W), seg, color=M_COLOR, linewidth=1.7,
                        linestyle="--", label="닮은 구간", zorder=4)
            if len(fwd_path):
                # 경계에서 선이 끊기지 않도록 마지막 점을 이어 붙인다
                y0 = seg[-1] if len(seg) == W else fwd_path[0]
                ax.plot(np.arange(W - 1, W + len(fwd_path)),
                        np.concatenate([[y0], fwd_path]),
                        color=F_COLOR, linewidth=1.7, zorder=5,
                        label="매치의 이후 구간")
                ax.axvline(W - 0.5, color=F_COLOR, linestyle=":",
                           linewidth=1.8, zorder=2)
                ax.axvspan(W - 0.5, W + len(fwd_path), color=F_COLOR,
                           alpha=0.06, zorder=1)
            # **질의의 이후 구간도 같은 칸에 그린다.**
            # 매치 쪽만 그리면 "그래서 우리 종목은 어떻게 됐는데?"에 답이 없다.
            # 둘을 겹쳐 놔야 닮은 모양이 닮은 결과로 이어졌는지가 보인다.
            if len(q_fwd):
                ax.plot(np.arange(W - 1, W + len(q_fwd)),
                        np.concatenate([[qz[-1]], q_fwd]),
                        color=Q_COLOR, linewidth=1.8, linestyle=(0, (1, 1.2)),
                        zorder=7, label="질의의 이후 구간")

            fwd = r.get("forward_pct")
            sub = f" · 이후 {fwd:+.1f}%" if fwd is not None else ""
            ax.set_title(f"{r['name'][:14]}\n유사도 {r['similarity']:.3f}{sub}",
                         fontsize=8.8, fontweight="bold",
                         color="#0a6b3d" if (fwd or 0) > 0 else "#5b6270")
            if ri == 0 and ci == 0:
                # 범례는 한 번만. 패널마다 매치가 다르므로 종목명이 아니라
                # **선 종류가 무엇을 뜻하는지**를 적는다.
                ax.legend(fontsize=6.8, loc="lower left", framealpha=0.9)

    n_fwd = max((len(r.get("forward_path", []))
                 for res in per_window.values()
                 for r in (res.get("results", []) if isinstance(res, dict)
                           else [])), default=0)
    fig.suptitle(
        f"{name} — 창 길이별 닮은 구간 (위에서 아래로 짧은 창 → 긴 창)\n"
        f"세로선 왼쪽은 겹친 정도 · 오른쪽은 이후 {n_fwd}거래일 "
        f"(빨강=매치, 점선=질의). 닮은 모양이 닮은 결과로 이어졌는지를 본다",
        fontsize=11.5, y=1.0, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    return _save(fig, out, verbose)


def reversion_effect(summ: pd.DataFrame, out: Path,
                     verbose: bool = True,
                     clustered: pd.DataFrame | None = None) -> Path:
    """
    급락 후 반등 — **검증을 통과한 두 신호 중 하나.**

    이 그림의 핵심은 막대 높이가 아니라 **기울기**다. 임계값을 올릴수록
    순초과가 커지면 실제 메커니즘이 있다는 뜻이다. 우연이라면 임계값과
    무관해야 한다. 그래서 급등 쪽(효과 없음)을 나란히 그려 대조군으로 삼는다.

    `clustered` 를 주면 **날짜 단위로 다시 센 값**을 같이 찍는다.
    급락은 시장 전체가 같이 빠지는 날에 몰려서 신호 877건이 날짜 563개에
    들어 있다 — 그대로 세면 효과가 부풀려진다. 보정 전과 후를 한 그림에
    같이 두는 이유는, **줄어든 만큼이 곧 뭉침의 크기**이기 때문이다.
    감추면 읽는 사람이 그 크기를 알 수 없다.
    """
    plt = setup()
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11.6, 4.8))
    x = np.arange(len(summ))
    thr = [f"|z| > {v}" for v in summ["임계값"]]

    reb = summ["급락후반등률"].to_numpy(float)
    base = summ["평상시상승률"].to_numpy(float)
    lo = reb - summ["신뢰구간하한"].to_numpy(float)
    hi = summ["신뢰구간상한"].to_numpy(float) - reb
    a1.bar(x, reb, width=0.5, color="#cfe3d4", edgecolor="#0a6b3d",
           linewidth=1.6, label="급락 다음날 반등률")
    a1.errorbar(x, reb, yerr=[lo, hi], fmt="none", ecolor="#20344a",
                elinewidth=1.6, capsize=6)
    a1.plot(x, base, "o--", color="#a3122a", linewidth=1.8, markersize=7,
            label="이 종목들의 평상시 상승률")
    # 날짜 단위 보정값 — 있으면 같이 찍는다. 줄어든 만큼이 곧 뭉침의 크기다.
    cl = None
    if clustered is not None and len(clustered):
        cl = (clustered.set_index("임계값")["급락후반등률"]
              .reindex(summ["임계값"]).to_numpy(float))
        a1.plot(x, cl, "D", color="#20344a", markersize=9, zorder=6,
                linestyle="none", label="날짜 단위로 보정한 값")
    for xi, (r, b, n) in enumerate(zip(reb, base, summ["급락n"])):
        # 라벨은 **오차막대 위**에 둔다. 막대 바로 위에 두면 캡과 겹친다.
        a1.text(xi, r + hi[xi] + 0.014, f"{r:.1%}  +{(r-b)*100:.1f}%p",
                ha="center", fontsize=8.6, fontweight="bold", color="#0a6b3d")
        if cl is not None and np.isfinite(cl[xi]):
            a1.text(xi + 0.28, cl[xi], f"{cl[xi]:.1%}", ha="left",
                    va="center", fontsize=8.4, color="#20344a",
                    fontweight="bold")
        a1.text(xi, 0.412, f"n={int(n):,}", ha="center", fontsize=8,
                color="#555")
    a1.set_xticks(x); a1.set_xticklabels(thr, fontsize=10)
    a1.set_xlim(-0.6, len(summ) - 0.2)
    a1.set_ylim(0.40, 0.65)
    a1.set_ylabel("다음날 상승 확률")
    a1.legend(fontsize=8.2, loc="upper left", framealpha=0.92)
    a1.set_title("급락 후 반등 — 효과 있음\n임계값을 올릴수록 커진다",
                 fontsize=11, fontweight="bold")

    drop = summ["급등후하락률"].to_numpy(float)
    base_dn = 1 - base
    a2.bar(x, drop, width=0.5, color="#e8eaee", edgecolor="#5b6270",
           linewidth=1.4, hatch="//", label="급등 다음날 하락률")
    a2.plot(x, base_dn, "o--", color="#a3122a", linewidth=1.8, markersize=7,
            label="평상시 하락률")
    for xi, (d, b, n) in enumerate(zip(drop, base_dn, summ["급등n"])):
        a2.text(xi, max(d, b) + 0.014, f"{d:.1%}  {(d-b)*100:+.1f}%p",
                ha="center", fontsize=8.6, fontweight="bold", color="#5b6270")
        a2.text(xi, 0.412, f"n={int(n):,}", ha="center", fontsize=8,
                color="#555")
    a2.set_xticks(x); a2.set_xticklabels(thr, fontsize=10)
    a2.set_ylim(0.40, 0.65)
    a2.legend(fontsize=8.6, loc="upper left")
    a2.set_title("급등 후 하락 — 효과 없음\n대조군", fontsize=11,
                 fontweight="bold")

    fig.suptitle("비대칭이 요점이다 — 급락은 되돌리고 급등은 되돌리지 않는다\n"
                 "급락에는 반대매매·손절 같은 강제 매도가 섞여 과도하게 밀린다",
                 fontsize=11.5, y=1.06)
    return _save(fig, out, verbose)
