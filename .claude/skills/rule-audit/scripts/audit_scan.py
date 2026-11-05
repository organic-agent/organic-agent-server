#!/usr/bin/env python3
"""rule-audit 보조 스캐너. 저장소 루트에서 `python3 -I audit_scan.py <모드>` 로 돌린다.
위반만 한 줄씩 `경로:줄: 설명` 으로 찍는다(없으면 아무것도 찍지 않는다)."""
import glob, os, re, sys

ROOT = os.getcwd()
MAIN = 'wes-*/src/main/kotlin/**/*.kt'
TEST = 'wes-*/src/test/kotlin/**/*.kt'


def files(pattern):
    return sorted(glob.glob(os.path.join(ROOT, pattern), recursive=True))


def rel(path):
    return os.path.relpath(path, ROOT)


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def line_of(text, idx):
    return text.count('\n', 0, idx) + 1


def strip(src):
    """주석·문자열을 같은 길이의 공백으로 바꾼다(줄바꿈 유지)."""
    out, i, n = [], 0, len(src)
    blank = lambda s: re.sub(r'[^\n]', ' ', s)
    while i < n:
        c, two = src[i], src[i:i + 2]
        if two == '//':
            j = src.find('\n', i); j = n if j < 0 else j
            out.append(' ' * (j - i)); i = j
        elif two == '/*':
            j = src.find('*/', i + 2); j = n if j < 0 else j + 2
            out.append(blank(src[i:j])); i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append(blank(src[i:j])); i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            j = min(j + 1, n)
            out.append(blank(src[i:j])); i = j
        elif c == "'":
            m = re.match(r"'(\\.|[^'\\])'", src[i:])
            if m: out.append(' ' * m.end()); i += m.end()
            else: out.append(c); i += 1
        else:
            out.append(c); i += 1
    return ''.join(out)


def match_brace(t, k):
    d = 0
    for p in range(k, len(t)):
        if t[p] == '{': d += 1
        elif t[p] == '}':
            d -= 1
            if d == 0: return p
    return len(t) - 1


def enclosing_open(t, idx):
    d = 0
    for p in range(idx - 1, -1, -1):
        if t[p] == '}': d += 1
        elif t[p] == '{':
            if d == 0: return p
            d -= 1
    return -1


def word_at(t, k, w):
    return t.startswith(w, k) and (k == 0 or not (t[k - 1].isalnum() or t[k - 1] in '_.'))


def companion(mode):
    """엔티티·값객체(@Entity/@Embeddable/@MappedSuperclass)의 companion 은 클래스 맨 끝,
    그 밖은 필드 바로 아래·메서드 위(= 앞에 member fun 이 없고, 뒤에 member val/var 가 없다)."""
    for f in files(MAIN):
        src = read(f)
        if 'companion object' not in src: continue
        t = strip(src)
        for m in re.finditer(r'companion object', t):
            idx = m.start()
            b = enclosing_open(t, idx)
            if b < 0: continue
            e = match_brace(t, b)
            hdr = list(re.finditer(r'\b(class|object|interface)\b\s+(\w+)', t[:b]))
            if not hdr: continue
            hm = hdr[-1]
            ps = max(t.rfind('\n}', 0, hm.start()), t.rfind('\nimport ', 0, hm.start()), t.rfind('{', 0, hm.start()))
            entity = bool(re.search(r'@(Entity|Embeddable|MappedSuperclass)\b', t[max(ps, 0):hm.start()]))
            funs, props, d, pd = [], [], 0, 0
            for k in range(b + 1, e):
                ch = t[k]
                if ch == '{': d += 1
                elif ch == '}': d -= 1
                elif ch == '(' and d == 0: pd += 1
                elif ch == ')' and d == 0: pd -= 1
                elif d == 0 and pd == 0:
                    if word_at(t, k, 'fun '): funs.append(k)
                    elif word_at(t, k, 'val ') or word_at(t, k, 'var '): props.append(k)
            # 몸통 `{`는 이름·상위 타입 뒤 줄바꿈을 넘어 올 수 있다. 다음 선언이 먼저 나오면 몸통 없는 companion이다.
            body = re.match(r'companion object(?:\s+\w+)?(?:\s*:\s*[\w<>.,?\s()]+?)?\s*\{', t[idx:])
            ce = match_brace(t, idx + body.end() - 1) if body else t.find('\n', idx) if t.find('\n', idx) >= 0 else len(t) - 1
            at_end = t[ce + 1:e].strip() == ''
            below_fields = not [x for x in funs if x < idx] and not [x for x in props if x > ce]
            name = hm.group(2)
            if mode == 'companion-entity' and entity and not at_end:
                print(f'{rel(f)}:{line_of(t, idx)}: {name} companion 이 맨 끝이 아니다')
            if mode == 'companion-other' and not entity and not below_fields:
                print(f'{rel(f)}:{line_of(t, idx)}: {name} companion 이 필드 바로 아래·메서드 위가 아니다')


