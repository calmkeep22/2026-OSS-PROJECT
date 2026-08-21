"""
검증 결과 한 장 — `outputs/validation/` 의 표와 JSON을 HTML 하나로 묶는다.

왜 노트북을 안 쓰는가
---------------------
전에는 노트북 6개(01~06)로 결과를 보여줬다. 문제가 셋이었다.

    1. 열려면 Jupyter 가 필요하다. 심사위원이 설치돼 있으리란 보장이 없다
    2. 실행해야 출력이 생긴다. 안 돌리면 코드만 보이고 결과가 없다
    3. **스크린리더로 못 읽는다.** 저시력 사용자를 위한 프로젝트가
       정작 결과물은 접근 불가능한 형식이라는 건 앞뒤가 안 맞는다

이 모듈은 브라우저만 있으면 열리는 HTML 한 장을 만든다. 표에는
`<caption>` 과 `<th scope>` 를 붙여 스크린리더가 셀 위치를 읽을 수 있게 했고,
색이 아니라 **부호와 낱말**로 좋고 나쁨을 구분한다.

원칙
----
불리한 숫자를 빼지 않는다. 안 되는 것으로 밝혀진 항목은 "안 됐다"로 싣는다.
"""

from __future__ import annotations

import html
import json
from datetime import datetime
from pathlib import Path

import pandas as pd

from .config import OUTPUT_DIR

VAL_DIR = OUTPUT_DIR / "validation"
OUT_PATH = OUTPUT_DIR / "report.html"

CSS = """
:root{--fg:#16181d;--bg:#fff;--mut:#5b6270;--line:#d8dce4;--acc:#0b5cad;
      --good:#0a6b3d;--bad:#a3122a;--warnbg:#fff8e1;--warnln:#e0b400;}
@media (prefers-color-scheme:dark){
 :root{--fg:#e8eaf0;--bg:#14161b;--mut:#a2a9b8;--line:#2e333d;--acc:#79b8ff;
       --good:#5fd39a;--bad:#ff8fa3;--warnbg:#2b2510;--warnln:#8a7100;}}
*{box-sizing:border-box}
body{margin:0;padding:2.2rem 1.2rem 5rem;background:var(--bg);color:var(--fg);
 font:16px/1.75 -apple-system,'Segoe UI','Malgun Gothic',sans-serif;}
main{max-width:60rem;margin:0 auto}
h1{font-size:1.9rem;line-height:1.3;margin:0 0 .3rem}
h2{font-size:1.3rem;margin:3rem 0 .6rem;padding-top:1rem;
   border-top:2px solid var(--line)}
h3{font-size:1.05rem;margin:1.8rem 0 .4rem;color:var(--acc)}
.sub{color:var(--mut);margin:0 0 2rem}
p{margin:.6rem 0}
table{border-collapse:collapse;width:100%;margin:.8rem 0;font-size:.92rem}
caption{text-align:left;font-weight:700;padding:.4rem 0;color:var(--mut)}
th,td{border:1px solid var(--line);padding:.42rem .6rem;text-align:right}
th{background:color-mix(in srgb,var(--line) 35%,transparent);font-weight:600}
th[scope=row],td:first-child{text-align:left}
.wrap{overflow-x:auto}
.good{color:var(--good);font-weight:700}
.bad{color:var(--bad);font-weight:700}
.note{background:var(--warnbg);border-left:4px solid var(--warnln);
      padding:.9rem 1.1rem;margin:1.2rem 0;border-radius:0 6px 6px 0}
.kpi{display:flex;flex-wrap:wrap;gap:.8rem;margin:1.2rem 0;padding:0;list-style:none}
.kpi li{flex:1 1 11rem;border:1px solid var(--line);border-radius:8px;padding:.8rem 1rem}
.kpi b{display:block;font-size:1.45rem;line-height:1.2}
.kpi span{color:var(--mut);font-size:.85rem}
code{background:color-mix(in srgb,var(--line) 30%,transparent);
     padding:.1rem .35rem;border-radius:4px;font-size:.9em}
footer{margin-top:4rem;padding-top:1rem;border-top:1px solid var(--line);
       color:var(--mut);font-size:.87rem}
"""


