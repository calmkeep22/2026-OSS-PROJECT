plugins { `java-library` }

dependencies {
    // 종목명 읽기(KoreanReading)를 쓴다. 이름을 다루는 규칙은 음성이 아니라
    // 도메인의 것이라, 검색창과 음성이 같은 규칙을 쓰게 하려고 여기 둔다.
    api(project(":modules:finance-domain"))
}
