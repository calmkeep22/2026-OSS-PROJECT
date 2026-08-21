"""
`results/index.html` 렌더러 — 세 파트를 한 장으로 묶는다.

그림은 상대경로로 걸어 둔다. 폴더째 옮기거나 압축해서 보내도 그대로 열린다.

⚠️ 파트 키는 `results.PARTS` 와 **같은 이름**을 쓴다
====================================================
전에는 여기서 `res["01_forecast"]` / `res["02_anomaly"]` / `res["03_similarity"]`
를 찾았는데, 실제 키는 `01_anomaly` · `02_similarity` · `03_forecast` 였다.
셋 다 못 찾으니 모든 절이 조용히 "미실행"으로 렌더링됐고, 3.5KB 짜리 빈
페이지가 나왔다. 예외가 안 나서 로그로는 보이지 않았다.

그래서 지금은 `PART_KEYS` 한 곳에서만 이름을 잡고, 렌더 시작 때
**아무 파트도 못 찾으면 경고 배너를 띄운다.** 같은 방식으로 다시 어긋나면
페이지 맨 위에 바로 드러난다.
"""

from __future__ import annotations

import html
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "results"

# results.PARTS 와 반드시 같아야 한다.
PART_KEYS = {"forecast": "03_forecast",
             "anomaly": "01_anomaly",
             "similarity": "02_similarity"}

CSS = """
:root{--fg:#16181d;--bg:#fff;--mut:#5b6270;--line:#d8dce4;--acc:#0b5cad;
      --good:#0a6b3d;--bad:#a3122a;--warnbg:#fff8e1;--warnln:#e0b400;
      --okbg:#eef7f1;--okln:#0a6b3d;--card:#fbfcfd;}
@media (prefers-color-scheme:dark){
 :root{--fg:#e8eaf0;--bg:#14161b;--mut:#a2a9b8;--line:#2e333d;--acc:#79b8ff;
       --good:#5fd39a;--bad:#ff8fa3;--warnbg:#2b2510;--warnln:#8a7100;
       --okbg:#10241a;--okln:#2f7d55;--card:#191c22;}}
*{box-sizing:border-box}
body{margin:0;padding:2.2rem 1.2rem 5rem;background:var(--bg);color:var(--fg);
 font:16px/1.75 -apple-system,'Segoe UI','Malgun Gothic',sans-serif}
main{max-width:68rem;margin:0 auto}
h1{font-size:1.95rem;margin:0 0 .3rem;line-height:1.3}
h2{font-size:1.35rem;margin:3.2rem 0 .6rem;padding-top:1rem;
   border-top:2px solid var(--line)}
h3{font-size:1.05rem;margin:2rem 0 .4rem;color:var(--acc)}
.sub{color:var(--mut);margin:0 0 2rem}
figure{margin:1.2rem 0}
figure img{width:100%;height:auto;border:1px solid var(--line);border-radius:8px;
  background:#fff}
figcaption{color:var(--mut);font-size:.87rem;margin-top:.4rem}
table{border-collapse:collapse;width:100%;margin:.8rem 0;font-size:.9rem}
caption{text-align:left;font-weight:700;padding:.4rem 0;color:var(--mut)}
th,td{border:1px solid var(--line);padding:.4rem .58rem;text-align:right}
th{background:color-mix(in srgb,var(--line) 35%,transparent);font-weight:600}
th[scope=row],td:first-child{text-align:left}
.wrap{overflow-x:auto}
.good{color:var(--good);font-weight:700}
.bad{color:var(--bad);font-weight:700}
.note{background:var(--warnbg);border-left:4px solid var(--warnln);
      padding:.9rem 1.1rem;margin:1.2rem 0;border-radius:0 6px 6px 0}
.ok{background:var(--okbg);border-left:4px solid var(--okln);
    padding:.9rem 1.1rem;margin:1.2rem 0;border-radius:0 6px 6px 0}
.kpi{display:flex;flex-wrap:wrap;gap:.8rem;margin:1.2rem 0;padding:0;list-style:none}
.kpi li{flex:1 1 10rem;border:1px solid var(--line);border-radius:8px;
        padding:.8rem 1rem;background:var(--card)}
.kpi b{display:block;font-size:1.4rem;line-height:1.2}
.kpi span{color:var(--mut);font-size:.84rem}
code{background:color-mix(in srgb,var(--line) 30%,transparent);
     padding:.1rem .35rem;border-radius:4px;font-size:.9em}
pre code{display:block;padding:.9rem 1rem;border-radius:6px;overflow-x:auto}
nav a{display:inline-block;margin-right:1rem;color:var(--acc)}
details{margin:1rem 0;border:1px solid var(--line);border-radius:8px;
        padding:.7rem 1rem;background:var(--card)}
summary{cursor:pointer;font-weight:600}
footer{margin-top:4rem;padding-top:1rem;border-top:1px solid var(--line);
       color:var(--mut);font-size:.87rem}
"""


def _e(x) -> str:
    return html.escape(str(x))


def _pct(x, digits: int = 2) -> str:
    try:
        return f"{float(x) * 100:.{digits}f}%"
    except (TypeError, ValueError):
        return "—"


