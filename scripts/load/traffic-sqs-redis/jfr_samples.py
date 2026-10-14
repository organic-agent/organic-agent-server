"""jfr.sh fetch 의 CPU 표본 요약 — `jfr print --events jdk.ExecutionSample` 텍스트를 표준 입력으로 받아 스트림으로 센다.

    python3 jfr_samples.py [since HH:MM] [until HH:MM]   # 시각은 jfr print 가 찍는 현지 시각(KST), 분 단위 양끝 포함

녹화가 수백 MB 라 이벤트를 쌓지 않고 한 건씩 센다. 묶는 기준 넷:
  스레드      — 이름의 숫자를 N 으로(http-nio-8080-exec-N = 사용자 요청)
  라이브러리  — 스택 맨 위에서 JDK·Kotlin 런타임을 건너뛴 첫 프레임의 패키지(JDK 일은 그것을 부른 라이브러리 몫)
  기능 입구   — 스택 맨 아래(바깥)에서 가장 가까운 com.soma.wes 프레임 — 어느 컨트롤러·스케줄러 아래의 CPU 인가
  우리 코드   — 스택 맨 위에서 가장 가까운 com.soma.wes 프레임 — 우리 코드 중 어디가 직접 일을 시키나
"""
import collections
import re
import sys

SINCE = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else None
UNTIL = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else None

RUNTIME = ("java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.")
LIBRARIES = [  # (접두사, 이름) — 먼저 맞는 것
    ("com.soma.wes.", "wes 코드"),
    ("tools.jackson.", "Jackson(JSON)"), ("com.fasterxml.jackson.", "Jackson(JSON)"),
    ("org.hibernate.", "Hibernate"), ("org.postgresql.", "PostgreSQL 드라이버"), ("com.zaxxer.hikari.", "Hikari"),
    ("org.springframework.security.", "Spring Security"), ("io.jsonwebtoken.", "JWT(jjwt)"),
    ("org.springframework.web.", "Spring MVC"), ("org.springframework.data.", "Spring Data"),
    ("org.springframework.aop.", "Spring AOP·프록시"), ("org.springframework.transaction.", "Spring 트랜잭션"),
    ("org.springframework.", "Spring(기타)"),
    ("org.apache.catalina.", "Tomcat"), ("org.apache.coyote.", "Tomcat"), ("org.apache.tomcat.", "Tomcat"),
    ("ch.qos.logback.", "로깅"), ("org.slf4j.", "로깅"),
    ("software.amazon.", "AWS SDK"), ("io.micrometer.", "Micrometer"), ("io.netty.", "Netty(AWS SDK)"),
    ("org.springdoc.", "springdoc"), ("com.pgvector.", "pgvector"),
]
FRAME = re.compile(r"^\s{4}(\S+)\(")  # "    pkg.Class.method(args) line: N"


def library(frames):
    for f in frames:
        if f.startswith(RUNTIME):
            continue
        for prefix, name in LIBRARIES:
            if f.startswith(prefix):
                return name
        return ".".join(f.split(".")[:2])
    return "JDK(호출자 없음 — GC 아닌 JVM 내부 Java 코드)"


def short(frame):  # com.soma.wes.photo.service.PhotoService.list → photo.PhotoService.list
    parts = frame.split(".")
    domain = parts[3] if len(parts) > 4 else ""
    return f"{domain}.{parts[-2]}.{parts[-1]}" if domain else ".".join(parts[-2:])


counts = {k: collections.Counter() for k in ("thread", "lib", "entry", "nearest")}
total = 0
skipped = 0
cur_time = None
cur_thread = None
frames = []
in_stack = False


def flush():
    global total, skipped
    if cur_time is None:
        return
    if (SINCE and cur_time < SINCE) or (UNTIL and cur_time > UNTIL):
        skipped += 1
        return
    total += 1
    counts["thread"][re.sub(r"\d+", "N", cur_thread or "?")] += 1
    counts["lib"][library(frames)] += 1
    wes = [f for f in frames if f.startswith("com.soma.wes.")]
    counts["entry"][short(wes[-1]) if wes else "(wes 코드 없음)"] += 1
    counts["nearest"][short(wes[0]) if wes else "(wes 코드 없음)"] += 1


for line in sys.stdin:
    if line.startswith("jdk.ExecutionSample {"):
        flush()
        cur_time, cur_thread, frames, in_stack = None, None, [], False
    elif line.startswith("  startTime = "):
        cur_time = line.split("=", 1)[1].strip()[:5]  # HH:MM
    elif line.startswith("  sampledThread = "):
        m = re.search(r'"(.*)"', line)
        cur_thread = m.group(1) if m else "?"
    elif line.startswith("  stackTrace = ["):
        in_stack = True
    elif in_stack:
        m = FRAME.match(line)
        if m:
            frames.append(m.group(1))
        elif line.startswith("  ]"):
            in_stack = False
flush()

if total == 0:
    print("    표본 없음" + (f" — 구간 {SINCE}~{UNTIL} 밖 {skipped}개" if skipped else ""))
    sys.exit()
print(f"    표본 {total:,}개" + (f" (구간 밖 {skipped:,}개 제외)" if skipped else "") + " · 10ms 간격이라 표본 100개 ≈ CPU 1초")
TITLES = {"thread": "스레드", "lib": "라이브러리(실행 중인 곳)", "entry": "기능 입구(바깥 wes 프레임)", "nearest": "우리 코드(가장 안쪽 wes 프레임)"}
for key, top in (("thread", 10), ("lib", 15), ("entry", 20), ("nearest", 20)):
    print(f"\n    [{TITLES[key]}]")
    for name, n in counts[key].most_common(top):
        print(f"    {n * 100 / total:5.1f}%  {n:>8,}  {name}")
