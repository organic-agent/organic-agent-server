# 검사 방법 목록 — rule-audit 스킬이 실행하는 `checks.md`

> 규칙 **문구는 `.claude/rules/*.md`(와 `.claude/spec/git-convention.md`)가 단일 출처**이고, 이 문서는 규칙 ID마다 **어떻게 찾는가**(찾는 법·강제 방식·담당·명령)만 안다. 규칙 문구도, 위반 위치·개수도(정답) 여기 없다.
> 규칙 찾기: 규칙 파일에서 `<!-- ID -->` 마커를 검색한다(예: `rg -n 'KT-02' .claude/rules`). 마커는 그 규칙을 말하는 문장 끝에 붙어 있다.
> 동기화: `python3 -I .claude/skills/rule-audit/scripts/check_ids.py`(종료 코드 0이어야 한다).

## 1. 열 설명과 명령 규약

| 열 | 내용 |
|---|---|
| ID | 영역 접두사 + 번호. KT Kotlin 관용구 · LY 레이어·패키지·참조 방향(서비스·서포트 책임 포함) · EP 입구 · TX 트랜잭션 · DM 도메인·엔티티 · DTO · CT 컨트롤러 · EX 예외·에러코드 · RP repository·ORM · INF 인프라·포트 · MG 마이그레이션 · TS 테스트 · CM 공통 포맷·네이밍·주석 · GIT · LT(`scripts/load`). ID에 소문자(`KT-s1`)가 든 것은 권장(부록 A) |
| 출처 | 마커가 붙은 규칙 파일(`.claude/` 기준 경로). `CLAUDE.md`의 규칙도 마커가 붙어 있다 |
| 찾는 법 | **G** 한 줄 grep/ripgrep 명령 · **S** 스크립트·여러 줄 명령(`scripts/audit_scan.py` 또는 셸 반복문) · **J** 코드를 읽고 판단(무엇을 보면 되는지 한 줄) · **G→J** 명령이 후보를 모으고 담당 sonnet이 읽어 판단 |
| 강제 | **H** 편집 시 hook으로 막을 수 있음(파일 하나만 보고 기계 판정) · **T** 테스트/CI로(여러 파일·구조 대조) · **R** 글 규칙으로만(판단 필요) |
| 담당 | `script` = G·S 규칙 — 명령 출력 줄이 곧 위반이다 · `sonnet-a` 레이어·입구·트랜잭션(LY·EP·TX) · `sonnet-b` 도메인·DTO·예외·Kotlin 판단형(DM·DTO·EX·KT) · `sonnet-c` 저장·인프라·마이그레이션(RP·INF·MG) · `sonnet-d` 컨트롤러·공통·git·load(CT·CM·GIT·LT) · `sonnet-e` 테스트(TS) |

### 명령 규약

- 저장소 루트(`wes/`)에서 실행한다. 검색은 `rg`(ripgrep)로 쓴다 — 이 환경의 `grep`은 ugrep 래퍼라 옵션이 다르다. 특히 **`rg -L`은 `--follow`**라서 "패턴이 없는 파일"은 `--files-without-match`로 쓴다. Kotlin 파일 필터는 `-t kotlin`이다.
- 출력 한 줄 = 후보 한 곳(또는 파일 하나). 출력이 없으면 그 검사로는 위반이 없다.
- **G·S(`script`)**: 출력 줄이 그대로 위반이다. 스크립트가 보고한다.
- **G→J**: 명령이 **후보 목록**을 만들고, 그 규칙의 담당 sonnet이 후보를 읽어 진짜 위반만 가른다(오탐·규칙이 허용한 예외를 걸러 낸다). 후보가 규칙의 예외인지는 규칙 파일의 해당 문장으로 판단한다.
- **J**: 담당 sonnet이 해당 코드를 직접 읽고 판단한다. 읽을 범위는 「찾는 법」 한 줄이 말해 준다.
- 허용 목록은 쓰지 않는다 — 규칙이 허용한다고 적은 예외(규칙 파일에 있는 것)만 예외다. 기존 코드의 예외 처리는 이 문서의 몫이 아니다.
- 멀티라인 검사는 한 위반이 한 줄로 찍히도록 `-U -P`와 앞쪽 단언(lookahead)으로 쓴다.
- 보고 형식: `ID 경로:줄 한 줄 설명`. 규칙 문구는 보고서에 옮겨 적지 말고 ID만 쓴다.


## 2. 영역별 검사


### KT — Kotlin 관용구 (24)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| KT-01 | rules/kotlin.md | **G** `!!` 토큰 | H | script |
| KT-02 | rules/kotlin.md | **G** `lateinit`·`@Autowired` (main) | H | script |
| KT-03 | rules/kotlin.md | **G** `String.format`·`".format(` | H | script |
| KT-04 | rules/kotlin.md | **G→J** `"…" + x` 후보를 읽어 정규식 문자열 같은 오탐을 거른다 | H | sonnet-b |
| KT-05 | rules/kotlin.md | **G** `.findById(` (`findByIdOrNull`은 걸리지 않는다) · 선언 시그니처의 `Optional<` | H | script |
| KT-06 | rules/kotlin.md | **G** `?.let … ?: run` 한 줄·줄바꿈 형태 | H | script |
| KT-07 | rules/kotlin.md | **G** `return@forEach` | H | script |
| KT-08 | rules/kotlin.md | **G→J** `x.forEach {` 후보 중 nullable 수신자·체인 끝이 아닌 것 | R | sonnet-b |
| KT-09 | rules/kotlin.md | **G** `run {`·`with(` (함수 이름이 `run`인 선언은 제외) | H | script |
| KT-10 | rules/kotlin.md | **J** 중첩된 `let/apply/also` · 여러 줄 람다의 `it` | R | sonnet-b |
| KT-11 | rules/kotlin.md | **J** 손으로 쓴 누적 루프 · 루프 안 선형 탐색 | R | sonnet-b |
| KT-12 | rules/kotlin.md | **G→J** `runCatching` 사용처를 모아 자리·로그 유무를 읽는다 | R | sonnet-b |
| KT-13 | rules/kotlin.md | **G** `Result<…>` 타입 | H | script |
| KT-14 | rules/kotlin.md | **G** `@JvmOverloads` | H | script |
| KT-15 | rules/kotlin.md | **G→J** 파일 이름 찾기(find) + 확장 함수가 도메인 행위인지 읽기 | H | sonnet-b |
| KT-16 | rules/kotlin.md | **S** `when` 분기가 enum·sealed 이름(`X.Y`) 또는 enum 상수 이름(`Y`)인데 `else`가 있다 | T | script |
| KT-17 | rules/kotlin.md | **J** 문자열 리터럴 비교·분기  | R | sonnet-b |
| KT-18 | rules/kotlin.md | **G** `@JvmInline value class` 타입이 `domain/`·`repository/` 필드·시그니처에 나오는지 | H | script |
| KT-19 | rules/kotlin.md | **J** 엔티티 두 개를 `==`·`in`·`contains`·`toSet`으로 비교하는 곳 | R | sonnet-b |
| KT-20 | rules/kotlin.md | **G→J** 스프링 애노테이션 붙은 `object` + `object` 안의 `var` | H | sonnet-b |
| KT-21 | rules/kotlin.md | **G→J** public 시그니처의 `Mutable*` (G) · `var` 정당성(J) | H | sonnet-b |
| KT-22 | rules/kotlin.md | **G** `ZonedDateTime/Instant/… .now()` 무인자 | H | script |
| KT-23 | rules/kotlin.md | **J** `=` 본문인데 분기·여러 단계가 있는 서비스·support 메서드 (컨트롤러는 CT-04) | R | sonnet-b |
| KT-24 | rules/kotlin.md | **J** `Long, Long, Long` 나란한 위치 인자 호출 · `Dto(a, b)` 위치 호출 | R | sonnet-b |

<details><summary>KT 명령 (18)</summary>