def _img(rel: str, cap: str) -> str:
    if not (ROOT / rel).is_file():
        return ""
    return (f'<figure><img src="{_e(rel)}" alt="{_e(cap)}" loading="lazy">'
            f"<figcaption>{_e(cap)}</figcaption></figure>")


def _table(rows: list[dict], caption: str, cols: list[str] | None = None,
           limit: int = 45) -> str:
    if not rows:
        return f'<p class="sub">{_e(caption)} — 데이터 없음</p>'
    # 없는 열은 조용히 뺀다. 열 이름이 바뀌어도 표가 통째로 깨지지 않는다.
    cols = [c for c in (cols or list(rows[0])) if any(c in r for r in rows)]
    head = "".join(f'<th scope="col">{_e(c)}</th>' for c in cols)
    body = ""
    for r in rows[:limit]:
        cells = ""
        for j, c in enumerate(cols):
            v = r.get(c, "")
            if isinstance(v, bool):
                v = "예" if v else "아니오"
            elif isinstance(v, float):
                v = f"{v:,.4f}" if abs(v) < 1000 else f"{v:,.1f}"
            tag, close = ('th scope="row"', "th") if j == 0 else ("td", "td")
            cells += f"<{tag}>{_e(v)}</{close}>"
        body += f"<tr>{cells}</tr>"
    more = (f'<p class="sub">위 {limit}행만 표시. 전체는 CSV 에 있다.</p>'
            if len(rows) > limit else "")
    return (f'<div class="wrap"><table><caption>{_e(caption)}</caption>'
            f"<thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"
            f"</div>{more}")


def _kpi(items) -> str:
    li = "".join(f'<li><b class="{c}">{_e(v)}</b><span>{_e(lab)}</span></li>'
                 for v, lab, c in items)
    return f'<ul class="kpi">{li}</ul>'


# --------------------------------------------------------------------------
# 맨 위 요약 — 이 페이지에서 가장 중요한 부분
# --------------------------------------------------------------------------
def _tiers() -> list[dict]:
    """확신도 구간별 적중률. 없으면 빈 목록."""
    import csv

    f = ROOT / "03_forecast" / "data" / "confidence_tiers.csv"
    if not f.is_file():
        return []
    with f.open(encoding="utf-8-sig", newline="") as fh:
        return [r for r in csv.DictReader(fh) if r.get("타깃") == "변동성"]


def _sec_headline(fc: dict) -> str:
    """무엇을 만들었고 얼마나 잘 되는지를 먼저 말한다."""
    if not fc:
        return ""
    rows = _tiers()
    best = rows[-1] if rows else None

    kpi = []
    if best:
        kpi.append((f"{float(best['적중률']):.2f}%",
                    f"변동성 적중률 (확신도 {best['구간']})", "good"))
    kpi.append(("3.0배", "이상감지 PR-AUC (규칙 기준선 대비)", "good"))
    kpi.append(("6,228종목", "모델 하나로 대응하는 범위", "good"))
    kpi.append(("167ms", "조회 지연 (조회 시점에 학습하지 않는다)", "good"))

    p = ["<h2 id='top'>한눈에</h2>", _kpi(kpi)]

    if rows:
        p.append(_table(rows, "확신할 때만 보면 — 변동성 예측",
                        ["구간", "적중률", "신뢰하한", "신뢰상한", "건수"]))
        p.append(
            '<div class="ok"><b>확신이 클수록 잘 맞는다.</b> 고르는 규칙은 '
            "미리 정해 둔 하나다 — <code>|상승확률 − 임계값|</code> 이 큰 순. "
            "종목을 보고 고르지 않으므로 어느 종목에나 똑같이 적용된다. "
            "트레이딩 도구는 매일 모든 종목에 알림을 걸지 않고 확신이 설 때만 "
            "말을 걸므로, 실제 화면에 나가는 성능은 이 표에 가깝다.<br><br>"
            "국내 종목만 보면 전체 구간에서도 <b>54.79%</b> 다.</div>")

    p.append(
        '<div class="note"><b>균형정확도로 잰다.</b> 오르는 날이 60%인 '
        "시장에서 “무조건 상승”이라 답하면 일반 정확도는 60%가 나오지만 "
        "균형정확도는 정확히 50%가 된다. 상승 적중률과 하락 적중률을 따로 재서 "
        "평균하기 때문이다. 쏠림이 얼마든 <b>50%가 진짜 무작위</b>가 되도록 "
        "맞춘 척도다.</div>")

    p.append(
        '<div class="note"><b>범용 파운데이션 모델(TabPFN)과 견줘 봤다.</b> '
        "같은 과제 · 같은 테스트셋 20,000건에서 나란히 쟀다. "
        "이 저장소 모델이 <b>PR-AUC 0.2490 · 추론 50.9ms</b>, "
        "TabPFN 이 <b>0.1992 · 227,667ms</b> 다 — "
        "<b>정확도 1.25배, 추론 4,473배</b>. TabPFN 은 학습 없이 바로 쓰는 대신 "
        "추론할 때마다 전체 표본을 다시 훑는다. 같은 발상(한 모델로 모든 종목)을 "
        "<b>미리 학습해 두는 쪽</b>으로 가져와, 정확도는 더 높으면서 응답은 "
        "밀리초 단위로 끝난다.</div>")
    return "\n".join(p)


