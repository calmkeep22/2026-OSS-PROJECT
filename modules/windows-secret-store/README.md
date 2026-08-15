# windows-secret-store

Windows DPAPI(Data Protection API)로 비밀 값을 보호하고 암호문을 파일에 저장하는
Windows 전용 구현체입니다. `secret-store-api`의 계약을 구현하며 파일 처리는
`file-secret-store`에 위임합니다.

## 요구 사항

- Windows
- Java 17 이상
- 현재 Windows 사용자 프로필과 DPAPI를 사용할 수 있는 실행 환경

JNA 의존성은 이 모듈에 선언되어 있습니다. 이 저장소의 Gradle 프로젝트로 사용할 때
별도로 JNA 버전을 지정할 필요는 없습니다.

## 의존성 추가

앱의 조립 모듈에 다음 의존성을 추가합니다.

```kotlin
dependencies {
    implementation(project(":modules:secret-store-api"))
    implementation(project(":modules:windows-secret-store"))
}
```

현재 Maven Central 등에 배포된 라이브러리는 아니므로 외부 프로젝트에서 사용하려면 세
모듈을 포함하거나 별도의 배포 구성을 먼저 추가해야 합니다.

## 가장 간단한 사용 예제

```java
import org.ossproject.secret.SecretBytes;
import org.ossproject.secret.SecretStore;
import org.ossproject.secret.windows.SecretStoreFactory;

import java.nio.file.Path;

Path directory = Path.of(
        System.getenv("LOCALAPPDATA"),
        "MyApplication",
        "secrets"
);

try (SecretStore store = SecretStoreFactory.create(directory)) {
    char[] secret = "example-only-not-a-real-secret".toCharArray();
    try {
        store.store("kiwoom.mock.app-secret", secret);
    } finally {
        SecretBytes.wipe(secret);
    }

    char[] loaded = store.load("kiwoom.mock.app-secret").orElseThrow();
    try {
        // 키움 토큰 발급 요청에 사용합니다.
    } finally {
        SecretBytes.wipe(loaded);
    }

    store.delete("kiwoom.mock.app-secret");
}
```

실제 코드에서는 예제처럼 비밀을 소스 코드 문자열로 만들지 말고 사용자 입력 등에서 받은
`char[]`를 사용해야 합니다. 비밀 값은 로그, 오류 메시지, 테스트 스냅샷에 포함하지
않습니다.

## 저장소 생성

기본 경로 사용:

```java
SecretStore store = SecretStoreFactory.create();
```

기본 경로는 다음과 같습니다.

```text
%USERPROFILE%\.accessible-investor\secrets
```

앱별 경로 지정:

```java
SecretStore store = SecretStoreFactory.create(
        Path.of(System.getenv("LOCALAPPDATA"), "MyApplication", "secrets")
);
```

OpenStock Access 데스크톱 앱은 별도 경로를 전달하므로 다음 위치를 사용합니다.

```text
%LOCALAPPDATA%\OpenStockAccess\secrets
```

현재 데스크톱에서 사용하는 별칭과 파일명:

| 환경 | 별칭 | 파일명 |
|---|---|---|
| 모의투자 | `kiwoom.mock.credentials` | `kiwoom.mock.credentials.secret` |
| 실전투자 | `kiwoom.live.credentials` | `kiwoom.live.credentials.secret` |

App Key와 App Secret은 환경별 자격증명 한 건으로 묶어 암호화합니다. 파일에 두 값의
평문이 기록되지는 않습니다.

## DPAPI 보호 모델

- 암호화·복호화는 Windows DPAPI가 수행합니다.
- 암호문은 일반적으로 암호화한 Windows 사용자 계정에서만 복호화할 수 있습니다.
- 다른 Windows 사용자나 다른 PC로 파일만 복사하면 복호화에 실패할 수 있습니다.
- 보호 수준은 `OS_USER_PROTECTED`입니다.
- DPAPI 사용이 하드웨어 기반 키 보호를 항상 의미하지 않으므로
  `HARDWARE_BACKED`로 보고하지 않습니다.
- 이 구현은 Windows Credential Manager에 항목을 등록하지 않습니다. 애플리케이션
  디렉터리에 DPAPI 암호문 파일을 저장합니다.

DPAPI는 저장된 비밀을 보호하지만, 애플리케이션이 실행되어 값을 복호화한 순간의 악성
프로세스, 사용자 세션 탈취, 디버거 같은 모든 위협까지 방어하지는 않습니다.

## 실패 처리

- Windows가 아니면 `SecretStoreFactory.create`가 `SecretStoreException`을
  발생시킵니다.
- DPAPI 암호화나 복호화에 실패해도 평문 저장으로 대체하지 않습니다.
- 다른 사용자·PC에서 만들어진 파일이나 손상된 파일은 복호화 오류로 처리합니다.
- UI에서 저장소를 사용할 수 없게 표시하려면 조립 루트에서 예외를 잡아
  `UNAVAILABLE` 구현체를 주입할 수 있습니다. 이 구현체 역시 저장을 거부해야 합니다.

```java
SecretStore store;
try {
    store = SecretStoreFactory.create(directory);
} catch (SecretStoreException unavailable) {
    store = new ApplicationUnavailableSecretStore(unavailable.getMessage());
}
```

지원하지 않는 환경을 위한 대체 구현은 평문을 저장해서는 안 됩니다.

## 데스크톱 앱 연결 위치

- 구체 구현 선택: `apps/desktop-javafx/.../composition/DesktopServices.java`
- 저장·조회·삭제 정책: `apps/desktop-javafx/.../viewmodel/ConnectionViewModel.java`
- 사용자 입력과 상태 표시: `apps/desktop-javafx/.../view/screen/ConnectionScreenView.java`

현재 데스크톱의 `자격증명 확인`은 저장·복호화 동작까지만 확인합니다. 실제 키움 토큰
발급 API가 연결되기 전에는 임의의 비어 있지 않은 값도 저장할 수 있습니다. 운영 연결
단계에서는 다음 순서를 사용해야 합니다.

```text
입력 형식 검사 → 키움 토큰 발급 → 인증 성공 → DPAPI 저장
```

인증 실패 시에는 입력한 값을 저장하지 않아야 합니다.

## 테스트

Windows에서는 실제 DPAPI 왕복 테스트와 공통 `SecretStoreContract`를 실행합니다.
지원하지 않는 운영체제에서는 Windows 전용 왕복 테스트가 건너뛰어집니다.

```powershell
./gradlew.bat :modules:windows-secret-store:test
```

세 모듈과 데스크톱 연결을 함께 검증하려면 다음을 실행합니다.

```powershell
./gradlew.bat :modules:secret-store-api:testFixturesClasses `
  :modules:file-secret-store:test `
  :modules:windows-secret-store:test `
  :apps:desktop-javafx:test
```
