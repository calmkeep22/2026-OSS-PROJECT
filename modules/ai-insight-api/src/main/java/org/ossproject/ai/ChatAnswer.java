package org.ossproject.ai;

import java.util.List;

/**
 * 질문 하나에 대한 답.
 *
 * <p>{@code declined} 는 실패가 아니다. 사고파는 판단과 미래 가격은 우리가 가진 값으로
 * 뒷받침되지 않아 답하지 않기로 한 것이다. 실패로 표시하면 화면이 오류처럼 보여 주고,
 * 사용자는 다시 물으면 답이 나올 것으로 오해한다.
 *
 * @param grounds     무엇을 근거로 답했는지. 비어 있으면 답하지 않은 것이다
 * @param suggestions 물어볼 만한 것. 무엇을 물어야 할지 모르면 아무것도 못 묻는다
 */
public record ChatAnswer(String text, List<String> grounds, boolean declined,
                         List<String> suggestions) {

    public ChatAnswer {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("답변은 필수입니다.");
        }
        grounds = List.copyOf(grounds == null ? List.of() : grounds);
        suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
    }

    /** 근거를 읽어 줄 말. 없으면 비어 있다. */
    public String groundsText() {
        return grounds.isEmpty() ? "" : "근거: " + String.join(", ", grounds) + ".";
    }
}