```bash
# KT-01
rg -n -t kotlin '!!' wes-*/src/main/kotlin

# KT-02
rg -n -t kotlin '\blateinit\b|@Autowired' wes-*/src/main/kotlin

# KT-03
rg -n -t kotlin 'String\.format|"\s*\.format\(' wes-*/src/main/kotlin

# KT-04
rg -n -t kotlin '"\s*\+\s*[A-Za-z(]|[A-Za-z)\]]\s*\+\s*"' wes-*/src/main/kotlin

# KT-05
rg -n -t kotlin '\.findById\(|(fun|val|var) [^=]*:\s*Optional<' wes-*/src/main/kotlin

# KT-06
rg -nU -t kotlin '\?\.let[^\n]*\?:\s*run\b|\}\s*\n\s*\?:\s*run\s*\{' wes-*/src/main/kotlin

# KT-07
rg -n -t kotlin 'return@forEach' wes-*/src/main/kotlin

# KT-08
rg -n -t kotlin '[A-Za-z0-9_)\]]\.forEach \{' wes-*/src/main/kotlin | rg -v '\?\.forEach'

# KT-09
rg -n -t kotlin '(^|[^A-Za-z_.])with\(|(^|[^A-Za-z_.?])run\s*\{|\.run\s*\{' wes-*/src/main/kotlin

# KT-12
rg -n -t kotlin 'runCatching' wes-*/src/main/kotlin

# KT-13
rg -n -t kotlin 'Result<[A-Za-z]' wes-*/src/main/kotlin

# KT-14
rg -n -t kotlin '@JvmOverloads' wes-*/src/main/kotlin

# KT-15
find wes-*/src/main \( -name '*Utils.k?' -o -name '*Util.k?' -o -name '*Extensions.k?' -o -name '*Ext.k?' -o -name '*Helper.k?' \)

# KT-16
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py enum-when-else

# KT-18
vc=$(rg -N -I -A2 -t kotlin '@JvmInline' wes-*/src/main/kotlin | rg -o 'class \w+' | cut -d' ' -f2 | paste -sd'|' -); rg -nP -t kotlin -g '**/domain/*' -g '**/repository/*' "^\s*(@\w+(\(.*\))?\s+)*(val|var) \w+: ($vc)\??\b|fun \w+\(.*: ($vc)\b" wes-*/src/main/kotlin | rg -v "$(rg -l '@JvmInline' wes-*/src/main/kotlin | paste -sd'|' -)"

# KT-20
rg -nU -t kotlin '^@(Component|Service|Configuration|Repository)[^\n]*\n(@[^\n]*\n)*object ' wes-*/src/main/kotlin

# KT-21
rg -n -t kotlin '^\s*(public |internal |override )?(fun|val|var) [^=]*:\s*(Mutable(List|Set|Map|Collection)|ArrayList|HashMap|HashSet|LinkedHashMap)<' wes-*/src/main/kotlin | rg -v '^\S+:\d+:\s*(private|protected) '

# KT-22
rg -n -t kotlin '\b(ZonedDateTime|OffsetDateTime|LocalDateTime|LocalDate|LocalTime|Instant)\.now\(\)|System\.currentTimeMillis\(\)' wes-*/src/main/kotlin

```

명령 없음(J 또는 별도 테스트 실행): KT-10, KT-11, KT-17, KT-19, KT-23, KT-24

</details>

### LY — 레이어·패키지·참조 방향 (서비스·서포트 책임 포함) (21)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| LY-01 | rules/common.md | **G** `support/`의 `import …service.X` | T | script |
| LY-02 | rules/common.md | **G** `infrastructure/`의 `import …service\|repository` | T | script |
| LY-03 | rules/common.md | **G** `repository/`·`domain/`의 상위 계층 import | T | script |
| LY-04 | rules/infrastructure.md | **G** 도메인별로 `infrastructure/`의 `import com.soma.wes.<다른 도메인>` (global 제외) | T | script |
| LY-05 | rules/infrastructure.md | **G** `service/*`의 최상위 `interface` | T | script |
| LY-06 | rules/project-structure.md | **G** `com/soma/wes/infrastructure` 디렉터리 | T | script |
| LY-07 | rules/common.md | **G→J** 대문자·밑줄·하이픈이 든 디렉터리(G) + 새 도메인 이름(J) | T | sonnet-a |
| LY-08 | rules/project-structure.md | **S** 도메인 아래 2~3단계 디렉터리 중 목록 밖 | T | script |
| LY-09 | rules/project-structure.md | **G** `wes-domain`·`wes-api`에 admin 디렉터리 / `wes-domain`에 controller 디렉터리·Application 클래스 | T | script |
| LY-10 | rules/service.md | **G** `class …Controller`인데 `@RestController`가 없는 파일 | H | script |
| LY-11 | rules/service.md | **J** 컨트롤러가 호출하는 public 서비스 메서드의 반환 타입·파라미터 순서 | R | sonnet-a |
| LY-12 | rules/service.md | **J** 컨트롤러가 부르는 서비스 public 메서드의 첫 문장 | R | sonnet-a |
| LY-13 | rules/service.md | **J** `findById…` 뒤에 `.galleryId ==`·`!=` 비교가 따라오는 곳 | R | sonnet-a |
| LY-14 | rules/service.md | **J** 중복·정원 검사를 하는 쓰기에서 잠금 호출(`requireWithLockById`·`findWithLock…`) 유무 | R | sonnet-a |
| LY-15 | rules/service.md | **J** id 목록을 받는 쓰기 메서드의 필터링·건너뜀 | R | sonnet-a |
| LY-16 | rules/service.md | **J** `save` 뒤에 `throw`가 오는 흐름 | R | sonnet-a |
| LY-17 | rules/service.md | **J** `List<Long>`·`Collection<Long>` 요청 필드를 받는 서비스의 상한 검사 | R | sonnet-a |
| LY-18 | rules/service.md | **J** `for`·`map` 안의 `find…`·`toResponse`·`jdbc` 호출 | R | sonnet-a |
| LY-19 | rules/support.md | **G** `*Policy*`·`*Access*` 파일의 public `fun can…` | H | script |
| LY-20 | rules/support.md | **J** `require*` public 메서드의 KDoc 유무·내용 | R | sonnet-a |
| LY-21 | rules/support.md | **J** support의 검증 메서드 이름·예외 종류, 서비스끼리 복제된 검증 | R | sonnet-a |

<details><summary>LY 명령 (11)</summary>

```bash
# LY-01
rg -n -t kotlin -g '**/support/**/*' '^import com\.soma\.wes\.[a-z.]+\.service\.[A-Z]' wes-*/src/main/kotlin

# LY-02
rg -n -t kotlin -g '**/infrastructure/**/*' '^import com\.soma\.wes\.[a-z.]+\.(service|repository)\.' wes-*/src/main/kotlin | rg -v '\.service\.port\.'

# LY-03
rg -n -t kotlin -g '**/repository/**/*' '^import com\.soma\.wes\.[a-z.]+\.(service|support|controller)\.' wes-*/src/main/kotlin | rg -v '\.service\.port\.'; rg -n -t kotlin -g '**/domain/*' '^import com\.soma\.wes\.[a-z.]+\.(service|support|controller|repository|infrastructure|config)\.' wes-*/src/main/kotlin

# LY-04
for d in $(ls -d wes-*/src/main/kotlin/com/soma/wes/*/ | xargs -n1 basename | sort -u); do rg -n -P -t kotlin -g "**/$d/infrastructure/**/*" "^import com\.soma\.wes\.(?!$d\.|global\.)" wes-*/src/main/kotlin; done

# LY-05
rg -n -t kotlin -g '**/service/*' '^(public |internal )?(fun )?interface ' wes-*/src/main/kotlin

# LY-06
find wes-*/src/main/kotlin/com/soma/wes -maxdepth 1 -name infrastructure

# LY-07
find wes-*/src/main/kotlin -type d | rg '/[^/]*[A-Z_-][^/]*$'

# LY-08
find wes-*/src/main/kotlin/com/soma/wes -mindepth 2 -maxdepth 3 -type d | sed -E 's#.*/com/soma/wes/##' | rg -v '^(global|security)/' | rg -v '^[a-z]+/(domain|repository|service|support|controller|scheduler|listener|dto|exception|config|infrastructure)$' | rg -v '^[a-z]+/(repository/projection|service/port|controller/docs|dto/request|dto/response)$' | rg -v '^admin/(audit|impersonation|resource)/(domain|repository|service|support|controller|dto|exception|config)$' | sort -u

# LY-09
find wes-domain/src/main wes-api/src/main -type d -path '*/com/soma/wes/admin*'; find wes-domain/src/main -type d -name controller; find wes-domain/src/main -name '*Application.k?'

# LY-10
rg -l -t kotlin '^(open |abstract )?class \w*Controller\b' wes-*/src/main/kotlin | xargs rg --files-without-match '^@RestController\b'

# LY-19
rg -n -t kotlin -g '*Policy*' -g '*Access*' '\bfun can[A-Z]' wes-*/src/main/kotlin | rg -v 'private fun'

```