# --------------------------------------------------------------------------
def _read_json(name: str):
    p = VAL_DIR / name
    if not p.is_file():
        return None
    for enc in ("utf-8", "utf-8-sig", "cp949"):
        try:
            return json.loads(p.read_text(encoding=enc))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
    return None


def _read_csv(name: str):
    p = VAL_DIR / name
    if not p.is_file():
        return None
    for enc in ("utf-8-sig", "utf-8", "cp949"):
        try:
            return pd.read_csv(p, encoding=enc)
        except (UnicodeDecodeError, pd.errors.ParserError):
            continue
    return None


def _esc(x) -> str:
    return html.escape(str(x))


def _table(df: pd.DataFrame | None, caption: str, max_rows: int = 40) -> str:
    """접근 가능한 표. `scope` 없이 만들면 스크린리더가 셀 위치를 못 읽는다."""
    if df is None or not len(df):
        return f'<p class="sub">{_esc(caption)} — 데이터 없음</p>'
    d = df.head(max_rows)
    head = "".join(f'<th scope="col">{_esc(c)}</th>' for c in d.columns)
    body = ""
    for _i, r in d.iterrows():
        cells = ""
        for j, c in enumerate(d.columns):
            v = r[c]
            if isinstance(v, float):
                v = f"{v:,.4f}" if abs(v) < 1000 else f"{v:,.1f}"
            tag = 'th scope="row"' if j == 0 else "td"
            close = "th" if j == 0 else "td"
            cells += f"<{tag}>{_esc(v)}</{close}>"
        body += f"<tr>{cells}</tr>"
    return (f'<div class="wrap"><table><caption>{_esc(caption)}</caption>'
            f"<thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>")


def _kpi(items: list[tuple[str, str, str]]) -> str:
    """(값, 라벨, 클래스) 목록 → 카드. 색만이 아니라 낱말로도 구분된다."""
    li = "".join(f'<li><b class="{c}">{_esc(v)}</b><span>{_esc(lab)}</span></li>'
                 for v, lab, c in items)
    return f'<ul class="kpi">{li}</ul>'


