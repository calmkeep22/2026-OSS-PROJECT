package org.ossproject.voice;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 말 한마디를 앱이 실행할 수 있는 형태로 바꾼 것.
 *
 * <p>{@code heard} 를 항상 들고 다닌다. 실행하기 전에 "이렇게 들었습니다" 라고 되읽어
 * 주어야 하고, 알아듣지 못했을 때도 무엇으로 들었는지 알려 주어야 사용자가 다시 말할지
 * 다르게 말할지 정할 수 있다.
 *
 * @param intent   무엇을 하려는지
 * @param stock    대상 종목. 종목이 필요 없는 명령이면 비어 있다.
 * @param quantity 주문 수량. 수량을 말하지 않았으면 비어 있다.
 * @param heard    인식기가 알아들은 말 그대로
 * @param stockHint 아는 종목에 못 붙였을 때, 종목명일 법한 남은 말. 앱이 이것으로
 *                  실제 종목 검색을 걸어 본다. 붙일 것이 없으면 빈 문자열.
 */
public record VoiceCommand(Intent intent, Optional<KnownStock> stock,
                           OptionalInt quantity, String heard, String stockHint) {

    public VoiceCommand {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(stock, "stock");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(heard, "heard");
        stockHint = stockHint == null ? "" : stockHint.strip();
    }

    /** 아는 종목에는 없지만 종목명일 법한 말이 남아 있는가. */
    public boolean hasStockHint() {
        return !stockHint.isEmpty();
    }

    public VoiceCommand withStockHint(String hint) {
        return new VoiceCommand(intent, stock, quantity, heard, hint);
    }

    public static VoiceCommand unknown(String heard) {
        return new VoiceCommand(Intent.UNKNOWN, Optional.empty(), OptionalInt.empty(), heard, "");
    }

    public static VoiceCommand of(Intent intent, String heard) {
        return new VoiceCommand(intent, Optional.empty(), OptionalInt.empty(), heard, "");
    }

    public static VoiceCommand of(Intent intent, KnownStock stock, String heard) {
        return new VoiceCommand(intent, Optional.of(stock), OptionalInt.empty(), heard, "");
    }

    public static VoiceCommand order(Intent intent, KnownStock stock, int quantity, String heard) {
        if (!intent.requiresConfirmation()) {
            throw new IllegalArgumentException("주문이 아닌 명령에 수량을 붙일 수 없습니다: " + intent);
        }
        if (quantity <= 0) throw new IllegalArgumentException("수량은 1주 이상이어야 합니다: " + quantity);
        return new VoiceCommand(intent, Optional.of(stock), OptionalInt.of(quantity), heard, "");
    }

    /**
     * 되읽어 줄 때 쓰는, 길이를 자른 말.
     *
     * <p>인식기가 같은 말을 수십 번 되풀이해 내놓는 일이 있다. 실제로 2.5초짜리
     * 발화가 "이상감지" 쉰여섯 번으로 왔고, 앱이 그것을 그대로 소리내어 읽었다.
     * 화면을 볼 수 없는 사용자는 그 낭독을 멈출 방법도 마땅치 않다.
     *
     * <p>서버에서 되풀이를 줄이지만 여기서도 막는다. 되읽기는 사용자를 지키려고 있는
     * 것이지 사용자를 붙잡아 두려고 있는 것이 아니다.
     */
    private String shortHeard() {
        return heard.length() <= MAX_SPOKEN_BACK
                ? heard
                : heard.substring(0, MAX_SPOKEN_BACK) + "…";
    }

    /** 되읽어 줄 최대 글자 수. 한국어 한 문장이면 넉넉하다. */
    private static final int MAX_SPOKEN_BACK = 40;

    /** 되돌릴 수 없는 일이라 사람에게 다시 물어야 하는가. */
    public boolean requiresConfirmation() {
        return intent.requiresConfirmation();
    }

    /**
     * 바로 실행해도 되는가.
     *
     * <p>주문은 아무리 또렷하게 알아들어도 여기서 false 다. 수량을 말하지 않은 주문도
     * 마찬가지로 실행하지 않는다. 빠진 값을 채워 넣는 순간 사용자가 말하지 않은 주문이
     * 나간다.
     *
     * <p>종목을 알아야 답할 수 있는 명령({@link Intent#requiresStock})도 종목을 놓쳤으면
     * false 다. 화면에 떠 있던 종목으로 대신 답하면 사용자는 자기가 말한 종목의 답을
     * 들었다고 믿는다.
     */
    public boolean readyToRun() {
        if (intent == Intent.UNKNOWN) return false;
        if (requiresConfirmation()) return false;
        return !intent.requiresStock() || stock.isPresent();
    }

    /**
     * 실행 전에 읽어 줄 말.
     *
     * <p>들은 말과 이해한 것을 함께 담는다. 이해한 것만 읽으면 잘못 들은 것을 사용자가
     * 알아챌 수 없고, 들은 말만 읽으면 앱이 무엇을 하려는지 알 수 없다.
     */
    public String confirmationQuestion() {
        if (intent == Intent.UNKNOWN) {
            return heard.isBlank()
                    ? "아무 말도 알아듣지 못했습니다. 다시 말씀해 주세요."
                    : "\"" + shortHeard() + "\" 로 들었습니다. 알아듣지 못한 명령입니다.";
        }
        StringBuilder said = new StringBuilder("\"").append(shortHeard()).append("\" 로 들었습니다. ");
        stock.ifPresent(target -> said.append(target.name()).append(" "));
        quantity.ifPresent(count -> said.append(count).append("주 "));
        said.append(intent.label());
        return said.append(requiresConfirmation() ? " 하시겠습니까?" : " 합니다.").toString();
    }
}
