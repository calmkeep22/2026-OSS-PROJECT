package org.ossproject.desktop.state;

/** 매매일지 한 행. 금액은 현재 화면 표시 문자열이며 실제 주문·체결 원장과 분리한다. */
public record JournalEntry(
        String date,
        String securityName,
        String buyAmount,
        String sellAmount,
        String profitLoss,
        String memo,
        String tags
) {
    public JournalEntry {
        date = required(date, "date");
        securityName = required(securityName, "securityName");
        buyAmount = fallback(buyAmount, "0원");
        sellAmount = fallback(sellAmount, "0원");
        profitLoss = fallback(profitLoss, "0원");
        memo = optional(memo);
        tags = optional(tags);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
