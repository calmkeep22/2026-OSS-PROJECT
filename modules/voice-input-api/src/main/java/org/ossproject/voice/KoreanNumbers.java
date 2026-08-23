package org.ossproject.voice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 말로 한 수량을 숫자로 바꾼다.
 *
 * <p>같은 열 주를 사람마다 다르게 말한다. "십 주", "열 주", "10주" 가 모두 나오고,
 * 인식기도 어느 쪽으로 적을지 일정하지 않다. 실제로 재 보니 같은 발화를 whisper 는
 * "매수 10주" 로, 다른 번에는 "매수열조" 로 적었다.
 *
 * <p>세 자리까지만 다룬다. 천 주 넘는 주문을 말로 넣는 것은 이 기능이 노리는 바가 아니고,
 * 자릿수를 잘못 알아들었을 때 손실이 커진다.
 */
public final class KoreanNumbers {

    private static final Pattern DIGITS = Pattern.compile("(\\d{1,3})");

    /** 한자어 수. "이십오" 처럼 자리를 곱하고 더한다. */
    private static final Map<Character, Integer> SINO_DIGIT = Map.of(
            '일', 1, '이', 2, '삼', 3, '사', 4, '오', 5,
            '육', 6, '칠', 7, '팔', 8, '구', 9);

    /** 고유어 수. 조합 규칙이 한자어와 달라 통째로 적어 둔다. */
    private static final Map<String, Integer> NATIVE = buildNative();

    private KoreanNumbers() {
    }

    private static Map<String, Integer> buildNative() {
        Map<String, Integer> table = new LinkedHashMap<>();
        String[] ones = {"", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉"};
        String[] tens = {"", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔"};
        // 긴 것부터 넣어야 "열다섯" 이 "열" 로 먼저 잘리지 않는다.
        for (int ten = 9; ten >= 0; ten--) {
            for (int one = 9; one >= 0; one--) {
                int value = ten * 10 + one;
                if (value == 0) continue;
                table.put(tens[ten] + ones[one], value);
            }
        }
        // 수량을 셀 때만 쓰는 꼴. "하나", "둘" 은 "한 주", "두 주" 와 같은 수다.
        table.put("하나", 1);
        table.put("둘", 2);
        table.put("셋", 3);
        table.put("넷", 4);
        return Map.copyOf(table);
    }

    /**
     * 말에서 주문 수량을 찾는다.
     *
     * <p>수량을 못 찾으면 비워서 돌려준다. 하나로 넘겨짚지 않는다. 사용자가 수량을 말하지
     * 않았는데 1주로 정해 버리면, 말하지 않은 주문이 나간다.
     */
    public static OptionalInt quantityIn(String text) {
        if (text == null || text.isBlank()) return OptionalInt.empty();
        String plain = KnownStock.normalize(text);

        // 종목 코드(여섯 자리)가 수량으로 잡히면 안 된다. 먼저 지운다.
        plain = plain.replaceAll("\\d{6,}", " ");

        Matcher digits = DIGITS.matcher(plain);
        if (digits.find()) {
            int value = Integer.parseInt(digits.group(1));
            if (value > 0) return OptionalInt.of(value);
        }
        return spelledOut(plain);
    }

    private static OptionalInt spelledOut(String plain) {
        for (Map.Entry<String, Integer> entry : NATIVE.entrySet()) {
            if (plain.contains(entry.getKey())) return OptionalInt.of(entry.getValue());
        }
        return sino(plain);
    }

    /** 한자어 수를 자리별로 읽는다. "백이십삼" 은 123, "십" 은 10. */
    private static OptionalInt sino(String plain) {
        int total = 0;
        int current = 0;
        boolean seen = false;
        for (char letter : plain.toCharArray()) {
            Integer digit = SINO_DIGIT.get(letter);
            if (digit != null) {
                current = digit;
                seen = true;
            } else if (letter == '십') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
                seen = true;
            } else if (letter == '백') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
                seen = true;
            } else if (seen && total + current > 0) {
                // 수를 읽다가 수가 아닌 글자를 만나면 거기서 끊는다.
                break;
            }
        }
        int value = total + current;
        return seen && value > 0 && value < 1000 ? OptionalInt.of(value) : OptionalInt.empty();
    }
}
