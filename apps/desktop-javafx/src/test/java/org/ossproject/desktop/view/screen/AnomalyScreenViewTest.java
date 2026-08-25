package org.ossproject.desktop.view.screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.finance.model.account.Account;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 알림 한 줄에서 종목·등급·문장을 갈라낸다.
 *
 * <p>실제로 겹쳤다. 종목명을 " 최근 " 앞까지 통째로 잘라 "동화약품 · 주의 · 동화약품"
 * 을 이름으로 삼았고, 본문은 같은 문장을 다시 적었다. 화면에는 같은 말이 세 번 나왔고,
 * 스크린리더는 제목과 본문을 잇달아 읽어 두 번 들려주었다.
 */
@ExtendWith(JavaFxToolkit.class)
class AnomalyScreenViewTest {

    private static final String NOTIFICATION =
            "08-25 14:30 · 이상 감지 · 동화약품 · 주의 · 동화약품 최근 1분 거래량이 평소의 4.92배입니다.";

    @Test
    @DisplayName("종목명은 첫 마디만 가져온다")
    void readsOnlyTheSecurityName() {
        String content = AnomalyScreenView.messageOf(NOTIFICATION);
        assertEquals("동화약품", AnomalyScreenView.securityNameOf(content));
    }

    @Test
    @DisplayName("본문에서 종목명과 등급을 걷어낸다")
    void stripsTheRepeatedHeadFromTheDetail() {
        String content = AnomalyScreenView.messageOf(NOTIFICATION);
        String detail = AnomalyScreenView.detailOf(content);

        assertEquals("최근 1분 거래량이 평소의 4.92배입니다.", detail);
        assertFalse(detail.contains("동화약품"), "종목명이 본문에 또 나오면 안 됩니다: " + detail);
        assertFalse(detail.contains("주의"), "등급이 본문에 또 나오면 안 됩니다: " + detail);
    }

    @Test
    @DisplayName("등급과 종류를 따로 읽어 낸다")
    void readsGradeAndKind() {
        String content = AnomalyScreenView.messageOf(NOTIFICATION);
        assertEquals("주의", AnomalyScreenView.gradeOf(content));
        assertEquals("거래량 급증", AnomalyScreenView.kindOf(content));
        assertEquals("08-25 14:30", AnomalyScreenView.timeOf(NOTIFICATION));
    }

    /** 등급이 앞에 오는 형식도 들어온다. 그때는 그 뒤가 종목명이다. */
    @Test
    @DisplayName("등급이 앞에 오는 형식도 갈라낸다")
    void handlesTheGradeFirstForm() {
        String content = AnomalyScreenView.messageOf(
                "08-25 09:05 · 이상 감지 · 높음 · 삼성전자 최근 1분 거래량이 평소의 3.25배입니다.");

        assertEquals("삼성전자", AnomalyScreenView.securityNameOf(content));
        assertEquals("최근 1분 거래량이 평소의 3.25배입니다.", AnomalyScreenView.detailOf(content));
    }

    /** 모양이 다른 문장이 와도 무너지지 않는다. 신호를 통째로 잃는 것이 더 나쁘다. */
    @Test
    @DisplayName("모양이 다른 알림도 문장을 잃지 않는다")
    void keepsUnexpectedShapesReadable() {
        String content = AnomalyScreenView.messageOf("08-25 10:00 · 이상 감지 · 알 수 없는 신호입니다.");

        assertEquals("알 수 없는 신호입니다.", AnomalyScreenView.detailOf(content));
    }

    @Test
    @DisplayName("이상 신호를 키보드로 듣고 지운다")
    void listensAndDeletesFromTheKeyboard() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<String> entries = FXCollections.observableArrayList(NOTIFICATION);
            AtomicReference<String> spoken = new AtomicReference<>();
            ScrollPane root = new AnomalyScreenView(entries, 1,
                    () -> Account.of("12345678", BigDecimal.TEN), new Label("AI 분석"),
                    () -> { }, (text, id) -> spoken.set(text), ignored -> { }, () -> { },
                    (title, text) -> { }).create();
            List<ListView<?>> lists = new ArrayList<>();
            collectLists(root, lists);
            ListView<?> list = lists.stream().filter(candidate -> !candidate.getItems().isEmpty())
                    .findFirst().orElse(null);
            assertNotNull(list);

            list.fireEvent(key(KeyCode.ENTER));
            assertEquals(NOTIFICATION, spoken.get());
            list.fireEvent(key(KeyCode.DELETE));
            assertEquals(0, entries.size());
        });
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false);
    }

    private static void collectLists(Node node, List<ListView<?>> into) {
        if (node instanceof ListView<?> list) into.add(list);
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collectLists(scroll.getContent(), into);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectLists(child, into));
        }
    }
}
