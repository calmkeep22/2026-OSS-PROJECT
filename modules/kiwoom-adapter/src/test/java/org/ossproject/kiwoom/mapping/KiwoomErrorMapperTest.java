package org.ossproject.kiwoom.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 키움은 문서 오류코드를 {@code return_code} 가 아니라 메시지 안에 넣어 준다.
 *
 * <p>{@code return_code} 는 분류값이라 같은 값에 여러 원인이 묶인다. 사용자에게 무엇을
 * 해야 할지 알리려면 대괄호 안의 코드를 읽어야 한다.
 */
class KiwoomErrorMapperTest {

    @Test
    @DisplayName("문서 오류코드는 return_msg 의 대괄호 안에서 읽는다")
    void readsDocumentedCodeFromMessage() {
        // 실제 응답 형태. return_code 는 분류값이고 문서 코드는 메시지 안에 들어 있다.
        assertEquals("1700", KiwoomErrorMapper.documentedCodeOf(
                "허용된 요청 개수를 초과하였습니다[1700:허용된 API 요청 개수를 초과하였습니다. 유량=1, API ID=ka10001]"));
        assertEquals("8020", KiwoomErrorMapper.documentedCodeOf(
                "인증 실패[8020:입력파라미터로 appkey 또는 secretkey가 들어오지 않았습니다.]"));
        // 코드 뒤에 콜론이 아니라 닫는 대괄호가 오는 형태도 실제 응답에서 확인했다.
        assertEquals("2000", KiwoomErrorMapper.documentedCodeOf("[2000](RC4058:모의투자 장종료)"));
        assertEquals(null, KiwoomErrorMapper.documentedCodeOf("코드가 없는 메시지"));
    }
}
