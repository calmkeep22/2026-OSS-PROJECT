#!/usr/bin/env python
"""
accessible-investor — AI 엔진 CLI

이 저장소는 **AI 파트만** 담는다. 소리 변환·음성 안내·웹 데모는 다른 담당이
맡기로 해서 전부 뺐다(`_local/sound/` 에 보관).

서비스 (임의 종목 — 코스피·코스닥·나스닥·S&P500 전부)
    python cli.py serve brief   카카오          ★ 세 기능 한 번에 (관심종목용)
    python cli.py serve predict 카카오          다음 거래일 예측
    python cli.py serve anomaly 카카오          이상 움직임 + 위험도
    python cli.py serve similar 카카오          닮은 차트 종목
    python cli.py serve health                 배포 파일 점검
    python cli.py registry 카카오 NVDA          종목 조회 (6,228종목)

결과물 만들기
    python cli.py results                      ★ 전부 생성 → results/
    python cli.py forecast                     다음날 방향 예측만
    python cli.py risk                         종목별 위험도만
    python cli.py similar-viz                  차트 유사도 시각화만

개별 분석
    python cli.py predict 삼성전자              내일 방향 + 근거 기사
    python cli.py news    삼성전자              뉴스 감성 지수
    python cli.py similar 삼성전자 --window 120 유사 차트 패턴
    python cli.py pairs   --top 20             페어 관계 이탈
    python cli.py judge   삼성전자 카카오        관심종목 투자판단

서비스 준비 (배포 전 한 번)
    python cli.py registry --refresh           전 종목 목록 갱신
    python cli.py reference --build            비교군 패널 + 위험도 기준분포
    python cli.py train-pooled                 파운데이션 모델 학습 → models/
    python cli.py eval-pooled --days 40        파운데이션 vs 종목별 성능 비교

학습·검증
    python cli.py collect-news                 뉴스 아카이브 적립 (매일!)
    python cli.py backfill-news --days 365     과거 뉴스 소급 수집 (한 번)
    python cli.py train   --stocks 140         이상감지 랭커 학습
    python cli.py validate --stocks 30         이상감지 엔진 검증
    python cli.py news-predict                 국내 뉴스 예측력 3-way
    python cli.py overseas --ablate            해외 다음날 방향 어블레이션
    python cli.py tabpfn-bench                 TabPFN vs GBM
    python cli.py report                       검증 결과 HTML

산출물은 results/ 아래에 쌓인다. 요약은 results/index.html 하나.
"""

from __future__ import annotations

import argparse
import json
import sys
import warnings
from pathlib import Path

warnings.filterwarnings("ignore")
sys.path.insert(0, str(Path(__file__).resolve().parent))

# 윈도우 콘솔 기본 코드페이지(cp949)에서 한글이 깨지는 것을 막는다
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except Exception:
        pass

import pandas as pd  # noqa: E402

from accessible_investor import data as D  # noqa: E402
from accessible_investor import news as N  # noqa: E402
from accessible_investor import pairs as PR  # noqa: E402
from accessible_investor import pipeline as P  # noqa: E402
from accessible_investor import similarity as SIM  # noqa: E402
from accessible_investor.config import OUTPUT_DIR  # noqa: E402

ROOT = Path(__file__).resolve().parent


def resolve_all(queries: list[str]) -> list[tuple[str, str]]:
    out = []
    for q in queries:
        hit = D.resolve(q)
        if hit is None:
            print(f"  [{q}] 종목을 찾을 수 없습니다.")
            continue
        out.append(hit)
    return out


def _write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    print(f"  → {path.relative_to(ROOT)}")


# ==========================================================================
# 결과물 생성
# ==========================================================================
def cmd_results(args):
    from accessible_investor import results as R

    R.run_all(n_days=args.days, refit_every=args.refit)


def cmd_forecast(args):
    from accessible_investor import results as R

    R.run_forecast(n_days=args.days, refit_every=args.refit)


def cmd_risk(args):
    from accessible_investor import results as R

    R.run_anomaly()


