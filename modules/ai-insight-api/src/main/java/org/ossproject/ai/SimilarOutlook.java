package org.ossproject.ai;

/**
 * 닮은 구간 다음에 실제로 무슨 일이 있었나.
 *
 * <p>유사도 기능이 사실로 말할 수 있는 거의 전부다. 오른 경우 몇 건 · 내린 경우 몇
 * 건까지만 센다. 평균 수익률로 요약하지 않는 이유는 요약하는 순간 예측처럼 읽히기
 * 때문이다. 서비스는 중앙 수익률도 함께 주지만 같은 이유로 쓰지 않는다.
 *
 * <p>{@code note} 와 {@code disclaimer} 는 서비스가 쓴 문장이다. 우리가 고쳐 쓰지
 * 않는다. 같은 뜻으로 바꿔 쓰면 무엇이 서비스의 주장이고 무엇이 우리 해석인지
 * 사용자가 구별할 수 없다.
 *
 * @param matches 닮은 구간을 몇 건 세었는지. 표본 수다
 * @param up      그중 다음에 오른 건수
 * @param down    그중 다음에 내린 건수
 * @param note    건수에 딸린 단서. 표본이 적다는 사실이 여기 들어 있다
 */
public record SimilarOutlook(int matches, int up, int down, String note, String disclaimer) {

    public SimilarOutlook {
        if (matches < 0 || up < 0 || down < 0) {
            throw new IllegalArgumentException("건수는 음수일 수 없습니다.");
        }
        note = note == null ? "" : note.trim();
        disclaimer = disclaimer == null ? "" : disclaimer.trim();
    }

    /** 셀 것이 없으면 문장을 만들지 않는다. 0건 0건은 사실이 아니라 자료 없음이다. */
    public boolean hasCounts() {
        return up + down > 0;
    }

    /**
     * 읽어 줄 문장.
     *
     * <p>건수를 앞에 두지 않고 무엇을 센 것인지 먼저 말한다. 표본 수를 함께 말하는
     * 이유는 "3건 상승" 만 들으면 그것이 3건 중 3건인지 300건 중 3건인지 모르기 때문이다.
     */
    public String describe() {
        String counts = "닮은 구간 " + matches + "건 가운데 다음에 오른 경우 " + up
                + "건, 내린 경우 " + down + "건이었습니다.";
        return note.isBlank() ? counts : counts + " " + note;
    }
}
