"""
말을 글로 옮긴다.

왜 base 인가
------------
2026-08-23, i7-7500U(2016년 2코어)에서 같은 한국어 다섯 마디로 실측했다.

    tiny       한 마디 1.46초   반복 쓰레기가 섞여 나옴
    base       한 마디 2.14초   <- 고른 것
    small      한 마디 9.87초   느려서 못 씀
    vosk ko    한 마디 2.74초   느리고 더 틀림

글자만 놓고 보면 base 는 다섯 중 셋만 맞혔다. 그 수치로는 더 큰 모델을 골라야
하지만, 앱의 파서(`org.ossproject.voice.VoiceCommandParser`)를 거치면 다섯이
모두 올바른 명령이 된다. 인식기는 종목 목록을 모르고 파서는 안다. 그 차이가
모델 두 단계 값어치를 해서, 느린 기계에서도 2초에 끝난다.

어휘를 먼저 받는 이유
--------------------
어휘 없이 돌리면 "에스케이하이닉스" 가 "SKINIX" 로, "카카오" 가 "다가오" 로
나온다. 이건 파서도 못 고친다 — 고칠 만큼 기준을 낮추면 엉뚱한 종목에 붙고,
그 종목으로 주문이 나간다. 그래서 앱이 보유·관심 종목을 먼저 넘겨 주고
`initial_prompt` 로 물린다.

지어내지 않는다
--------------
못 알아들으면 빈 문자열을 돌려준다. 비슷한 말을 채워 넣지 않는다. 화면을 볼 수
없는 사용자는 앱이 지어낸 말과 자기가 한 말을 구별할 수 없다.
"""
from __future__ import annotations

import collections
import io
import logging
import os
import re
import sys
import threading
import time
import wave
from pathlib import Path

LOG = logging.getLogger("ai-service.transcribe")

MODEL_SIZE = "base"
MODEL_FILES = ("config.json", "model.bin", "tokenizer.json", "vocabulary.txt")
BUNDLED_MODEL_PATH = Path("whisper-models") / "faster-whisper-base"

# 한 번에 받을 수 있는 소리 길이. 명령 한마디는 길어야 몇 초다. 이보다 길면
# 마이크가 열린 채로 방치된 것이므로, 분 단위 소리를 붙들고 CPU 를 태우지 않는다.
MAX_SECONDS = 30.0

# 이 값을 넘으면 사람 말이 아니라고 본다.
#
# whisper 는 소리가 불분명하면 조용히 지어낸다. 특히 initial_prompt 에 넣어 둔
# 낱말을 그대로 뱉는 버릇이 있어서, 종목명을 물려 둔 우리 쪽에서는 아무 말도 하지
# 않았는데 "삼성전자" 가 나온다. 그것이 명령으로 실행되면 사용자는 자기가 시키지도
# 않은 일을 당한다.
MAX_NO_SPEECH = 0.6

#: 이 아래로 떨어지면 "어휘를 그냥 뱉은 것" 으로 의심한다.
#:
#: 실측 근거: 사용자가 또렷하게 말한 "종목찾기" 는 확신도 0.80~0.94, 무음확률
#: 0.003~0.006 으로 들어왔다. 소리가 불분명해 인식기가 프롬프트를 되뱉던 경우와는
#: 뚜렷하게 갈린다. 확신할 때는 어휘와 같아도 사용자가 그렇게 말한 것으로 본다.
ECHO_SUSPECT_CONFIDENCE = 0.55

# 앞뒤로 붙는 군말. 사용자가 말한 것이 아니라 인식기가 습관적으로 뱉는 것이다.
_NOISE = {"", ".", "..", "...", "감사합니다.", "시청해주셔서 감사합니다.",
          "구독과 좋아요 부탁드립니다."}


class Unavailable(RuntimeError):
    """인식기를 쓸 수 없다. 왜인지를 메시지에 담는다."""


def _complete_model(path: Path) -> bool:
    """CTranslate2 모델을 열 수 있는 최소 파일이 모두 있는가."""
    return path.is_dir() and all((path / name).is_file() for name in MODEL_FILES)