명령 없음(J 또는 별도 테스트 실행): LY-11, LY-12, LY-13, LY-14, LY-15, LY-16, LY-17, LY-18, LY-20, LY-21

</details>

### EP — 입구 (scheduler·listener) (7)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| EP-01 | rules/entrypoint.md | **G** `@Scheduled`가 든 파일 중 `/scheduler/` 밖 | H | script |
| EP-02 | rules/entrypoint.md | **G** 리스너·러너 표지가 든 파일 중 `/listener/` 밖 | H | script |
| EP-03 | rules/entrypoint.md | **G** `scheduler/`·`listener/` 안 파일 이름 | T | script |
| EP-04 | rules/entrypoint.md | **G→J** 입구 파일의 `…repository.` import(G) + 본문의 분기 판단(J) | R | sonnet-a |
| EP-05 | rules/entrypoint.md | **G** `@Scheduled` 직후 `fun`의 첫 문장이 리더 가드가 아니다(규칙에 적힌 예외 제외) | T | script |
| EP-06 | rules/entrypoint.md | **J** 각 `@Scheduled`가 부르는 service 메서드의 멱등·잠금 방식 | R | sonnet-a |
| EP-07 | rules/entrypoint.md | **G** 입구 파일(service 안 입구 제외)의 `@Transactional` | H | script |

<details><summary>EP 명령 (6)</summary>

```bash
# EP-01
rg -l -t kotlin '^[ \t]*@Scheduled\b' wes-*/src/main/kotlin | rg -v '/scheduler/'

# EP-02
rg -l -t kotlin '^[ \t]*@(EventListener|TransactionalEventListener|SqsListener)\b|:\s*(ApplicationRunner|CommandLineRunner)\b|^(open |internal )?class \w+Listener\b' wes-*/src/main/kotlin | rg -v '/listener/'

# EP-03
rg --files -t kotlin -g '**/scheduler/*' wes-*/src/main/kotlin | rg -v 'Scheduler[.]kt$'; rg --files -t kotlin -g '**/listener/*' wes-*/src/main/kotlin | rg -v '(Listener|Runner)[.]kt$'

# EP-04
rg -l -t kotlin 'import com\.soma\.wes\.[a-z.]+\.repository\.' $(rg -l -t kotlin '^[ \t]*@(Scheduled|EventListener|TransactionalEventListener|SqsListener)\b|:\s*(ApplicationRunner|CommandLineRunner)\b|^(open |internal )?class \w+(Listener|Scheduler)\b' wes-*/src/main/kotlin)

# EP-05
rg -nUP -t kotlin '^[ \t]*@Scheduled\b[^\n]*\n[ \t]*fun \w+\([^\n]*\{\n(?!\s*if \(!\w*[lL]eadership\.isLeader\(\)\) return)' wes-*/src/main/kotlin | rg -v 'wes-admin-api/'

# EP-07
rg -n -t kotlin '^\s*@Transactional(\(|\s*$)' $(rg -l -t kotlin '^[ \t]*@(Scheduled|EventListener|TransactionalEventListener|SqsListener)\b|:\s*(ApplicationRunner|CommandLineRunner)\b|^(open |internal )?class \w+(Listener|Scheduler)\b' wes-*/src/main/kotlin | rg -v '/service/')

```

명령 없음(J 또는 별도 테스트 실행): EP-06

</details>

### TX — 트랜잭션 (5)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| TX-01 | rules/service.md | **G** 줄머리(0열)의 `@Transactional` | H | script |
| TX-02 | rules/service.md | **G** `@Transactional` 다음 `private fun` | H | script |
| TX-03 | rules/service.md | **J** 클래스 안에서 자기 `@Transactional` 메서드를 부르는 곳(오버로드 포함) | R | sonnet-a |
| TX-04 | rules/service.md | **J** 서비스 public 메서드의 표기와 실제 DB 접근 일치 | R | sonnet-a |
| TX-05 | rules/service.md | **J** `@Transactional` 메서드 안의 `PhotoStorage`·`AiTaskSender`·`OAuthClient` 호출 | R | sonnet-a |

<details><summary>TX 명령 (2)</summary>

```bash
# TX-01
rg -n -t kotlin '^@Transactional\b' wes-*/src/main/kotlin

# TX-02
rg -nUP -t kotlin '@Transactional[^\n]*\n(\s*@[^\n]*\n)*\s*private fun' wes-*/src/main/kotlin

```

명령 없음(J 또는 별도 테스트 실행): TX-03, TX-04, TX-05

</details>

### DM — 도메인·엔티티 (12)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| DM-01 | rules/domain.md | **G** `@Entity`·`@Embeddable` 다음 `data class` | H | script |
| DM-02 | rules/domain.md | **G** `@Entity` 파일 중 `BaseEntity()` 상속이 없는 것 (있으면 KDoc 사유를 읽는다) | H | script |
| DM-03 | rules/domain.md | **G** `@Column`(한 줄형·무인자) 중 `name =`이 없는 줄 (`updatable`은 J) | H | script |
| DM-04 | rules/domain.md | **J** 엔티티의 `var`가 실제로 바뀌는지, AI 소유 컬럼이 `val`인지 | R | sonnet-b |
| DM-05 | rules/domain.md | **G→J** `domain/`의 `init {` (G) + 생성자 안 `require`(EX-01) | H | sonnet-b |
| DM-06 | rules/common.md | **J** 서비스가 엔티티 생성자를 직접 부르는 곳이 `of`가 있는 엔티티인지 | R | sonnet-b |
| DM-07 | rules/domain.md | **J** 마이그레이션의 UK/CHECK 대비 서비스 검증 · 역정규화 컬럼의 KDoc | R | sonnet-b |
| DM-08 | rules/domain.md | **S** 엔티티·`@Embeddable`·`@MappedSuperclass`의 companion 뒤에 멤버가 더 있다 | H | script |
| DM-09 | rules/domain.md | **J** 같은 정렬·상수 리터럴이 도메인마다 복제되었는지 | R | sonnet-b |
| DM-10 | rules/domain.md | **S** 엔티티 메타데이터(Kotlin 타입의 nullable)와 `information_schema.columns.is_nullable` 비교 테스트 — Docker 필요 | T | script |
| DM-11 | rules/domain.md | **G** `@Entity` 파일의 `var` 본문 프로퍼티 중 다음 줄이 `protected set`이 아닌 것 | H | script |
| DM-12 | rules/domain.md | **G→J** `x.prop = …` 대입 후보를 읽어 엔티티인지 가른다(엔티티가 아닌 라이브러리 객체 대입은 거른다) | R | sonnet-b |

<details><summary>DM 명령 (7)</summary>

```bash
# DM-01
rg -nUP -t kotlin '^@(Entity|Embeddable)\b[^\n]*\n(@[^\n]*\n)*(open )?data class' wes-*/src/main/kotlin

# DM-02
rg -l -t kotlin '^@Entity\b' wes-*/src/main/kotlin | xargs rg --files-without-match 'BaseEntity\(\)'

# DM-03
rg -n -t kotlin -g '**/domain/*' '@Column\b' wes-*/src/main/kotlin | rg -v '@Column\(.*name\s*='

# DM-05
rg -n -t kotlin -g '**/domain/*' '^\s*init\s*\{' wes-*/src/main/kotlin

# DM-08
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py companion-entity

# DM-11
rg -l -t kotlin '^@Entity' wes-*/src/main/kotlin | xargs rg -nUP '^[ \t]+(?:override |lateinit )?var \w+(?>[^\n]*)(?!\n[ \t]*protected set)'

# DM-12
rg -n -t kotlin -g '**/service/**/*' -g '**/support/**/*' '^\s*[a-z]\w*(\?)?\.[a-z]\w* = [^=]' wes-*/src/main/kotlin | rg -v 'val |\.apply|propagationBehavior|queryTimeout|isDaemon|timeout'

```