def cmd_similar_viz(args):
    from accessible_investor import results as R

    # ⚠️ 인자 이름은 `windows`(복수)다. 창을 넷 쓰도록 바꾼 뒤에도 여기만
    # `window=` 로 남아 있어서, 이 명령은 부를 때마다 TypeError 로 죽었다.
    wins = args.windows or None
    R.run_similarity(windows=wins, top_k=args.top, forward=args.forward,
                     pool=args.pool)


def cmd_rebuild(args):
    """
    저장된 워크포워드 CSV 로 뒷단만 다시 만든다. **재학습 없음.**
    모델 선택 규칙·집계·그림·요약을 고쳤을 때 7시간을 다시 쓰지 않아도 된다.
    """
    from accessible_investor import results as R

    R.rebuild_forecast()
    R.rebuild_index()


def cmd_ablate(args):
    """
    피처 묶음 어블레이션. **비싸다** — 조합 하나가 45분쯤이다.
    가끔 돌려 CSV 로 남기고, 일반 실행은 그 CSV 로 그림만 그린다.
    """
    from accessible_investor import results as R

    R.run_ablation(n_days=args.days, refit_every=args.refit)


def cmd_index(args):
    from accessible_investor import results as R

    R.rebuild_index()


def cmd_package(args):
    from accessible_investor import package as PK

    PK.run()


def cmd_universe(args):
    from accessible_investor import universe as U

    if args.rebuild:
        U.build(refresh=args.refresh, verbose=True)
        return
    uni = U.load()
    print(uni.to_string(index=False))
    print(f"\n{len(uni)}종목 · 지수 {uni['index'].nunique()}개")


def cmd_newsxfer(args):
    from accessible_investor import newsxfer as NX

    NX.train(verbose=True)


def cmd_predict(args):
    """관심종목 하나 → 내일 방향 + 근거 기사."""
    from accessible_investor import forecast as FC

    for name in args.stocks:
        try:
            r = FC.predict_next(name, kind=args.model)
        except Exception as e:
            print(f"  {name}: {type(e).__name__}: {e}")
            continue
        ev = FC.news_evidence(name, top=args.evidence)
        if len(ev):
            print("   근거 기사")
            for _i, a in ev.iterrows():
                print(f"     {a['polarity']:+.2f} [{a['label']:8s}] "
                      f"{str(a['ts'])[:10]} {a['title'][:66]}")


# ==========================================================================
# 개별 분석
# ==========================================================================
def cmd_news(args):
    hits = resolve_all(args.stocks)
    for code, name in hits:
        res = N.analyze(name, days=args.days, use_llm=args.llm)
        if not res:
            print(f"\n[{name}] 관련 뉴스를 찾지 못했습니다.")
            continue
        print(f"\n{'='*62}\n{name} ({code})\n{'='*62}")
        print(f"감성 지수 : {res['score']:+.1f} ({res['label']})")
        print(f"기사      : {res['n_articles']}건 / 사건 {res['n_events']}개")
        print(f"내역      : 긍정 {res['n_pos']} 중립 {res['n_neu']} 부정 {res['n_neg']}")
        for s in res.get("summaries", [])[:3]:
            print(f"  · {s}")
        out = OUTPUT_DIR / "news" / f"{code}_{pd.Timestamp.today():%Y%m%d}"
        _write(out / "result.json",
               json.dumps(res, ensure_ascii=False, indent=2, default=str))


def cmd_similar(args):
    hits = resolve_all(args.stocks)
    if not hits:
        return
    code, _name = hits[0]
    pool = P.watchlist_from_cache(args.pool)
    if code not in pool:
        pool = [code] + pool
    horizon = "intraday" if args.intraday else "swing"
    unit = "봉" if args.intraday else "일"

    data, names = {}, {}
    for c in pool:
        bars = D.load_bars(c, horizon)
        if bars is not None and len(bars) > args.window + 20:
            data[c] = bars
            names[c] = D.name_of(c)
    print(f"후보 풀 {len(data)}종목 · 윈도우 {args.window}{unit}")

    if args.multi:
        res = SIM.find_similar_multi(code, data, top_k=args.top, name_map=names,
                                     forward_bars=args.forward, unit=unit)
        text, tag = SIM.speak_multi(res), "multi"
    else:
        res = SIM.find_similar(code, data, W=args.window, top_k=args.top,
                               name_map=names, forward_bars=args.forward,
                               unit=unit)
        text, tag = SIM.speak(res), str(args.window)
    print(f"\n{text}\n")
    out = OUTPUT_DIR / "similar" / f"{code}_{tag}"
    _write(out / "result.json",
           json.dumps(res, ensure_ascii=False, indent=2, default=str))


