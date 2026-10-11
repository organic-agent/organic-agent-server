// 가상 사용자(부부) — 분석이 끝난 갤러리를 보고 셀렉한다(#286 R-3, 01-drivers QA-1 표).
//
//   k6 run -e API=https://dev.api.easyselect.kr -e TARGETS=targets.json -e PROFILE=full -e SUMMARY=summary.json users.js
//
// 한 명의 동작은 organic-agent-web 의 실제 호출을 따른다.
//   열기   갤러리 · 사진 요약 · 셀렉 상태 · 사진 목록 전체(200장씩 끝까지) — 세션 시작과 12분마다(주소 갈아 주기)
//   5초    사진 상세
//   10초   별점(PUT, 덮어쓰기) · 셀렉 감시(photo-selection)
//   15초   담기 → 다음 차례에 같은 사진 빼기(여럿이 같은 갤러리라 409·404 는 정상 응답으로 센다)
//   60초   알림
// 요청마다 action(무엇)·stage(그 순간 목표 사용자 수) 태그를 달아, 요약에서 단계별·동작별 p95 를 따로 본다.
import http from "k6/http";
import exec from "k6/execution";
import { sleep } from "k6";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.1.0/index.js";

const API = __ENV.API;
const TARGETS = JSON.parse(open(__ENV.TARGETS)); // [{ userId, galleryId, token }]
const PROFILE = __ENV.PROFILE || "full";

const TICK_SECONDS = 5;
const RELIST_MS = 12 * 60 * 1000;
const PAGE_SIZE = 200;
const MAX_PAGES = 50;

// 단계 = [올리는 시간, 유지 시간, 목표 사용자 수]
const PROFILES = {
  smoke: [["5s", "1m", 10]],
  full: [["30s", "2m", 50], ["30s", "2m", 100], ["30s", "2m", 200], ["30s", "2m", 400]],
};
const STEPS = PROFILES[PROFILE];
const ACTIONS = ["open", "list", "detail", "rating", "select", "deselect", "watch", "notifications"];

function seconds(text) {
  return text.endsWith("m") ? parseInt(text, 10) * 60 : parseInt(text, 10);
}

// 경과 시간 → 그 순간의 목표 사용자 수(올리는 중이면 다음 목표).
const BOUNDARIES = [];
{
  let at = 0;
  for (const [ramp, hold, target] of STEPS) {
    at += seconds(ramp) + seconds(hold);
    BOUNDARIES.push([at, String(target)]);
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
let listedAt = 0;
let tick = 0;
let held = null;

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
  call("GET", `/api/v1/galleries/${g}/photos/summary`, "open");
  call("GET", `/api/v1/galleries/${g}/photo-selection`, "open");
  const ids = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const res = call("GET", `/api/v1/galleries/${g}/photos?page=${page}&size=${PAGE_SIZE}`, "list");
    if (res.status !== 200) break;
    const body = res.json();
    for (const photo of body.contents) ids.push(photo.photoId);
    if (!body.hasNext) break;
  }
  if (ids.length > 0) photoIds = ids;
  listedAt = Date.now();
}

function pick() {
  return photoIds[Math.floor(Math.random() * photoIds.length)];
}

export default function () {
  const started = Date.now();
  const g = me().galleryId;

  if (photoIds === null || started - listedAt > RELIST_MS) openGallery(g);

  if (photoIds !== null) {
    call("GET", `/api/v1/galleries/${g}/photos/${pick()}`, "detail");
    if (tick % 2 === 0) {
      call("PUT", `/api/v1/galleries/${g}/photos/${pick()}/rating`, "rating", { score: 1 + Math.floor(Math.random() * 5) });
    }
    if (tick % 3 === 0) {
      if (held === null) {
        held = pick();
        call("POST", `/api/v1/galleries/${g}/photo-selection/photos`, "select",
          { photoIds: [held], retouchPhotos: [] }, { responseCallback: SELECTION_STATUSES });
      } else {
        call("DELETE", `/api/v1/galleries/${g}/photo-selection/photos/${held}`, "deselect",
          undefined, { responseCallback: SELECTION_STATUSES });
        held = null;
      }
    }
  }
  if (tick % 2 === 1) call("GET", `/api/v1/galleries/${g}/photo-selection`, "watch");
  if (tick % 12 === 0) call("GET", "/api/v1/notifications", "notifications");

  tick++;
  sleep(Math.max(0, TICK_SECONDS - (Date.now() - started) / 1000));
}

export function handleSummary(data) {
  return {
    [__ENV.SUMMARY || "summary.json"]: JSON.stringify(data),
    stdout: textSummary(data, { indent: " ", enableColors: false }),
  };
}