명령 없음(J 또는 별도 테스트 실행): DM-04, DM-06, DM-07, DM-09, DM-10

</details>

### DTO — DTO (10)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| DTO-01 | rules/dto.md | **G** `dto/` 안 `var`, `dto/request\|response`의 비-data `class`·`object` | H | script |
| DTO-02 | rules/dto.md | **G** `dto/*`(루트) 최상위 타입 이름이 `Dto`로 끝나지 않는다 | H | script |
| DTO-03 | rules/dto.md | **S** `dto/*`(루트) 파일의 최상위 타입 수 > 1 | H | script |
| DTO-04 | rules/dto.md | **G** `service/`·`support/`·`infrastructure/` 안의 `data class` 선언 + 들여쓴 `sealed`/`enum` | H | script |
| DTO-05 | rules/dto.md | **G** `dto/request`·`dto/response` 최상위 타입 이름 접미사 | H | script |
| DTO-06 | rules/dto.md | **S** `dto/request\|response` 파일별 `val` 수 > `@field:Schema` 수 | H | script |
| DTO-07 | rules/dto.md | **J** 요청 DTO의 필드 검증 애노테이션 유무, 서비스의 2차 방어(`EMPTY_PHOTO_IDS` 류) | R | sonnet-b |
| DTO-08 | rules/dto.md | **G→J** `@field:Size(max = 숫자)` 리터럴이 도메인 상수와 같은 값인지 | R | sonnet-b |
| DTO-09 | rules/dto.md | **G** `dto/response`의 `val x: <엔티티·값객체>` | H | script |
| DTO-10 | rules/repository.md | **G** `repository/`(projection 제외) 안의 `data class` | H | script |

<details><summary>DTO 명령 (9)</summary>

```bash
# DTO-01
rg -n -t kotlin -g '**/dto/**/*' '^\s*(var|lateinit var) ' wes-*/src/main/kotlin; rg -n -t kotlin -g '**/dto/request/*' -g '**/dto/response/*' '^(open |abstract |internal )?(class|object) \w+' wes-*/src/main/kotlin

# DTO-02
rg -n -o -t kotlin -g '**/dto/*' '^(data |sealed |enum |open |abstract )*(class|interface|object) \w+' wes-*/src/main/kotlin | rg -v 'Dto$'

# DTO-03
for f in $(rg --files -t kotlin -g '**/dto/*' wes-*/src/main/kotlin); do n=$(rg -c '^(data |sealed |enum |open |abstract )*(class|interface|object) ' $f); [ "${n:-0}" -gt 1 ] && echo "$f $n"; done

# DTO-04
rg -n -t kotlin -g '**/service/**/*' -g '**/support/**/*' -g '**/infrastructure/**/*' '^\s*(private |internal |public )?data class' wes-*/src/main/kotlin; rg -n -t kotlin -g '**/service/**/*' -g '**/support/**/*' '^\s+(private |internal )?(sealed (interface|class)|enum class) ' wes-*/src/main/kotlin

# DTO-05
rg -n -o -t kotlin -g '**/dto/request/*' '^(data |sealed |enum )*(class|interface) \w+' wes-*/src/main/kotlin | rg -v 'Request$'; rg -n -o -t kotlin -g '**/dto/response/*' '^(data |sealed |enum )*(class|interface) \w+' wes-*/src/main/kotlin | rg -v 'Response$'

# DTO-06
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py dto-schema

# DTO-08
rg -n -t kotlin -g '**/dto/request/*' '@field:Size\(max = [0-9]+' wes-*/src/main/kotlin

# DTO-09
ents=$(rg -l -t kotlin '^@(Entity|Embeddable)' wes-*/src/main/kotlin | xargs -n1 basename | sed 's/[.]kt$//' | paste -sd'|' -); rg -nP -t kotlin -g '**/dto/response/*' "^\s*(@\w+(\(.*\))?\s+)*val \w+: ($ents)\??\b" wes-*/src/main/kotlin

# DTO-10
rg -n -t kotlin -g '**/repository/**/*' -g '!**/projection/**' '^\s*(private |internal |public )?data class' wes-*/src/main/kotlin

```

명령 없음(J 또는 별도 테스트 실행): DTO-07

</details>

### CT — 컨트롤러 (10)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| CT-01 | rules/controller.md | **G** `controller/`의 `…repository.`·`…infrastructure.`·엔티티 클래스 import | H | script |
| CT-02 | rules/controller.md | **G→J** 정책 클래스 import(G) + 본문의 분기·매핑(J) | R | sonnet-d |
| CT-03 | rules/controller.md | **G** `@RequestMapping`이 없거나 두 접두사로 시작하지 않는 컨트롤러 | H | script |
| CT-04 | rules/controller.md | **G** `=` 본문 `fun`(여러 줄 시그니처 포함) · `@ResponseStatus` | H | script |
| CT-05 | rules/controller.md | **S** 바인딩 애노테이션 순서가 principal→path→header·param→body가 아닌 `fun` | H | script |
| CT-06 | rules/controller.md | **G** `@Valid` 없는 `@RequestBody` | H | script |
| CT-07 | rules/controller.md | **G** `ControllerDocs`를 구현하지 않는 `@RestController` · docs 밖의 문서 애노테이션 | H | script |
| CT-08 | rules/controller.md | **G→J** `permitAll`·`requestMatchers` 사용처가 `PublicPaths` 경유인지 | R | sonnet-d |
| CT-09 | rules/common.md | **G→J** 경로 리터럴의 대문자·밑줄(G) + 복수형 여부(J) | H | sonnet-d |
| CT-10 | CLAUDE.md (마커 없음) | **G** `MultipartFile`·`@RequestPart` | H | script |

<details><summary>CT 명령 (10)</summary>

```bash
# CT-01
ents=$(rg -l -t kotlin '^@(Entity|Embeddable)' wes-*/src/main/kotlin | xargs -n1 basename | sed 's/[.]kt$//' | paste -sd'|' -); rg -n -t kotlin -g '**/controller/**/*' "^import com\.soma\.wes\.[a-z.]+\.($ents)\$" wes-*/src/main/kotlin; rg -n -t kotlin -g '**/controller/**/*' '^import com\.soma\.wes\.[a-z.]+\.(repository|infrastructure)\.' wes-*/src/main/kotlin

# CT-02
rg -n -t kotlin -g '**/controller/*' '^import .*\.(\w*Policy|\w*Access)$' wes-*/src/main/kotlin

# CT-03
rg --files-without-match -t kotlin -g '**/controller/*' '@RequestMapping\(' wes-*/src/main/kotlin; rg -n -t kotlin -g '**/controller/*' '^@RequestMapping\(' wes-*/src/main/kotlin | rg -v '"/(api/v1|internal/admin/v1)'

# CT-04
rg -nUP -t kotlin -g '**/controller/*' '^\s*(override )?fun \w+(?=\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\)\s*(:\s*[^\n{=]+)?=\s)' wes-*/src/main/kotlin; rg -n -t kotlin -g '**/controller/*' '@ResponseStatus' wes-*/src/main/kotlin

# CT-05
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py ctrl-param-order

# CT-06
rg -nP -t kotlin -g '**/controller/*' '(?<!@Valid )@RequestBody' wes-*/src/main/kotlin

# CT-07
rg -l -t kotlin '^@RestController\b' wes-*/src/main/kotlin | xargs rg --files-without-match 'ControllerDocs'; rg -n -t kotlin -g '!**/controller/docs/**' '^\s*@(Tag|Operation|ApiResponses?)\b' wes-*/src/main/kotlin

# CT-08
rg -n -t kotlin 'permitAll|requestMatchers\(' wes-*/src/main/kotlin

# CT-09
rg -n -o -t kotlin -g '**/controller/*' '@(Request|Get|Post|Put|Patch|Delete)Mapping\("[^"]*"' wes-*/src/main/kotlin | perl -ne 'if (/"([^"]*)"/) { ($p=$1) =~ s/\{[^}]*\}//g; print if $p =~ /[A-Z_]/ }'

# CT-10
rg -n -t kotlin 'MultipartFile|@RequestPart|MultipartHttpServletRequest' wes-*/src/main/kotlin

```