def cmd_pairs(args):
    codes = P.watchlist_from_cache(args.top)
    frames, names = {}, {}
    for c in codes:
        df = D.load_daily(c)
        if df is not None and len(df) > 300:
            frames[c] = df["close"]
            names[c] = D.name_of(c)
    closes = pd.DataFrame(frames).dropna()
    print(f"페어 후보 {closes.shape[1]}종목 · 공통 구간 {len(closes)}일")

    res = PR.scan(closes, name_map=names, verbose=True)
    print(f"\n공적분 페어 {len(res['pairs'])}쌍, "
          f"현재 관계 이탈 {res.get('n_broken', 0)}쌍\n")
    for b in res["breaks"][:args.show]:
        mark = "⚠ " if b["state"] == "이탈" else "  "
        print(f"{mark}{b['name_a']} ↔ {b['name_b']}  z={b['z']:+.2f}  "
              f"p={b['pvalue']:.5f}  이탈률 {b['break_rate']*100:.0f}%")
    _write(OUTPUT_DIR / "pairs" / "result.json",
           json.dumps(res, ensure_ascii=False, indent=2, default=str))


def cmd_judge(args):
    from accessible_investor import news_judge as J

    if args.validate:
        J.validate(horizon=args.horizon_days)
        return
    hits = resolve_all(args.stocks) if args.stocks else [
        (c, D.name_of(c)) for c in P.watchlist_from_cache(5)]
    df = J.judge_many([n for _c, n in hits], days=args.days)
    if not len(df):
        print("판단할 종목이 없습니다.")
        return
    print()
    print(df.drop(columns=["_speech"]).to_string(index=False))
    out = OUTPUT_DIR / "judge"
    out.mkdir(parents=True, exist_ok=True)
    df.to_csv(out / "judge.csv", index=False, encoding="utf-8-sig")
    print(f"  → {out / 'judge.csv'}")


# ==========================================================================
# 학습 · 검증
# ==========================================================================
def cmd_validate(args):
    from accessible_investor import validation as V

    codes = P.watchlist_from_cache(args.stocks)
    if args.refresh:
        P.ensure_intraday(codes, "5m")
    panel = P.build_panel(codes, args.horizon, verbose=True)
    V.run_all(panel, args.horizon)
    if args.news:
        res = N.validate_predictive_power(codes[:args.news_stocks], days=7)
        _write(OUTPUT_DIR / "validation" / "07_news_predictive.json",
               json.dumps(res, ensure_ascii=False, indent=2, default=str))


def cmd_collect(args):
    """
    뉴스 아카이브 적립. **매일 한 번 돌려야 한다.**

    구글 뉴스 RSS는 최근 7일까지만 준다. 그래서 "지난 6개월 뉴스로 학습"이
    불가능하고, 유일한 우회는 매일 받아 쌓는 것이다. 오늘 안 돌리면 그날치는
    영영 없다.
    """
    codes = P.watchlist_from_cache(args.stocks)
    names = [D.name_of(c) for c in codes]
    print(f"뉴스 수집 — {len(names)}종목 · 최근 {args.days}일")
    r = N.archive_collect(names, days=args.days)
    df = N.archive_load()
    if len(df):
        print(f"  아카이브: {r['total']:,}건 · 종목 {df['corp'].nunique()} "
              f"· 날짜 {df['ts'].dt.date.nunique()}일")
        print(f"  {df['ts'].min():%Y-%m-%d} ~ {df['ts'].max():%Y-%m-%d}")


def cmd_newspredict(args):
    from accessible_investor import news_predict as NP

    NP.run(use_tabpfn=args.tabpfn)


