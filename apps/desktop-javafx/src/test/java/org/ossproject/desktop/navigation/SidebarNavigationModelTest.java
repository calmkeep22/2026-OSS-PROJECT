package org.ossproject.desktop.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SidebarNavigationModelTest {
    @Test void startsWithOverviewExpanded() {
        SidebarNavigationModel model = new SidebarNavigationModel();

        assertEquals(Screen.NavigationGroup.OVERVIEW, model.expandedGroup().orElseThrow());
        assertTrue(model.isExpanded(Screen.NavigationGroup.OVERVIEW));
    }

    @Test void toggleKeepsAtMostOneGroupExpandedAndCanCollapseIt() {
        SidebarNavigationModel model = new SidebarNavigationModel();

        model.toggle(Screen.NavigationGroup.MARKET_EXPLORATION);

        assertTrue(model.isExpanded(Screen.NavigationGroup.MARKET_EXPLORATION));
        assertFalse(model.isExpanded(Screen.NavigationGroup.OVERVIEW));

        model.toggle(Screen.NavigationGroup.MARKET_EXPLORATION);

        assertTrue(model.expandedGroup().isEmpty());
    }

    @Test void revealExpandsTheGroupForHiddenDetailScreensToo() {
        SidebarNavigationModel model = new SidebarNavigationModel();

        model.reveal(Screen.STOCK_DETAIL);

        assertTrue(model.isExpanded(Screen.NavigationGroup.MARKET_EXPLORATION));
    }

    @Test void childrenContainOnlyVisibleScreensInDeclarationOrder() {
        SidebarNavigationModel model = new SidebarNavigationModel();

        assertEquals(List.of(
                Screen.SEARCH,
                Screen.WATCHLIST
        ), model.children(Screen.NavigationGroup.MARKET_EXPLORATION));
        assertFalse(model.children(Screen.NavigationGroup.MARKET_EXPLORATION)
                .contains(Screen.STOCK_DETAIL));
    }
}