</details>

### EX — 예외·에러코드 (5)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| EX-01 | rules/common.md | **G→J** `require(`·`check(`·`throw Illegal…Exception` 후보 — 후보를 읽어 요청·비즈니스 실패인지, 설정 검증·불변식인지 가른다 | R | sonnet-b |
| EX-02 | rules/common.md | **S** `enum class …ErrorCode`마다 `ErrorCodeFormatTest`에 `X.entries`가 있는지 | T | script |
| EX-03 | rules/common.md | **J** 의미가 이름만으로 안 보이는 에러코드의 KDoc 유무 | R | sonnet-b |
| EX-04 | rules/kotlin.md | **G→J** 메시지 없는 `checkNotNull(x)`(G) + 메시지 품질(J) | H | sonnet-b |
| EX-05 | rules/kotlin.md | **G** `requiredId` 정의 중 `checkNotNull(id) {` 형태가 아닌 것 | H | script |

<details><summary>EX 명령 (4)</summary>

```bash
# EX-01
rg -n -t kotlin '(^|[^A-Za-z_.])(require|check)\(|\bthrow (Illegal|Runtime|Unsupported|NoSuchElement)\w*Exception\(|\bthrow Exception\(' wes-*/src/main/kotlin

# EX-02
f=$(rg --files -g 'ErrorCodeFormatTest*' wes-*/src/test/kotlin); for n in $(rg -o -I -N -t kotlin 'enum class \w+ErrorCode' wes-*/src/main/kotlin | sed 's/enum class //'); do rg -q "$n\.entries" $f || echo $n; done

# EX-04
rg -n -t kotlin 'checkNotNull\(' wes-*/src/main/kotlin | rg -v 'checkNotNull\(.*\) \{|checkNotNull\($'

# EX-05
rg -nUP -t kotlin 'val requiredId: Long(?=\s*\n?\s*get\(\) = (?!checkNotNull\(id\) \{))' wes-*/src/main/kotlin

```

명령 없음(J 또는 별도 테스트 실행): EX-03

</details>

### RP — repository·ORM (14)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| RP-01 | rules/repository.md | **S** native `@Query` 앞 KDoc 유무 · `JdbcClient` repository 클래스 KDoc 유무 · `EntityManager` 사용 줄 (근거의 질은 J) | R | script |
| RP-02 | rules/repository.md | **G** `JdbcTemplate`류 사용 (`BulkRepository` 제외) | H | script |
| RP-03 | rules/repository.md | **S** `repository/*`에 파일명과 같은 최상위 타입이 없거나 repository가 둘 이상 (SQL 상수 파일 제외) | H | script |
| RP-04 | rules/repository.md | **G→J** 최상위 `fun XRepository.require…` 목록을 보고 사용처가 둘 이상인지 읽는다 | R | sonnet-c |
| RP-05 | rules/repository.md | **G→J** `@Lock` 다음 `fun` 이름에 `WithLock`/`lock` 없음(G) + KDoc 한 줄(J) | H | sonnet-c |
| RP-06 | rules/repository.md | **G** `@Modifying` 중 두 옵션이 모두 `true`가 아닌 줄 | H | script |
| RP-07 | rules/repository.md | **G** `@Modifying` 다음 `fun`의 반환 타입이 `Int`가 아니다 | H | script |
| RP-08 | rules/repository.md | **J** `@Modifying` 메서드 KDoc의 선행 조건 문장 | R | sonnet-c |
| RP-09 | rules/repository.md | **G** `batchUpdate`가 `*BulkRepository` 밖에 있음 · `*BulkRepository`에 `MANDATORY` 없음 | H | script |
| RP-10 | rules/repository.md | **J** 소프트 삭제 테이블을 읽는 native·JDBC SQL의 `deleted_at` 조건 / KDoc | R | sonnet-c |
| RP-11 | rules/repository.md | **J** JDBC 쓰기 + 같은 트랜잭션의 엔티티 접근 혼용 지점 | R | sonnet-c |
| RP-12 | rules/repository.md | **G** `…Projection` 타입이 `/projection/` 밖 | H | script |
| RP-13 | rules/repository.md | **G** `repository/*` 함수 파라미터의 `List<Long>`·`Set<Long>` | H | script |
| RP-14 | rules/repository.md | **G** `JdbcClient`·`JdbcTemplate`을 쓰는 파일이 `/repository/` 밖 (리더 임대 제외) | T | script |

<details><summary>RP 명령 (11)</summary>

```bash
# RP-01
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py orm-kdoc

# RP-02
rg -n -t kotlin '\b(Named ?Parameter)?JdbcTemplate\b' wes-*/src/main/kotlin | rg -v ':\d+:\s*import|BulkRepository'

# RP-03
for f in $(rg --files -t kotlin -g '**/repository/*' wes-*/src/main/kotlin | rg -v 'PurgeObjectKeysSql'); do b=$(basename $f | sed 's/[.]kt$//'); n=$(rg -c '^(interface|abstract class|open class|class) \w*Repository\b' $f); rg -q "^(interface|abstract class|open class|class|object) $b\b" $f || echo "NAME $f"; [ "${n:-0}" -gt 1 ] && echo "MULTI $f $n"; done

# RP-04
rg -n -t kotlin '^fun \w+Repository\.require' wes-*/src/main/kotlin

# RP-05
rg -nUP -t kotlin '@Lock\([^\n]*\)\s*\n(?:\s*@[^\n]*\n)*\s*fun (?!\w*WithLock|lock)\w+' wes-*/src/main/kotlin

# RP-06
rg -n -t kotlin '@Modifying' wes-*/src/main/kotlin | rg -v 'clearAutomatically\s*=\s*true.*flushAutomatically\s*=\s*true|flushAutomatically\s*=\s*true.*clearAutomatically\s*=\s*true'

# RP-07
rg -nUP -t kotlin '@Modifying[^\n]*\n(?:(?![^\n]*\bfun \w+\()[^\n]*\n){0,14}[ \t]*fun \w+\((?:[^()]|\([^()]*\))*\)(?!\s*:\s*Int\b)' wes-*/src/main/kotlin

# RP-09
rg -l -t kotlin 'batchUpdate' wes-*/src/main/kotlin | rg -v 'BulkRepository'; rg --files -t kotlin -g '*BulkRepository' wes-*/src/main/kotlin | xargs rg --files-without-match 'Propagation.MANDATORY'

# RP-12
rg -n -t kotlin '^\s*(data |sealed )?(class|interface) \w*Projection\b' wes-*/src/main/kotlin | rg -v '/projection/'

# RP-13
rg -nP -t kotlin -g '**/repository/*' '\bfun \w+\((?:[^()]*,\s*)?\w+:\s*(List|Set)<Long>' wes-*/src/main/kotlin

# RP-14
rg -l -t kotlin '\bJdbcClient\b|\bJdbcTemplate\b' wes-*/src/main/kotlin | rg -v '/repository/|global/cluster/PostgresLeaderLeaseStore'

```

명령 없음(J 또는 별도 테스트 실행): RP-08, RP-10, RP-11

</details>

