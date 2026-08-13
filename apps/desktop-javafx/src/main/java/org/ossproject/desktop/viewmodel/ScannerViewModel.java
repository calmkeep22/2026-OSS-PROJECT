package org.ossproject.desktop.viewmodel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 시장 스캐너의 필터·정렬 규칙. */
public final class ScannerViewModel {
    private final List<ScannerItem> items = List.of(
            new ScannerItem("KOSPI", "005930", "삼성전자", "72,500원", 2.12, 18_320_122, 2_100_000, "거래량 급증"),
            new ScannerItem("KOSPI", "000660", "SK하이닉스", "184,500원", 1.42, 5_821_330, 1_400_000, "기관 순매수"),
            new ScannerItem("KOSDAQ", "042700", "한미반도체", "132,200원", 6.82, 3_129_443, 412_000, "신고가 근접"),
            new ScannerItem("KOSPI", "035420", "NAVER", "205,000원", -0.71, 1_230_922, 254_000, "외국인 순매도"),
            new ScannerItem("KOSPI", "005380", "현대차", "281,000원", 0.64, 2_992_101, 842_000, "프로그램 매수"),
            new ScannerItem("KOSDAQ", "247540", "에코프로비엠", "192,400원", -3.11, 2_120_443, 406_000, "VI 근접"),
            new ScannerItem("KOSPI", "373220", "LG에너지솔루션", "381,500원", 2.88, 1_084_201, 410_000, "거래대금 증가"),
            new ScannerItem("KOSDAQ", "086520", "에코프로", "98,200원", -4.25, 4_220_104, 418_000, "신저가 근접"),
            new ScannerItem("NASDAQ", "NVDA", "NVIDIA", "$142.65", 2.34, 42_381_210, 6_045_000, "52주 신고가"),
            new ScannerItem("NASDAQ", "AAPL", "Apple", "$228.40", 0.83, 31_800_000, 7_260_000, "기관 순매수"),
            new ScannerItem("NASDAQ", "TSLA", "Tesla", "$216.10", -1.28, 51_200_000, 11_064_000, "거래량 급증"),
            new ScannerItem("NYSE", "PLTR", "Palantir", "$31.40", 4.76, 64_200_000, 2_015_000, "연속 상승"));

    public List<ScannerItem> filter(String market, String criterion, long minimumVolume) {
        List<ScannerItem> result = new ArrayList<>(items.stream()
                .filter(item -> marketMatches(item, market))
                .filter(item -> item.volume() >= minimumVolume).toList());
        result.sort(comparator(criterion)); return result;
    }

    private boolean marketMatches(ScannerItem item, String market) {
        if (market == null || market.equals("전체")) return true;
        if (market.equals("국내 전체")) return item.market().equals("KOSPI") || item.market().equals("KOSDAQ");
        return item.market().equals(market);
    }

    private Comparator<ScannerItem> comparator(String criterion) {
        Comparator<ScannerItem> comparator = switch (criterion == null ? "거래량" : criterion) {
            case "거래대금" -> Comparator.comparingLong(ScannerItem::tradingValueMillion);
            case "상승률", "하락률" -> Comparator.comparingDouble(ScannerItem::changeRate);
            default -> Comparator.comparingLong(ScannerItem::volume);
        };
        return "하락률".equals(criterion) ? comparator : comparator.reversed();
    }
}