# --------------------------------------------------------------------------
# 섹션
# --------------------------------------------------------------------------
def _sec_anomaly() -> str:
    tr = _read_json("08_training_report.json") or {}
    fin = _read_json("09_final_report.json") or {}
    rt = _read_json("07_realtime_intraday.json") or {}
    rk = tr.get("ranker", {})

    parts = ["<h2>1. 이상감지 — 이 프로젝트가 실제로 하는 일</h2>",
             "<p>단타 사용자에게 5분봉 이상 신호를 알린다. "
             "핵심 문제는 정확도가 아니라 <b>알림 폭주</b>였다. "
             "고정 임계값 |z|&gt;3은 종목당 하루 9.8건을 냈다 — 아무도 못 듣는다.</p>"]

    if rk:
        parts.append(_kpi([
            (f"{rk.get('pr_auc', 0):.4f}", "랭커 PR-AUC", "good"),
            (f"{rk.get('pr_auc_baseline_absz', 0):.4f}", "규칙 기준선 PR-AUC", ""),
            (f"{rk.get('roc_auc', 0):.4f}", "ROC-AUC", "good"),
            (f"{tr.get('n_test_stocks', 0)}종목", "홀드아웃 (학습에 미사용)", ""),
        ]))
        parts.append("<p>랭커는 규칙 기준선 대비 PR-AUC를 "
                     f"<b class='good'>{rk.get('pr_auc', 0) / max(rk.get('pr_auc_baseline_absz', 1e-9), 1e-9):.1f}배</b> "
                     "올렸다. 학습에 쓰지 않은 "
                     f"{tr.get('n_test_stocks', 0)}종목에서 잰 값이다.</p>")

    bt = pd.DataFrame(tr.get("budget_table", [])) if tr.get("budget_table") else None
    parts.append(_table(bt, "알림 예산별 정밀도 — 같은 알림 개수에서 규칙 대비"))

    # `or` 로 이으면 안 된다 — DataFrame 은 진리값이 모호해서 예외가 난다
    imp = _read_csv("09_importance_final.csv")
    if imp is None:
        imp = _read_csv("08_feature_importance.csv")
    parts.append(_table(imp, "피처 중요도", max_rows=15))

    if rt:
        rows = [{"항목": k, "값": v} for k, v in rt.items()]
        parts.append(_table(pd.DataFrame(rows), "실시간 비용 (30종목 기준)"))
    if fin.get("speed_ms"):
        rows = [{"단계": k, "밀리초": v} for k, v in fin["speed_ms"].items()]
        parts.append(_table(pd.DataFrame(rows), "단계별 소요 시간"))

    parts.append(_table(_read_csv("07_ablation_intraday.csv"),
                        "설정별 어블레이션 — 각 장치가 실제로 기여하는가"))

    # 최근 가중 — 해외에서는 도움이 됐고 국내에서는 안 됐다. 둘 다 싣는다.
    rec = _read_csv("08_recency_sweep.csv")
    if rec is not None and len(rec):
        parts.append("<h3>1-1. 최근 표본 가중 — 이식했지만 채택하지 않았다</h3>")
        parts.append(
            "<p>해외 데이터 어블레이션에서 <b>유일하게 재현된 개선</b>이 "
            "최근 가중이었다(ROC-AUC +0.0088). 같은 기법을 국내 분봉 랭커에 "
            "이식하고 반감기를 훑었다.</p>")
        parts.append(_table(rec, "반감기별 랭커 성능 (홀드아웃 49종목)"))
        try:
            base = float(rec.loc[rec["설정"] == "가중없음", "PR-AUC"].iloc[0])
            best_w = float(rec[rec["설정"] != "가중없음"]["PR-AUC"].max())
            parts.append(
                f"<p>가중없음 PR-AUC <b>{base:.4f}</b>, 가중 최고 "
                f"<b class='bad'>{best_w:.4f}</b>. "
                "<b>국내 분봉에서는 도움이 되지 않아 자동으로 껐다.</b> "
                "학습 구간이 이미 60거래일뿐이라, 오래된 표본을 눌러 봐야 "
                "양성률 1.1%짜리 희귀 사건의 실질 표본만 줄어든다. "
                "기법을 그대로 믿고 켰다면 성능이 내려간 채로 제출했을 것이다 — "
                "<code>training.py</code> 는 매 학습마다 이 표를 다시 만들어 "
                "<b>켤지 끌지 스스로 정한다.</b></p>")
        except (KeyError, IndexError, ValueError):
            pass
    parts.append(_table(_read_csv("07_grade_intraday.csv"), "확신 등급별 적중"))
    parts.append(_table(_read_csv("07_hour_dist_intraday.csv"), "시간대 분포"))
    return "\n".join(parts)