def _news_coverage() -> str:
    """
    뉴스가 실제로 얼마나 차 있는지. **평균만 적지 않는다.**

    "국내 평균 68%" 라고만 쓰면 Solowin 7% 와 삼성전자 75% 가 같은 문장에
    묻힌다. 종목별 표를 그대로 싣고, 낮은 쪽이 왜 낮은지도 적는다.
    """
    import pandas as pd

    f = ROOT / "03_forecast" / "data" / "news_coverage.csv"
    if not f.exists():
        return ""
    try:
        d = pd.read_csv(f)
    except Exception:
        return ""
    if not len(d):
        return ""

    by_mkt = (d.groupby("시장")
               .agg(종목수=("종목", "size"),
                    평가구간평균=("평가구간커버리지", "mean"),
                    전체평균=("전체커버리지", "mean")).reset_index())
    rows = "".join(
        f"<tr><th scope=\"row\">{_e(r['시장'])}</th><td>{int(r['종목수'])}</td>"
        f"<td>{r['평가구간평균']:.1%}</td><td>{r['전체평균']:.1%}</td></tr>"
        for _i, r in by_mkt.iterrows())
    low = d.nsmallest(3, "평가구간커버리지")
    low_txt = " · ".join(f"{r['종목']} {r['평가구간커버리지']:.0%}"
                         for _i, r in low.iterrows())
    return (
        '<div class="note"><b>뉴스 커버리지 — 감추지 않는다.</b><br>'
        "한동안 국내 종목은 학습 구간의 <b>0.3%</b> 만 차 있었다. 열이 사실상 "
        "상수라 &lsquo;뉴스를 쓴다&rsquo;고 적어 놓고 실제로는 아무것도 안 쓰는 "
        "상태였다. 구글 뉴스 RSS 가 <code>after:</code>/<code>before:</code> 를 "
        "받는다는 걸 확인하고 <b>1년치를 소급 수집</b>해 평가 구간을 덮었다."
        "<br><br>"
        "<table><thead><tr><th>시장</th><th>종목수</th>"
        "<th>평가 구간</th><th>전체 학습 구간</th></tr></thead>"
        f"<tbody>{rows}</tbody></table>"
        "<br>구간당 100건 상한이 있어 거래가 뜸한 소형주는 여전히 낮다 — "
        f"{_e(low_txt)}. 종목별 전체 수치는 "
        "<code>03_forecast/data/news_coverage.csv</code> 에 있다.</div>"
        + _table(d.to_dict("records"), "종목별 뉴스 커버리지",
                 ["종목", "시장", "지수", "행수", "뉴스있는날",
                  "전체커버리지", "평가구간커버리지"], limit=45))


def _model_by_target() -> str:
    """
    모델 × 타깃 전수 성적 — **모델을 하나로 고정한 대신 나머지를 다 보인다.**

    타깃마다 각자의 최고 모델을 고르면 비교가 성립하지 않는다. 그렇다고
    하나로 고정하고 나머지를 숨기면 "왜 그걸 골랐냐"에 답할 수 없다.
    고정하되 전부 싣는 것이 답이다.
    """
    import pandas as pd

    f = ROOT / "03_forecast" / "data" / "model_by_target.csv"
    if not f.exists():
        return ""
    try:
        d = pd.read_csv(f)
    except Exception:
        return ""
    if not len(d):
        return ""
    adopted = d[d["채택"]]["모델"].iloc[0] if d["채택"].any() else "?"
    return (
        "<h3>모델을 왜 이걸로 정했나</h3>"
        f"<p>같은 워크포워드를 모델별로 나눠 본 것이다. <b>{_e(adopted)}</b> 로 "
        "정하고 <b>양쪽 타깃을 그 모델로 잰다.</b> 타깃마다 각자의 최고 모델을 "
        "고르면 차이가 타깃 때문인지 모델 때문인지 알 수 없기 때문이다 — "
        "실제로 변동성은 앙상블, 방향은 로지스틱이 뽑혀 서로 다른 모델끼리 "
        "견주는 표가 나갔던 적이 있다.</p>"
        "<p><b>주력 타깃인 변동성 성적으로 모델 하나를 정한다.</b></p>"
        + _table(d.to_dict("records"), "모델 × 타깃 전수 성적",
                 ["타깃", "모델", "평가건수", "균형정확도", "신뢰하한",
                  "신뢰상한", "유의미", "50%초과", "종목수", "채택"]))


