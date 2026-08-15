package org.ossproject.desktop.state;

/** 관심종목 화면과 로컬 저장소가 공유하는 타입 안전 항목. */
public record WatchlistItem(
        String group,
        String securityName,
        String displayPrice,
        String displayChange,
        String displayVolume,
        String alertText
) {
    public WatchlistItem {
        group = required(group, "group");
        securityName = required(securityName, "securityName");
        displayPrice = fallback(displayPrice, "0원");
        displayChange = fallback(displayChange, "0.00%");
        displayVolume = fallback(displayVolume, "0");
        alertText = fallback(alertText, "없음");
    }

    public WatchlistItem withGroup(String replacement) {
        return new WatchlistItem(replacement, securityName, displayPrice, displayChange, displayVolume, alertText);
    }

    public WatchlistItem withAlertText(String replacement) {
        return new WatchlistItem(group, securityName, displayPrice, displayChange, displayVolume, replacement);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
