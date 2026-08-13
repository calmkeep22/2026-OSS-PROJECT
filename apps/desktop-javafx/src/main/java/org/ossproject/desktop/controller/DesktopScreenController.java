package org.ossproject.desktop.controller;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import org.ossproject.desktop.navigation.Screen;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 최상위 화면 생성과 내비게이션 표시 상태를 관리한다. */
public final class DesktopScreenController {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final StackPane host;
    private final Map<Screen, Button> navigationButtons;
    private final Consumer<String> status;
    private final Map<Screen, Supplier<? extends Node>> factories = new EnumMap<>(Screen.class);

    public DesktopScreenController(StackPane host, Map<Screen, Button> navigationButtons, Consumer<String> status) {
        this.host = Objects.requireNonNull(host, "host");
        this.navigationButtons = Objects.requireNonNull(navigationButtons, "navigationButtons");
        this.status = Objects.requireNonNull(status, "status");
    }

    public void register(Screen screen, Supplier<? extends Node> factory) {
        factories.put(Objects.requireNonNull(screen, "screen"), Objects.requireNonNull(factory, "factory"));
    }

    public void show(Screen screen) {
        Supplier<? extends Node> factory = factories.get(screen);
        if (factory == null) throw new IllegalStateException("등록되지 않은 화면: " + screen);
        navigationButtons.forEach((candidate, button) ->
                button.pseudoClassStateChanged(SELECTED, candidate == screen));
        Node content = factory.get();
        host.getChildren().setAll(content);
        content.requestFocus();
        status.accept(screen.label() + " 화면");
    }
}
