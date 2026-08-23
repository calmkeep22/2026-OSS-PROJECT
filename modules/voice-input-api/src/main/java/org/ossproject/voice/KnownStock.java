package org.ossproject.voice;

import org.ossproject.finance.model.market.KoreanReading;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 말로 부를 수 있는 종목 하나.
 *
 * <p>이 모듈은 시세도 계좌도 모른다. 말을 종목에 붙이는 데 필요한 것만 담는다.
 * 앱이 보유·관심·최근 조회 종목을 모아 넘겨 준다.
 *
 * @param symbol  종목 코드
 * @param name    종목명
 * @param aliases 말로 부르는 다른 이름. 인식기가 "에스케이하이닉스" 로 적을지
 *                "SK하이닉스" 로 적을지 알 수 없으므로 둘 다 등록해 둔다.
 */
public record KnownStock(String symbol, String name, List<String> aliases) {

    public KnownStock {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(name, "name");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (symbol.isBlank()) throw new IllegalArgumentException("symbol 이 비었습니다.");
        if (name.isBlank()) throw new IllegalArgumentException("name 이 비었습니다.");
    }

    public KnownStock(String symbol, String name) {
        this(symbol, name, List.of());
    }

    /**
     * 이 종목을 부를 수 있는 모든 말. 종목명과 별칭, 그리고 코드 자체.
     *
     * <p>코드를 넣는 이유는 "공오오구삼공" 처럼 코드를 부르는 사용자가 있기 때문이 아니라,
     * 인식기가 "005930" 을 숫자로 적어 낼 때 붙이기 위해서다.
     */
    public List<String> spokenForms() {
        java.util.Set<String> forms = new java.util.LinkedHashSet<>();
        forms.add(name);
        // 영문 이름은 사람이 한글로 읽는다. NAVER 를 "네이버" 라고 말해도 붙어야 한다.
        String read = KoreanReading.toHangul(name);
        if (!read.equals(name)) forms.add(read);
        forms.addAll(aliases);
        forms.add(symbol);
        return List.copyOf(forms);
    }

    /**
     * 붙여 볼 때 쓰는 형태. 띄어쓰기와 대소문자, 가운뎃점을 지운다.
     *
     * <p>인식기는 띄어쓰기를 제멋대로 넣는다. "관심 종목", "관심종목", "관심 종 목" 이
     * 모두 나온다. 띄어쓰기를 지우고 나면 같은 말이 된다.
     */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder builder = new StringBuilder(text.length());
        for (char letter : text.toCharArray()) {
            if (Character.isLetterOrDigit(letter)) builder.append(letter);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