### INF — 인프라·포트 (12)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| INF-01 | rules/infrastructure.md | **G** 벤더 import가 든 파일 중 `infrastructure`·`config` 밖 (규칙 문서의 `grep` 명령과 같다) | T | script |
| INF-02 | rules/infrastructure.md | **G** `service/port`의 벤더·web 타입 import·사용 | T | script |
| INF-03 | rules/infrastructure.md | **G** `service/port/*` 안 class·enum·sealed 선언 | H | script |
| INF-04 | rules/infrastructure.md | **G** `service/`·`support/`·`controller/`의 `…infrastructure.` import | T | script |
| INF-05 | rules/infrastructure.md | **G→J** `infrastructure/*` 파일 이름 목록을 보고 기술·`Local` 접두를 확인 | R | sonnet-c |
| INF-06 | rules/infrastructure.md | **G** `infrastructure/`의 `@Bean`·`@ConfigurationProperties`; `config` 밖의 `@ConfigurationProperties` | H | script |
| INF-07 | rules/infrastructure.md | **J** 어댑터의 catch → 도메인 예외 변환 / 서비스의 벤더 예외 import(INF-01) | R | sonnet-c |
| INF-08 | rules/infrastructure.md | **G→J** 어댑터 로거 선언 형태(G) + 로그 내용의 층(J) | H | sonnet-c |
| INF-09 | rules/infrastructure.md | **J** 어댑터 안의 `retry`·`fallback`·`while` 재시도 루프 | R | sonnet-c |
| INF-10 | rules/infrastructure.md | **J** 어댑터의 배치 호출에서 상수·`hasErrors()` 확인 | R | sonnet-c |
| INF-11 | CLAUDE.md (마커 없음) | **G** `"galleries/`·`"previews/` 리터럴이 `S3PhotoStorage` 밖에 있음 | T | script |
| INF-12 | CLAUDE.md (마커 없음) | **J** `AiTaskSender.send`·Lambda 호출이 사진 한 장씩 도는 루프 | R | sonnet-c |

<details><summary>INF 명령 (8)</summary>

```bash
# INF-01
rg -ln -t kotlin 'import (software\.amazon\.awssdk|io\.jsonwebtoken|org\.springframework\.web\.client|java\.net\.http)' wes-*/src/main/kotlin | rg -v '/(infrastructure|config)/'

# INF-02
rg -n -t kotlin -g '**/service/port/*' '^import (software\.amazon|io\.jsonwebtoken|org\.springframework\.(web|http|util\.(Linked)?MultiValueMap)|java\.net\.http)|MultiValueMap|ResponseEntity|HttpHeaders|HttpEntity|RestClient' wes-*/src/main/kotlin

# INF-03
rg -n -t kotlin -g '**/service/port/*' '^\s*(data class|enum class|sealed|class |object )' wes-*/src/main/kotlin

# INF-04
rg -n -t kotlin -g '**/service/**/*' -g '**/support/**/*' -g '**/controller/**/*' '^import com\.soma\.wes\.[a-z.]+\.infrastructure\.' wes-*/src/main/kotlin

# INF-05
ls wes-*/src/main/kotlin/com/soma/wes/*/infrastructure/* | xargs -n1 basename | sort

# INF-06
rg -n -t kotlin -g '**/infrastructure/**/*' '^\s*@(Bean|ConfigurationProperties)\b' wes-*/src/main/kotlin; rg -n -t kotlin -g '!**/config/**' '^\s*@ConfigurationProperties\b' wes-*/src/main/kotlin

# INF-08
rg -n -t kotlin -g '**/infrastructure/**/*' 'LoggerFactory\.getLogger\(' wes-*/src/main/kotlin | rg -v 'getLogger\(javaClass\)'

# INF-11
rg -n -t kotlin '"previews/|"galleries/' wes-*/src/main/kotlin | rg -v 'S3PhotoStorage|:\d+:\s*(//|\*)'

```

명령 없음(J 또는 별도 테스트 실행): INF-07, INF-09, INF-10, INF-12

</details>

### MG — 마이그레이션 (10)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| MG-01 | rules/migration.md | **G** `ddl-auto`가 `validate`가 아닌 설정 (`*BoundaryTest` 제외) | H | script |
| MG-02 | rules/migration.md | **S** `ddl-auto=validate` 컨텍스트 로딩이 대조해 준다: `./gradlew :wes-api:test --tests com.soma.wes.WesApplicationTests` (Docker 필요) | T | script |
| MG-03 | rules/migration.md | **S** 버전 번호가 1부터 연속인지 · 파일명 형식 | T | script |
| MG-04 | rules/migration.md | **S** git 이력에서 마이그레이션 파일이 수정(M)된 적 있는지 | H | script |
| MG-05 | rules/migration.md | **G** 그 밖의 `V*__*.sql` | H | script |
| MG-06 | rules/migration.md | **G** compose·Testcontainers·CI의 `postgres` 이미지 | H | script |
| MG-07 | rules/migration.md | **S** Kotlin 상수와 현재 `photo_analysis.embedding` 차원 비교 (인프라 repo는 이 저장소 밖) | T | script |
| MG-08 | rules/migration.md | **S** BEGIN/END 마커 쌍 불일치 파일 (새 테이블에 GRANT가 필요한지는 J) | T | script |
| MG-09 | rules/migration.md | **S** 마이그레이션이 만든 테이블 중 TRUNCATE 목록에 없는 것 (`scheduler_leader_lease` 제외) | T | script |
| MG-10 | rules/migration.md | **J** native·JDBC SQL의 `deleted_at` 조건, `sub_scores` 갱신 SQL (RP-10과 같은 점검) | R | sonnet-c |

<details><summary>MG 명령 (8)</summary>

```bash
# MG-01
rg -nP 'ddl-auto\s*[:=]\s*(?!validate)\w+' wes-*/src wes-domain/src/testFixtures docker-compose*.yml | rg -v 'BoundaryTest'

# MG-03
ls wes-api/src/main/resources/db/migration | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | awk '$1!=NR {print "gap/dup at position " NR ": V" $1}'; ls wes-api/src/main/resources/db/migration | rg -v '^V[0-9]+__[a-z0-9_]+\.sql$'

# MG-04
git log --diff-filter=M --format= --name-only -- wes-api/src/main/resources/db/migration | sort -u

# MG-05
find . -name 'V*__*.sql' -not -path '*/build/*' -not -path './wes-api/src/main/resources/db/migration/*'

# MG-06
rg -n -i '(image:\s*|DockerImageName\.parse\()"?postgres' docker-compose*.yml wes-*/src wes-domain/src/testFixtures .github deploy

# MG-07
n=$(rg -o 'const val EMBEDDING_DIMENSION = [0-9]+' wes-domain/src/main | rg -o '[0-9]+$'); rg -o -I 'embedding public\.vector\([0-9]+\)' wes-api/src/main/resources/db/migration | rg -v "vector\($n\)"

# MG-08
for f in wes-api/src/main/resources/db/migration/*.sql; do b=$(rg -c '_GRANT_CONTRACT_BEGIN' $f); e=$(rg -c '_GRANT_CONTRACT_END' $f); [ "${b:-0}" != "${e:-0}" ] && echo "$f BEGIN=${b:-0} END=${e:-0}"; done

# MG-09
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py truncate

```

명령 없음(J 또는 별도 테스트 실행): MG-02, MG-10

</details>

### TS — 테스트 (23)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| TS-01 | rules/test.md | **G** 테스트 소스에 `repository/`·`domain/` 디렉터리 | H | script |
| TS-02 | rules/test.md | **G** `MockMvc`가 든 테스트 파일 중 이름이 `~ContractTest`가 아닌 것 | H | script |
| TS-03 | rules/test.md | **J** ContractTest들의 단언 내용과 위치 | R | sonnet-e |
| TS-04 | rules/test.md | **S** `~Test`(ContractTest·회귀 가드 제외) 파일명에서 접미사를 뗀 이름이 main의 클래스에 없다 | T | script |
| TS-05 | rules/test.md | **S** 대상 클래스는 있으나 테스트 패키지가 다르다 | T | script |
| TS-06 | rules/test.md | **G** 테스트 파일 이름의 `Integration` | H | script |
| TS-07 | rules/test.md | **S** `@IntegrationTest`·`@SpringBootTest`·`@DataJpaTest`·`@WebMvcTest`가 없는 `~Test`(UnitTest·ContractTest 제외) | H | script |
| TS-08 | rules/test.md | **G** `@TestPropertySource`·목 빈·스파이 빈 애노테이션 줄, 들여쓴 `@TestConfiguration` | H | script |
| TS-09 | rules/test.md | **G→J** `@SpringBootTest`인데 `DatabaseClearExtension`·`@IntegrationTest`가 없는 파일 중 DB를 쓰는 것 | R | sonnet-e |
| TS-10 | rules/test.md | **G** 코드 줄의 `deleteAllInBatch(` | H | script |
| TS-11 | rules/test.md | **S** `*Fixture` 중 `@Component` 없음 · `/fixture/` 밖 · 영문 이름 `fun` | H | script |
| TS-12 | rules/test.md | **G** `fun {signUp\|save\|create}User(` | H | script |
| TS-13 | rules/test.md | **G** 테스트·픽스처 소스의 `class …Builder` | H | script |
| TS-14 | rules/test.md | **G** 코드 줄머리의 `@TestComponent` | H | script |
| TS-15 | rules/test.md | **G** `ReflectionTestUtils` | H | script |
| TS-16 | rules/test.md | **G** 테스트 파일의 `AtomicLong(` | H | script |
| TS-17 | rules/test.md | **J** 테스트가 검증하는 동작을 같은 테스트의 given에서 픽스처가 이미 수행하는지 | R | sonnet-e |
| TS-18 | rules/test.md | **G** `@Nested` 다음 비-inner `class` · 백틱 없는 `@Test fun` | H | script |
| TS-19 | rules/test.md | **G** `@Test`가 있는데 단계 주석이 하나도 없는 파일 | H | script |
| TS-20 | rules/test.md | **G** `import kotlin.test.`·`org.junit.jupiter.api.Assertions`·`org.junit.Assert` | H | script |
| TS-21 | rules/test.md | **S** `isInstanceOf(XException::class…)` 뒤 3줄 안에 `errorCode`가 없다(SQL·Timeout 등 비도메인 예외 제외) | H | script |
| TS-22 | rules/test.md | **G** `count()` 결과를 정수 리터럴과 비교 | H | script |
| TS-23 | rules/test.md | **G** 테스트·픽스처의 무인자 `now()` 호출 · `class MutableClock` 정의 | H | script |

