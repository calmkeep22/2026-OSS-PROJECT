package org.ossproject.desktop.view.screen;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.desktop.viewmodel.AccountScreenData;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.account.Account;
import org.ossproject.finance.model.account.Balance;
import org.ossproject.finance.model.account.Position;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(JavaFxToolkit.class)
class AccountScreenViewTest {

    @Test
    @DisplayName("계좌의 보유종목·매매일지·탭을 키보드로 조작한다")
    void supportsAccountKeyboardActions() {
        JavaFxToolkit.onFxThread(() -> {
            JournalEntry entry = new JournalEntry("2026-08-26", "삼성전자", "1원", "0원", "0원", "메모", "");
            AtomicInteger opened = new AtomicInteger();
            AtomicReference<OrderSide> traded = new AtomicReference<>();
            AtomicReference<JournalEntry> edited = new AtomicReference<>();
            AtomicInteger deleted = new AtomicInteger();
            AccountScreenView view = new AccountScreenView(
                    () -> FXCollections.observableArrayList(entry),
                    ignored -> opened.incrementAndGet(),
                    (ignored, side) -> traded.set(side), ignored -> { }, edited::set,
                    ignored -> deleted.incrementAndGet());
            Account account = new Account("12345678", Balance.of(new BigDecimal("1000000")),
                    List.of(Position.of("005930", "삼성전자", 1, new BigDecimal("70000"))));
            ScrollPane root = view.create(new AccountScreenData(account, List.of()));

            List<TableView<?>> tables = new ArrayList<>();
            collectTables(root, tables);
            TableView<?> holdings = namedTable(tables, "보유종목 표");
            assertNotNull(holdings);
            holdings.getSelectionModel().selectFirst();
            holdings.fireEvent(key(KeyCode.ENTER, false, false, false));
            assertEquals(1, opened.get());
            holdings.fireEvent(key(KeyCode.B, false, false, true));
            assertEquals(OrderSide.BUY, traded.get());
            holdings.fireEvent(key(KeyCode.B, true, false, true));
            assertEquals(OrderSide.SELL, traded.get());

            TableView<?> journal = namedTable(tables, "매매일지");
            assertNotNull(journal);
            journal.getSelectionModel().selectFirst();
            journal.fireEvent(key(KeyCode.ENTER, false, false, false));
            assertEquals(entry, edited.get());
            journal.fireEvent(key(KeyCode.DELETE, false, false, false));
            assertEquals(1, deleted.get());

            TabPane tabs = findTabPane(root);
            assertNotNull(tabs);
            root.fireEvent(key(KeyCode.TAB, false, true, false));
            assertEquals(1, tabs.getSelectionModel().getSelectedIndex());
        });
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean control, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, false);
    }

    private static TableView<?> namedTable(List<TableView<?>> tables, String name) {
        return tables.stream().filter(table -> name.equals(table.getAccessibleText())).findFirst().orElse(null);
    }

    private static void collectTables(Node node, List<TableView<?>> into) {
        if (node instanceof TableView<?> table) into.add(table);
        if (node instanceof TabPane tabs) {
            tabs.getTabs().forEach(tab -> collectTables(tab.getContent(), into));
        } else if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collectTables(scroll.getContent(), into);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectTables(child, into));
        }
    }

    private static TabPane findTabPane(Node node) {
        if (node instanceof TabPane tabs) return tabs;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            TabPane found = findTabPane(scroll.getContent());
            if (found != null) return found;
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TabPane found = findTabPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