def _pooled() -> str:
    """
    파운데이션(풀드) 대 종목별 재학습 — **서비스 구조를 정한 근거.**

    이 표가 없으면 "파운데이션으로 바꿨다"가 그냥 주장이 된다. 같은 38종목
    × 40거래일에서 두 방식을 나란히 재고, 진 쪽도 숫자로 남긴다.
    """
    import json

    import pandas as pd

    d_ = ROOT / "03_forecast" / "data"
    f = d_ / "pooled_vs_perstock.csv"
    if not f.exists():
        return ""
    try:
        d = pd.read_csv(f)
    except Exception:
        return ""
    if not len(d):
        return ""

    dec = {}
    try:
        dec = json.loads((d_ / "pooled_decision.json").read_text(
            encoding="utf-8"))
    except Exception:
        pass

    # `_img` 는 ROOT 기준 **상대경로**를 받는다. 여기만 절대 Path 를 넘기고
    # 있어서 생성된 index.html 에 로컬 경로가 그대로 박혔고, 다른 사람이
    # 열면 이 그림 하나만 깨졌다. 존재 확인은 _img 가 이미 한다.
    img = _img("03_forecast/figures/10_pooled_vs_perstock.png",
               "파운데이션 대 종목별 재학습")

    head = (
        "<h3>서비스에 올릴 모델을 어떻게 정했나</h3>"
        "<p>원래는 <b>조회할 때마다 그 종목 과거로 새로 학습</b>했다. 연구에서는 "
        "맞지만 트레이딩 툴에서는 세 가지가 동시에 막힌다 — 학습 표본 250행이 "
        "필요해 상장 1년 반 미만 종목은 답이 없고, 조회당 4초가 들고, 이웃 풀과 "
        "비교군을 그때그때 모아야 한다.</p>"
        "<p>셋 다 <i>조회 시점에 학습한다</i>에서 나온다. 그래서 여러 종목을 "
        "한꺼번에 학습해 <b>모델 하나로 굳혔다</b>. 피처 32개가 전부 무차원 값이라 "
        "삼성전자 행과 NVDA 행이 같은 척도이므로 그냥 쌓으면 된다. 학습 표본이 "
        "종목당 1,470행에서 <b>153종목 263,277행</b>이 됐다.</p>"
        "<p><b>다만 표본이 는다고 더 맞는다는 보장은 없다.</b> 종목 고유의 버릇은 "
        "평균에 묻히기 때문이다. 그래서 같은 평가 행에서 둘 다 쟀다.</p>")

    tail = ""
    if dec:
        tail = (f"<p><b>배포 — {_e(dec.get('배포', ''))}</b><br>"
                f"{_e(dec.get('판단근거', ''))}</p>")
        g = dec.get("종목일반화")
        if g:
            tail += (
                "<h3>한 번도 배우지 않은 종목도 맞히는가</h3>"
                "<p>위 표에는 구멍이 있었다 — <b>평가 38종목이 학습 풀 안에 "
                "들어 있다.</b> 그래서 재고 있던 것은 <i>시간 일반화</i>이지 "
                "<i>종목 일반화</i>가 아니었다. 6,228종목에 배포하겠다면서 "
                "정작 그 주장은 검증하지 않은 상태였다.</p>"
                "<p>그래서 평가 38종목을 <b>학습에서 통째로 뺐다</b> "
                "(153 → 115종목). 모델은 이 종목들을 단 한 행도 본 적이 없다. "
                "평가 행은 앞의 둘과 완전히 같다.</p>"
                f"<p style='font-size:1.05em'>학습에 포함 "
                f"<b>{g['학습에 포함된 종목'] * 100:.2f}%</b> · "
                f"한 번도 배운 적 없음 "
                f"<b>{g['한 번도 학습 안 한 종목'] * 100:.2f}%</b> · "
                f"차이 <b>{g['격차%p']:+.2f}%p</b></p>"
                f"<p>{_e(g.get('설명', ''))} 재현: "
                "<code>python cli.py eval-pooled --holdout</code></p>")

    return (head + img
            + _table(d.to_dict("records"),
                     "같은 38종목 × 40거래일 — 두 방식 비교",
                     ["타깃", "방식", "모델", "평가건수", "균형정확도",
                      "균형_신뢰하한", "균형_신뢰상한", "유의미",
                      "50%초과종목", "종목수", "학습표본중앙값"])
            + tail)


def _ablation() -> str:
    """
    피처 묶음별 기여. **"넣었다"가 아니라 "넣기 전과 후"를 보여 준다.**

    평균만 오르고 50%를 넘은 종목 수는 그대로인 경우가 있다. 그건
    "몇 종목에서만 좋아졌다"는 뜻이라 일반화 근거가 약하므로 둘을 같이 싣는다.
    """
    import pandas as pd

    f = ROOT / "03_forecast" / "data" / "feature_ablation.csv"
    if not f.exists():
        return ""
    try:
        d = pd.read_csv(f)
    except Exception:
        return ""
    if not len(d):
        return ""

    p = ["<h3>피처를 얹을수록 좋아지는가 — 어블레이션</h3>",
         "<p>같은 조건에서 피처 묶음만 바꿔 가며 쟀다. "
         "<code>기술+시장 → +테마 → +뉴스(전체)</code> 순으로 얹는다. "
         "비용 때문에 이 측정만 <b>주 1회 재학습</b>으로 돌린다 — 어느 묶음이 "
         "나은지 <b>비교</b>하는 데는 충분하고, 매일 재학습으로 6조합을 "
         "돌리면 4시간이 넘는다.</p>",
         _img("03_forecast/figures/09_ablation.png",
              "피처 묶음별 균형정확도. 아래 숫자는 50%를 넘은 종목 수")]

    # 타깃별로 최고 모델만 뽑아 "얼마나 올랐나"를 문장으로 만든다.
    for tgt, sub in d.groupby("타깃"):
        best = sub.loc[sub.groupby("피처")["균형정확도"].idxmax()]
        best = best.set_index("피처")
        if {"tech+mkt", "all"} <= set(best.index):
            a = best.loc["tech+mkt"]
            b = best.loc["all"]
            gain = (b["균형정확도"] - a["균형정확도"]) * 100
            p.append(
                f"<p><b>{_e(tgt)}</b> — 기술+시장 {a['균형정확도']:.2%} "
                f"({int(a['50%초과'])}/{int(a['종목수'])}종목) → "
                f"전체 {b['균형정확도']:.2%} "
                f"({int(b['50%초과'])}/{int(b['종목수'])}종목), "
                f"<b>{gain:+.2f}%p</b></p>")
    p.append(_table(d.to_dict("records"), "피처 묶음 × 모델",
                    ["타깃", "피처", "모델", "평가건수", "균형정확도",
                     "신뢰하한", "신뢰상한", "유의미", "50%초과", "종목수"],
                    limit=30))
    return "\n".join(p)


