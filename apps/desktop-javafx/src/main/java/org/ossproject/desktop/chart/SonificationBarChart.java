package org.ossproject.desktop.chart;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ossproject.sonification.model.TimeSeriesSample;

import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Compact visual companion for the audible playback points. */
final class SonificationBarChart extends StackPane {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final NumberFormat PRICE = NumberFormat.getNumberInstance(Locale.KOREA);
    private static final double MINIMUM_BAR_HEIGHT = 22;
    private static final double MAXIMUM_BAR_HEIGHT = 142;

    private final List<Region> bars = new ArrayList<>();
    private final IntConsumer seekAction;

    SonificationBarChart(List<TimeSeriesSample> samples, IntConsumer seekAction) {
        this.seekAction = Objects.requireNonNull(seekAction, "seekAction");
        getStyleClass().add("sonification-chart");
        setAccessibleRole(AccessibleRole.PARENT);
        setAccessibleText("소리로 재생되는 가격 흐름 막대 그래프");
        setSamples(samples);
    }

    void setSamples(List<TimeSeriesSample> samples) {
        List<TimeSeriesSample> points = List.copyOf(Objects.requireNonNull(samples, "samples"));
        bars.clear();
        getChildren().clear();

        double minimum = points.stream().mapToDouble(TimeSeriesSample::value).min().orElse(0);
        double maximum = points.stream().mapToDouble(TimeSeriesSample::value).max().orElse(minimum);
        double span = Math.max(1.0, maximum - minimum);

        HBox plot = new HBox(5);
        plot.getStyleClass().add("sonification-bars");
        plot.setAlignment(Pos.BOTTOM_CENTER);
        // HBox fills children to its own height by default, which makes every bar look identical.
        plot.setFillHeight(false);
        for (int index = 0; index < points.size(); index++) {
            TimeSeriesSample point = points.get(index);
            double ratio = (point.value() - minimum) / span;
            Region bar = new Region();
            bar.getStyleClass().add("sonification-bar");
            bar.setMinWidth(5);
            bar.setPrefWidth(18);
            bar.setMaxWidth(Double.MAX_VALUE);
            double height = MINIMUM_BAR_HEIGHT + ratio * (MAXIMUM_BAR_HEIGHT - MINIMUM_BAR_HEIGHT);
            bar.setMinHeight(height);
            bar.setPrefHeight(height);
            bar.setMaxHeight(height);
            HBox.setHgrow(bar, Priority.ALWAYS);

            String description = (index + 1) + "번째 재생 지점, " + DATE.format(point.timestamp())
                    + ", " + PRICE.format(Math.round(point.value())) + "원";
            bar.setAccessibleRole(AccessibleRole.BUTTON);
            bar.setAccessibleText(description);
            Tooltip.install(bar, new Tooltip(description));
            int playbackIndex = index;
            bar.setOnMouseClicked(event -> seekAction.accept(playbackIndex));
            bars.add(bar);
            plot.getChildren().add(bar);
        }

        Label low = new Label("낮은 가격");
        Label high = new Label("높은 가격");
        low.getStyleClass().add("sonification-axis-label");
        high.getStyleClass().add("sonification-axis-label");
        Region axisSpacer = new Region();
        VBox.setVgrow(axisSpacer, Priority.ALWAYS);
        VBox axis = new VBox(high, axisSpacer, low);
        axis.getStyleClass().add("sonification-axis");
        axis.setMinWidth(56);
        BorderPane chartLayout = new BorderPane();
        chartLayout.setLeft(axis);
        chartLayout.setCenter(plot);
        getChildren().add(chartLayout);
    }

    void select(int index) {
        for (int i = 0; i < bars.size(); i++) {
            bars.get(i).pseudoClassStateChanged(SELECTED, i == index);
        }
    }
}
