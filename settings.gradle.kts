rootProject.name = "wes"

include("wes-domain")
include("wes-api")
include("wes-admin-api")

// 소스는 모듈(wes-*) 안에만 둔다(#309). 루트 src/ 의 파일은 어느 모듈도 컴파일하지 않는다 — 옛 구조의 브랜치나 hotfix를 옮겨 올 때
// 새 파일이 충돌 없이 루트에 남으면 빌드·테스트는 통과하고 기능만 조용히 빠진다. 그런 파일이 있으면 빌드를 멈춘다.
// macOS가 만드는 .DS_Store 는 소스가 아니라 건너뛴다.
val strayRootSources = rootDir.resolve("src")
    .takeIf { it.isDirectory }
    ?.walkTopDown()
    ?.filter { it.isFile && it.name != ".DS_Store" }
    ?.map { it.relativeTo(rootDir).path }
    ?.toList()
    .orEmpty()
check(strayRootSources.isEmpty()) {
    "루트 src/ 에 모듈 밖 파일 ${strayRootSources.size}개가 있다. 알맞은 모듈(wes-domain·wes-api·wes-admin-api)의 src/ 로 옮길 것:\n" +
        strayRootSources.take(20).joinToString("\n") { "  $it" }
}
