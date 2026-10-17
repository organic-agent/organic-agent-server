// 가상 사용자(부부) — 분석이 끝난 갤러리를 보고 셀렉한다(#286 R-3, 01-drivers QA-1 표).
//
//   k6 run -e API=https://dev.api.easyselect.kr -e TARGETS=targets.json -e PROFILE=full -e SUMMARY=summary.json users.js
//
// 한 명의 동작은 organic-agent-web(1e56e2b) 부부 화면(`app/(client)/gallery/[galleryId]`)의 실제 호출을 따른다.
//   열기   갤러리 · 멤버 · 컨셉 폴더 · 사진 목록 전체(200장씩 끝까지) · 셀렉 상태 · AI 추천 · 협업 세션(세션마다 사진 목록) — 세션 시작에 한 번
//   그 뒤  동작마다 운영에서 잰 빈도(활동 1분당, RATES)로 부른다 — 운영 로그 일주일(갤러리 52 부부, 11,664요청, 02 §15):
//          읽기 87% · 쓰기 13% · 한 사람 분당 ≈ 12.3(1×). 비율만 따르고 강도는 INTENSITY 배(기본 2×, 분당 ≈ 24.6).
//          실제로 가장 많은 것은 사진 목록(그리드 스크롤)이고 상세는 드물다.
//          (10-13 전 대본은 상세 5초·별점 10초·담기/빼기 15초마다 — 쓰기 37%로 실제보다 5배 넘게 많았다.)
//          담기 → 다음 차례에 같은 사진 빼기(DELETE 본문 photoIds). 409·404 는 정상 응답으로 센다.
// 사진 요약(photos/summary)·최신 분석(ai-analysis)은 부르지 않는다 — 웹은 작가 스튜디오와 부부 개인 갤러리 업로드의
// 분석 감시에서만 부른다(10-13 대조, 02 §8.1; 운영 표본에서도 개인 갤러리 업로드 동안만 분당 0.54씩). 회차 갤러리는 초대
// 갤러리라 뺀다. 협업 세션 사진 목록(실제 분당 1.07)은 회차 갤러리에 세션이 없어 사진 목록 빈도에 합쳤다.
// 요청마다 action(무엇)·stage(그 순간 목표 사용자 수) 태그를 달아, 요약에서 단계별·동작별 p95 를 따로 본다.
import http from "k6/http";
import exec from "k6/execution";
import { sleep } from "k6";
import { SharedArray } from "k6/data";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.1.0/index.js";

const API = __ENV.API;
// 가상 사용자마다 따로 읽으면 사람 수 × 파일 크기만큼 메모리를 먹는다(A-4a 1,800명: 발생기 4GB 에서 k6 가 OOM 으로 죽음) — 한 벌만 두고 같이 읽는다.
const TARGETS = new SharedArray("targets", () => JSON.parse(open(__ENV.TARGETS))); // [{ userId, galleryId, token }]
const PROFILE = __ENV.PROFILE || "full";

const TICK_SECONDS = 5;
const TICKS_PER_MINUTE = 60 / TICK_SECONDS;
// 운영 로그의 빈도는 한 부부의 평균(분당 ≈ 12.3)이라 비율로만 쓰고, 강도는 배수로 정한다(작성자 10-13).
// 기본 2× = 분당 ≈ 24.6 — 같은 로그의 가장 바쁜 1분은 86~167회였다. run-k6.sh --intensity 로 바꾼다.
const INTENSITY = Number(__ENV.INTENSITY || 2);
// 활동 1분당 호출 수(1×) — 운영 로그(02 §15). 5초 차례마다 빈도 × 강도 / 12 만큼 쌓아 1이 넘으면 부른다.
const RATES = {
  list: 5.6,          // 사진 목록 한 페이지(갤러리 4.5 + 협업 세션 1.07)
  watch: 2.7,         // 셀렉 상태(photo-selection)
  notifications: 1.1,
  retouch: 0.75,      // 보정 회차 목록
  detail: 0.54,       // 사진 상세
  rating: 1.5,        // 별점(PUT)
  selection: 0.13,    // 담기·빼기 번갈아
};
const PAGE_SIZE = 200;
const MAX_PAGES = 50;

