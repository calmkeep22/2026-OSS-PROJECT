package org.ossproject.desktop.accessibility;

import javafx.scene.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.orderbook.DepthChartCanvas;
import org.ossproject.desktop.orderbook.OrderBookLadderView;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.desktop.view.screen.ScannerScreenView;
import org.ossproject.desktop.view.screen.SearchScreenView;
import org.ossproject.desktop.view.screen.SettingsScreenView;
import org.ossproject.desktop.view.screen.WatchlistScreenView;
import org.ossproject.desktop.viewmodel.DesktopSession;
import org.ossproject.desktop.viewmodel.ScannerViewModel;
import org.ossproject.desktop.viewmodel.StockSearchViewModel;
import org.ossproject.desktop.viewmodel.WatchlistViewModel;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.desktop.trades.TradeTapeView;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 화면 조각에 접근성 검사기를 돌린다.
 *
 * <p>초점을 받을 수 있는데 접근 가능한 이름이 없으면 스크린리더가 "버튼" 이라고만 읽는다.
 * 무엇을 하는 버튼인지 알 수 없다. 이 검사는 사람이 매번 확인하지 않아도 되게 한다.
 *
 * <p>여기서 만드는 것은 서비스 없이 세울 수 있는 화면 조각이다. 전체 화면은 조립 루트가
 * 필요해서 아직 다루지 못한다. 화면을 하나씩 이 검사에 넣어 가는 것이 목표다.
 */
@ExtendWith(JavaFxToolkit.class)
class ScreenAccessibilityTest {

    private final AccessibilityAudit audit = new AccessibilityAudit();

    /**
     * 이름 없는 조작 요소가 없는지 본다.
     *
     * <p>조작 요소가 하나도 없으면 검사는 그냥 통과한다. 화면을 잘못 세워 빈 트리를
     * 넘기고도 통과하는 일이 없도록 개수를 함께 확인한다.
     */
    private void assertNoMissingNames(String what, Node root) {
        materialise(root);
        assertTrue(countFocusable(root) > 0,
                () -> what + " 에 조작 요소가 하나도 없습니다. 화면을 제대로 세우지 못한 것입니다.");
        List<AccessibilityAudit.Issue> issues = audit.audit(root);
        assertTrue(issues.isEmpty(), () -> what + " 에 이름 없는 조작 요소가 있습니다: "
                + issues.stream().map(AccessibilityAudit.Issue::message).collect(Collectors.joining(", ")));
    }

    /**
     * 스킨을 만들어 실제 자식 노드를 채운다.
     *
     * <p>{@code ScrollPane}, {@code TabPane}, {@code TableView} 의 내용은 스킨이 만들어져야
     * 자식으로 잡힌다. 스킨은 장면에 붙고 CSS 가 적용될 때 생긴다. 이 과정을 건너뛰면
     * 검사기가 빈 껍데기만 훑고 아무 문제도 찾지 못한다.
     */
    private static void materialise(Node root) {
        if (root.getScene() == null) {
            javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(root);
            new javafx.scene.Scene(host, 1280, 900);
        }
        root.applyCss();
        if (root instanceof javafx.scene.Parent parent) {
            parent.layout();
        }
    }

