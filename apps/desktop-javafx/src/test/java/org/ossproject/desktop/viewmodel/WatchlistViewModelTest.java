package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WatchlistViewModelTest {
    @Test void preventsDeletingUsedGroupAndRenamesRowsTogether() {
        DesktopSession session = new DesktopSession();
        WatchlistViewModel viewModel = new WatchlistViewModel(session);

        assertEquals(WatchlistViewModel.GroupDeleteResult.IN_USE, viewModel.deleteGroup("반도체"));
        assertTrue(viewModel.renameGroup("반도체", "반도체 핵심"));
        assertTrue(viewModel.rows().stream().filter(row -> row.get(1).equals("삼성전자"))
                .allMatch(row -> row.get(0).equals("반도체 핵심")));
    }
}
