# file-secret-store

보호된 비밀 데이터를 파일로 영속화하는 플랫폼 독립 모듈입니다. 파일 생성·조회·삭제와
안전한 교체만 담당하고, 실제 암호화·복호화는 주입받은 `SecretCodec`에 위임합니다.

이 모듈에는 의도적으로 평문 코덱이 없습니다. `FileSecretStore`는 보호 기능을 제공하는
코덱 없이는 생성할 수 없습니다.

## 책임과 비책임

이 모듈이 담당하는 기능:

- 별칭별 `.secret` 파일 저장
- 임시 파일 작성 후 원자적 교체
- 저장·복호화 과정의 임시 `byte[]` 초기화
- 경로 이탈을 막기 위한 별칭 검증
- 저장된 별칭 조회 및 삭제

이 모듈이 담당하지 않는 기능:

- 암호화 키 생성·보관
- 운영체제 보안 API 선택
- API 자격증명의 유효성 검증
- 토큰 발급이나 증권사 네트워크 연결

## 의존성 추가

```kotlin
dependencies {
    implementation(project(":modules:secret-store-api"))
    implementation(project(":modules:file-secret-store"))
}
```

현재 모듈은 저장소 내부 Gradle 프로젝트로 제공되며 외부 Maven 저장소에는 아직 배포되지
않았습니다.

## 공개 API

- `org.ossproject.secret.file.FileSecretStore`
- `org.ossproject.secret.file.SecretCodec`

## 조립 방법

`SecretCodec`은 암호화된 새 배열을 반환하고 입력 배열을 보관하지 않아야 합니다.

```java
Path directory = Path.of("application-data", "secrets");
SecretCodec codec = createProtectedCodec();
SecretStore store = new FileSecretStore(directory, codec);
```

Windows 사용자는 코덱을 직접 조립하기보다 `windows-secret-store`의
`SecretStoreFactory`를 사용하는 것이 권장됩니다.

```java
SecretStore store = SecretStoreFactory.create(directory);
```

## 파일 규칙

- 별칭은 영문자, 숫자, `.`, `_`, `-`만 사용할 수 있으며 길이는 1~64자입니다.
- 별칭은 파일명에서 소문자로 정규화됩니다.
- 파일명은 `<alias>.secret` 형식입니다.
- 동일한 별칭을 다시 저장하면 기존 값이 교체됩니다.
- 파일 교체는 가능한 경우 `ATOMIC_MOVE`를 사용하고, 파일 시스템이 지원하지 않으면
  일반 교체로 전환합니다.
- 파일 내용은 코덱이 반환한 보호된 바이트입니다. 평문 파일 형식은 제공하지 않습니다.

예를 들어 별칭 `Kiwoom.Mock.Credentials`는 다음 파일로 저장됩니다.

```text
kiwoom.mock.credentials.secret
```

## `SecretCodec` 구현 계약

```java
public interface SecretCodec {
    byte[] encrypt(byte[] plaintext);
    byte[] decrypt(byte[] ciphertext);
    SecretProtectionLevel protectionLevel();
    String description();
}
```

코덱 구현체는 다음 규칙을 따라야 합니다.

1. `encrypt`와 `decrypt`는 항상 새 배열을 반환합니다.
2. 전달받은 배열을 필드나 캐시에 보관하지 않습니다.
3. 호출자가 입력·출력 배열을 지울 수 있도록 소유권을 넘깁니다.
4. 암호화 실패나 손상된 데이터는 성공처럼 처리하지 않고 예외를 발생시킵니다.
5. 실제 보장 수준에 맞는 `SecretProtectionLevel`을 반환합니다.
6. `description()`과 오류 메시지에 원문이나 암호문을 포함하지 않습니다.

암호화 키를 같은 디렉터리에 평문으로 저장하는 코덱은 실질적인 보호를 제공하지 못하므로
사용하면 안 됩니다.

## 오류와 복구

- 디렉터리를 만들 수 없거나 파일을 읽고 쓸 수 없으면 `SecretStoreException`이
  발생합니다.
- 쓰기 도중 실패하면 임시 파일을 가능한 범위에서 정리합니다.
- 손상된 암호문이나 다른 키로 만든 파일의 처리는 코덱이 실패로 보고해야 합니다.
- 저장 실패 시 평문 파일로 재시도하지 않습니다.
- 자격증명을 다시 발급할 수 있으므로 비밀 파일은 일반적으로 다른 PC에 복사해 복원하는
  백업 대상으로 간주하지 않습니다.

## 테스트

이 모듈은 `secret-store-api`의 공통 `SecretStoreContract`를 실행해 저장, 덮어쓰기,
조회, 삭제, 별칭 조회 및 배열 격리를 검증합니다.

Windows PowerShell:

```powershell
./gradlew.bat :modules:file-secret-store:test
```

macOS/Linux:

```bash
./gradlew :modules:file-secret-store:test
```