    private static int countFocusable(Node node) {
        int count = node.isFocusTraversable() ? 1 : 0;
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countFocusable(child);
            }
        }
        return count;
    }

    @Test
    @DisplayName("호가창의 모든 조작 요소에 이름이 있다")
    void orderBookLadderNamesEverythingFocusable() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView view = new OrderBookLadderView("삼성전자");
            view.showUnavailable("호가를 기다리고 있습니다.");

            assertNoMissingNames("호가창", view.root());
        });
    }

    @Test
    @DisplayName("체결 목록의 모든 조작 요소에 이름이 있다")
    void tradeTapeNamesEverythingFocusable() {
        JavaFxToolkit.onFxThread(() -> {
            TradeTapeView view = new TradeTapeView("삼성전자", focused -> { });

            assertNoMissingNames("체결 목록", view.root());
        });
    }

    /** 그래프는 스크린리더가 읽을 수 없다. 이름이라도 있어야 무엇인지 알 수 있다. */
    @Test
    @DisplayName("깊이 그래프에 접근 가능한 이름이 있다")
    void depthChartCarriesAName() {
        JavaFxToolkit.onFxThread(() -> {
            DepthChartCanvas canvas = new DepthChartCanvas();

            // 그래프에는 조작 요소가 없다. 초점을 주면 읽을 수 없는 캔버스에 갇힌다.
            assertTrue(audit.audit(canvas).isEmpty());
            assertTrue(canvas.getAccessibleText() != null && !canvas.getAccessibleText().isBlank(),
                    "그래프가 무엇인지 알릴 이름이 필요합니다");
        });
    }

    // ------------------------------------------------------------------
    // 사이드바에서 열리는 화면들
    // ------------------------------------------------------------------

    private MarketApplicationPort market() {
        return new MarketApplicationService(new FakeStockQueryAdapter(),
                new FakeCandleQueryAdapter(), new FakeMarketDataStreamAdapter(), Runnable::run);
    }

    @Test
    @DisplayName("통합 검색 화면의 모든 조작 요소에 이름이 있다")
    void searchScreenNamesEverythingFocusable() {
        JavaFxToolkit.onFxThread(() -> {
            StockSearchViewModel viewModel = new StockSearchViewModel(
                    new DesktopSession(), market(), Runnable::run);
            SearchScreenView view = new SearchScreenView(viewModel, screen -> { }, text -> { });

            assertNoMissingNames("통합 검색", view.create());
        });
    }

    @Test
    @DisplayName("관심종목 화면의 모든 조작 요소에 이름이 있다")
    void watchlistScreenNamesEverythingFocusable() {
        JavaFxToolkit.onFxThread(() -> {
            WatchlistViewModel viewModel = new WatchlistViewModel(
                    new DesktopSession(), market(), Runnable::run);
            WatchlistScreenView view = new WatchlistScreenView(
                    viewModel, item -> { }, () -> { }, text -> { });

            assertNoMissingNames("관심종목", view.create());
        });
    }

    /** 연동 전이라 값이 비어 있어도 조작 요소에는 이름이 있어야 한다. */
    @Test
    @DisplayName("스캐너 화면의 모든 조작 요소에 이름이 있다")
    void scannerScreenNamesEverythingFocusable() {
        JavaFxToolkit.onFxThread(() -> {
            ScannerScreenView view = new ScannerScreenView(
                    new ScannerViewModel(), text -> { }, symbol -> { });

            assertNoMissingNames("스캐너", view.create());
        });
    }

    /**
     * 검사기가 실제로 잡는지 확인한다.
     *
     * <p>통과만 하고 아무것도 지키지 않는 검사는 없느니만 못하다. 이름 없는 단추를 넣어
     * 잡히는 것을 보인다. 이 단추는 툴킷 없이는 만들 수도 없었다.
     */
    @Test
    @DisplayName("이름 없는 단추를 실제로 잡아낸다")
    void actuallyCatchesAnUnnamedControl() {
        JavaFxToolkit.onFxThread(() -> {
            javafx.scene.layout.VBox panel = new javafx.scene.layout.VBox(
                    new javafx.scene.control.Button());

            List<AccessibilityAudit.Issue> issues = audit.audit(panel);

            assertEquals(1, issues.size(), "이름 없는 단추 하나를 잡아야 합니다");
            assertEquals("MISSING_ACCESSIBLE_NAME", issues.get(0).code());
        });
    }

    /**
     * 설정 화면은 이 앱에서 접근성 기능을 켜고 끄는 유일한 자리다.
     *
     * <p>조작 요소가 스물 몇 개라 이름 누락이 생기기 쉽고, 여기서 이름이 빠지면 접근성
     * 기능 자체를 켤 수 없게 된다.
     */
    @Test
    @DisplayName("설정 화면의 모든 조작 요소에 이름이 있다")
    void settingsScreenNamesEverythingFocusable() {
        JavaFxToolkit.onFxThread(() -> {
            assertNoMissingNames("설정", settingsView().create());
        });
    }

    /**
     * 화면은 값을 바꿔 돌려주기만 한다.
     *
     * <p>화면이 합성기를 직접 만지고 저장은 다른 곳에서 하면 한쪽만 도는 경우가 생긴다 —
     * 소리는 바뀌었는데 다음 실행 때 되돌아가거나 그 반대다.
     */
    @Test
    @DisplayName("설정을 바꾸면 바뀐 값 전체를 앱에 돌려준다")
    void settingsHandBackTheWholeValue() {
        JavaFxToolkit.onFxThread(() -> {
            java.util.List<org.ossproject.desktop.state.AccessibilityPreferences> changes =
                    new java.util.ArrayList<>();
            SettingsScreenView view = settingsView(changes::add);
            Node root = view.create();
            materialise(root);

            javafx.scene.control.CheckBox contrast = findCheckBox(root, "고대비");
            assertTrue(contrast != null, "고대비 스위치를 찾지 못했습니다.");
            contrast.setSelected(true);

            assertEquals(1, changes.size());
            assertTrue(changes.get(0).highContrastEnabled());
        });
    }

    /** 앞서 바꾼 값이 다음 변경에 살아 있어야 한다. 낱개로 돌려주면 서로를 덮어쓴다. */
    @Test
    @DisplayName("설정을 연달아 바꾸면 앞의 변경이 살아 있다")
    void settingsAccumulateAcrossChanges() {
        JavaFxToolkit.onFxThread(() -> {
            java.util.List<org.ossproject.desktop.state.AccessibilityPreferences> changes =
                    new java.util.ArrayList<>();
            SettingsScreenView view = settingsView(changes::add);
            Node root = view.create();
            materialise(root);

            findCheckBox(root, "고대비").setSelected(true);
            findCheckBox(root, "큰 글자").setSelected(true);

            org.ossproject.desktop.state.AccessibilityPreferences last =
                    changes.get(changes.size() - 1);
            assertTrue(last.highContrastEnabled() && last.largeTextEnabled(),
                    "앞서 바꾼 고대비가 큰 글자 변경에 덮여 사라졌습니다.");
        });
    }

    private SettingsScreenView settingsView() {
        return settingsView(preferences -> { });
    }

    private SettingsScreenView settingsView(
            java.util.function.Consumer<org.ossproject.desktop.state.AccessibilityPreferences> onChanged) {
        SettingsScreenView.Context context = new SettingsScreenView.Context(
                "키움 모의투자", "운영체제 보호",
                new javafx.beans.property.SimpleStringProperty("연결됨"),
                new javafx.beans.property.SimpleStringProperty("3개"),
                () -> "1234****",
                java.util.List::of);
        SettingsScreenView.Actions actions = new SettingsScreenView.Actions(
                onChanged, value -> { }, text -> { }, () -> { }, screen -> { }, text -> { });
        return new SettingsScreenView(
                org.ossproject.desktop.state.AccessibilityPreferences.DEFAULT, true, context, actions);
    }

    private static javafx.scene.control.CheckBox findCheckBox(Node node, String text) {
        if (node instanceof javafx.scene.control.CheckBox box && text.equals(box.getText())) {
            return box;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            return findCheckBox(scroll.getContent(), text);
        }
        if (node instanceof javafx.scene.control.TabPane tabs) {
            for (javafx.scene.control.Tab tab : tabs.getTabs()) {
                if (tab.getContent() != null) {
                    javafx.scene.control.CheckBox found = findCheckBox(tab.getContent(), text);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.control.CheckBox found = findCheckBox(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