// 단계 = [올리는 시간, 유지 시간, 목표 사용자 수, (이름 — 같은 수가 두 번 나올 때 단계별 지표를 나누려고)]
const PROFILES = {
  smoke: [["5s", "1m", 10]],
  full: [["30s", "2m", 50], ["30s", "2m", 100], ["30s", "2m", 200], ["30s", "2m", 400]],
  // #313 가용성 기준선 — 200명을 10분 동안 그대로 두고 그 사이에 배포·장애 자극을 넣는다(availability-2-servers/).
  steady: [["30s", "10m", 200]],
  // #313 QA-4 피크 흡수(A-3) — 200명 3분(기준) → 30초에 600명으로 올려 10분 → 다시 200명 5분. 그 사이 ASG 가 서버를 늘리고 줄인다.
  step600: [["30s", "3m", 200], ["30s", "10m", 600], ["30s", "5m", 200, "200-after"]],
  // #313 A-4a 요청 상한 — 600명부터 300명씩 5분 계단으로 1,800명까지. 처음 p95 > 500ms·오류 ≥ 1% 인 계단이 그 구성의 천장이다.
  stairs1800: [["30s", "5m", 600], ["30s", "5m", 900], ["30s", "5m", 1200], ["30s", "5m", 1500], ["30s", "5m", 1800]],
};
const STEPS = PROFILES[PROFILE];
const ACTIONS = ["open", "list", "detail", "rating", "select", "deselect", "watch", "notifications", "retouch"];
const COLLAB_PAGE_SIZE = 200;

function seconds(text) {
  return text.endsWith("m") ? parseInt(text, 10) * 60 : parseInt(text, 10);
}

// 경과 시간 → 그 순간의 목표 사용자 수(올리는 중이면 다음 목표).
const BOUNDARIES = [];
{
  let at = 0;
  for (const [ramp, hold, target, name] of STEPS) {
    at += seconds(ramp) + seconds(hold);
    BOUNDARIES.push([at, name || String(target)]);
  }
}
function stageNow() {
  const elapsed = exec.instance.currentTestRunDuration / 1000;
  for (const [end, label] of BOUNDARIES) if (elapsed < end) return label;
  return "down";
}

const stages = [];
for (const [ramp, hold, target] of STEPS) {
  stages.push({ duration: ramp, target }, { duration: hold, target });
}
stages.push({ duration: "10s", target: 0 });

// 임계값은 판정이 아니라 요약에 단계별·동작별 지표를 만들게 하는 장치다. 판정(200명 p95 ≤ 500ms)은 run-k6.sh 가 한다.
const thresholds = { http_req_duration: ["p(95)<100000"], http_req_failed: ["rate<1"] };
for (const [, label] of BOUNDARIES) {
  thresholds[`http_req_duration{stage:${label}}`] = ["p(95)<100000"];
  thresholds[`http_req_failed{stage:${label}}`] = ["rate<1"];
}
for (const action of ACTIONS) thresholds[`http_req_duration{action:${action}}`] = ["p(95)<100000"];

export const options = {
  scenarios: { users: { executor: "ramping-vus", startVUs: 0, stages, gracefulRampDown: "10s" } },
  thresholds,
  summaryTrendStats: ["avg", "p(50)", "p(95)", "p(99)", "max"],
  insecureSkipTLSVerify: false,
};

const SELECTION_STATUSES = http.expectedStatuses({ min: 200, max: 299 }, 404, 409);

// VU 마다 따로 사는 상태
let photoIds = null;
let pageCount = 1;
let nextPage = 0;
let held = null;
// 동작마다 쌓인 몫. 시작값을 흩어 같은 순간에 들어온 사람들이 같은 차례에 몰리지 않게 한다.
const due = Object.fromEntries(Object.keys(RATES).map((action) => [action, Math.random()]));

function call(method, path, action, body, extra) {
  const params = Object.assign(
    {
      headers: { Authorization: `Bearer ${me().token}`, "Content-Type": "application/json" },
      tags: { action, stage: stageNow(), name: action },
      timeout: "30s",
    },
    extra || {},
  );
  return http.request(method, `${API}${path}`, body === undefined ? null : JSON.stringify(body), params);
}