def _model_source(size: str) -> tuple[str | Path | None, str]:
    """
    실행할 모델의 위치를 고른다.

    배포본은 PyInstaller가 푼 ``_MEIPASS`` 아래의 모델만 쓴다. 파일이 빠졌는데
    조용히 인터넷에서 받기 시작하면 오프라인 시연에서 음성 단추가 멈춘 것처럼 보인다.
    개발 환경에서는 기존처럼 모델 이름을 넘겨 faster-whisper 캐시를 사용할 수 있다.
    """
    configured = os.environ.get("OPENSTOCK_WHISPER_MODEL", "").strip()
    if configured:
        explicit = Path(configured).expanduser().resolve()
        if _complete_model(explicit):
            return explicit, ""
        return None, f"지정한 Whisper 모델 파일이 완전하지 않습니다: {explicit}"

    runtime_root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    bundled = runtime_root / BUNDLED_MODEL_PATH
    if size == MODEL_SIZE and _complete_model(bundled):
        return bundled, ""

    if getattr(sys, "frozen", False):
        return None, f"배포본에 Whisper {size} 모델이 포함되지 않았습니다."
    return size, ""


class Transcriber:
    """
    모델 하나를 붙들고 재사용한다.

    적재에 3초 남짓 걸린다. 부를 때마다 올리면 그 값을 매번 치른다. 반대로
    서버가 뜰 때 무조건 올리면, 음성을 쓰지 않는 사용자도 메모리 150MB 와
    기동 3초를 낸다. 그래서 처음 쓸 때 올린다.
    """

    def __init__(self, size: str = MODEL_SIZE) -> None:
        self._size = size
        self._model = None
        self._device = "미적재"
        self._reason = ""
        self._vocabulary: list[str] = []
        # 모델은 한 번에 한 건만 처리한다. 마이크도 하나뿐이라 줄 서도 문제없다.
        self._lock = threading.Lock()

    # --- 상태 ---------------------------------------------------------

    def available(self) -> bool:
        if self._model is not None:
            return True
        try:
            import faster_whisper  # noqa: F401
        except ImportError as missing:
            self._reason = f"faster-whisper 가 설치되지 않았습니다: {missing}"
            return False
        source, reason = _model_source(self._size)
        if source is None:
            self._reason = reason
            return False
        self._reason = ""
        return True

    def status(self) -> dict:
        return {
            "쓸수있음": self.available(),
            "사유": self._reason,
            "모델": self._size,
            "장치": self._device,
            "어휘수": len(self._vocabulary),
        }

    # --- 어휘 ---------------------------------------------------------

    def use_vocabulary(self, phrases: list[str]) -> dict:
        """
        앱이 아는 종목명과 명령어를 받아 둔다.

        문장을 그대로 이어 붙이지 않는다. whisper 의 `initial_prompt` 는 224
        토큰까지만 본다. 넘치면 앞쪽이 잘려 나가는데, 앱은 잘린 줄 모른다.
        """
        unique: list[str] = []
        seen = set()
        for phrase in phrases:
            text = (phrase or "").strip()
            if text and text not in seen:
                seen.add(text)
                unique.append(text)
        self._vocabulary = unique
        return {"받은어휘": len(unique), "프롬프트글자수": len(self._prompt())}

    def _prompt(self) -> str:
        if not self._vocabulary:
            return ""
        # 종목명이 명령어보다 훨씬 잘 틀리므로 종목명 쪽에 자리를 더 준다.
        # 400자쯤이면 224토큰 근처다. 넘치면 뒤를 버린다 — 앞이 잘리는 것보다 낫다.
        head = "주식 음성 명령입니다. "
        body = ", ".join(self._vocabulary)
        room = 400 - len(head)
        return head + (body if len(body) <= room else body[:room].rsplit(",", 1)[0])

    # --- 인식 ---------------------------------------------------------

    def _load(self):
        if self._model is not None:
            return self._model
        if not self.available():
            raise Unavailable(self._reason)

        from faster_whisper import WhisperModel

        device, compute = self._pick_device()
        source, reason = _model_source(self._size)
        if source is None:
            self._reason = reason
            raise Unavailable(reason)
        started = time.perf_counter()
        local = isinstance(source, Path)
        self._model = WhisperModel(str(source), device=device, compute_type=compute,
                                   local_files_only=local)
        self._device = f"{device}/{compute}"
        LOG.info("%s 모델 적재 %.1f초 (%s, %s)", self._size,
                 time.perf_counter() - started, self._device,
                 "배포본 내장" if local else "개발 환경 캐시")
        return self._model

    @staticmethod
    def _pick_device() -> tuple[str, str]:
        """GPU 가 있으면 쓴다. 없으면 CPU int8 로 떨어진다."""
        try:
            import ctranslate2
            if ctranslate2.get_cuda_device_count() > 0:
                return "cuda", "float16"
        except Exception as error:  # GPU 판별 실패는 GPU 없음과 같이 다룬다.
            LOG.debug("CUDA 판별 실패, CPU 로 갑니다: %s", error)
        return "cpu", "int8"

    def transcribe(self, audio: bytes) -> dict:
        seconds = _wav_seconds(audio)
        if seconds > MAX_SECONDS:
            raise ValueError(f"소리가 너무 깁니다: {seconds:.1f}초 (최대 {MAX_SECONDS:.0f}초)")

        with self._lock:
            model = self._load()
            started = time.perf_counter()
            segments, info = model.transcribe(
                io.BytesIO(audio),
                language="ko",
                beam_size=1,
                temperature=0,
                condition_on_previous_text=False,
                # 같은 낱말을 되풀이하는 버릇을 조금이라도 눌러 둔다. 완전히 막지는
                # 못해서 위에서 한 번 더 줄인다.
                repetition_penalty=1.15,
                initial_prompt=self._prompt() or None,
            )
            collected = list(segments)
            text = "".join(segment.text for segment in collected).strip()
            spent = time.perf_counter() - started

        silence = _no_speech(collected)
        collapsed = _collapse_repeats(text)
        dropped = ""
        if collapsed != text:
            # 같은 말을 되풀이한 것은 사용자가 그렇게 말한 것이 아니라 인식기가
            # 빠진 반복 루프다. 하나로 줄이면 대개 사용자가 실제로 한 말이 남는다.
            dropped = f"반복 {len(text)}자 -> {len(collapsed)}자"
            text = collapsed
        # 무엇을 버렸는지 함께 남긴다. 버린 글자를 적지 않으면, 사용자가 "안 된다" 고
        # 할 때 정답을 버린 것인지 인식기가 지어낸 것인지 가릴 방법이 없다.
        heard = text
        if text in _NOISE:
            dropped, text = "군말", ""
        elif silence is not None and silence > MAX_NO_SPEECH:
            # 사람이 말한 것 같지 않다. 지어낸 말을 명령으로 넘기지 않는다.
            dropped, text = f"무음(무음확률 {silence:.2f})", ""
        elif self._echoed_prompt(text) and _unsure(collected, silence):
            dropped, text = "어휘 되뱉음", ""

        answer = {
            "말": text,
            "확신도": _confidence(collected),
            "무음확률": silence,
            "소리길이초": round(seconds, 2),
            "인식초": round(spent, 2),
            "모델": self._size,
            "장치": self._device,
        }
        # 무엇으로 들었는지 남긴다. 이것이 없으면 사용자가 "다르게 들었다" 고 해도
        # 인식기가 틀린 것인지 마이크가 문제인지 확인할 방법이 없다. 실제로 그랬다.
        LOG.info("인식 %.1f초 소리 -> %r (확신도 %s, 무음확률 %s)%s",
                 seconds, text, answer["확신도"], silence,
                 f" [버림: {dropped} / 들은 것 {heard!r}]" if dropped else "")
        return answer

    def _echoed_prompt(self, text: str) -> bool:
        """
        물려 준 어휘를 그대로 뱉은 것인가.

        <p>어휘와 완전히 같은지만 본다. 그것만으로는 버릴 수 없다 — 사용자가 낱말
        하나로 말하는 것이 음성 명령의 가장 흔한 쓰임이고, 그때 결과는 당연히 어휘와
        같다. 실제로 "종목찾기" 를 또렷하게 말했는데 확신도 0.94 짜리 정답이 이 검사
        하나에 버려져, 음성 명령이 통째로 먹통이 됐다.

        <p>그래서 부르는 쪽에서 {@code _unsure} 와 함께 본다. 인식기가 자신 없을
        때만 되뱉음으로 친다.
        """
        if not text or not self._vocabulary:
            return False
        plain = text.strip().rstrip(".?!")
        return any(plain == phrase.strip() for phrase in self._vocabulary)