<details><summary>TS 명령 (21)</summary>

```bash
# TS-01
find wes-*/src/test/kotlin -type d \( -name repository -o -name domain \)

# TS-02
rg -l -t kotlin '\b(MockMvc|mockMvc|MockMvcBuilders|@AutoConfigureMockMvc)\b' wes-*/src/test/kotlin | rg -v 'ContractTest[.]kt$'

# TS-04
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py test-noclass

# TS-05
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py test-nomirror

# TS-06
rg --files -t kotlin wes-*/src/test/kotlin | rg 'Integration'

# TS-07
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py test-nonspring

# TS-08
rg -n -t kotlin '^\s*@(TestPropertySource|Mock\w*Bean|Spy\w*Bean)\b' wes-*/src/test/kotlin; rg -n -t kotlin '^\s+@TestConfiguration' wes-*/src/test/kotlin

# TS-09
rg -l -t kotlin '@SpringBootTest' wes-*/src/test/kotlin | xargs rg --files-without-match 'DatabaseClearExtension|IntegrationTest'

# TS-10
rg -nP -t kotlin '^\s*[^*/\s].*\bdeleteAllInBatch\(' wes-*/src/test/kotlin wes-domain/src/testFixtures/kotlin

# TS-11
F=$(rg --files -g '*Fixture.kt' wes-domain/src/testFixtures/kotlin wes-admin-api/src/test/kotlin wes-api/src/test/kotlin); [ -n "$F" ] && rg --files-without-match '^@Component' $F; rg --files -g '*Fixture.kt' wes-domain/src/testFixtures/kotlin wes-admin-api/src/test/kotlin wes-api/src/test/kotlin | rg -v '/fixture/'; rg -n -g '*Fixture.kt' '^    fun [A-Za-z_]' wes-domain/src/testFixtures/kotlin wes-admin-api/src/test/kotlin wes-api/src/test/kotlin

# TS-12
rg -n -t kotlin 'fun (signUp|save|create)User\(' wes-*/src/test/kotlin

# TS-13
rg -n -t kotlin '^\s*(private |internal )?class \w*Builder\b' wes-*/src/test/kotlin wes-domain/src/testFixtures/kotlin

# TS-14
rg -n -t kotlin '^\s*@TestComponent' wes-*/src/test/kotlin wes-domain/src/testFixtures/kotlin

# TS-15
rg -l -t kotlin 'ReflectionTestUtils' wes-*/src/test/kotlin wes-domain/src/testFixtures/kotlin

# TS-16
rg -n -t kotlin 'AtomicLong\(' wes-*/src/test/kotlin

# TS-18
rg -nUP -t kotlin '@Nested[^\n]*\n(\s*@[^\n]*\n)*\s*(?!inner )class \w+' wes-*/src/test/kotlin; rg -nUP -t kotlin '@Test\b(?=[^\n]*\n(\s*@[^\n]*\n)*\s*fun (?!`)\w+)' wes-*/src/test/kotlin

# TS-19
rg -l -t kotlin -g '*Test' '@Test' wes-*/src/test/kotlin | xargs rg --files-without-match '// (given|when|then)'

# TS-20
rg -n -t kotlin '^import (kotlin\.test\.|org\.junit\.jupiter\.api\.Assertions|org\.junit\.Assert)' wes-*/src/test/kotlin wes-domain/src/testFixtures/kotlin

# TS-21
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py errorcode-assert

# TS-22
rg -nP -t kotlin 'assertThat\([^)]*\.count\(\)\)\.isEqualTo\(\d+\)' wes-*/src/test/kotlin

# TS-23
rg -n -t kotlin '\b(ZonedDateTime|OffsetDateTime|LocalDateTime|LocalDate|Instant)\.now\(\)|class MutableClock' wes-*/src/test/kotlin wes-domain/src/testFixtures/kotlin

```

명령 없음(J 또는 별도 테스트 실행): TS-03, TS-17

</details>

### CM — 공통 포맷·네이밍·주석 (10)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| CM-01 | rules/common.md | **G** `import …*` | H | script |
| CM-02 | rules/common.md | **G** import 줄 뒤에 빈 줄이 둘 이상 | H | script |
| CM-03 | rules/common.md | **G→J** 선언 파라미터 마지막 줄에 쉼표가 없다(G, 호출부 쉼표는 J) | H | sonnet-d |
| CM-04 | rules/common.md | **J** 비교·상한·키 이름으로 쓰인 리터럴 | R | sonnet-d |
| CM-05 | rules/common.md | **G→J** `config` 밖의 `@ConfigurationProperties`(G, INF-06과 같은 명령) + 코드 속 운영 조정값(J) | R | sonnet-d |
| CM-06 | rules/common.md | **S** 그 밖 클래스의 companion 앞에 member `fun`이 있거나 뒤에 member `val/var`가 있다 | H | script |
| CM-07 | rules/common.md | **J** 단계 사이 빈 줄 없이 뭉친 서비스 메서드 | R | sonnet-d |
| CM-08 | rules/common.md | **J** private 메서드와 호출 위치의 거리 | R | sonnet-d |
| CM-09 | rules/common.md | **G→J** 대문자·밑줄 시작 `fun`(G) + 접두사와 반환 타입의 일치(J) | R | sonnet-d |
| CM-10 | rules/common.md | **G→J** 영문 주석 줄(G) + 반복·리뷰어용 주석(J) | R | sonnet-d |

<details><summary>CM 명령 (7)</summary>

```bash
# CM-01
rg -n -t kotlin '^import .*\.\*\s*$' wes-*/src/main/kotlin

# CM-02
rg -nUP -t kotlin '^import [^\n]*(?=\n[ \t]*\n[ \t]*\n)' wes-*/src/main/kotlin

# CM-03
rg -nUP -t kotlin '^[ \t]+(?:@[\w:]+(?:\([^)]*\))?\s+)*(?:private |internal |override |protected |public )*(?:val |var )?\w+: [^\n]*[^,\s(\[{<]$(?=\n[ \t]*\)\s*(?::|\{|=|$))' wes-*/src/main/kotlin

# CM-05
rg -n -t kotlin -g '!**/config/**' '^\s*@ConfigurationProperties\b' wes-*/src/main/kotlin

# CM-06
python3 -I .claude/skills/rule-audit/scripts/audit_scan.py companion-other

# CM-09
rg -n -t kotlin '\bfun (<[^>]+> )?(\w+\.)?([A-Z]\w*|\w*_\w*)\(' wes-*/src/main/kotlin

# CM-10
rg -n -t kotlin '^\s*(//|/\*\*?|\*)\s+[A-Za-z]+( [A-Za-z\x27,.()-]+){2,}\s*(\*/)?$' wes-*/src/main/kotlin | rg -v '[가-힣]' | rg -v '@(param|return|see|throws|property|since)|https?://'