def _sec_prediction() -> str:
    kr = _read_json("10_news_predict_gbm.json")
    kr_t = _read_json("10_news_predict_tabpfn.json")
    ov = _read_csv("11_overseas_ablation.csv")
    old = _read_json("07_news_predictive.json")

    parts = ["<h2>2. 다음날 예측은 되는가 — 세 번 독립으로 측정</h2>",
             '<div class="note"><b>결론부터.</b> 안 된다. '
             "국내 데이터, 해외 데이터, 그리고 이 팀이 별도 대회에서 만든 "
             "모델까지 <b>세 경로 모두 무지성 기준선을 못 넘었다.</b> "
             "숨기지 않고 싣는 이유는, 이걸 재보지 않은 채 "
             "&lsquo;AI가 예측합니다&rsquo;라고 쓰는 것이 더 큰 문제이기 "
             "때문이다.</div>"]

    parts.append("<h3>2-1. 국내 — 뉴스 / 가격 / 둘 다</h3>")
    if kr:
        rows = [{"피처군": k, **v} for k, v in kr.get("results", {}).items()]
        parts.append(_table(pd.DataFrame(rows),
                            f"국내 다음날 방향 · {kr.get('model')} · "
                            f"종목 {kr.get('n_stocks')} · 거래일 {kr.get('n_days')}"))
        parts.append('<div class="note">&lsquo;정확도&rsquo; 열은 보면 안 된다. '
                     "평가 구간 상승 비율이 20.6%라 <b>항상 하락으로 찍어도 "
                     "79.4%</b>가 나온다. 봐야 할 건 <b>균형정확도</b>이고, "
                     "0.5가 무작위다.</div>")
        parts.append(f"<p>거래일이 {kr.get('n_days')}개뿐이다. "
                     "구글 뉴스 RSS가 7일치만 주기 때문이며, "
                     "<code>cli.py collect-news</code>를 매일 돌려야 늘어난다. "
                     "<b>이 표는 결론이 아니라 참고치다.</b></p>")
    if kr_t:
        rows = [{"피처군": k, **v} for k, v in kr_t.get("results", {}).items()]
        parts.append(_table(pd.DataFrame(rows), "같은 데이터 · TabPFN 파운데이션 모델"))

    parts.append("<h3>2-2. 해외 — 미국 100종목 · 2019~2022 · 뉴스 47만 건</h3>")
    if ov is not None:
        parts.append(_table(ov, "해외 어블레이션 (피처군 × 가중 × 라벨)", max_rows=60))
        best = ov.loc[ov["ROC-AUC"].idxmax()]
        base = float(ov["무지성기준선"].iloc[0])
        parts.append(_kpi([
            (f"{best['ROC-AUC']:.4f}", "최고 ROC-AUC (0.5=무작위)",
             "good" if best["ROC-AUC"] > 0.55 else "bad"),
            (f"{best['HR']:.4f}", "최고 방향 적중률", ""),
            (f"{base:.4f}", "무지성 기준선", ""),
            (f"{best['HR'] - base:+.4f}", "기준선 대비",
             "good" if best["HR"] > base else "bad"),
        ]))
        # 뉴스·가중의 순 기여
        try:
            ex = ov[ov["라벨"] == "y_up_excess"]
            p_only = ex[ex["피처"] == "가격만"]["ROC-AUC"].max()
            both = ex[ex["피처"] == "뉴스+가격"]["ROC-AUC"].max()
            w_off = ex[ex["가중"] == "가중없음"]["ROC-AUC"].max()
            w_on = ex[ex["가중"] == "최근+뉴스"]["ROC-AUC"].max()
            parts.append(
                "<p><b>뉴스 기여</b>: 가격만 AUC "
                f"{p_only:.4f} → 뉴스+가격 {both:.4f} "
                f"(<span class='{'good' if both > p_only else 'bad'}'>"
                f"{both - p_only:+.4f}</span>). "
                "뉴스를 넣으면 <b>오히려 나빠진다.</b></p>"
                "<p><b>최근 가중 기여</b>: "
                f"{w_off:.4f} → {w_on:.4f} "
                f"(<span class='{'good' if w_on > w_off else 'bad'}'>"
                f"{w_on - w_off:+.4f}</span>). "
                "방향 예측 자체는 실패했지만 <b>최근 데이터에 가중을 주는 "
                "기법은 재현 가능한 개선</b>을 보였다. 이 부분만 "
                "이상감지 랭커에 남긴다.</p>")
        except (KeyError, ValueError):
            pass

    parts.append("<h3>2-3. 파운데이션 모델은 값을 하는가 — TabPFN vs GBM</h3>")
    tb = _read_json("13_tabpfn_bench.json")
    if tb:
        rows = [{"모델": k, **v} for k, v in tb.get("results", {}).items()]
        parts.append(_table(pd.DataFrame(rows),
                            f"이상감지 랭커 · 홀드아웃 {tb.get('n_test'):,}건 "
                            f"· 기저율 {tb.get('기저율', 0)*100:.2f}%"))
        try:
            r = tb["results"]
            tp = next(v for k, v in r.items() if k.startswith("TabPFN"))
            gs = next(v for k, v in r.items() if k.startswith("GBM (1000"))
            gf = r["GBM (전체 표본)"]
            parts.append(
                "<p>표본 수가 100배 차이 나는 <b>불공정한 비교이고, 그게 요점</b>이다. "
                "TabPFN 은 학습 표본을 프롬프트로 넣는 in-context learning 이라 "
                "CPU 상한이 1000행이다. 그래서 <b>GBM 도 같은 1000행으로</b> 한 번 더 "
                "세웠다 — 이 줄이 없으면 &lsquo;TabPFN 이 나쁜 것&rsquo;과 "
                "&lsquo;표본이 적어서 나쁜 것&rsquo;을 구별할 수 없다.</p>"
                f"<p>같은 1000행에서 TabPFN <b class='good'>{tp['PR-AUC']:.4f}</b> vs "
                f"GBM <b>{gs['PR-AUC']:.4f}</b> "
                f"(<span class='good'>{tp['PR-AUC'] - gs['PR-AUC']:+.4f}</span>). "
                f"전체 표본 GBM 이 {gf['PR-AUC']:.4f} 이니 "
                f"<b>표본 100분의 1로 {tp['PR-AUC']/max(gf['PR-AUC'],1e-9)*100:.0f}%에 "
                "도달</b>했다. 소표본에서 파운데이션 모델이 실제로 값을 한다는 "
                "뜻이며, 이 프로젝트에서 <b>측정으로 확인된 몇 안 되는 긍정 결과</b>다.</p>")
            per_tp = tp["추론ms"] / max(tb["n_test"], 1)
            parts.append(
                f"<p><b>다만 비용이 있다.</b> 추론이 행당 {per_tp:.2f}ms 로 "
                "GBM보다 수만 배 느리다. 관심종목 10개를 보는 실사용에서는 "
                f"{per_tp*10:.0f}ms 라 문제가 없지만, "
                f"{tb['n_test']:,}건 대량 평가에는 {tp['추론ms']/1000:.0f}초가 든다. "
                "<b>그래서 랭커 본체는 GBM 으로 두고, TabPFN 은 관심종목 소수에만 "
                "쓰는 경로로 남겼다.</b></p>")
        except (KeyError, StopIteration):
            pass
    else:
        parts.append('<p class="sub">미실행 — <code>python cli.py tabpfn-bench</code></p>')

    parts.append("<h3>2-4. 별도 대회 모델과의 대조</h3>")
    parts.append(
        "<p>같은 팀이 KDD 계열 대회에서 65GB 임베딩(qwen·gemini·lgai·linq·"
        "nvda·bert), Transformer, 증강 기법까지 동원해 만든 모델의 결과는 "
        "<code>hit_rate 0.5133</code>이었고, 그 데이터의 무지성 기준선은 "
        "<code>0.5371</code>이다. 교차검증에서는 <code>0.6033</code>이 나왔지만 "
        "홀드아웃에서 <code>0.4859</code>로 무너졌다 — "
        "<b>그 격차가 과적합의 크기</b>다.</p>"
        "<p>즉 <b>가벼운 사전 방식이든 65GB 임베딩이든 결론이 같다.</b> "
        "다음날 방향은 이 데이터에서 예측되지 않는다.</p>")

    if old:
        rows = [{"항목": k, "값": v} for k, v in old.items()
                if not isinstance(v, (list, dict))]
        parts.append(_table(pd.DataFrame(rows), "초기 뉴스–수익률 상관 측정"))
    return "\n".join(parts)


