"""
음성 인식 창구 검사.

모델을 부르지 않는다. 모델을 부르면 검사 한 번에 몇 초가 걸리고, 처음 도는
기계에서는 150MB 를 내려받느라 멈춘 것처럼 보인다. 여기서 지키려는 것은
인식 성능이 아니라 그 둘레의 약속이다 — 없을 때 뭐라고 답하는지, 너무 긴
소리를 어떻게 막는지, 어휘가 잘릴 때 어떻게 되는지.

인식 성능 쪽은 자바 파서 검사(MeasuredWhisperOutputTest)가 실측 출력을 박아
두고 지킨다.
"""
from __future__ import annotations

import io
import struct
import sys
import wave
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import transcribe as T


def wav_bytes(seconds: float, rate: int = 16000) -> bytes:
    """말이 아닌 무음. 길이만 맞으면 되는 검사에 쓴다."""
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(rate)
        writer.writeframes(struct.pack("<h", 0) * int(rate * seconds))
    return buffer.getvalue()


class TestVocabulary:
    def test_중복과_공백을_걸러_낸다(self):
        engine = T.Transcriber()

        result = engine.use_vocabulary(["삼성전자", " 삼성전자 ", "", "  ", "카카오"])

        assert result["받은어휘"] == 2

    def test_어휘가_없으면_프롬프트도_비어_있다(self):
        engine = T.Transcriber()

        engine.use_vocabulary([])

        assert engine._prompt() == ""

    def test_어휘가_넘치면_뒤를_버린다(self):
        """
        whisper 는 initial_prompt 를 224 토큰까지만 본다. 넘치면 앞이 잘리는데,
        앞에는 "주식 음성 명령입니다" 라는 맥락이 있다. 그것이 잘리면 어휘만
        나열된 이상한 프롬프트가 되므로, 앞을 지키고 뒤를 버린다.
        """
        engine = T.Transcriber()

        engine.use_vocabulary([f"종목{n}" for n in range(500)])
        prompt = engine._prompt()

        assert prompt.startswith("주식 음성 명령입니다.")
        assert len(prompt) <= 400
        assert not prompt.endswith(","), "낱말 중간에서 자르면 없는 종목명이 생긴다"


class TestGuards:
    def test_너무_긴_소리는_거절한다(self):
        engine = T.Transcriber()

        with pytest.raises(ValueError, match="너무 깁니다"):
            engine.transcribe(wav_bytes(T.MAX_SECONDS + 1))

    def test_WAV_가_아니면_이유를_말한다(self):
        engine = T.Transcriber()

        with pytest.raises(ValueError, match="WAV"):
            engine.transcribe(b"this is not audio")

    def test_길이를_먼저_보므로_모델을_올리지_않는다(self):
        """긴 소리를 거절하는 데 모델이 필요 없다. 올렸다면 순서가 잘못된 것이다."""
        engine = T.Transcriber()

        with pytest.raises(ValueError):
            engine.transcribe(wav_bytes(T.MAX_SECONDS + 1))

        assert engine._model is None


class TestUnavailable:
    def test_설치되지_않았으면_이유를_담아_알린다(self, monkeypatch):
        engine = T.Transcriber()
        real_import = __builtins__["__import__"] if isinstance(__builtins__, dict) \
            else __builtins__.__import__

        def missing(name, *args, **kwargs):
            if name == "faster_whisper":
                raise ImportError("No module named 'faster_whisper'")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr("builtins.__import__", missing)

        assert engine.available() is False
        assert "faster-whisper" in engine.status()["사유"]

    def test_상태는_언제나_답한다(self):
        """
        상태 조회가 터지면 앱은 서버가 죽은 것과 구별할 수 없다. 인식기가
        없더라도 없다고 답해야 한다.
        """
        status = T.Transcriber().status()

        assert set(status) == {"쓸수있음", "사유", "모델", "장치", "어휘수"}
        assert status["모델"] == "base"


