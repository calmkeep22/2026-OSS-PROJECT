package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.desktop.state.WatchlistItem;

import static org.junit.jupiter.api.Assertions.*;

class WatchlistViewModelTest {
    @Test void preventsDeletingUsedGroupAndRenamesRowsTogether() {
        DesktopSession session = new DesktopSession();
        WatchlistViewModel viewModel = new WatchlistViewModel(session);

        assertEquals(WatchlistViewModel.GroupDeleteResult.IN_USE, viewModel.deleteGroup("반도체"));
        assertTrue(viewModel.renameGroup("반도체", "반도체 핵심"));
        assertTrue(viewModel.items().stream().filter(item -> item.securityName().equals("삼성전자"))
                .allMatch(item -> item.group().equals("반도체 핵심")));
    }

    @Test void addsEditsMovesAlertsAndRemovesTypedItems() {
        DesktopSession session = new DesktopSession();
        WatchlistViewModel viewModel = new WatchlistViewModel(session);
        WatchlistItem added = new WatchlistItem("AI", "테스트 종목", "1,000원", "+1.0%", "10", "없음");

        viewModel.save(null, added);
        assertTrue(viewModel.items().contains(added));

        WatchlistItem alerted = viewModel.setAlert(added, "1,200원");
        assertEquals("1,200원", alerted.alertText());
        assertTrue(viewModel.move(alerted, -1));

        WatchlistItem edited = new WatchlistItem("AI", "테스트 종목", "1,100원", "+2.0%", "20", "1,200원");
        viewModel.save(alerted, edited);
        assertTrue(viewModel.items().contains(edited));

        viewModel.remove(edited);
        assertFalse(viewModel.items().contains(edited));
    }
}