```

명령 없음(J 또는 별도 테스트 실행): CM-04, CM-07, CM-08

</details>

### GIT — Git (검사 가능한 것만) (2)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| GIT-01 | spec/git-convention.md | **G** 최근 100 커밋 중 형식에 안 맞는 것 (squash 머지의 `(#PR)` 접미사 허용) | H | script |
| GIT-02 | spec/git-convention.md | **G** 로컬 브랜치 이름 형식 | H | script |

<details><summary>GIT 명령 (2)</summary>

```bash
# GIT-01
git log --format='%h %s' -n 100 | rg -v '^\w+ (feat|fix|refactor|hotfix|docs|test|cicd|chore): .+\(#[0-9]+[^)]*\)( \(#[0-9]+\))?$'

# GIT-02
git branch --format='%(refname:short)' | rg -v '^(develop|main)$' | rg -v '^(feat|fix|refactor|hotfix|docs|test|cicd|chore)/[0-9]+-[a-z0-9]+(-[a-z0-9]+)*$'

```

</details>

### LT — 부하 측정 스크립트 (`scripts/load`) (4)

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| LT-01 | rules/load-test.md | **G→J** `lib/report.sh`를 읽지 않는 `scripts/load/**/*.sh` (규칙이 말한 보조 도구 제외, 남은 후보는 보조 도구인지 읽어 가른다) | T | sonnet-d |
| LT-02 | rules/load-test.md | **G** `wes-prod`·`/wes/prod`·`easyselect.kr`·`wes-dev`·`/wes/dev` 리터럴 (주석·`lib/` 제외) | H | script |
| LT-03 | rules/load-test.md | **G** `scripts/load/<하위>/`에 README.md 없음 | T | script |
| LT-04 | rules/load-test.md | **G** 하위 폴더 스크립트의 `source scripts/load/lib/report.sh`(저장소 루트 상대 경로) | H | script |

<details><summary>LT 명령 (4)</summary>

```bash
# LT-01
rg --files-without-match 'lib/report\.sh' scripts/load/*.sh scripts/load/*/*.sh | rg -v 'clone-gallery-photos|replay'

# LT-02
rg -n 'wes-prod|/wes/prod|easyselect\.kr|wes-dev|/wes/dev' scripts/load --glob '*.sh' --glob '*.py' --glob '*.sql' --glob '!**/lib/**' --glob '!**/.venv/**' | rg -v '^[^:]+:[0-9]+:\s*#'

# LT-03
for d in scripts/load/*/; do case $d in */lib/|*/.venv/) continue;; esac; [ -f ${d}README.md ] || echo $d; done

# LT-04
rg -n '^\s*(source|\.) .*lib/report\.sh' scripts/load/*/*.sh | rg -v 'dirname'

```

</details>

## 3. 규칙 수 요약

| 영역 | 규칙 수 | G | S | G→J | J | 강제 H | T | R | script | sonnet 담당 |
|---|---|---|---|---|---|---|---|---|---|---|
| KT | 24 | 11 | 1 | 6 | 6 | 15 | 1 | 8 | 12 | sonnet-b 12 |
| LY | 21 | 9 | 1 | 1 | 10 | 2 | 9 | 10 | 10 | sonnet-a 11 |
| EP | 7 | 5 | 0 | 1 | 1 | 3 | 2 | 2 | 5 | sonnet-a 2 |
| TX | 5 | 2 | 0 | 0 | 3 | 2 | 0 | 3 | 2 | sonnet-a 3 |
| DM | 12 | 4 | 2 | 2 | 4 | 6 | 1 | 5 | 6 | sonnet-b 6 |
| DTO | 10 | 6 | 2 | 1 | 1 | 8 | 0 | 2 | 8 | sonnet-b 2 |
| CT | 10 | 6 | 1 | 3 | 0 | 8 | 0 | 2 | 7 | sonnet-d 3 |
| EX | 5 | 1 | 1 | 2 | 1 | 2 | 1 | 2 | 2 | sonnet-b 3 |
| RP | 14 | 7 | 2 | 2 | 3 | 8 | 1 | 5 | 9 | sonnet-c 5 |
| INF | 12 | 6 | 0 | 2 | 4 | 3 | 4 | 5 | 6 | sonnet-c 6 |
| MG | 10 | 3 | 6 | 0 | 1 | 4 | 5 | 1 | 9 | sonnet-c 1 |
| TS | 23 | 15 | 5 | 1 | 2 | 18 | 2 | 3 | 20 | sonnet-e 3 |
| CM | 10 | 2 | 1 | 4 | 3 | 4 | 0 | 6 | 3 | sonnet-d 7 |
| GIT | 2 | 2 | 0 | 0 | 0 | 2 | 0 | 0 | 2 | sonnet-d 0 |
| LT | 4 | 3 | 0 | 1 | 0 | 2 | 2 | 0 | 3 | sonnet-d 1 |
| **합계** | **169** | 82 | 22 | 26 | 39 | 87 | 28 | 54 | 104 | |

담당별: script 104 · sonnet-a 16 · sonnet-b 23 · sonnet-c 12 · sonnet-d 11 · sonnet-e 3  (권장 부록 A 15개는 별도)

## 부록 A. 권장(should) 규칙의 검사

어겨도 "위반"이 아니라 "개선 후보"로 보고한다. 규칙 문구는 해당 파일의 마커 위치에 있다.

| ID | 출처 | 찾는 법 | 강제 | 담당 |
|---|---|---|---|---|
| KT-s1 | rules/kotlin.md | **G** | H | script |
| KT-s2 | rules/kotlin.md | **J** | R | sonnet-b |
| KT-s3 | rules/kotlin.md | **J** | R | sonnet-b |
| KT-s4 | rules/kotlin.md | **J** | R | sonnet-b |
| LY-s1 | rules/support.md | **S** | R | script |
| LY-s2 | rules/service.md | **J** | R | sonnet-a |
| RP-s1 | rules/repository.md | **J** | R | sonnet-c |
| DTO-s1 | rules/dto.md | **J** | R | sonnet-b |
| CT-s1 | rules/controller.md | **J** | R | sonnet-d |
| TS-s1 | rules/test.md | **J** | R | sonnet-e |
| TS-s2 | rules/test.md | **G** | T | script |
| CM-s1 | rules/common.md | **J** | R | sonnet-d |
| CM-s2 | rules/common.md | **J** | R | sonnet-d |
| MG-s1 | rules/migration.md | **G** | H | script |
| DM-s1 | rules/domain.md | **G** | H | script |

```bash
# KT-s1
rg -n -t kotlin 'if \(\w+ == null\) (return|throw)' wes-*/src/main/kotlin

# LY-s1
for d in $(ls -d wes-*/src/main/kotlin/com/soma/wes/*/ | xargs -n1 basename | sort -u); do rg -l -P -t kotlin -g "**/$d/**/*" "^import com\.soma\.wes\.(?!$d\.|global\.)[a-z]+\.repository\." wes-*/src/main/kotlin; done | sort -u

# TS-s2
rg -n 'INSERT INTO' wes-admin-api/src/test/kotlin

# MG-s1
rg -n -t kotlin -g '*.sql' -g '*.sh' 'ai_concept_assignments|ai_analysis_jobs|photo_category_assignments|folder_set_job_id|\bmodel_version\b|\bcluster_(id|size)\b' wes-*/src scripts --glob '!**/db/migration/**' | rg -v 'GLOSSARY-2'

# DM-s1
rg -n -t kotlin -g '**/domain/*' 'nullable\s*=' wes-*/src/main/kotlin

```


## 부록 B. 스캐너

`%SC%` = `python3 -I .claude/skills/rule-audit/scripts/audit_scan.py <모드>` — 모드: `companion-entity` · `companion-other` · `enum-when-else` · `test-noclass` · `test-nomirror` · `test-nonspring` · `dto-schema` · `ctrl-param-order` · `orm-kdoc` · `truncate` · `errorcode-assert`. 위반만 `경로:줄: 설명`으로 찍는다.

동기화 점검은 `python3 -I .claude/skills/rule-audit/scripts/check_ids.py`.