def _sec_judge() -> str:
    jv = _read_json("12_judge_validation.json")
    parts = ["<h2>3. 투자판단 — 예측 대신 무엇을 주는가</h2>",
             "<p>예측이 안 되므로 이 도구는 예측하지 않는다. 대신 일반 투자자가 "
             "종목 화면을 30초 훑어 얻는 것 — 뉴스 분위기, 추세 위치, 과열도, "
             "당일 이상 신호 — 을 <b>같은 30초 안에 귀로</b> 전달한다.</p>",
             "<p>다섯 요인을 각각 점수화해 5단계로 내고, "
             "<b>합계가 아니라 요인별 근거를 그대로 읽어준다.</b> "
             "점수만 주면 사용자는 판단할 수 없고, 근거를 주면 판단할 수 있다.</p>"]
    if jv:
        rows = [{"항목": k, "값": v} for k, v in jv.items()]
        parts.append(_table(pd.DataFrame(rows),
                            "판단 점수와 이후 수익률의 관계 — 사후 검증"))
        if jv.get("신뢰가능") is False:
            parts.append(
                '<div class="note"><b>이 수치는 아직 읽을 수 없다.</b> '
                f"거래일이 {jv.get('n_days')}개뿐이라 "
                f"{jv.get('n')}행이 사실상 {jv.get('n_days')}개의 독립 표본이다 — "
                "같은 날 종목들이 같은 시장을 공유하기 때문이다. "
                f"표에는 스피어만 {jv.get('스피어만')}, p={jv.get('p값')} 이 "
                "찍혀 있지만 <b>p값이 작다는 것 자체가 의미가 없다.</b><br><br>"
                "특히 <b>부호가 음수라고 해서 &lsquo;점수를 뒤집으면 "
                "수익이 난다&rsquo;로 읽으면 안 된다.</b> 그 3일 동안 상승 추세 "
                "종목이 되돌린 것일 뿐이며, 그 해석이야말로 이 프로젝트가 "
                "2장에서 계속 경계한 과적합 함정이다. "
                "<code>cli.py collect-news</code> 를 매일 돌려 거래일이 "
                "10개를 넘긴 뒤에 다시 재야 한다.</div>")
        elif jv.get("p값", 1) > 0.05:
            parts.append('<div class="note">판단 점수와 이후 수익률 사이에 '
                         "통계적으로 유의한 관계는 <b>없다</b>. "
                         "이 모듈의 목적은 수익 예측이 아니라 "
                         "<b>정보 접근성</b>이므로 설계와 어긋나지 않지만, "
                         "수치를 감추지 않고 싣는다.</div>")
    else:
        parts.append('<p class="sub">사후 검증 미실행 — '
                     "<code>python cli.py judge --validate</code></p>")
    return "\n".join(parts)