function me() {
  return TARGETS[(exec.vu.idInTest - 1) % TARGETS.length];
}

function openGallery(g) {
  call("GET", `/api/v1/galleries/${g}`, "open");
  call("GET", `/api/v1/galleries/${g}/members`, "open");
  call("GET", `/api/v1/galleries/${g}/concept-folders`, "open");
  listPhotos(g);
  call("GET", `/api/v1/galleries/${g}/photo-selection`, "open");
  call("GET", `/api/v1/galleries/${g}/photo-selection/recommendations`, "open");
  const sessions = call("GET", `/api/v1/galleries/${g}/collab-sessions`, "open");
  for (const session of sessionsOf(sessions)) {
    call("GET", `/api/v1/galleries/${g}/collab-sessions/${session}/photos?page=0&size=${COLLAB_PAGE_SIZE}`, "open");
  }
}

// 응답은 CollabSessionResponse 배열이다. 회차 갤러리에는 보통 세션이 없어 목록 한 번으로 끝난다.
function sessionsOf(res) {
  return res.status === 200 ? res.json().map((session) => session.sessionId) : [];
}

function listPhotos(g) {
  const ids = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const res = call("GET", `/api/v1/galleries/${g}/photos?page=${page}&size=${PAGE_SIZE}`, "list");
    if (res.status !== 200) break;
    const body = res.json();
    for (const photo of body.contents) ids.push(photo.photoId);
    if (!body.hasNext) break;
  }
  if (ids.length > 0) {
    photoIds = ids;
    pageCount = Math.ceil(ids.length / PAGE_SIZE);
  }
}

// 이번 차례에 그 동작을 몇 번 부를지 — 빈도를 쌓아 정수 부분만큼.
function turns(action) {
  due[action] += (RATES[action] * INTENSITY) / TICKS_PER_MINUTE;
  const n = Math.floor(due[action]);
  due[action] -= n;
  return n;
}

function pick() {
  return photoIds[Math.floor(Math.random() * photoIds.length)];
}

export default function () {
  const started = Date.now();
  const g = me().galleryId;

  if (photoIds === null) openGallery(g);

  if (photoIds !== null) {
    for (let i = turns("list"); i > 0; i--) {
      call("GET", `/api/v1/galleries/${g}/photos?page=${nextPage}&size=${PAGE_SIZE}`, "list");
      nextPage = (nextPage + 1) % pageCount;
    }
    for (let i = turns("detail"); i > 0; i--) call("GET", `/api/v1/galleries/${g}/photos/${pick()}`, "detail");
    for (let i = turns("rating"); i > 0; i--) {
      call("PUT", `/api/v1/galleries/${g}/photos/${pick()}/rating`, "rating", { score: 1 + Math.floor(Math.random() * 5) });
    }
    for (let i = turns("selection"); i > 0; i--) {
      if (held === null) {
        held = pick();
        call("POST", `/api/v1/galleries/${g}/photo-selection/photos`, "select",
          { photoIds: [held], retouchPhotos: [] }, { responseCallback: SELECTION_STATUSES });
      } else {
        call("DELETE", `/api/v1/galleries/${g}/photo-selection/photos`, "deselect",
          { photoIds: [held] }, { responseCallback: SELECTION_STATUSES });
        held = null;
      }
    }
  }
  for (let i = turns("watch"); i > 0; i--) call("GET", `/api/v1/galleries/${g}/photo-selection`, "watch");
  for (let i = turns("retouch"); i > 0; i--) call("GET", `/api/v1/galleries/${g}/retouch/rounds`, "retouch");
  for (let i = turns("notifications"); i > 0; i--) call("GET", "/api/v1/notifications", "notifications");

  sleep(Math.max(0, TICK_SECONDS - (Date.now() - started) / 1000));
}

export function handleSummary(data) {
  return {
    [__ENV.SUMMARY || "summary.json"]: JSON.stringify(data),
    stdout: textSummary(data, { indent: " ", enableColors: false }),
  };
}