def _unsure(segments, silence: float | None) -> bool:
    """
    인식기가 자신 없어 하는가.

    <p>프롬프트를 되뱉는 것은 소리가 불분명할 때 일어난다. 또렷하게 말한 것까지 같이
    버리지 않으려면 그 둘을 갈라야 한다.

    <p>확신도를 모르면 자신 없는 것으로 친다. 모르는 것을 믿어 주면, 되뱉은 말이
    그대로 명령이 되어 화면이 엉뚱한 곳으로 간다.
    """
    if silence is not None and silence > 0.3:
        return True
    score = _confidence(segments)
    return score is None or score < ECHO_SUSPECT_CONFIDENCE


def _wav_seconds(audio: bytes) -> float:
    try:
        with wave.open(io.BytesIO(audio)) as opened:
            return opened.getnframes() / float(opened.getframerate())
    except wave.Error as bad:
        raise ValueError(f"WAV 로 읽을 수 없습니다: {bad}") from bad


def _collapse_repeats(text: str) -> str:
    """
    같은 말이 계속 이어지면 한 번으로 줄인다.

    whisper 는 짧은 소리에서 같은 낱말을 수십 번 되풀이하는 버릇이 있다. 실제로
    2.5초짜리 발화가 "이상감지" 쉰여섯 번으로 나왔고, 확신도는 0.96 이었다. 값이
    높아서 다른 방어를 전부 통과했다.

    지우지 않고 줄이는 이유는, 되풀이된 그 말이 대개 사용자가 실제로 한 말이기
    때문이다. 버리면 멀쩡한 명령까지 잃는다.

    앱이 "이렇게 들었습니다" 하고 되읽어 주므로, 줄이지 않으면 쉰여섯 번을 소리내어
    읽는다. 화면을 볼 수 없는 사용자는 그것을 멈출 방법도 마땅치 않다.
    """
    parts = [part for part in re.split(r"[,\s]+", text.strip()) if part]
    if len(parts) < 3:
        return text
    common, count = collections.Counter(parts).most_common(1)[0]
    if count >= 3 and count / len(parts) >= 0.5:
        return common
    return text


