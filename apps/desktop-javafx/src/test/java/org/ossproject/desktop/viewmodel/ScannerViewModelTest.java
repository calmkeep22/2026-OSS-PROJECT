package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScannerViewModelTest {
    private final ScannerViewModel viewModel = new ScannerViewModel();

    @Test void filtersMarketAndMinimumVolume() {
        var results = viewModel.filter("NASDAQ", "거래량", 40_000_000);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(item -> item.market().equals("NASDAQ") && item.volume() >= 40_000_000));
    }

    @Test void sortsDeclinersFromLowestRate() {
        var results = viewModel.filter("국내 전체", "하락률", 0);
        assertTrue(results.get(0).changeRate() <= results.get(1).changeRate());
    }
}