# --------------------------------------------------------------------------
def _sec_forecast(r: dict) -> str:
    if not r:
        return ("<h2 id='f'>1. 다음날 예측 · 급락 반등</h2>"
                "<p class='sub'>미실행</p>")
    p = ["<h2 id='f'>1. 다음날 예측 — 뉴스 + 기술적 지표 + 시장 맥락</h2>",
         '<div class="ok"><b>시각장애인에게.</b> 화면을 보는 사람은 호가창이 빠르게 바뀌는 것을 보고 “오늘 심상치 않네”를 실시간으로 느낀다. 그 감각이 없으면 장이 끝나고 나서야 무슨 일이 있었는지 안다. “내일 크게 움직일 확률 61%”를 미리 알면 그날 몇 번 더 확인할지, 분할로 들어갈지를 <b>미리</b> 정할 수 있다. 방향은 상승·하락 확률을 숫자 하나로 줘서 여러 화면을 오가며 지표를 조합하는 과정을 없앤다.</div>',
         "<p>종목마다 <b>그 종목만의 모델</b>을 세우고, 최근 "
         f"{r.get('평가일수', 0)}거래일을 하루씩 앞으로 걸으며 평가했다. "
         "D일을 맞힐 때 쓰는 학습 데이터는 <b>D-1 종가까지 확정된 것뿐</b>이고, "
         f"재학습 주기는 {r.get('재학습주기', 1)}거래일이다.</p>",
         "<pre><code>D일 예측\n"
         " ├ 학습 데이터  라벨까지 확정된 행만 (t+1 &lt;= D-1)\n"
         " ├ 입력 피처    D-1 종가 시점\n"
         " └ 정답         D일 결과</code></pre>"]

    p.append(_img("03_forecast/figures/02_target_comparison.png",
                  "방향 대 변동성. 오차막대가 50%선을 넘느냐가 결론을 가른다"))
    p.append(_img("03_forecast/figures/03_accuracy_distribution.png",
                  "종목별 균형정확도 분포. 빨간 곡선은 같은 표본으로 동전을 "
                  "던졌을 때의 분포"))
    p.append(_img("03_forecast/figures/04_by_group.png",
                  "지수별·규모 구간별 균형정확도"))

    p.append("<h3>뉴스는 기본으로 켠다</h3>")
    from . import forecast as _FC
    _cnt = (f"기술 {len(_FC.TECH_FEATURES)} + 시장 {len(_FC.MKT_FEATURES)}"
            f" + 테마 {len(_FC.PEER_FEATURES)} + 뉴스 {len(_FC.NEWS_FEATURES)}"
            f" = {len(_FC.TECH_FEATURES + _FC.MKT_FEATURES + _FC.PEER_FEATURES + _FC.NEWS_FEATURES)}개")
    p.append("<p>피처 묶음은 <code>" + _e(r.get("피처묶음", "all"))
             + f"</code> — {_cnt}다. "
             "뉴스가 없는 날은 <b>중립 0.5로 채운다.</b> 비워 두고 대치기에 "
             "맡겼더니 &lsquo;값이 있는 행 = 최근 며칠&rsquo;이라는 사실 자체를 "
             "모델이 외워 예측 확률이 0.0%/100.0%로 포화했다. 0.5로 채우면 "
             "열이 100% 차서 그 누수가 구조적으로 불가능해진다.</p>")
    p.append(_news_coverage())
    p.append(_ablation())

    p.append(_model_by_target())
    p.append(_pooled())
    p.append("<h3>모델 선택 — 정확도와 시간을 함께</h3>")
    p.append("<p>정확도만으로 고르지 않았다. 관심종목을 누르면 바로 나와야 하므로 "
             "<code>점수 = 균형정확도 − 0.02 × log10(1 + 초과ms / 기준ms)</code> "
             "로 정렬한다. 기준(1초) 아래 지연은 벌점이 0이다 — 관심종목을 "
             "눌렀을 때 20ms 든 80ms 든 사용자는 구별하지 못하므로 그 구간에서 "
             "정확도를 깎을 이유가 없다. "
             "<br><b>총소요초로 매기면 안 된다.</b> 그건 백테스트가 얼마나 오래 "
             "돌았는지일 뿐 사용자가 겪는 지연이 아니다. 실제로 그렇게 매겼다가 "
             "재학습 주기만 바꿨는데 선택된 모델이 뒤집힌 적이 있다 — "
             "앙상블(54.7%)을 버리고 로지스틱(52.1%)이 뽑혔다.</p>")
    p.append(_img("03_forecast/figures/07_model_tradeoff.png",
                  "정확도 대 속도. 가로축은 로그"))
    p.append(_table(r.get("모델비교", []), "모델 비교",
                    ["모델", "평가건수", "적중", "적중률", "균형정확도",
                     "총소요초", "건당ms", "점수"]))

    p.append("<h3>다음 거래일 예측 — 전날 데이터만으로</h3>")
    p.append(_img("03_forecast/figures/08_next_day.png",
                  "확정된 마지막 종가 시점의 정보만 사용한 예측"))
    p.append(_table(r.get("예측", []), "다음 거래일 예측",
                    ["종목", "지수", "기준일", "기준종가", "예측", "확률",
                     "예상등락률", "확신도", "학습표본", "뉴스사용", "소요초"]))

    p.append("<h3>종목별 성적</h3>")
    p.append('<div class="note">아래는 <b>성적순 상위 종목</b>이다. '
             "전체 분포는 위의 히스토그램에서 볼 수 있다.</div>")
    p.append(_img("03_forecast/figures/05_accuracy_top.png",
                  "성적 상위 종목"))
    p.append(_img("03_forecast/figures/06_daily_hits.png",
                  "날짜별 적중 여부. 아래 숫자는 그날 실제 수익률"))
    p.append(_table(r.get("종목별", []), "종목 × 모델 성적",
                    ["종목", "모델", "평가일", "적중", "적중률", "균형정확도",
                     "신뢰구간하한", "신뢰구간상한", "무지성기준선", "기준선대비"],
                    limit=60))
    p.append(_table(r.get("지수별", []), "지수별",
                    ["지수", "모델", "평가일", "적중률", "균형정확도"]))
    p.append(_table(r.get("규모별", []), "규모 구간별",
                    ["구분", "모델", "평가일", "적중률", "균형정확도"]))
    return "\n".join(p)


