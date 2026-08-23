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
            new ScannerItem("KOSPI", "000660", "SK하이닉스", "184,500원", 1.42, 5_821_330, 1_074_000, "52주 신고가"),
            new ScannerItem("KOSPI", "005380", "현대차", "216,000원", -1.28, 5_120_000, 1_106_000, "거래량 급증"));

    private final ScannerViewModel viewModel = new ScannerViewModel(SAMPLE);

    @Test void filtersMarketAndMinimumVolume() {
        var results = viewModel.filter("KOSPI", "거래량", 5_000_000);
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(item -> item.market().equals("KOSPI") && item.volume() >= 5_000_000));
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