def cmd_backfill(args):
    """
    과거 뉴스를 소급 수집한다. **한 번만 돌리면 된다.**

    `when:Nd` 로는 최근 7일치밖에 안 와서, 한동안 국내 종목의 뉴스 피처가
    학습 구간의 0.3% 만 차 있었다 — 열이 사실상 상수라 배울 것이 없었다.
    구글 뉴스 RSS 가 `after:`/`before:` 를 받으므로 구간을 쪼개 내려가면
    과거도 모인다.
    """
    from accessible_investor import news as N
    from accessible_investor.universe import all_entries

    names = [e["label"] for e in all_entries()]
    N.backfill_all(names, days_back=args.days)


def cmd_train(args):
    from accessible_investor import training

    training.run(n_stocks=args.stocks, test_frac=args.test_frac,
                 epochs=args.epochs, with_deep=args.deep,
                 reuse_deep=args.reuse_deep)


def cmd_overseas(args):
    from accessible_investor import overseas as OV

    cached = OV.CACHE_DIR / "panel.parquet"
    if args.rebuild or not cached.is_file():
        df = OV.build(args.xf_dir)
        cached.parent.mkdir(parents=True, exist_ok=True)
        df.to_parquet(cached, index=False)
    else:
        df = pd.read_parquet(cached)
        print(f"캐시된 학습 테이블 사용 {df.shape}")

    if args.ablate:
        OV.ablate(df, models=["gbm"] + (["tabpfn_reg"] if args.tabpfn else []))
    else:
        OV.train(df, target=args.target, model=args.model,
                 recency=not args.no_recency, news=args.news_weight)


def cmd_report(args):
    from accessible_investor import report as R

    R.build()


def cmd_tabpfn(args):
    from accessible_investor import tabpfn_bench as TB

    TB.run(n_stocks=args.stocks, max_test=args.max_test)


# ==========================================================================
# 서비스 — 임의 종목 (코스피·코스닥·나스닥·S&P500)
# ==========================================================================
def _dump(obj):
    print(json.dumps(obj, ensure_ascii=False, indent=1, default=str))


def cmd_serve(args):
    """팀원이 붙일 API 를 커맨드라인에서 그대로 확인한다."""
    from accessible_investor import serving as SV

    if args.action == "health":
        _dump(SV.health())
        return
    if args.action == "warm":
        _dump(SV.warm())
        return

    for q in args.stocks:
        try:
            if args.action == "brief":
                r = SV.brief(q, with_news=not args.no_news,
                             with_similar=True, target=args.target,
                             precise=args.precise)
            elif args.action == "predict":
                r = SV.predict(q, target=args.target,
                               with_news=not args.no_news,
                               precise=args.precise)
            elif args.action == "anomaly":
                r = SV.anomaly(q, window=args.window or 60)
            else:
                r = SV.similar(q, window=args.window or 120, top_k=args.top,
                               forward=args.forward)
        except SV.ServiceError as e:
            print(f"[{q}] {type(e).__name__}: {e}")
            continue
        except Exception as e:
            print(f"[{q}] 실패 — {type(e).__name__}: {e}")
            continue

        if args.json:
            _dump(r)
        elif args.action == "similar":
            print(f"\n{r['종목명']}({r['종목코드']}) — 후보 "
                  f"{r['후보종목수']}종목 · {r['소요ms']}ms")
            for x in r.get("results", []):
                print(f"  {x['rank']}. {x['name'][:20]:22s} "
                      f"유사도 {x['similarity']:.3f}  ~{x['end']}")
                c = x.get("components", {})
                if c:
                    print("       " + " · ".join(f"{k} {v:.3f}"
                                                 for k, v in c.items()))
        elif args.action == "brief":
            # brief 는 세 기능을 합친 결과라 `사용봉수` 같은 단일 기능 필드가
            # 없다. 예전엔 아래 공통 분기로 떨어져 KeyError 로 죽었다.
            print(f"\n{r['문안']}")
            tail = [r["종목코드"], f"{r['소요ms']}ms"]
            if r.get("오류"):
                tail.append(f"오류 {list(r['오류'])}")
            print("  (" + " · ".join(tail) + ")")
        else:
            print(f"\n{r['문안']}")
            bits = [r["종목코드"], r.get("신뢰도", "-")]
            if r.get("방식"):
                bits.append(r["방식"])
            bits += [f"{r['소요ms']}ms", f"봉 {r['사용봉수']}개"]
            print("  (" + " · ".join(bits) + ")")