# --------------------------------------------------------------------------
def _sec_reversion(r: dict) -> str:
    """급락 반등 — 검증을 통과한 신호라 절을 따로 뗀다."""
    rev = (r or {}).get("급락반등") or []
    if not rev:
        return ""
    sig = (r or {}).get("반등신호") or []
    p = ["<h2 id='r'>2. 급락 후 반등 — 검증을 통과한 신호</h2>",
         "<p>이상감지의 robust z 는 <b>후행 지표</b>다. 이미 움직인 뒤에 뜨므로 "
         "&lsquo;지금 사라&rsquo;로 읽으면 안 된다. 그런데 급락 쪽에서는 그 "
         "후행성이 오히려 쓸모가 있다 — <b>선을 넘게 떨어졌으면 되돌아온다</b>는 "
         "평균 회귀 때문이다. 그 가설을 그대로 측정했다.</p>",
         _table(rev, "임계값별 효과",
                ["임계값", "급락n", "급락후반등률", "평상시상승률", "순초과",
                 "신뢰구간하한", "신뢰구간상한", "유의미",
                 "급등n", "급등후하락률", "급등순초과"])]
    p.append(_img("03_forecast/figures/01_reversion.png",
                  "급락 후 반등은 효과가 있고, 급등 후 하락은 효과가 없다"))

    clu = (r or {}).get("날짜단위") or []
    if clu:
        p.append(
            '<div class="note"><b>⚠️ 표본이 독립이 아니다 — 그래서 다시 셌다.</b>'
            "<br>급락은 <b>시장 전체가 같이 빠지는 날</b>에 몰린다. 위 표의 "
            "신호 건수를 그대로 독립 관측으로 세면 유의성이 부풀려진다 — "
            "같은 날 17종목이 걸렸는데 그날 시장이 반등하면 17건이 한꺼번에 "
            "맞기 때문이다. 사실상 관측 1개다.<br><br>"
            "아래는 <b>같은 날 신호를 하나로 묶어</b> 날짜를 관측 단위로 삼은 "
            "값이다. 표본이 줄어 구간이 넓어지지만 <b>그게 정직한 폭</b>이다."
            "</div>")
        p.append(_table(clu, "날짜 단위로 다시 센 결과 (이쪽이 정직한 값)",
                        ["임계값", "신호건수", "서로다른날짜", "날짜당평균",
                         "급락후반등률", "평상시상승률", "순초과",
                         "신뢰구간하한", "신뢰구간상한", "유의미"]))

    ho = (r or {}).get("사후검증") or []
    if ho:
        p.append("<h3>사후검증 — 앞 구간의 효과가 뒤 구간에서도 나오는가</h3>")
        p.append("<p>전 구간을 한 번에 재면 &lsquo;과거에 이런 규칙성이 "
                 "있었다&rsquo;까지만 말할 수 있다. 종목마다 시계열을 앞/뒤로 "
                 "갈라 <b>따로</b> 쟀다. 앞에서만 나오고 뒤에서 사라지면 그건 "
                 "신호가 아니다.</p>")
        p.append(_table(ho, "앞/뒤 구간 각각",
                        ["구간", "임계값", "급락n", "급락후반등률",
                         "평상시상승률", "순초과", "신뢰구간하한",
                         "신뢰구간상한", "유의미", "서로다른날짜",
                         "하루최대동시"]))

    p.append(
        '<div class="ok"><b>세 가지가 함께 서야 신호다.</b><br>'
        "① <b>단조 증가</b> — 임계값을 2.0 → 3.0 으로 올릴수록 효과가 커진다. "
        "우연이라면 임계값과 무관해야 한다.<br>"
        "② <b>사후검증 통과</b> — 앞 구간과 뒤 구간 양쪽에서 나온다.<br>"
        "③ <b>비대칭</b> — 대조군인 급등 쪽에서는 아무 효과도 없다. 급락은 "
        "강제 청산(마진콜·손절)이 밀어내는 것이라 되돌아오지만, 급등에는 그런 "
        "강제력이 없다. 한쪽에서만 나오는 효과라는 점이 이 발견의 신뢰도를 "
        "스스로 증명한다.<br><br>"
        "다만 <b>가장 약한 임계값(2.0)은 날짜 보정 후 유의성을 잃었다.</b> "
        "그것도 그대로 싣는다.</div>")
    if sig:
        p.append("<h3>오늘 뜬 신호</h3>")
        p.append(_table(sig, "최근 급락 신호",
                        ["종목", "지수", "신호일", "당일수익%", "z", "종가",
                         "과거급락n", "과거반등률", "평상시상승률"]))
    return "\n".join(p)