class TestModelSource:
    @staticmethod
    def _model(directory: Path) -> Path:
        directory.mkdir(parents=True)
        for name in T.MODEL_FILES:
            (directory / name).write_bytes(b"test")
        return directory

    def test_명시한_로컬_모델은_다운로드_없이_쓴다(self, tmp_path, monkeypatch):
        model = self._model(tmp_path / "whisper")
        monkeypatch.setenv("OPENSTOCK_WHISPER_MODEL", str(model))

        source, reason = T._model_source("base")

        assert source == model.resolve()
        assert reason == ""

    def test_배포본에서_내장_모델이_빠지면_인터넷으로_우회하지_않는다(
            self, tmp_path, monkeypatch):
        monkeypatch.delenv("OPENSTOCK_WHISPER_MODEL", raising=False)
        monkeypatch.setattr(T.sys, "frozen", True, raising=False)
        monkeypatch.setattr(T.sys, "_MEIPASS", str(tmp_path), raising=False)

        source, reason = T._model_source("base")

        assert source is None
        assert "배포본" in reason

    def test_배포본의_내장_모델을_찾는다(self, tmp_path, monkeypatch):
        model = self._model(tmp_path / T.BUNDLED_MODEL_PATH)
        monkeypatch.delenv("OPENSTOCK_WHISPER_MODEL", raising=False)
        monkeypatch.setattr(T.sys, "frozen", True, raising=False)
        monkeypatch.setattr(T.sys, "_MEIPASS", str(tmp_path), raising=False)

        source, reason = T._model_source("base")

        assert source == model
        assert reason == ""


class _Segment:
    def __init__(self, logprob, start=0.0, end=1.0):
        self.avg_logprob = logprob
        self.start = start
        self.end = end


class TestConfidence:
    """
    확신도는 앱이 "되물을까" 를 정하는 데 쓴다. 값이 실제 인식 품질을 따라
    움직이지 않으면 그 판단이 통째로 무의미해진다.
    """

    def test_토막이_없으면_None_이지_기본값이_아니다(self):
        assert T._confidence([]) is None

    def test_avg_logprob_이_없는_토막만_있어도_None(self):
        class Bare:
            start = 0.0
            end = 1.0

        assert T._confidence([Bare()]) is None

    def test_인식이_나쁠수록_낮아진다(self):
        """
        처음에 language_probability 를 확신도로 내보냈다가 무엇을 말하든 1.0 이
        나왔다. language="ko" 로 고정해 부르니 당연한 값이었다. 앱은 그것을
        "100% 확신" 으로 읽어 되물어야 할 때 되묻지 않는다.
        """
        good = T._confidence([_Segment(-0.1)])
        bad = T._confidence([_Segment(-1.5)])

        assert good is not None and bad is not None
        assert good > bad, "확신도가 인식 품질을 따라 움직여야 한다"
        assert bad < 0.5

    def test_긴_토막이_더_크게_반영된다(self):
        """짧은 토막 하나가 전체 판단을 뒤집으면 안 된다."""
        mostly_good = T._confidence([_Segment(-0.1, 0.0, 5.0), _Segment(-2.0, 5.0, 5.2)])

        assert mostly_good > 0.7

    def test_0_과_1_사이를_벗어나지_않는다(self):
        assert 0.0 <= T._confidence([_Segment(0.0)]) <= 1.0


class TestNoInvention:

    def test_인식기_군말은_말로_치지_않는다(self):
        """
        whisper 는 무음에 "감사합니다." 를 뱉는 버릇이 있다. 학습 자료에 유튜브
        자막이 많이 섞인 탓이다. 그것을 사용자가 한 말로 넘기면, 아무 말도 하지
        않았는데 명령이 실행된다.
        """
        assert "감사합니다." in T._NOISE
        assert "시청해주셔서 감사합니다." in T._NOISE