def cmd_registry(args):
    from accessible_investor import registry as REG

    if args.refresh:
        REG.refresh(verbose=True)
    tb = REG.table()
    print(f"레지스트리 {len(tb):,}종목  {tb['index'].value_counts().to_dict()}")
    for q in args.stocks or []:
        try:
            e = REG.resolve(q)
            print(f"  {q} → {e['label']}({e['code']}) {e['index']} "
                  f"· {e['sector']}")
        except Exception as ex:
            print(f"  {q} → {ex}")


def cmd_reference(args):
    from accessible_investor import reference as RF

    if args.build:
        RF.build_all(verbose=True)
    for m in ("KR", "US"):
        if RF.has_panel(m):
            p = RF.load_panel(m)
            print(f"  참조패널 {m}: {p.shape[0]}일 × {p.shape[1]}종목 "
                  f"· 위험도 비교군 {RF.reference_n(m)}종목")
        else:
            print(f"  참조패널 {m}: 없음 — `--build` 로 만드세요")


def cmd_train_pooled(args):
    from accessible_investor import pooled as PL

    stacked = None
    if args.stack and Path(args.stack).is_file():
        stacked = pd.read_parquet(args.stack)
        print(f"  저장된 학습자료 사용 — {stacked.shape}")
    for t in args.targets:
        PL.train(target=t, stacked=stacked, verbose=True)


def cmd_eval_pooled(args):
    from accessible_investor import results as R

    R.run_pooled_eval(days=args.days, stack_path=args.stack,
                      refit_every=args.refit, holdout=args.holdout,
                      verbose=True)


