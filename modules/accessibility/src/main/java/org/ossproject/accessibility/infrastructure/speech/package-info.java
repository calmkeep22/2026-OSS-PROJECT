/**
 * 운영체제별 음성 합성 구현.
 *
 * <p>윈도우는 SAPI, macOS 는 say, 리눅스는 spd-say 를 쓴다. 어느 것도 없으면 조용한
 * 구현으로 물러선다. 음성이 없다고 앱을 쓰지 못하게 하지는 않는다.
 */
package org.ossproject.accessibility.infrastructure.speech;
