package org.ossproject.desktop.controller;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import org.ossproject.desktop.navigation.NavigationHistory;
import org.ossproject.desktop.navigation.Screen;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 최상위 화면 생성과 내비게이션 표시 상태를 관리한다. */
public final class DesktopScreenController {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final StackPane host;
    private final Map<Screen, Button> navigationButtons;
    private final Consumer<String> status;
    private final Map<Screen, Supplier<? extends Node>> factories = new EnumMap<>(Screen.class);
    private final Map<Screen, Node> contentCache = new EnumMap<>(Screen.class);
    private final Set<Screen> statePreservingScreens = EnumSet.noneOf(Screen.class);
    private final Map<Screen, Node> focusedNodes = new EnumMap<>(Screen.class);
    private final Map<Screen, Screen> sidebarOwners = new EnumMap<>(Screen.class);
    private final NavigationHistory history = new NavigationHistory();
    private final ReadOnlyBooleanWrapper canGoBack = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyObjectWrapper<Screen> currentScreen = new ReadOnlyObjectWrapper<>();
    private final ChangeListener<Node> focusListener = (obs, old, focused) -> rememberFocusedNode(focused);
    private Screen selectedSidebar;

    public DesktopScreenController(StackPane host, Map<Screen, Button> navigationButtons, Consumer<String> status) {
        this.host = Objects.requireNonNull(host, "host");
        this.navigationButtons = Objects.requireNonNull(navigationButtons, "navigationButtons");
        this.status = Objects.requireNonNull(status, "status");
        host.sceneProperty().addListener((obs, old, scene) -> observeScene(old, scene));
        if (host.getScene() != null) observeScene(null, host.getScene());
    }

    public void register(Screen screen, Supplier<? extends Node> factory) {
        Screen checkedScreen = Objects.requireNonNull(screen, "screen");
        factories.put(checkedScreen, Objects.requireNonNull(factory, "factory"));
        statePreservingScreens.remove(checkedScreen);
        contentCache.remove(checkedScreen);
    }

    /** 검색 조건처럼 사용자가 조작한 화면 상태를 재방문 때 그대로 복원한다. */
    public void registerPreservingState(Screen screen, Supplier<? extends Node> factory) {
        register(screen, factory);
        statePreservingScreens.add(screen);
    }

    public void show(Screen screen) {
        requireFactory(screen);
        rememberFocusedNode();
        history.visit(screen);
        display(screen);
    }

    /** 이전 화면과 그 화면의 컨트롤·스크롤·선택 상태를 복원한다. */
    public boolean goBack() {
        rememberFocusedNode();
        return history.back().map(screen -> {
            display(screen);
            return true;
        }).orElse(false);
    }

    /** 다음에 화면을 열 때 최신 컨텍스트로 다시 만들도록 캐시를 비운다. */
    public void invalidate(Screen screen) {
        contentCache.remove(Objects.requireNonNull(screen, "screen"));
        focusedNodes.remove(screen);
        if (!screen.shownInSidebar()) sidebarOwners.remove(screen);
    }

    public ReadOnlyBooleanProperty canGoBackProperty() {
        return canGoBack.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<Screen> currentScreenProperty() {
        return currentScreen.getReadOnlyProperty();
    }

    public java.util.Optional<Screen> currentScreen() {
        return history.current();
    }

    /** F6 영역 탐색 등에서 본문의 첫 실제 컨트롤로 포커스를 옮긴다. */
    public void focusContent() {
        Node content = host.getChildren().isEmpty() ? null : host.getChildren().get(0);
        if (content == null) return;
        Node target = firstFocusable(content);
        Platform.runLater(() -> (target == null ? content : target).requestFocus());
    }

    private void display(Screen screen) {
        Supplier<? extends Node> factory = requireFactory(screen);
        Node content = statePreservingScreens.contains(screen)
                ? contentCache.computeIfAbsent(screen,
                ignored -> Objects.requireNonNull(factory.get(), "screen factory result"))
                : Objects.requireNonNull(factory.get(), "screen factory result");
        if (!statePreservingScreens.contains(screen)) focusedNodes.remove(screen);

        // 상세 화면처럼 사이드바에 없는 하위 화면은 진입한 상위 메뉴를 기억해 복원한다.
        if (screen.shownInSidebar()) {
            selectedSidebar = screen;
            sidebarOwners.put(screen, screen);
        } else if (!sidebarOwners.containsKey(screen) && selectedSidebar != null) {
            sidebarOwners.put(screen, selectedSidebar);
        }
        Screen sidebarOwner = sidebarOwners.get(screen);
        if (sidebarOwner != null) {
            selectedSidebar = sidebarOwner;
            navigationButtons.forEach((candidate, button) ->
                    button.pseudoClassStateChanged(SELECTED, candidate == sidebarOwner));
        }

        host.getChildren().setAll(content);
        currentScreen.set(screen);
        canGoBack.set(history.canGoBack());
        status.accept(screen.label() + " 화면");

        Node previousFocus = focusedNodes.get(screen);
        Platform.runLater(() -> {
            if (previousFocus != null && previousFocus.getScene() == host.getScene()) {
                previousFocus.requestFocus();
                return;
            }
            Node first = firstFocusable(content);
            if (first != null) first.requestFocus();
            else content.requestFocus();
        });
    }

    private Supplier<? extends Node> requireFactory(Screen screen) {
        Supplier<? extends Node> factory = factories.get(Objects.requireNonNull(screen, "screen"));
        if (factory == null) throw new IllegalStateException("등록되지 않은 화면: " + screen);
        return factory;
    }

    private void rememberFocusedNode() {
        rememberFocusedNode(host.getScene() == null ? null : host.getScene().getFocusOwner());
    }

    private void rememberFocusedNode(Node focused) {
        Screen current = history.current().orElse(null);
        if (current != null && statePreservingScreens.contains(current)
                && focused != null && isDescendantOf(focused, host)) {
            focusedNodes.put(current, focused);
        }
    }

    private void observeScene(Scene oldScene, Scene newScene) {
        if (oldScene != null) oldScene.focusOwnerProperty().removeListener(focusListener);
        if (newScene != null) newScene.focusOwnerProperty().addListener(focusListener);
    }

    private boolean isDescendantOf(Node node, Parent ancestor) {
        for (Node candidate = node; candidate != null; candidate = candidate.getParent()) {
            if (candidate == ancestor) return true;
        }
        return false;
    }

    private Node firstFocusable(Node node) {
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node focusable = firstFocusable(child);
                if (focusable != null) return focusable;
            }
        }
        return node.isFocusTraversable() && !node.isDisabled() && node.isVisible() ? node : null;
    }
}
