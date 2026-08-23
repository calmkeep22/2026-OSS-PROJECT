/**
 * 말로 앱을 부리는 데 필요한 창구와 규칙.
 *
 * <p>여기에는 인식기가 없다. 인식기를 어떤 것으로 쓰든 달라지지 않는 것만 둔다 —
 * 무엇을 시킬 수 있는지({@link org.ossproject.voice.Intent}), 알아들은 말을 명령으로
 * 어떻게 바꾸는지({@link org.ossproject.voice.VoiceCommandParser}), 되돌릴 수 없는 일에
 * 어떤 문턱을 두는지.
 *
 * <p>인식기를 갈아 끼워도 이 규칙은 그대로 남는다. 실측해 보니 어느 인식기를 쓰든
 * 한국어 종목명은 틀렸다. 정확도를 인식기에만 맡길 수 없다는 뜻이고, 그래서 붙이는 일을
 * 여기서 한다.
 */
package org.ossproject.voice;
