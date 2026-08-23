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
    private static final System.Logger LOGGER =
            System.getLogger(AiServiceProcess.class.getName());

    public static final int DEFAULT_PORT = 8765;

    private final Path serviceDirectory;
    private final int port;
    private final Path logFile;
    private Process process;

    public AiServiceProcess(Path serviceDirectory, int port) {
        this(serviceDirectory, port, null);
    }

    /**
     * @param logFile 서버가 남기는 말을 모아 둘 파일. {@code null} 이면 버린다.
     *
     * <p>예전에는 무조건 버렸다. 그래서 "음성이 다르게 들린다" 는 말을 들어도 인식기가
     * 무엇으로 들었는지, 애초에 소리가 들어가긴 했는지 확인할 방법이 없었다. 사용자가
     * 재현해 줄 수도 없는 종류라 로그가 유일한 단서다.
     */
    public AiServiceProcess(Path serviceDirectory, int port, Path logFile) {
        this.serviceDirectory = serviceDirectory;
        this.port = port;
        this.logFile = logFile;
    }

    /** 서버가 남긴 말이 쌓이는 파일. 로그를 두지 않으면 비어 있다. */
    public Optional<Path> logFile() {
        return Optional.ofNullable(logFile);
    }

    /**
     * 서버가 남기는 말을 어디로 보낼지.
     *
     * <p>덧붙여 쓴다. 앱을 다시 켤 때마다 지우면 "아까 그 일" 을 확인할 수 없다.
     * 파일을 만들지 못해도 서버는 띄운다 — 로그가 없다고 기능을 막을 이유는 없다.
     */
    private ProcessBuilder.Redirect logDestination() {
        if (logFile == null) return ProcessBuilder.Redirect.DISCARD;
        try {
            Files.createDirectories(logFile.getParent());
            return ProcessBuilder.Redirect.appendTo(logFile.toFile());
        } catch (IOException unwritable) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "AI 서버 로그 파일을 만들지 못했습니다: " + logFile, unwritable);
            return ProcessBuilder.Redirect.DISCARD;
        }
    }

    /** 저장소 안의 {@code ai-service} 를 찾는다. 없으면 비어 있다. */
    public static Optional<Path> locateServiceDirectory() {
        String packagedLauncher = System.getProperty("jpackage.app-path", "");
        if (!packagedLauncher.isBlank()) {
            Path launcher = Path.of(packagedLauncher).toAbsolutePath();
            Path packaged = launcher.getParent().resolve("app").resolve("ai-service");
            if (Files.isRegularFile(packaged.resolve("OpenStockAiService.exe"))) {
                return Optional.of(packaged);
            }
        }
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
        Path packaged = serviceDirectory.resolve("OpenStockAiService.exe");
        if (Files.isRegularFile(packaged)) {
            try {
                process = processBuilder(packaged.toString(), "--port", Integer.toString(port)).start();
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
        for (String python : List.of("python", "python3", "py")) {
            try {
                process = processBuilder(python, "server.py", "--port", Integer.toString(port)).start();
                return true;
            } catch (IOException ignored) {
                // 이 이름으로는 파이썬을 못 찾았다. 다음 이름을 시도한다.
            }
        }
        return false;
    }

    private ProcessBuilder processBuilder(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(serviceDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logDestination());
        String localAppData = System.getenv("LOCALAPPDATA");
        Path data = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".openstock-access", "ai-data")
                : Path.of(localAppData, "OpenStockAccess", "ai-data");
        builder.environment().put("OPENSTOCK_AI_DATA_DIR", data.toString());
        return builder;
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
