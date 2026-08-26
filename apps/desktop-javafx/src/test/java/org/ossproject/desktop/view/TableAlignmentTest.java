package org.ossproject.desktop.view;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.util.List;
import java.util.Comparator;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 표의 열 정렬.
 *
 * <p>숫자를 가운데 정렬하면 자릿수가 세로로 어긋난다. 5,080원 아래에 641,000원이 오면
 * "원" 이 서로 다른 자리에 서서, 어느 쪽이 큰지 눈으로 훑을 수 없다. 화면을 확대해 쓰는
 * 사용자에게는 한 화면에 몇 줄 안 들어오기 때문에 이 어긋남이 특히 크게 걸린다.
 */
@ExtendWith(JavaFxToolkit.class)
class TableAlignmentTest {

    @Test
    @DisplayName("숫자로 보이는 값을 가려낸다")
    void tellsNumbersFromWords() {
        for (String number : List.of("2", "5,080원", "+180원", "-3.2%", "1.77%", "680,000원",
                "0", "005930")) {
            assertTrue(UiKit.looksNumeric(number), number + " 는 숫자로 봐야 합니다");
        }
        for (String word : List.of("동화약품", "삼성화재", "매수", "KOSPI", "조회 필요",
                "—", "", "전략·메모")) {
            assertFalse(UiKit.looksNumeric(word), word + " 는 숫자가 아닙니다");
        }
    }

    @Test
    @DisplayName("모든 열을 가운데로 맞춘다")
    void alignsEveryColumnInTheCenter() {
        JavaFxToolkit.onFxThread(() -> {
            TableView<ObservableList<String>> table = UiKit.textTable("보유종목 표",
                    FXCollections.observableArrayList(
                            FXCollections.observableArrayList("동화약품", "2", "5,080원"),
                            FXCollections.observableArrayList("삼성화재", "1", "641,000원")),
                    "종목", "수량", "평균단가");

            assertTrue(table.getColumns().get(0).getStyle().contains("CENTER;"),
                    "종목 열: " + table.getColumns().get(0).getStyle());
            assertTrue(table.getColumns().get(1).getStyle().contains("CENTER;"),
                    "수량 열: " + table.getColumns().get(1).getStyle());
            assertTrue(table.getColumns().get(2).getStyle().contains("CENTER;"),
                    "평균단가 열: " + table.getColumns().get(2).getStyle());
        });
    }

    /** 머리글도 같이 옮겨야 값과 이어진다. 값만 오른쪽에 붙으면 둘이 어긋난다. */
    @Test
    @DisplayName("숫자 열도 별도 오른쪽 정렬 표시를 남기지 않는다")
    void doesNotMarkNumericHeaders() {
        JavaFxToolkit.onFxThread(() -> {
            TableView<ObservableList<String>> table = UiKit.textTable("표",
                    FXCollections.<ObservableList<String>>observableArrayList(
                            FXCollections.observableArrayList("삼성전자", "680,000원")),
                    "종목", "현재가");

            assertFalse(table.getColumns().get(0).getStyleClass().contains("numeric-column"));
            assertFalse(table.getColumns().get(1).getStyleClass().contains("numeric-column"));
        });
    }

    /**
     * 값 하나가 글자라고 열 전체가 왼쪽으로 가면 안 된다. 계좌를 아직 조회하지 못하면
     * 현재가 칸에 "조회 필요" 가 들어오는데, 그것 하나 때문에 나머지 숫자가 다 흐트러진다.
     */
    @Test
    @DisplayName("값 하나가 글자여도 과반이 숫자면 숫자 열이다")
    void oneWordDoesNotBreakANumberColumn() {
        JavaFxToolkit.onFxThread(() -> {
            TableView<ObservableList<String>> table = UiKit.textTable("표",
                    FXCollections.observableArrayList(
                            FXCollections.observableArrayList("5,080원"),
                            FXCollections.observableArrayList("641,000원"),
                            FXCollections.observableArrayList("조회 필요")),
                    "현재가");

            assertTrue(table.getColumns().get(0).getStyle().contains("CENTER;"));
        });
    }

    /** 처음엔 비어 있다가 나중에 채워지는 표가 흔하다. 그때도 다시 재야 한다. */
    @Test
    @DisplayName("값이 나중에 들어와도 다시 맞춘다")
    void realignsWhenRowsArriveLater() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            TableView<ObservableList<String>> table = UiKit.textTable("표", rows, "종목", "현재가");
            TableColumn<ObservableList<String>, ?> price = table.getColumns().get(1);

            assertTrue(price.getStyle().contains("CENTER;"), "빈 표도 가운데 정렬한다");

            rows.add(FXCollections.observableArrayList("삼성전자", "680,000원"));

            assertTrue(price.getStyle().contains("CENTER;"), "값이 온 뒤에도 가운데 정렬한다");
        });
    }

    @Test
    @DisplayName("머리글 경계에서 그은 한 선이 표 본문까지 이어진다")
    void headerGridLinesUseTheExactHeaderEdges() {
        JavaFxToolkit.onFxThread(() -> {
            TableView<ObservableList<String>> table = UiKit.textTable("표",
                    FXCollections.<ObservableList<String>>observableArrayList(
                            FXCollections.observableArrayList("국내", "005930", "삼성전자", "68,000원")),
                    "그룹", "종목코드", "종목", "현재가");
            StackPane root = new StackPane(table);
            Scene scene = new Scene(root, 1200, 360);
            scene.getStylesheets().add(Objects.requireNonNull(
                    TableAlignmentTest.class.getResource("/styles/application.css")).toExternalForm());
            root.applyCss();
            root.layout();
            table.requestLayout();
            root.layout();

            List<Node> headers = table.lookupAll(".column-header").stream()
                    .filter(node -> node instanceof Parent parent
                            && parent.getChildrenUnmodifiable().stream()
                            .anyMatch(child -> child instanceof Label label
                                    && label.getText() != null && !label.getText().isBlank()))
                    .sorted(Comparator.comparingDouble(node -> sceneBounds(node).getMinX()))
                    .toList();
            List<Line> dividers = table.lookupAll(".aligned-column-divider").stream()
                    .filter(Line.class::isInstance)
                    .map(Line.class::cast)
                    .sorted(Comparator.comparingDouble(line ->
                            line.localToScene(line.getStartX(), line.getStartY()).getX()))
                    .toList();

            assertEquals(table.getColumns().size(), headers.size());
            assertEquals(table.getColumns().size() - 1, dividers.size());
            for (int index = 0; index < dividers.size(); index++) {
                double lineX = dividers.get(index)
                        .localToScene(dividers.get(index).getStartX(), dividers.get(index).getStartY()).getX();
                assertEquals(sceneBounds(headers.get(index)).getMaxX(),
                        lineX, 0.51, "열 " + index + "의 오른쪽 경계");
                assertTrue(dividers.get(index).getEndY() > dividers.get(index).getStartY() + 100,
                        "구분선은 머리글에서 본문까지 이어져야 한다");
            }
        });
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }
}