def _no_speech(segments) -> float | None:
    """사람 말이 아닐 확률. 토막마다 붙는 값 중 가장 큰 것을 쓴다."""
    values = [float(getattr(s, "no_speech_prob", 0.0)) for s in segments
              if getattr(s, "no_speech_prob", None) is not None]
    return round(max(values), 3) if values else None


def _confidence(segments) -> float | None:
    """
    옮긴 말을 얼마나 믿을 수 있는지. 0.0~1.0, 모르면 None.

    `language_probability` 를 쓰면 안 된다. 그것은 "이 소리가 한국어일 확률" 이고,
    우리는 language="ko" 로 고정해 부르므로 언제나 1.0 이 나온다. 처음에 그것을
    확신도로 내보냈는데, 앱이 보기에는 무엇을 말하든 100% 확신하는 것으로 보인다.
    되물어야 할 때 되묻지 않게 된다.

    쓰는 값은 토막마다 붙는 `avg_logprob` 다. 모델이 고른 낱말들의 로그확률
    평균이라 실제로 인식 결과에 따라 움직인다. 토막 길이로 가중해 평균 낸다 —
    짧은 토막 하나가 전체를 끌어내리면 안 된다.

    0.5 같은 그럴듯한 기본값을 넣지 않는다. 지어낸 값을 넣으면 확신도를 모르는
    것과 확신하는 것이 구별되지 않는다.
    """
    import math

    weighted = 0.0
    total = 0.0
    for segment in segments:
        logprob = getattr(segment, "avg_logprob", None)
        if logprob is None:
            continue
        span = max(0.01, float(getattr(segment, "end", 0.0))
                   - float(getattr(segment, "start", 0.0)))
        weighted += math.exp(float(logprob)) * span
        total += span
    if total == 0.0:
        return None
    return round(min(1.0, max(0.0, weighted / total)), 3)


TRANSCRIBER = Transcriber()
