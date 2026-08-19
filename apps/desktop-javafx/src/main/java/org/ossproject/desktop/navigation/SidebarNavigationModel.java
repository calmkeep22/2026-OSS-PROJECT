package org.ossproject.desktop.navigation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 사이드바의 펼침 상태를 JavaFX 컨트롤과 분리해 관리한다.
 *
 * <p>동시에 하나의 그룹만 펼칠 수 있고, 화면 이동 시 해당 화면이 속한
 * 그룹을 자동으로 드러낸다.</p>
 */
public final class SidebarNavigationModel {
    private Screen.NavigationGroup expandedGroup;

    public SidebarNavigationModel() {
        this(Screen.NavigationGroup.OVERVIEW);
    }

    SidebarNavigationModel(Screen.NavigationGroup initiallyExpanded) {
        expandedGroup = Objects.requireNonNull(initiallyExpanded, "initiallyExpanded");
    }

    public Optional<Screen.NavigationGroup> expandedGroup() {
        return Optional.ofNullable(expandedGroup);
    }

    public boolean isExpanded(Screen.NavigationGroup group) {
        return expandedGroup == Objects.requireNonNull(group, "group");
    }

    /** 이미 열린 그룹을 누르면 닫고, 다른 그룹을 누르면 그 그룹만 연다. */
    public void toggle(Screen.NavigationGroup group) {
        Screen.NavigationGroup checkedGroup = Objects.requireNonNull(group, "group");
        expandedGroup = expandedGroup == checkedGroup ? null : checkedGroup;
    }

    public void expand(Screen.NavigationGroup group) {
        expandedGroup = Objects.requireNonNull(group, "group");
    }

    public void collapse(Screen.NavigationGroup group) {
        if (expandedGroup == Objects.requireNonNull(group, "group")) expandedGroup = null;
    }

    public void reveal(Screen screen) {
        expand(Objects.requireNonNull(screen, "screen").navigationGroup());
    }

    /** 사이드바에 실제로 노출되는 하위 화면만 선언 순서대로 반환한다. */
    public List<Screen> children(Screen.NavigationGroup group) {
        Screen.NavigationGroup checkedGroup = Objects.requireNonNull(group, "group");
        return Arrays.stream(Screen.values())
                .filter(Screen::shownInSidebar)
                .filter(screen -> screen.navigationGroup() == checkedGroup)
                .toList();
    }
}
