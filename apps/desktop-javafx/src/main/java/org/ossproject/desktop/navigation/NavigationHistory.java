package org.ossproject.desktop.navigation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/** 브라우저의 뒤로가기처럼 최상위 화면 이동 기록을 관리한다. */
public final class NavigationHistory {
    private final Deque<Screen> backStack = new ArrayDeque<>();
    private Screen current;

    /** 새 화면을 방문한다. 같은 화면을 다시 열 때는 기록을 중복으로 쌓지 않는다. */
    public void visit(Screen screen) {
        Screen checked = Objects.requireNonNull(screen, "screen");
        if (current != null && current != checked) backStack.push(current);
        current = checked;
    }

    /** 이전 화면으로 이동한다. 기록이 없으면 빈 값을 돌려준다. */
    public Optional<Screen> back() {
        if (backStack.isEmpty()) return Optional.empty();
        current = backStack.pop();
        return Optional.of(current);
    }

    public Optional<Screen> current() {
        return Optional.ofNullable(current);
    }

    public Optional<Screen> previous() {
        return Optional.ofNullable(backStack.peek());
    }

    public boolean canGoBack() {
        return !backStack.isEmpty();
    }
}