def enum_when_else():
    enums, sealed, entries = set(), set(), set()
    for f in files(MAIN):
        s = read(f)
        enums |= set(re.findall(r'enum class (\w+)', s))
        sealed |= set(re.findall(r'sealed (?:class|interface) (\w+)', s))
        for m in re.finditer(r'enum class \w+[^{]*\{', s):
            head = s[m.end():].split(';')[0].split('}')[0]
            entries |= set(re.findall(r'^\s*([A-Z][A-Z0-9_]*)\b', head, re.M))
    for f in files(MAIN):
        s = read(f)
        for m in re.finditer(r'\bwhen\s*(\([^)]*\))?\s*\{', s):
            i, d = m.end(), 1
            while i < len(s) and d > 0:
                d += (s[i] == '{') - (s[i] == '}'); i += 1
            body, depth, conds, has_else = s[m.end():i - 1], 0, [], False
            for line in body.split('\n'):
                if depth == 0:
                    t = line.strip()
                    if t.startswith('else ->') or t == 'else': has_else = True
                    mm = re.match(r'([A-Za-z_][\w.]*(?:\s*,\s*[A-Za-z_][\w.]*)*)\s*->', t)
                    if mm: conds.append(mm.group(1))
                depth += line.count('{') - line.count('}')
            qualified = [c for cond in conds for c in re.split(r'\s*,\s*', cond) if '.' in c]
            bare = [c for cond in conds for c in re.split(r'\s*,\s*', cond) if '.' not in c and c in entries]
            if has_else and (any(c.split('.')[0] in enums | sealed for c in qualified) or bare):
                print(f'{rel(f)}:{line_of(s, m.start())}: enum/sealed when 에 else 가 있다')


def declared_types():
    types = {}
    for f in files(MAIN):
        s = read(f)
        pkg = re.search(r'^package ([\w.]+)', s, re.M)
        pkg = pkg.group(1) if pkg else ''
        for m in re.finditer(r'^\s*(?:(?:public|internal|private|abstract|open|data|sealed|enum|annotation|fun|value)\s+)*(?:class|interface|object)\s+(\w+)', s, re.M):
            types.setdefault(m.group(1), set()).add(pkg)
    return types


def test_names(mode):
    types = declared_types()
    for f in files(TEST):
        b = os.path.basename(f)[:-3]
        if not b.endswith('Test') or b.endswith(('ContractTest', 'FlowTest')): continue
        s = read(f)
        pkg = re.search(r'^package ([\w.]+)', s, re.M).group(1)
        base = re.sub(r'(UnitTest|Test)$', '', b)
        guard = re.search(r'(BoundaryTest|WiringTest|GuardTest)$', b) or b == 'ErrorCodeFormatTest'
        if mode == 'test-noclass' and base not in types and not guard:
            print(f'{rel(f)}:1: 대상 클래스 {base} 가 main 에 없다')
        elif mode == 'test-nomirror' and base in types and pkg not in types[base]:
            print(f'{rel(f)}:1: 패키지가 대상({sorted(types[base])[0]})을 미러링하지 않는다')
        elif mode == 'test-nonspring' and not b.endswith('UnitTest') and not guard \
                and not re.search(r'@(IntegrationTest|SpringBootTest|DataJpaTest|WebMvcTest)|ApplicationContextRunner', s):
            print(f'{rel(f)}:1: Spring 없는 테스트인데 UnitTest 이름이 아니다')


