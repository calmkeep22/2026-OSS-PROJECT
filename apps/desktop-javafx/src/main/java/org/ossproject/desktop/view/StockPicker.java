package org.ossproject.desktop.view;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.viewmodel.StockSelection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 분석 화면 위에 놓는 종목 고르개.
 *
 * <p>청각 차트와 닮은 차트와 뉴스는 모두 "지금 고른 종목" 하나를 본다. 그런데 그 값을
 * 바꾸는 길이 검색뿐이었다. 삼성전자 청각 차트를 듣다 SK하이닉스를 들으려면 검색 화면으로
 * 나갔다가 돌아와야 한다 — 종목 하나 바꾸는 데 화면을 두 번 옮긴다.
 *
 * <p>게다가 화면에 고르개가 없으면 지금 어느 종목을 보고 있는지 알 방법이 제목뿐이다.
 * 눈으로 보면 제목이 시야에 들어오지만 스크린리더로는 처음 한 번 읽히고 지나간다.
 *
 * <p>보유 종목을 먼저, 관심 종목을 뒤에 놓는다. 돈이 들어가 있는 쪽이 먼저 와야 한다.
 * 최근 검색은 넣지 않는다 — 검색 기록은 글자일 뿐이라 종목을 특정하지 못하고, 고르는
 * 순간 다시 조회해야 해서 실패할 수 있는 항목이 목록에 섞인다.
 */
public final class StockPicker {

    private static final String HELD = "보유";
    private static final String WATCHED = "관심";
    private static final String VIEWING = "보는 중";

    private final ComboBox<Entry> box = new ComboBox<>();
    private final HBox root;
    private final ObservableList<WatchlistItem> watchlist;
    private final Consumer<StockSelection> onSelect;

    private List<StockSelection> holdings = List.of();
    private StockSelection current;
    /** 목록을 새로 채우는 동안 선택 이벤트가 나가지 않게 막는다. */
    private boolean rebuilding;
    /** 펼치기 직전의 값. Esc 로 닫으면 여기로 되돌린다. */
    private Entry valueBeforeOpen;
    /** Esc 로 닫는 중인지. 그때는 확정하지 않는다. */
    private boolean cancelled;

    /** 목록의 한 줄. 어디서 온 종목인지 함께 든다. */
    private record Entry(String source, StockSelection stock) {
        String label() {
            return source + " · " + stock.name();
        }
    }

    public StockPicker(ObservableList<WatchlistItem> watchlist, StockSelection current,
                       Consumer<StockSelection> onSelect) {
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
        this.current = Objects.requireNonNull(current, "current");
        this.onSelect = Objects.requireNonNull(onSelect, "onSelect");

        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Entry entry) {
                return entry == null ? "" : entry.label();
            }

            @Override
            public Entry fromString(String value) {
                return box.getValue();
            }
        });
        box.getStyleClass().add("stock-picker");
        box.setAccessibleText("종목 선택");
        // 목록을 펼친 동안에는 확정하지 않는다.
        //
        // JavaFX 고르개는 펼친 채로 방향키를 누르면 그때마다 값이 바뀐다. 보통은 무해한
        // 동작이지만 여기서는 한 번 바뀔 때마다 화면을 다시 세우고 시세를 새로 받는다.
        // 다섯 개를 훑어보면 다섯 번 갈아엎는 셈이고, 소리로 이름을 확인하며 내려가는
        // 사용자에게는 훑어보는 일 자체가 불가능하다.
        //
        // 펼친 동안은 옮겨만 다니고, 닫힐 때 확정한다. Enter 나 클릭으로 닫으면 그 값이
        // 확정되고, Esc 로 닫으면 펼치기 전 값으로 되돌린다.
        box.setOnShowing(event -> valueBeforeOpen = box.getValue());
        box.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && box.isShowing()) {
                cancelled = true;
            }
        });
        box.showingProperty().addListener((observed, was, showing) -> {
            if (showing) {
                cancelled = false;
                return;
            }
            if (cancelled) {
                cancelled = false;
                rebuilding = true;
                box.setValue(valueBeforeOpen);
                rebuilding = false;
                return;
            }
            commit(box.getValue());
        });
        box.valueProperty().addListener((observable, old, selected) -> {
            // 펼친 동안의 값 변화는 아직 고른 것이 아니다. 닫힐 때 한 번만 확정한다.
            if (box.isShowing()) {
                return;
            }
            commit(selected);
        });

        // "종목" 이라는 글자를 옆에 두지 않는다. 고르개 안에 이미 "보는 중 · NAVER" 가
        // 적혀 있어 무엇을 고르는 자리인지 보이고, 스크린리더에는 box 의 접근성 이름
        // ("종목 선택, 현재 …")이 그대로 읽힌다. 글자를 빼도 잃는 것이 없다.
        root = new HBox(8, box);
        root.setAlignment(Pos.CENTER_LEFT);
        root.getStyleClass().add("stock-picker-row");

        // 관심 목록이 바뀌면 따라간다. 방금 담은 종목이 목록에 없으면 다시 검색해야 한다.
        watchlist.addListener((ListChangeListener<WatchlistItem>) change -> rebuild());
        rebuild();
    }

    /** 고른 것을 실제로 적용한다. 같은 종목이면 아무 일도 하지 않는다. */
    private void commit(Entry selected) {
        if (rebuilding || selected == null
                || selected.stock().securityId().equals(this.current.securityId())) {
            return;
        }
        this.current = selected.stock();
        onSelect.accept(selected.stock());
    }

    public javafx.scene.Node root() {
        return root;
    }

    /**
     * 보유 종목을 채운다.
     *
     * <p>계좌 조회가 끝나야 알 수 있어 나중에 온다. 그때까지는 관심 종목만 보인다 —
     * 비어 있는 것보다 낫고, 채워질 때 지금 고른 종목은 그대로 남는다.
     */
    public void setHoldings(List<StockSelection> loaded) {
        this.holdings = loaded == null ? List.of() : List.copyOf(loaded);
        rebuild();
    }

    private void rebuild() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (StockSelection stock : holdings) {
            entries.putIfAbsent(key(stock), new Entry(HELD, stock));
        }
        for (WatchlistItem item : watchlist) {
            if (item.needsIdentityRepair()) {
                continue;
            }
            StockSelection stock = new StockSelection(item.market(), item.symbol(),
                    item.securityName(), item.exchange(), item.currency());
            entries.putIfAbsent(key(stock), new Entry(WATCHED, stock));
        }
        // 지금 보는 종목이 어느 목록에도 없을 수 있다. 검색으로 찾아 들어온 경우다.
        // 빼 버리면 고르개가 다른 종목을 가리켜 화면 내용과 어긋난다.
        entries.putIfAbsent(key(current), new Entry(VIEWING, current));

        List<Entry> items = new ArrayList<>(entries.values());
        Entry selected = entries.get(key(current));

        rebuilding = true;
        box.setItems(FXCollections.observableArrayList(items));
        box.setValue(selected);
        rebuilding = false;

        box.setAccessibleText("종목 선택, 현재 " + current.name() + ", 모두 " + items.size() + "개");
    }

    /** 화면이 다른 길로 종목을 바꿨을 때 고르개를 맞춘다. */
    public void select(StockSelection stock) {
        this.current = Objects.requireNonNull(stock, "stock");
        rebuild();
    }

    private static String key(StockSelection stock) {
        return stock.exchange().toUpperCase(java.util.Locale.ROOT) + ':' + stock.symbol();
    }
}