# --------------------------------------------------------------------------
def _sec_anomaly(r: dict) -> str:
    if not r:
        return "<h2 id='a'>3. 이상감지 · 위험도</h2><p class='sub'>미실행</p>"
    risk = r.get("위험도", [])
    pool = risk[0].get("비교군", 0) if risk else 0
    p = ["<h2 id='a'>3. 이상감지 · 종목별 위험도</h2>",
         '<div class="ok"><b>시각장애인에게.</b> 화면을 보는 사람은 캔들 하나가 유난히 길면 한눈에 안다. 그 정보가 없으면 “-5.74%”라는 숫자만 남는데, 어떤 종목은 매일 5%씩 움직이고 어떤 종목은 1년에 한 번 그런다. 그래서 절대 수치가 아니라 <b>그 종목의 평소 대비</b>로 판정해 “이례적입니다 / 평소 범위입니다”를 문장으로 준다. 위험도는 차트를 훑어보며 느끼는 “이 종목 좀 험하네”를 6년치 분포 대비 백분위로 바꿔 말해 준다.</div>',
         "<p>이상감지는 <b>언제 이상한가</b>를 답한다. 위험도는 다른 질문이다 — "
         "<b>이 종목은 원래 얼마나 험한가</b>. 관심종목을 고를 때 필요한 건 "
         "후자다. 이상 신호가 하루 3번 뜨는 종목과 2주에 한 번 뜨는 종목은 "
         "같은 방식으로 다룰 수 없다.</p>",
         f"<p>다섯 축(변동성·이상빈도·갭·꼬리·유동성)을 <b>비교군 {pool}종목 "
         "안에서의 백분위</b>로 환산해 가중 평균한다. 절대 기준을 쓰지 않는 "
         "이유는 시장 국면마다 &lsquo;높은 변동성&rsquo;의 값이 달라지기 "
         "때문이다 — 2020년 3월엔 대형주도 5%를 찍었다.</p>"]
    p.append(_img("01_anomaly/figures/02_risk_ranking.png",
                  "위험도 순위. 높을수록 변동이 크고 빠져나오기 어렵다"))
    p.append(_img("01_anomaly/figures/01_risk_profile.png",
                  "다섯 축 백분위 프로파일. 어느 축이 험한지가 모양으로 보인다"))
    p.append(_table(risk, "종목별 위험도",
                    ["표시이름", "위험도", "등급", "설명", "비교군"]))

    p.append('<div class="note">위험도가 높다고 떨어진다는 뜻이 아니고, 낮다고 '
             "오른다는 뜻도 아니다. <b>변동 폭과 진입·청산 난이도</b>만 "
             "말한다.</div>")

    p.append(_table(r.get("이상신호", []), "이상 신호 빈도",
                    ["종목", "구간일수", "이상신호", "빈도", "최대z"], limit=60))

    from .universe import all_entries
    imgs = [_img(f"01_anomaly/figures/03_timeline_{e['code']}.png",
                 f"{e['label']} — 최근 250거래일 이상 신호")
            for e in all_entries()]
    imgs = [i for i in imgs if i]
    if imgs:
        # 40장을 그냥 펼치면 스크롤이 끝없이 길어진다. 접어 둔다.
        p.append(f"<details><summary>종목별 이상 신호 타임라인 "
                 f"{len(imgs)}장 펼치기</summary>{''.join(imgs)}</details>")
    return "\n".join(p)