def dto_schema():
    """요청 DTO의 모든 필드, 응답 DTO의 null 허용 필드에 @field:Schema 가 있어야 한다(필드 단위)."""
    def field_type(line):
        m = re.search(r'\bval \w+\s*:\s*', line)
        if not m: return ''
        t, d = [], 0
        for ch in line[m.end():]:
            if ch in '<(': d += 1
            elif ch in '>)': d -= 1
            elif d == 0 and ch in '=,': break
            t.append(ch)
        return ''.join(t).strip()
    for f in files('wes-*/src/main/kotlin/**/dto/request/*.kt') + files('wes-*/src/main/kotlin/**/dto/response/*.kt'):
        request = '/dto/request/' in f
        lines = read(f).split('\n')
        seen = False
        comp = 0  # companion object 블록 안의 깊이(0이면 밖) — 그 안의 val 은 필드가 아니라 상수다
        for i, l in enumerate(lines):
            if comp:
                comp += l.count('{') - l.count('}')
                continue
            if re.match(r'\s*companion object', l):
                comp = max(l.count('{') - l.count('}'), 0)
                continue
            if '@field:Schema' in l: seen = True
            m = re.match(r'^\s+(?:@\S+(?:\(.*\))?\s+)*val (\w+)\b', l)
            if m:
                if not seen and (request or field_type(l).endswith('?')):
                    print(f'{rel(f)}:{i + 1}: {m.group(1)} 에 @field:Schema 가 없다')
                seen = False
            elif re.search(r'\b(class|object)\b', l): seen = False


def ctrl_param_order():
    rank = {'AuthenticationPrincipal': 0, 'PathVariable': 1, 'RequestParam': 2, 'RequestHeader': 2, 'CookieValue': 2, 'RequestBody': 3}
    for f in files('wes-*/src/main/kotlin/**/controller/*.kt'):
        s = read(f)
        for m in re.finditer(r'\bfun (\w+)\(', s):
            i, d = m.end(), 1
            while i < len(s) and d > 0:
                d += (s[i] == '(') - (s[i] == ')'); i += 1
            order = [rank[a] for a in re.findall(r'@(AuthenticationPrincipal|PathVariable|RequestParam|RequestHeader|CookieValue|RequestBody)\b', s[m.end():i - 1])]
            if order != sorted(order):
                print(f'{rel(f)}:{line_of(s, m.start())}: {m.group(1)} 파라미터 순서가 principal→path→기타→body 가 아니다')


def orm_kdoc():
    def kdoc_before(lines, idx):
        k = idx - 1
        while k >= 0 and (lines[k].strip().startswith('@') or lines[k].strip() == ''): k -= 1
        return k >= 0 and lines[k].strip().endswith('*/')
    for f in files(MAIN):
        s = read(f); lines = s.split('\n')
        for i, l in enumerate(lines):
            if 'nativeQuery = true' in l:
                j = i
                while j > 0 and '@Query' not in lines[j]: j -= 1
                if not kdoc_before(lines, j): print(f'{rel(f)}:{j + 1}: native 쿼리에 근거 KDoc 이 없다')
        if '/repository/' in f and re.search(r'\bJdbcClient\b', s):
            for i, l in enumerate(lines):
                if re.match(r'^(open |internal )?class \w+', l):
                    if not kdoc_before(lines, i): print(f'{rel(f)}:{i + 1}: JdbcClient 클래스에 근거 KDoc 이 없다')
                    break
        for i, l in enumerate(lines):
            if re.search(r'\b[eE]ntityManager\.', l) and not re.match(r'\s*(//|\*)', l):
                # 감싸는 함수의 KDoc 이 EntityManager 를 쓰는 까닭을 말하면 통과
                k = i
                while k >= 0 and not re.match(r'\s*(?:(?:private|internal|public|override|open) )*fun ', lines[k]): k -= 1
                j = k - 1
                while j >= 0 and lines[j].strip().startswith('@'): j -= 1
                doc_end = j
                while j >= 0 and not lines[j].strip().startswith('/**'): j -= 1
                doc = '\n'.join(lines[j:doc_end + 1]) if k >= 0 and doc_end >= 0 and lines[doc_end].strip().endswith('*/') else ''
                if 'EntityManager' not in doc:
                    print(f'{rel(f)}:{i + 1}: EntityManager 사용 — 감싸는 함수 KDoc 에 근거가 없다')