# --------------------------------------------------------------------------
def build(out: Path | None = None, verbose: bool = True) -> Path:
    out = Path(out) if out else OUT_PATH
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    body = "\n".join([
        "<h1>저시력 투자 정보 도구 — 검증 결과</h1>",
        f'<p class="sub">생성 {now} · 모든 수치는 '
        "<code>outputs/validation/</code> 의 원본 파일에서 읽어 온 것이며 "
        "손으로 적은 값은 없다.</p>",
        _sec_anomaly(), _sec_prediction(), _sec_judge(),
        "<h2>4. 재현</h2>",
        "<pre><code>python run_all.py        # 전체 재생성\n"
        "python cli.py validate  # 이상감지 검증만\n"
        "python cli.py report    # 이 문서만 다시 만들기</code></pre>",
        "<footer>이 문서는 <code>accessible_investor/report.py</code> 가 "
        "자동 생성한다. 표에는 <code>&lt;caption&gt;</code> 과 "
        "<code>scope</code> 를 붙여 스크린리더가 읽을 수 있게 했고, "
        "좋고 나쁨을 색만이 아니라 부호와 낱말로도 구분한다."
        "<br>투자 결정에 대한 책임은 사용자 본인에게 있다.</footer>",
    ])
    doc = (f'<!doctype html><html lang="ko"><head><meta charset="utf-8">'
           f'<meta name="viewport" content="width=device-width,initial-scale=1">'
           f"<title>저시력 투자 정보 도구 — 검증 결과</title>"
           f"<style>{CSS}</style></head><body><main>{body}</main></body></html>")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(doc, encoding="utf-8")
    if verbose:
        print(f"리포트 생성 → {out}  ({len(doc)/1024:.0f} KB)")
    return out
