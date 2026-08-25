package org.ossproject.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 지수나 환율 하나.
 *
 * <p>{@code asOf} 를 선택 사항으로 두지 않았다. 미국장은 한국 시간으로 하루 늦고,
 * 주말에는 금요일 종가가 월요일 내내 남는다. 날짜 없이 숫자만 보여 주면 사용자는
 * 그것을 오늘 값으로 읽는다. 화면이 날짜를 빼고 숫자만 골라 표시할 수 없도록
 * 값 안에 넣었다 — {@link AiInsight} 가 단서를 값에 넣는 것과 같은 이유다.
 *
 * <p>{@code changePercent} 는 비어 있을 수 있다. 직전 거래일을 받지 못하면 등락을
 * 낼 수 없고, 그때 0.0 으로 채우면 "보합" 으로 읽힌다. 모르는 것과 안 움직인 것은
 * 다르다.
 */
public record MarketIndex(String code, String name, String group,
                          BigDecimal value, Optional<BigDecimal> changePercent,
                          LocalDate asOf) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일");

    public MarketIndex {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("지표 코드는 필수입니다.");
        }
        if (value == null) {
            throw new IllegalArgumentException("지표 값은 필수입니다. 받지 못했으면 이 값을 만들지 않습니다.");
        }
        if (asOf == null) {
            throw new IllegalArgumentException("기준일은 필수입니다. 날짜 없는 시세는 오늘 값으로 읽힙니다.");
        }
        name = name == null || name.isBlank() ? code : name;
        group = group == null ? "" : group;
        changePercent = changePercent == null ? Optional.empty() : changePercent;
    }

    /** 상승·하락·보합. 등락을 받지 못했으면 빈 문자열 — 모르는 것을 보합이라 하지 않는다. */
    public String direction() {
        return changePercent.map(percent -> switch (percent.signum()) {
            case 1 -> "상승";
            case -1 -> "하락";
            default -> "보합";
        }).orElse("");
    }

    /** 화면에 쓸 값. 천 단위를 끊는다. */
    public String formattedValue() {
        return String.format("%,.2f", value);
    }

    /**
     * 화면에 쓸 등락.
     *
     * <p>부호 대신 말로 쓴다. "-3.12%" 는 색과 기호로만 방향을 전하는데, 색을 못 보는
     * 사용자에게는 앞의 작대기 하나가 전부다. 절댓값에 "하락" 을 붙이면 색이 없어도
     * 방향이 남는다.
     */
    public String changeText() {
        return changePercent
                .map(percent -> percent.signum() == 0
                        ? "보합"
                        : String.format("%.2f%% %s", percent.abs(), direction()))
                .orElse("등락 미상");
    }

    /** "8월 24일 기준". 오늘 값이 아닐 수 있다는 유일한 단서다. */
    public String asOfText() {
        return DAY.format(asOf) + " 기준";
    }

    /**
     * 읽어 줄 말.
     *
     * <p>기준일을 뺄 수 없게 여기서 함께 만든다. 화면이 문장을 새로 지으면 날짜가
     * 빠진 문장이 나온다.
     */
    public String spoken() {
        String movement = changePercent
                .map(percent -> percent.signum() == 0 ? "보합"
                        : String.format("%.2f퍼센트 %s", percent.abs(), direction()))
                .orElse("등락은 받지 못했습니다");
        return name + " " + formattedValue() + ", " + movement + ". " + asOfText() + ".";
    }
}