# ==========================================================================
def main():
    ap = argparse.ArgumentParser(
        description="accessible-investor — AI 엔진 (예측 · 이상감지 · 유사도)",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def stocks(p, required=True):
        p.add_argument("stocks", nargs="+" if required else "*",
                       help="종목명 또는 6자리 코드")

    # --- 결과물 ---
    p = sub.add_parser("results", help="★ AI 결과물 전부 생성 → ai_results/")
    p.add_argument("--days", type=int, default=40,
                   help="워크포워드 평가 일수 (보고서 기본 40일)")
    p.add_argument("--refit", type=int, default=1,
                   help="재학습 주기(거래일). 1=매일, 5=주1회. "
                        "긴 구간을 볼 때만 올린다")
    p.set_defaults(fn=cmd_results)

    p = sub.add_parser("forecast", help="다음날 방향 예측 결과만")
    p.add_argument("--days", type=int, default=7)
    p.add_argument("--refit", type=int, default=1)
    p.set_defaults(fn=cmd_forecast)

    p = sub.add_parser("risk", help="종목별 위험도·이상감지 시각자료")
    p.set_defaults(fn=cmd_risk)

    p = sub.add_parser("rebuild",
                       help="저장된 워크포워드로 집계·그림·요약만 다시 (재학습 X)")
    p.set_defaults(fn=cmd_rebuild)

    p = sub.add_parser("ablate",
                       help="피처 묶음 어블레이션 (느림 — 조합당 45분)")
    p.add_argument("--days", type=int, default=40)
    p.add_argument("--refit", type=int, default=5,
                   help="비교가 목적이라 5일 주기로 충분하다")
    p.set_defaults(fn=cmd_ablate)

    p = sub.add_parser("index", help="results/index.html 만 다시 만들기")
    p.set_defaults(fn=cmd_index)

    p = sub.add_parser("package", help="results/ README·업로드 목록 갱신")
    p.set_defaults(fn=cmd_package)

    p = sub.add_parser("universe", help="평가 대상 종목 목록")
    p.add_argument("--rebuild", action="store_true", help="다시 뽑기")
    p.add_argument("--refresh", action="store_true", help="후보 풀도 새로 받기")
    p.set_defaults(fn=cmd_universe)

    p = sub.add_parser("news-transfer",
                       help="미국 뉴스로 전이 모델 학습 (국내 뉴스 부족 우회)")
    p.set_defaults(fn=cmd_newsxfer)

    p = sub.add_parser("similar-viz", help="차트 유사도 시각자료")
    p.add_argument("--windows", type=int, nargs="*", default=None,
                   help="봉 수 여러 개. 기본 20 60 120 250")
    p.add_argument("--top", type=int, default=4)
    p.add_argument("--forward", type=int, default=20)
    p.add_argument("--pool", type=int, default=140)
    p.set_defaults(fn=cmd_similar_viz)

    p = sub.add_parser("predict", help="관심종목 내일 방향 + 근거 기사")
    stocks(p)
    p.add_argument("--model", default="그래디언트부스팅")
    p.add_argument("--evidence", type=int, default=4)
    p.set_defaults(fn=cmd_predict)

    # --- 개별 분석 ---
    p = sub.add_parser("news", help="뉴스 감성 지수")
    stocks(p)
    p.add_argument("--days", type=int, default=3)
    p.add_argument("--llm", action="store_true",
                   help="ANTHROPIC_API_KEY가 있으면 사건 요약에 사용")
    p.set_defaults(fn=cmd_news)

    p = sub.add_parser("similar", help="유사 차트 패턴 검색")
    stocks(p)
    p.add_argument("--window", type=int, default=SIM.DEFAULT_WINDOW,
                   choices=list(SIM.WINDOW_CHOICES))
    p.add_argument("--top", type=int, default=5)
    p.add_argument("--pool", type=int, default=60)
    p.add_argument("--forward", type=int, default=0)
    p.add_argument("--intraday", action="store_true")
    p.add_argument("--multi", action="store_true",
                   help="20/60/120봉 동시 — 최근 모양과 전체 추세를 함께")
    p.set_defaults(fn=cmd_similar)

    p = sub.add_parser("pairs", help="페어 관계 이탈")
    p.add_argument("--top", type=int, default=20)
    p.add_argument("--show", type=int, default=5)
    p.set_defaults(fn=cmd_pairs)

    p = sub.add_parser("judge", help="관심종목 투자판단 (뉴스+가격+이상신호)")
    stocks(p, required=False)
    p.add_argument("--days", type=int, default=3)
    p.add_argument("--validate", action="store_true")
    p.add_argument("--horizon-days", type=int, default=1,
                   help="쓸 수 있는 표본 = (아카이브 거래일 - 이 값)")
    p.set_defaults(fn=cmd_judge)

    # --- 학습·검증 ---
    p = sub.add_parser("collect-news", help="뉴스 아카이브 적립 (매일 실행)")
    p.add_argument("--stocks", type=int, default=140)
    p.add_argument("--days", type=int, default=7)
    p.set_defaults(fn=cmd_collect)

    p = sub.add_parser("backfill-news",
                       help="과거 뉴스 소급 수집 — 유니버스 38종목")
    p.add_argument("--days", type=int, default=365,
                   help="며칠 전까지 걸어 내려갈지")
    p.set_defaults(fn=cmd_backfill)

    p = sub.add_parser("train", help="이상감지 랭커 학습")
    p.add_argument("--stocks", type=int, default=140)
    p.add_argument("--test-frac", type=float, default=0.35)
    p.add_argument("--epochs", type=int, default=6)
    p.add_argument("--deep", action="store_true",
                   # argparse 는 help 문자열에 %-포매팅을 건다.
                   # 리터럴 퍼센트는 반드시 %% 로 써야 한다 — 안 그러면
                   # `--help` 가 ValueError 로 죽는다(실측).
                   help="2단 딥러닝 포함. 기본 꺼짐 — 순 기여 +1.3%%에 817ms")
    p.add_argument("--reuse-deep", action="store_true")
    p.set_defaults(fn=cmd_train)

    p = sub.add_parser("validate", help="이상감지 엔진 검증 리포트")
    p.add_argument("--stocks", type=int, default=30)
    p.add_argument("--horizon", default="intraday",
                   choices=["intraday", "realtime", "swing"])
    p.add_argument("--refresh", action="store_true")
    p.add_argument("--news", action="store_true")
    p.add_argument("--news-stocks", type=int, default=12)
    p.set_defaults(fn=cmd_validate)

    p = sub.add_parser("news-predict", help="국내 뉴스 예측력 3-way 측정")
    p.add_argument("--tabpfn", action="store_true")
    p.set_defaults(fn=cmd_newspredict)

    p = sub.add_parser("overseas", help="해외(미국) 다음날 방향 모델")
    p.add_argument("--ablate", action="store_true")
    p.add_argument("--tabpfn", action="store_true")
    p.add_argument("--target", default="y_up_excess",
                   choices=["y_up_excess", "y_up_raw"])
    p.add_argument("--model", default="gbm",
                   choices=["gbm", "tabpfn", "tabpfn_reg"])
    p.add_argument("--no-recency", action="store_true")
    p.add_argument("--news-weight", action="store_true")
    p.add_argument("--rebuild", action="store_true")
    p.add_argument("--xf-dir", default=None)
    p.set_defaults(fn=cmd_overseas)

    p = sub.add_parser("tabpfn-bench", help="TabPFN vs GBM 비교")
    p.add_argument("--stocks", type=int, default=140)
    p.add_argument("--max-test", type=int, default=20000)
    p.set_defaults(fn=cmd_tabpfn)

    p = sub.add_parser("report", help="이상감지 검증 결과 HTML")
    p.set_defaults(fn=cmd_report)

    # --- 서비스 (임의 종목) ---
    p = sub.add_parser("serve",
                       help="★ 서비스 API — 코스피·코스닥·나스닥·S&P500 아무 종목")
    p.add_argument("action",
                   choices=["brief", "predict", "anomaly", "similar",
                            "health", "warm"])
    p.add_argument("stocks", nargs="*", help="종목명 또는 코드")
    p.add_argument("--target", default="변동성", choices=["변동성", "방향"])
    p.add_argument("--window", type=int, default=None,
                   help="anomaly=기준봉수(기본 60), similar=비교봉수(기본 120)")
    p.add_argument("--top", type=int, default=5)
    p.add_argument("--forward", type=int, default=20)
    p.add_argument("--no-news", action="store_true")
    p.add_argument("--precise", action="store_true",
                   help="이 종목으로 새로 학습 (7초, 변동성 +2.42%%p)")
    p.add_argument("--json", action="store_true", help="원본 응답 그대로")
    p.set_defaults(fn=cmd_serve)

    p = sub.add_parser("registry", help="전 종목 레지스트리 조회·갱신")
    p.add_argument("stocks", nargs="*")
    p.add_argument("--refresh", action="store_true", help="목록 다시 받기")
    p.set_defaults(fn=cmd_registry)

    p = sub.add_parser("reference", help="비교군 패널·위험도 기준분포")
    p.add_argument("--build", action="store_true")
    p.set_defaults(fn=cmd_reference)

    p = sub.add_parser("train-pooled", help="파운데이션 모델 학습 → models/")
    p.add_argument("--targets", nargs="+", default=["변동성", "방향"],
                   choices=["변동성", "방향"])
    p.add_argument("--stack", default=None,
                   help="미리 만든 학습자료 parquet (없으면 새로 수집)")
    p.set_defaults(fn=cmd_train_pooled)

    p = sub.add_parser("eval-pooled",
                       help="파운데이션 vs 종목별 재학습 성능 비교")
    p.add_argument("--days", type=int, default=40)
    p.add_argument("--refit", type=int, default=1)
    p.add_argument("--stack", default=None)
    p.add_argument("--holdout", action="store_true",
                   help="평가 종목을 학습에서 빼고 다시 측정 "
                        "(처음 보는 종목 일반화 · 타깃당 40분 추가)")
    p.set_defaults(fn=cmd_eval_pooled)

    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
