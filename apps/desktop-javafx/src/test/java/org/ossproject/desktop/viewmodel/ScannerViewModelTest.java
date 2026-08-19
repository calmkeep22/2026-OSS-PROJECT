package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScannerViewModelTest {

    /** 정렬·필터 규칙을 검증하기 위한 표본. 앱이 화면에 보여 주는 값이 아니다. */
    private static final List<ScannerItem> SAMPLE = List.of(
            new ScannerItem("KOSPI", "005930", "삼성전자", "72,500원", 2.12, 18_320_122, 2_100_000, "거래량 급증"),
            new ScannerItem("KOSPI", "035420", "NAVER", "205,000원", -0.71, 1_230_922, 254_000, "외국인 순매도"),
            new ScannerItem("KOSDAQ", "086520", "에코프로", "98,200원", -4.25, 4_220_104, 418_000, "신저가 근접"),
            new ScannerItem("NASDAQ", "NVDA", "NVIDIA", "$142.65", 2.34, 42_381_210, 6_045_000, "52주 신고가"),
            new ScannerItem("NASDAQ", "TSLA", "Tesla", "$216.10", -1.28, 51_200_000, 11_064_000, "거래량 급증"));

    private final ScannerViewModel viewModel = new ScannerViewModel(SAMPLE);

    @Test void filtersMarketAndMinimumVolume() {
        var results = viewModel.filter("NASDAQ", "거래량", 40_000_000);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(item -> item.market().equals("NASDAQ") && item.volume() >= 40_000_000));
    }

    @Test void sortsDeclinersFromLowestRate() {
        var results = viewModel.filter("국내 전체", "하락률", 0);
        assertTrue(results.get(0).changeRate() <= results.get(1).changeRate());
    }

    @Test void hasNoDataUntilRankingQueriesAreConnected() {
        ScannerViewModel notConnected = new ScannerViewModel();

        assertFalse(notConnected.hasData());
        assertTrue(notConnected.filter("전체", "거래량", 0).isEmpty());
    }
}
