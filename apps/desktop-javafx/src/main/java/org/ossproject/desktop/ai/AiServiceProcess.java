package org.ossproject.desktop.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * AI 분석 서버를 자식 프로세스로 띄운다.
 *
 * <p>분석 엔진이 파이썬이라 같은 프로세스에서 부를 수 없다. 사용자가 터미널을 열어 서버를
 * 직접 띄우게 하면 대부분은 AI 기능을 못 보고 지나간다. 앱이 대신 띄운다.
 *
 * <p>사용자가 할 일은 한 번의 설치뿐이다.
 *
 * <pre>
 *   cd ai-service
 *   pip install -r requirements.txt
 * </pre>
 *
 * <p>파이썬이 없거나 설치가 안 되어 있으면 기동에 실패한다. 그래도 앱은 그대로 돌아간다.
 * AI 는 부가 기능이고, 시세와 주문은 이것 없이도 동작해야 한다.
 */
public final class AiServiceProcess implements AutoCloseable {

    /** 다른 프로그램과 겹치지 않을 만한 자리. 바깥에 열지 않는다. */
    public static final int DEFAULT_PORT = 8765;

    private final Path serviceDirectory;
    private final int port;
    private Process process;

    public AiServiceProcess(Path serviceDirectory, int port) {
        this.serviceDirectory = serviceDirectory;
        this.port = port;
    }

    /** 저장소 안의 {@code ai-service} 를 찾는다. 없으면 비어 있다. */
    public static Optional<Path> locateServiceDirectory() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path service = candidate.resolve("ai-service");
            if (Files.isRegularFile(service.resolve("server.py"))) {
                return Optional.of(service);
            }
        }
        return Optional.empty();
    }

    /**
     * 서버를 띄운다.
     *
     * <p>기동에 10초쯤 걸린다. 모델과 비교군 패널을 읽고 지수 넷을 네트워크에서 받는다.
     * 여기서 기다리지 않는다. 화면은 준비될 때까지 그 사실을 표시하고 나머지 기능을 계속
     * 쓸 수 있게 둔다.
     *
     * @return 시작했으면 참. 파이썬이 없거나 실행에 실패하면 거짓
     */
    public boolean start() {
        if (process != null && process.isAlive()) {
            return true;
        }
        for (String python : List.of("python", "python3", "py")) {
            try {
                process = new ProcessBuilder(python, "server.py", "--port", Integer.toString(port))
                        .directory(serviceDirectory.toFile())
                        // 로그를 그대로 흘려보낸다. 기동 실패 이유가 여기 남는다.
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return true;
            } catch (IOException ignored) {
                // 이 이름으로는 파이썬을 못 찾았다. 다음 이름을 시도한다.
            }
        }
        return false;
    }

    public boolean running() {
        return process != null && process.isAlive();
    }

    /**
     * 서버를 내린다.
     *
     * <p>앱이 꺼지는데 자식이 남으면 포트를 붙잡고 있어 다음 실행이 실패한다.
     */
    @Override
    public void close() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }
}
