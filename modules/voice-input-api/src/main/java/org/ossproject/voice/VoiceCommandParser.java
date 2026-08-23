package org.ossproject.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 알아들은 말을 명령으로 바꾼다.
 *
 * <p>인식기를 아무리 좋은 것으로 바꿔도 한국어 종목명은 틀린다. 실측에서 whisper 는
 * "관심종목" 을 "관심 좀 목" 으로, "청각 차트" 를 "청각착도" 로 적었다. 그래서 정확도를
 * 인식기에만 맡기지 않고 여기서 한 번 더 붙인다. 아는 낱말 목록이 있으니 가까운 것을
 * 고를 수 있다 — 인식기는 그 목록을 모른다.
 *
 * <p>붙이는 기준은 명령 종류마다 다르다. 화면 이동은 조금 헐겁게 붙여도 잘못 붙으면
 * 화면만 바뀌고 되돌리면 그만이다. 매수·매도·취소는 빡빡하게 붙인다. 잘못 붙으면
 * 되돌릴 수 없는 일이 벌어진다.
 *
 * <p>못 알아들으면 {@link Intent#UNKNOWN} 을 낸다. 비슷한 것을 억지로 고르지 않는다.
 */
public final class VoiceCommandParser {

    /** 화면 이동·조회에 쓰는 낱말. 값이 여럿인 것은 사람마다 다르게 부르기 때문이다. */
    private static final Map<Intent, List<String>> SAFE_WORDS = safeWords();

    /** 되돌릴 수 없는 일에 쓰는 낱말. 여기만 따로 두어 붙이는 기준을 다르게 준다. */
    private static final Map<Intent, List<String>> RISKY_WORDS = ordered(Map.entry(
            Intent.BUY, List.of("매수", "사줘", "삽니다", "매수해줘")), Map.entry(
            Intent.SELL, List.of("매도", "팔아", "팝니다", "매도해줘")), Map.entry(
            Intent.CANCEL_ORDER, List.of("주문취소", "취소해줘")));

    /**
     * 순서를 지키는 표를 만든다.
     *
     * <p>{@code Map.of} 와 {@code Map.copyOf} 를 쓰면 안 된다. 그 둘이 돌려주는 표는
     * 순회 순서가 JVM 마다 달라진다. 아래 {@code bestMatch} 는 거리와 길이가 모두 같을 때
     * 먼저 나온 것을 고르므로, 순서가 흔들리면 같은 말이 실행할 때마다 다른 명령이 된다.
     * 실제로 "예수금 얼마야" 가 어느 번에는 잔고 확인, 어느 번에는 현재가 조회가 되었다.
     */
    @SafeVarargs
    private static Map<Intent, List<String>> ordered(Map.Entry<Intent, List<String>>... entries) {
        Map<Intent, List<String>> table = new LinkedHashMap<>();
        for (Map.Entry<Intent, List<String>> entry : entries) table.put(entry.getKey(), entry.getValue());
        return java.util.Collections.unmodifiableMap(table);
    }

    /**
     * 문법에 등록해 둘 수량.
     *
     * <p>1주부터 999주까지 모두 등록하면 종목마다 이천 문장이 된다. 인식기가 느려지고
     * 비슷한 수끼리 헷갈린다. 자주 쓰는 몇 개만 등록하고, 나머지 수량은 자유 발화
     * 인식기를 쓸 때만 된다 — 그것이 {@link RecognitionMode#COMMAND_GRAMMAR} 의 뜻이다.
     */
    private static final List<Integer> GRAMMAR_QUANTITIES = List.of(1, 5, 10, 50, 100);

    /**
     * 명령을 이루는 말 조각. 종목명을 골라낼 때 걷어 낸다.
     *
     * <p>사용자는 "네이버 현재가 알려줘" 처럼 말한다. 아는 종목에 못 붙였을 때 남은
     * 말로 실제 종목 검색을 걸려면, 명령에 해당하는 말은 빼야 "네이버" 만 남는다.
     */
    private static final List<String> COMMAND_FILLERS = List.of(
            "알려줘", "보여줘", "열어줘", "읽어줘", "들려줘", "해줘", "해라", "가줘",
            "페이지", "화면", "으로", "이동", "좀", "줘", "주세요", "부탁해");

    private final List<KnownStock> known;

    public VoiceCommandParser(List<KnownStock> known) {
        this.known = known == null ? List.of() : List.copyOf(known);
    }

    private static Map<Intent, List<String>> safeWords() {
        Map<Intent, List<String>> words = new LinkedHashMap<>();
        words.put(Intent.OPEN_WATCHLIST, List.of("관심종목", "관심목록"));
        words.put(Intent.ADD_TO_WATCHLIST,
                List.of("관심종목에담아줘", "관심종목추가", "관심종목에넣어줘"));
        words.put(Intent.REMOVE_FROM_WATCHLIST,
                List.of("관심종목에서빼줘", "관심종목삭제", "관심종목취소"));
        words.put(Intent.OPEN_RADIO_CHART, List.of("청각차트", "소리차트", "차트들려줘"));
        words.put(Intent.OPEN_ANOMALY, List.of("이상감지", "이상신호"));
        words.put(Intent.OPEN_SIMILAR, List.of("닮은차트", "유사차트", "비슷한종목"));
        words.put(Intent.OPEN_ACCOUNT, List.of("계좌", "잔고", "내자산"));
        words.put(Intent.READ_BALANCE, List.of("잔고알려줘", "예수금", "얼마남았어"));
        words.put(Intent.OPEN_NEWS, List.of("뉴스"));
        words.put(Intent.OPEN_SEARCH, List.of("종목검색", "종목찾기"));
        words.put(Intent.OPEN_SETTINGS, List.of("설정", "환경설정"));
        words.put(Intent.OPEN_HOME, List.of("홈으로", "처음으로"));
        words.put(Intent.GO_BACK, List.of("뒤로", "이전화면", "돌아가"));
        words.put(Intent.QUOTE, List.of("현재가", "얼마야", "시세"));
        words.put(Intent.STOP_SPEECH, List.of("그만", "멈춰", "조용히"));
        words.put(Intent.REPEAT, List.of("다시말해줘", "다시읽어줘", "뭐라고"));
        words.put(Intent.SPEAK_FASTER, List.of("빠르게", "빨리읽어줘"));
        words.put(Intent.SPEAK_SLOWER, List.of("천천히", "느리게"));
        words.put(Intent.TOGGLE_LARGE_TEXT, List.of("큰글씨", "글씨크게"));
        words.put(Intent.TOGGLE_HIGH_CONTRAST, List.of("고대비"));
        words.put(Intent.HELP, List.of("도움말", "뭐라고말해야"));
        // 순서가 곧 우선순위다. 거리와 길이가 같으면 먼저 적힌 것이 이긴다.
        return java.util.Collections.unmodifiableMap(words);
    }

    public VoiceCommand parse(Transcript transcript) {
        return parse(transcript == null ? "" : transcript.text());
    }

    public VoiceCommand parse(String heard) {
        String spoken = heard == null ? "" : heard.strip();
        if (spoken.isEmpty()) return VoiceCommand.unknown("");

        String plain = KnownStock.normalize(spoken);
        StockMatch match = matchStock(plain);
        Optional<KnownStock> stock = Optional.ofNullable(match == null ? null : match.stock());

        Intent risky = bestMatch(plain, RISKY_WORDS, VoiceCommandParser::strictTolerance);
        if (risky == Intent.CANCEL_ORDER) {
            // 취소에는 수량이 없다. 무엇을 취소할지는 미체결 목록에서 고르게 한다.
            return stock.isPresent()
                    ? VoiceCommand.of(Intent.CANCEL_ORDER, stock.get(), spoken)
                    : VoiceCommand.of(Intent.CANCEL_ORDER, spoken);
        }
        if (risky != null) return orderCommand(risky, match, plain, spoken);

        Intent safe = bestMatch(plain, SAFE_WORDS, VoiceCommandParser::looseTolerance);
        if (safe == null) {
            // 종목명만 말한 것은 그 종목의 현재가를 묻는 것으로 본다. 읽어 주기만 하므로
            // 잘못 붙어도 손해가 없다.
            return stock.map(target -> VoiceCommand.of(Intent.QUOTE, target, spoken))
                    .orElseGet(() -> VoiceCommand.unknown(spoken));
        }
        if (stock.isPresent()) return VoiceCommand.of(safe, stock.get(), spoken);
        // 아는 종목에는 없다. 종목명일 법한 말을 남겨 두면 앱이 실제 검색을 걸어 본다.
        // 어휘 목록은 보유·관심 종목뿐이라, 그것만으로는 상장 종목 대부분을 못 부른다.
        return VoiceCommand.of(safe, spoken).withStockHint(stockHint(spoken, safe));
    }

    /**
     * 명령에 쓰인 말을 걷어 내고 남은 것.
     *
     * <p>"네이버 현재가 알려줘" 에서 "현재가" 와 "알려줘" 를 빼면 "네이버" 가 남는다.
     * 이것으로 앱이 실제 종목 검색을 건다. 하드코딩한 종목 목록을 들고 있는 것보다
     * 낫다 — 목록은 반드시 낡고, 빠진 종목은 사용자가 아무리 또렷하게 말해도 안 된다.
     */
    private String stockHint(String spoken, Intent intent) {
        if (!intent.requiresStock()) return "";
        List<String> keywords = new ArrayList<>(SAFE_WORDS.getOrDefault(intent, List.of()));
        keywords.addAll(COMMAND_FILLERS);

        List<String> left = new ArrayList<>();
        for (String token : spoken.split("[\s,.!?]+")) {
            String plain = KnownStock.normalize(token);
            if (plain.isEmpty()) continue;
            boolean isCommand = false;
            for (String keyword : keywords) {
                String needle = KnownStock.normalize(keyword);
                if (needle.isEmpty()) continue;
                int allowed = looseTolerance(needle.length());
                if (distance(plain, needle) <= allowed) {
                    isCommand = true;
                    break;
                }
            }
            if (!isCommand) left.add(token);
        }
        // 남은 것이 없거나 너무 많으면 종목명으로 보기 어렵다. 넘겨짚지 않는다.
        return left.size() == 1 ? left.get(0) : "";
    }

    /**
     * 주문 명령을 만든다.
     *
     * <p>종목이나 수량이 빠졌으면 주문으로 만들지 않는다. 빠진 값을 채워 넣으면 사용자가
     * 말하지 않은 주문이 된다. 들은 말을 그대로 담아 되읽어 주고 다시 말하게 한다.
     */
    private VoiceCommand orderCommand(Intent intent, StockMatch match, String plain, String spoken) {
        if (match == null) return VoiceCommand.unknown(spoken);
        // 종목명을 지우고 수량을 센다. "한화" 의 "한", "삼성" 의 "삼" 이 수량으로 잡히면
        // 사용자가 말하지 않은 수량으로 주문이 나간다.
        String withoutName = plain.substring(0, match.start()) + " " + plain.substring(match.end());
        OptionalInt quantity = KoreanNumbers.quantityIn(withoutName);
        if (quantity.isEmpty()) return VoiceCommand.unknown(spoken);
        return VoiceCommand.order(intent, match.stock(), quantity.getAsInt(), spoken);
    }

    /** 어느 종목이 어디에 걸렸는지. 수량을 셀 때 그 자리를 빼야 한다. */
    private record StockMatch(KnownStock stock, int start, int end) {
    }

    /**
     * 가장 잘 맞는 명령을 고른다.
     *
     * <p>거리가 같으면 <b>긴 낱말</b>이 이긴다. "관심종목에 담아줘" 는 "관심종목" 에도
     * 딱 맞게 걸리는데, 짧은 쪽을 고르면 담아 달라는 말이 그냥 화면 열기가 된다.
     * 더 많이 말한 쪽이 더 구체적인 요구다.
     *
     * <p>길이까지 같으면 표에 먼저 적힌 것이 이긴다. 그래서 표는 순서를 지키는 것이어야
     * 한다({@link #ordered}). 같은 말이 실행할 때마다 다른 명령이 되면 안 된다.
     */
    private Intent bestMatch(String plain, Map<Intent, List<String>> table,
                             java.util.function.IntUnaryOperator tolerance) {
        Intent best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestLength = -1;
        for (Map.Entry<Intent, List<String>> entry : table.entrySet()) {
            for (String word : entry.getValue()) {
                String needle = KnownStock.normalize(word);
                int allowed = tolerance.applyAsInt(needle.length());
                int distance = nearestWindow(plain, needle, allowed);
                if (distance > allowed) continue;
                boolean better = distance < bestDistance
                        || (distance == bestDistance && needle.length() > bestLength);
                if (better) {
                    bestDistance = distance;
                    bestLength = needle.length();
                    best = entry.getKey();
                }
            }
        }
        return best;
    }

    private StockMatch matchStock(String plain) {
        StockMatch best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (KnownStock candidate : known) {
            for (String form : candidate.spokenForms()) {
                String needle = KnownStock.normalize(form);
                if (needle.length() < 2) continue;
                int allowed = strictTolerance(needle.length());
                int[] found = nearestWindowAt(plain, needle, allowed);
                if (found[0] <= allowed && found[0] < bestDistance) {
                    bestDistance = found[0];
                    best = new StockMatch(candidate, found[1], found[2]);
                }
            }
        }
        return best;
    }

    /** 화면 이동처럼 틀려도 되돌릴 수 있는 것. 네 글자에 두 글자까지 봐준다. */
    private static int looseTolerance(int length) {
        if (length <= 2) return 0;
        return (length + 2) / 3;
    }

    /** 주문과 종목명. 여섯 글자에 한 글자까지만 봐준다. */
    private static int strictTolerance(int length) {
        if (length <= 3) return 0;
        return length <= 7 ? 1 : 2;
    }

    /**
     * 긴 말 안에서 찾는 말과 가장 가까운 토막의 편집 거리.
     *
     * <p>사용자는 명령만 딱 말하지 않는다. "그 삼성전자 좀 매수해 줘" 처럼 앞뒤에 말이
     * 붙는다. 그래서 전체를 비교하지 않고 길이가 비슷한 토막을 훑는다.
     */
    static int nearestWindow(String haystack, String needle, int allowed) {
        return nearestWindowAt(haystack, needle, allowed)[0];
    }

    /** @return {거리, 걸린 토막의 시작, 끝}. 못 찾으면 거리가 {@code Integer.MAX_VALUE}. */
    static int[] nearestWindowAt(String haystack, String needle, int allowed) {
        if (needle.isEmpty()) return new int[]{Integer.MAX_VALUE, 0, 0};
        int exact = haystack.indexOf(needle);
        if (exact >= 0) return new int[]{0, exact, exact + needle.length()};
        int[] best = {Integer.MAX_VALUE, 0, 0};
        int shortest = Math.max(1, needle.length() - allowed);
        int longest = Math.min(haystack.length(), needle.length() + allowed);
        for (int start = 0; start < haystack.length(); start++) {
            for (int length = shortest; length <= longest; length++) {
                if (start + length > haystack.length()) break;
                int found = distance(haystack.substring(start, start + length), needle);
                if (found < best[0]) best = new int[]{found, start, start + length};
                if (best[0] == 0) return best;
            }
        }
        return best;
    }

    /** 레벤슈타인 거리. 글자를 몇 번 고쳐야 같아지는가. */
    static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) previous[column] = column;
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitute = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(substitute,
                        Math.min(previous[column] + 1, current[column - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /**
     * 자유 발화 인식기에 물려 줄 <b>낱말</b> 목록.
     *
     * <p>{@link #grammarPhrases} 와 다르다. 저쪽은 "삼성전자 매수 10주" 같은 완성된
     * 문장인데, 그것을 whisper 의 initial_prompt 에 넣으면 소리가 불분명할 때 그 문장을
     * 통째로 되뱉는다. 실제로 그렇게 됐다 — 사용자가 "이상감지" 라고 말했는데
     * "삼성전자 매수 5주, 삼성전자" 가 나왔다. 프롬프트에 있던 문장 그대로였다.
     *
     * <p>그래서 여기서는 종목명과 명령 낱말만 준다. 되뱉더라도 낱말 하나라 걸러내기
     * 쉽고, 인식기가 종목명을 알아듣는 효과는 그대로다.
     */
    public List<String> vocabularyTerms() {
        List<String> terms = new ArrayList<>();
        for (KnownStock stock : known) {
            terms.add(stock.name());
            terms.addAll(stock.aliases());
        }
        SAFE_WORDS.values().forEach(terms::addAll);
        RISKY_WORDS.values().forEach(terms::addAll);
        return List.copyOf(new java.util.LinkedHashSet<>(terms));
    }

    /**
     * 인식기에 미리 등록할 문장 목록.
     *
     * <p>Windows 내장 인식기는 등록한 문장만 알아듣는다. 그 목록을 여기서 만들어야
     * 파서가 아는 말과 인식기가 아는 말이 어긋나지 않는다. 따로 적어 두면 한쪽만 고치는
     * 일이 반드시 생긴다.
     */
    public List<String> grammarPhrases() {
        List<String> phrases = new ArrayList<>(SAFE_WORDS.size() * 2);
        SAFE_WORDS.values().forEach(phrases::addAll);
        phrases.add("주문취소");
        for (KnownStock stock : known) {
            for (String form : stock.spokenForms()) {
                if (form.equals(stock.symbol())) continue;
                phrases.add(form);
                phrases.add(form + " 현재가");
                phrases.add(form + " 뉴스");
                phrases.add(form + " 관심종목에 담아줘");
                phrases.add(form + " 관심종목에서 빼줘");
                // 수량 없는 매수·매도는 등록하지 않는다. 인식기가 알아들어도 파서가
                // 주문으로 만들지 않으므로, 사용자는 또렷하게 말하고도 계속 실패한다.
                for (String verb : List.of("매수", "매도")) {
                    for (int count : GRAMMAR_QUANTITIES) {
                        phrases.add(form + " " + verb + " " + count + "주");
                    }
                }
            }
        }
        return List.copyOf(phrases);
    }
}