def truncate_list():
    created, dropped = set(), set()
    sql = sorted(glob.glob(os.path.join(ROOT, 'wes-api/src/main/resources/db/migration/V*.sql')), key=lambda p: int(re.search(r'/V(\d+)__', p).group(1)))
    for f in sql:
        s = re.sub(r'--[^\n]*', '', read(f))
        for m in re.finditer(r'CREATE TABLE (?:IF NOT EXISTS )?(?:public\.)?"?(\w+)"?', s, re.I): created.add(m.group(1)); dropped.discard(m.group(1))
        for m in re.finditer(r'DROP TABLE (?:IF EXISTS )?((?:public\.)?\w+(?:\s*,\s*(?:public\.)?\w+)*)', s, re.I):
            for t in re.split(r'\s*,\s*', m.group(1)):
                t = t.replace('public.', ''); dropped.add(t); created.discard(t)
        for m in re.finditer(r'ALTER TABLE (?:ONLY )?(?:public\.)?(\w+)\s+RENAME TO (\w+)', s, re.I):
            created.discard(m.group(1)); created.add(m.group(2))
    script = read(os.path.join(ROOT, 'scripts/reset-test-data.sh'))
    listed = set(re.findall(r'\b([a-z_]+)\b', ' '.join(re.findall(r'TABLES="[^"]*"', script))))
    for t in sorted(created - listed - {'flyway_schema_history', 'scheduler_leader_lease'}):
        print(f'scripts/reset-test-data.sh:1: TRUNCATE 목록에 {t} 가 없다')


def errorcode_assert():
    # errorCode를 가진 우리 예외만 본다 — JDK·라이브러리 예외(ClassNotFoundException·SQLException…)에는 단언할 errorCode가 없다.
    coded = set()
    for f in files(MAIN):
        src = read(f)
        for m in re.finditer(r'class (\w+Exception)\b', src):
            # 선언 머리(본문 `{` 전까지)를 본다 — 괄호 든 기본값·`private constructor(errorCode: …)`도 잡힌다.
            head = src[m.end():m.end() + 800].split('{', 1)[0]
            if 'errorCode' in head: coded.add(m.group(1))
    for f in files(TEST):
        s = read(f)
        for m in re.finditer(r'isInstanceOf\(\s*([A-Z]\w*Exception)::class', s):
            if m.group(1) not in coded: continue
            tail = s[m.end():m.end() + 160].split('\n')[:3]
            if not any('errorCode' in x for x in tail):
                print(f'{rel(f)}:{line_of(s, m.start())}: {m.group(1)} 를 타입만 단언한다(errorCode 없음)')


MODES = {
    'companion-entity': lambda: companion('companion-entity'),
    'companion-other': lambda: companion('companion-other'),
    'enum-when-else': enum_when_else,
    'test-noclass': lambda: test_names('test-noclass'),
    'test-nomirror': lambda: test_names('test-nomirror'),
    'test-nonspring': lambda: test_names('test-nonspring'),
    'dto-schema': dto_schema,
    'ctrl-param-order': ctrl_param_order,
    'orm-kdoc': orm_kdoc,
    'truncate': truncate_list,
    'errorcode-assert': errorcode_assert,
}

if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in MODES:
        sys.exit('모드: ' + ' | '.join(MODES))
    MODES[sys.argv[1]]()
