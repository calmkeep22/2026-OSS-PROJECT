package org.ossproject.desktop.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavigationHistoryTest {
    @Test void returnsThroughVisitedScreensWithoutDuplicatingCurrentScreen() {
        NavigationHistory history = new NavigationHistory();

        history.visit(Screen.SEARCH);
        history.visit(Screen.SEARCH);
        history.visit(Screen.STOCK_DETAIL);
        history.visit(Screen.TRADING);

        assertEquals(Screen.TRADING, history.current().orElseThrow());
        assertEquals(Screen.STOCK_DETAIL, history.previous().orElseThrow());
        assertEquals(Screen.STOCK_DETAIL, history.back().orElseThrow());
        assertEquals(Screen.SEARCH, history.back().orElseThrow());
        assertFalse(history.canGoBack());
        assertTrue(history.back().isEmpty());
    }
}
