# secret-store-api

운영체제와 저장 방식에 의존하지 않는 비밀 저장소 계약입니다. API 키, API Secret,
접근 토큰처럼 평문 설정 파일에 남기면 안 되는 값을 다루는 코드가 구체 구현에 직접
의존하지 않도록 경계를 제공합니다.

이 모듈 자체는 암호화하거나 파일을 만들지 않습니다. Windows에서는
`windows-secret-store`, 다른 플랫폼에서는 별도의 `SecretStore` 구현체를 조립해야
합니다.

## 모듈 관계

```text
애플리케이션 코드
  └─ secret-store-api (공통 계약)
       ├─ file-secret-store (암호화된 바이트의 파일 저장)
       └─ windows-secret-store (Windows DPAPI + 파일 저장)
```

## 저장소 내부에서 의존성 추가

현재 모듈은 Maven Central 등에 배포되지 않았습니다. 이 저장소의 Gradle 멀티 프로젝트
구성에서 다음과 같이 사용합니다.

```kotlin
dependencies {
    implementation(project(":modules:secret-store-api"))
}
```

Windows 구현체까지 직접 생성하는 조립 모듈은 `windows-secret-store`도 추가해야 합니다.

```kotlin
dependencies {
    implementation(project(":modules:secret-store-api"))
    implementation(project(":modules:windows-secret-store"))
}
```

## 공개 API

| 타입 | 역할 |
|---|---|
| `SecretStore` | 저장, 조회, 삭제, 존재 확인, 별칭 조회 계약 |
| `SecretProtectionLevel` | 구현체가 보장하는 보호 수준 표현 |
| `SecretStoreException` | 저장소 생성·암호화·파일 처리 실패 표현 |
| `SecretBytes` | `char[]`/`byte[]` 변환과 메모리 초기화 보조 기능 |
| `SecretStoreContract` | 모든 구현체가 따라야 할 동작을 검증하는 테스트 픽스처 |

## 기본 사용 방법

`SecretStore`는 애플리케이션 서비스나 ViewModel에 생성자 주입합니다. 구체 구현체를
선택하는 코드는 앱의 조립 루트 한 곳에만 둡니다.

```java
public final class ApiCredentialService {
    private final SecretStore secretStore;

    public ApiCredentialService(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    public void saveAppSecret(char[] appSecret) {
        try {
            secretStore.store("kiwoom.mock.app-secret", appSecret);
        } finally {
            SecretBytes.wipe(appSecret);
        }
    }

    public void useAppSecret() {
        char[] loaded = secretStore.load("kiwoom.mock.app-secret")
                .orElseThrow(() -> new IllegalStateException("저장된 자격증명이 없습니다."));
        try {
            // 토큰 발급 요청에 사용합니다. 로그나 화면에는 출력하지 않습니다.
        } finally {
            SecretBytes.wipe(loaded);
        }
    }

    public void deleteAppSecret() {
        secretStore.delete("kiwoom.mock.app-secret");
    }
}
```

저장소의 수명은 조립 루트가 관리합니다.

```java
try (SecretStore store = createPlatformSecretStore()) {
    ApiCredentialService credentials = new ApiCredentialService(store);
    // 애플리케이션 실행
}
```

## 호출자 계약

- 비밀 값은 가능하면 불변 `String`이 아니라 변경 가능한 `char[]`로 전달합니다.
- `store` 호출이 끝난 뒤 입력 배열은 호출자가 `SecretBytes.wipe`로 지웁니다.
- `load`가 반환한 배열은 호출자가 소유하며, 사용 직후 반드시 지웁니다.
- 비밀 값, 원문 배열, 복호화 결과를 로그·예외 메시지·화면에 출력하지 않습니다.
- `description()`은 사용자에게 표시할 수 있지만 자격증명 값은 포함하면 안 됩니다.
- `protectionLevel()`을 확인해 저장 기능 제공 여부와 사용자 안내를 결정합니다.
- 앱 종료 시 `close()`를 호출합니다. 현재 파일 구현은 열린 자원을 유지하지 않지만,
  다른 구현체가 자원을 소유할 수 있으므로 계약을 지키는 편이 안전합니다.

자바에서는 메모리 복사나 JVM 내부 동작을 완전히 통제할 수 없습니다. `char[]`와 명시적
초기화는 노출 시간을 줄이기 위한 방어 수단이며 절대적인 메모리 보안을 보장하지는
않습니다.

## 보호 수준

| 값 | 의미 |
|---|---|
| `UNAVAILABLE` | 안전한 저장소가 없어 저장 기능을 제공하지 않음 |
| `SOFTWARE_ENCRYPTED` | 애플리케이션이 제공한 소프트웨어 키로 암호화 |
| `OS_USER_PROTECTED` | 현재 운영체제 사용자 계정에 묶여 보호됨 |
| `HARDWARE_BACKED` | 구현체가 하드웨어 기반 키 보호를 실제로 보장함 |

보호 수준을 추측해서 높게 보고하면 안 됩니다. 예를 들어 Windows DPAPI 구현은 현재
사용자 계정으로 보호되지만 하드웨어 키 사용을 보장하지 않으므로
`OS_USER_PROTECTED`를 반환합니다.

## 새 구현체 작성 규칙

macOS Keychain, Linux Secret Service 같은 구현을 추가할 때는 다음을 지킵니다.

1. `SecretStore`를 구현하고 플랫폼 전용 패키지·모듈에 둡니다.
2. 지원하지 않는 환경에서는 평문 저장소로 대체하지 말고 명확하게 실패합니다.
3. 입력 배열을 보관하거나 외부에 그대로 반환하지 않습니다.
4. 반환 배열은 호출자가 독립적으로 변경할 수 있는 새 배열이어야 합니다.
5. `description()`과 예외 메시지에 비밀 값을 포함하지 않습니다.
6. 실제로 보장하는 `SecretProtectionLevel`만 반환합니다.
7. 공통 `SecretStoreContract`를 실행해 저장 생명주기와 버퍼 격리를 검증합니다.

구현체 테스트 예시:

```kotlin
dependencies {
    testImplementation(testFixtures(project(":modules:secret-store-api")))
}
```

```java
final class MySecretStoreContractTest extends SecretStoreContract {
    @Override
    protected SecretStore createStore(Path directory) {
        return new MySecretStore(directory);
    }
}
```

## 테스트

Windows PowerShell:

```powershell
./gradlew.bat :modules:secret-store-api:testFixturesClasses
```

macOS/Linux:

```bash
./gradlew :modules:secret-store-api:testFixturesClasses
```