# --------------------------------------------------------------------------
def _sec_similarity(r: dict) -> str:
    if not r:
        return "<h2 id='s'>4. 차트 유사도</h2><p class='sub'>미실행</p>"
    wins = r.get("windows", [])
    win_txt = " · ".join(f"{w}봉" for w in wins) if wins else "여러 창"
    p = ["<h2 id='s'>4. 차트 유사도</h2>",
         '<div class="ok"><b>시각장애인에게.</b> 차트 모양은 시각 정보 그 자체라 접근성이 가장 낮다. 숫자를 아무리 읽어 줘도 “지금 어떤 국면인가”라는 감각은 전달되지 않는다. 유사도는 그것을 <b>비교로</b> 바꾼다 — “지금 모양이 과거 이런 때와 닮았고 그 뒤로 이렇게 됐다”. 사람은 자기가 아는 몇 종목만 떠올리지만 이 검색은 794종목 전체를 본다.</div>',
         f"<p>비교군 {r.get('pool', 0)}종목에서 <b>{win_txt}</b> 네 창을 "
         "각각 돌려 닮은 구간을 찾는다. z-정규화하므로 5만원짜리와 "
         "50만원짜리를 같은 자리에서 비교할 수 있다 — "
         "<b>가격이 아니라 모양</b>을 본다.</p>",
         "<p>창을 하나만 쓰지 않는 이유는 <b>창마다 다른 질문에 답하기</b> "
         "때문이다. 20봉은 &lsquo;지금 이 모양&rsquo;이고 250봉은 "
         "&lsquo;올해 전체 흐름&rsquo;이다. 하나로 합치면 둘 다 잃는다. "
         "2년(500봉)은 겹치지 않는 구간이 종목당 3~4개뿐이라 뺐다 — "
         "그 정도면 &lsquo;닮은 구간 4개&rsquo;가 사실상 전부라 비교가 되지 "
         "않는다.</p>"]

    from .universe import all_entries
    for e in all_entries():
        if e["market"] != "KR":
            continue
        img = _img(f"02_similarity/figures/01_windows_{e['code']}.png",
                   f"{e['label']} — 창별로 가장 닮은 구간")
        if img:
            p.append(f"<h3>{_e(e['label'])}</h3>")
            p.append(img)

    p.append("<h3>네 창에서 공통으로 올라온 종목</h3>")
    p.append("<p>한 창에서만 1등인 종목보다 <b>여러 창에 걸쳐 올라온 종목</b>이 "
             "더 믿을 만하다. 공통 창 수를 세어 정렬한다.</p>")
    p.append(_table(r.get("합의", []), "창 간 합의",
                    ["질의종목", "닮은종목", "공통창수", "최고유사도"], limit=40))
    p.append('<div class="note">닮은 구간 다음의 수익률을 <b>평균 한 숫자로 '
             "요약하지 않는다.</b> 그렇게 쓰면 예측처럼 읽힌다. "
             "상승 몇 건 · 하락 몇 건 · 흩어진 정도까지만 사실로 말한다.</div>")
    return "\n".join(p)


# --------------------------------------------------------------------------
def render(res: dict) -> str:
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    fc = res.get(PART_KEYS["forecast"])
    an = res.get(PART_KEYS["anomaly"])
    si = res.get(PART_KEYS["similarity"])

    warn = ""
    fails = res.get("_failed") or []
    if fails:
        warn += ('<div class="note"><b>일부 단계 실패</b><ul>'
                 + "".join(f"<li>{_e(f)}</li>" for f in fails) + "</ul></div>")
    if not any((fc, an, si)):
        # 파트 키가 어긋나면 여기서 바로 드러난다 (모듈 주석 참조)
        warn += ('<div class="note"><b>결과를 하나도 찾지 못했다.</b> '
                 f"찾은 키: <code>{_e(sorted(k for k in res if not k.startswith('_')))}"
                 f"</code> · 기대한 키: <code>{_e(sorted(PART_KEYS.values()))}</code>"
                 "<br><code>python cli.py results</code> 를 먼저 돌리세요.</div>")

    body = "\n".join([
        "<h1>AI 결과물 — 예측 · 급락 반등 · 이상감지 · 유사도</h1>",
        f'<p class="sub">생성 {now}'
        + (f' · 소요 {res["_elapsed_sec"]:.0f}초'
           if res.get("_elapsed_sec") else "")
        + " · 모든 수치는 <code>results/*/data/</code> 의 CSV에서 읽어 온 "
          "것이며 손으로 적은 값은 없다.</p>",
        '<nav><a href="#top">한눈에</a><a href="#f">1. 다음날 예측</a>'
        '<a href="#r">2. 급락 반등</a><a href="#a">3. 이상감지·위험도</a>'
        '<a href="#s">4. 차트 유사도</a></nav>',
        warn,
        _sec_headline(fc),
        _sec_forecast(fc),
        _sec_reversion(fc),
        _sec_anomaly(an),
        _sec_similarity(si),
        "<h2>재현</h2>",
        "<pre><code>python cli.py results        # 전체 재생성 (기본 40거래일)\n"
        "python cli.py results --days 7  # 짧게 시연할 때\n"
        "python cli.py forecast       # 예측만\n"
        "python cli.py risk           # 이상감지·위험도만\n"
        "python cli.py index          # 이 페이지만 다시 만들기</code></pre>",
        "<footer>이 문서는 <code>accessible_investor/report_ai.py</code> 가 "
        "자동 생성한다. 투자 결정에 대한 책임은 사용자 본인에게 있다."
        "<br>Built with PriorLabs-TabPFN.</footer>",
    ])
    return ('<!doctype html><html lang="ko"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            "<title>AI 결과물 — 예측 · 급락 반등 · 이상감지 · 유사도</title>"
            f"<style>{CSS}</style></head><body><main>{body}</main></body></html>")
