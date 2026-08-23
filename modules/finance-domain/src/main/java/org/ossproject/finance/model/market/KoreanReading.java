package org.ossproject.finance.model.market;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 영문이 섞인 종목명과 사람이 말한 한글을 잇는다.
 *
 * <p>거래소에 올라 있는 이름은 영문이 섞여 있다 — {@code NAVER}, {@code LG화학},
 * {@code KB금융}. 그런데 사람은 "네이버", "엘지화학", "케이비금융" 이라고 말하고
 * 인식기도 그렇게 적는다. 이름 검색은 부분 문자열로 맞추므로
 * {@code "NAVER".contains("네이버")} 는 거짓이고, 아무리 또렷하게 말해도 찾지 못한다.
 *
 * <p>실제로 그랬다. "네이버" 라고 말했는데 앱이 아무 반응도 하지 않았고, 원인은
 * 인식기도 파서도 아니라 이름의 글자 종류였다.
 *
 * <p>두 방향을 모두 만든다. 아는 종목에는 한글 읽기를 별칭으로 붙이고, 모르는 종목은
 * 말한 한글을 영문으로 되돌려 검색에 넣는다.
 */
public final class KoreanReading {

    /**
     * 통째로 읽는 이름.
     *
     * <p>글자마다 읽으면 "네이버" 가 "엔에이브이이알" 이 된다. 널리 쓰이는 이름은
     * 사람이 통째로 읽으므로 따로 적어 둔다. 긴 것부터 맞춰야 {@code POSCO} 가
     * {@code P}+{@code OSCO} 로 쪼개지지 않는다.
     */
    private static final Map<String, String> WHOLE_WORDS = wholeWords();

    /** 글자마다 읽는 법. 위 표에 없는 영문은 이렇게 읽는다. */
    private static final Map<Character, String> LETTERS = letters();

    private KoreanReading() {
    }

    private static Map<String, String> wholeWords() {
        Map<String, String> table = new LinkedHashMap<>();
        // 거래소 등록명이 실제로 영문인 것만 넣는다. 삼성전자·카카오·현대차처럼
        // 한글로 등록된 회사를 여기 넣으면 "삼성전자" 가 "SAMSUNG전자" 로 바뀐다.
        table.put("NAVER", "네이버");
        table.put("POSCO", "포스코");
        table.put("KODEX", "코덱스");
        table.put("TIGER", "타이거");
        table.put("PLUS", "플러스");
        table.put("SOL", "솔");
        table.put("ACE", "에이스");
        return Map.copyOf(table);
    }

    private static Map<Character, String> letters() {
        String[] readings = {
                "에이", "비", "씨", "디", "이", "에프", "지", "에이치", "아이", "제이",
                "케이", "엘", "엠", "엔", "오", "피", "큐", "알", "에스", "티",
                "유", "브이", "더블유", "엑스", "와이", "제트"};
        Map<Character, String> table = new LinkedHashMap<>();
        for (int index = 0; index < readings.length; index++) {
            table.put((char) ('A' + index), readings[index]);
        }
        return Map.copyOf(table);
    }

    /**
     * 이름을 사람이 말하는 한글로 바꾼다. 영문이 없으면 그대로 돌려준다.
     *
     * <pre>
     *   NAVER  -&gt; 네이버
     *   LG화학 -&gt; 엘지화학
     *   KB금융 -&gt; 케이비금융
     *   삼성전자 -&gt; 삼성전자
     * </pre>
     */
    public static String toHangul(String name) {
        if (name == null || name.isBlank()) return "";
        StringBuilder read = new StringBuilder(name.length() * 2);
        int at = 0;
        while (at < name.length()) {
            char letter = name.charAt(at);
            if (!isLatin(letter)) {
                read.append(letter);
                at++;
                continue;
            }
            int end = at;
            while (end < name.length() && isLatin(name.charAt(end))) end++;
            read.append(readLatinRun(name.substring(at, end).toUpperCase(Locale.ROOT)));
            at = end;
        }
        return read.toString();
    }

    /**
     * 말한 한글을 이름에 쓰인 영문으로 되돌린다. 되돌릴 것이 없으면 빈 문자열.
     *
     * <p>빈 문자열을 돌려주는 것이 중요하다. 바꿀 것이 없는데 원래 말을 그대로 돌려주면,
     * 부르는 쪽이 같은 검색을 두 번 하게 된다.
     *
     * <pre>
     *   네이버   -&gt; NAVER
     *   엘지화학 -&gt; LG화학
     *   삼성전자 -&gt; (빈 문자열)
     * </pre>
     */
    public static String toLatin(String spoken) {
        if (spoken == null || spoken.isBlank()) return "";
        String left = spoken.strip();

        // 앞에서부터 읽기가 이어지는 데까지만 바꾸고, 끊기면 나머지는 그대로 둔다.
        //
        // 통째로 훑으면 한글 단어 속 글자까지 바꾼다. "에스케이하이닉스" 의 "하이닉스"
        // 에 있는 "이" 가 E 로 바뀌어 "SK하E닉스" 가 되었다. 회사 이름의 영문은 늘
        // 앞에 붙으므로 앞부분만 본다.
        StringBuilder built = new StringBuilder(left.length());
        int at = 0;
        int readings = 0;
        boolean wholeWord = false;
        while (at < left.length()) {
            Match found = longestReading(left, at);
            if (found == null) break;
            built.append(found.latin());
            at += found.length();
            readings++;
            if (found.latin().length() > 1) wholeWord = true;
        }

        // 글자 하나만 바뀐 것은 우연일 가능성이 높다. "이마트" 의 "이" 를 E 로 보고
        // "E마트" 를 만들면 있지도 않은 이름으로 검색하게 된다.
        if (readings == 0 || (readings < 2 && !wholeWord)) return "";
        return built.append(left.substring(at)).toString();
    }

    private record Match(String latin, int length) {
    }

    /** 자리 하나에서 가장 길게 맞는 읽기. 짧은 것부터 맞추면 "엘지" 가 "엘"+"지" 가 된다. */
    private static Match longestReading(String spoken, int at) {
        Match best = null;
        for (Map.Entry<String, String> entry : WHOLE_WORDS.entrySet()) {
            String reading = entry.getValue();
            if (spoken.startsWith(reading, at)
                    && (best == null || reading.length() > best.length())) {
                best = new Match(entry.getKey(), reading.length());
            }
        }
        for (Map.Entry<Character, String> entry : LETTERS.entrySet()) {
            String reading = entry.getValue();
            if (spoken.startsWith(reading, at)
                    && (best == null || reading.length() > best.length())) {
                best = new Match(String.valueOf(entry.getKey()), reading.length());
            }
        }
        return best;
    }

    private static String readLatinRun(String run) {
        String whole = WHOLE_WORDS.get(run);
        if (whole != null) return whole;
        StringBuilder read = new StringBuilder(run.length() * 2);
        for (char letter : run.toCharArray()) {
            read.append(LETTERS.getOrDefault(letter, String.valueOf(letter)));
        }
        return read.toString();
    }

    private static boolean isLatin(char letter) {
        return (letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z');
    }
}
