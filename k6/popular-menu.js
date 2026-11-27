// 인기 메뉴 조회 부하(노션 성능·동작 실험 EXP-4). 가상 사용자 50명이 30초 동안 조회 API 하나만 계속 부른다.
// 보통은 PopularMenuExperimentTest 가 서버·데이터를 준비하고 이 스크립트를 실행한다.
// 직접 실행: k6 run -e BASE_URL=http://localhost:8080 -e TARGET_PATH=/api/menus/popular k6/popular-menu.js
//   TARGET_PATH: /api/menus/popular (Redis) 또는 /api/menus/popular/db (DB 집계, 서버를 --experiment.popular-db.enabled=true 로 띄워야 함)
//   OUT: 요약 JSON 을 쓸 파일(없으면 화면 요약만). 폴더는 미리 있어야 한다
//   DURATION: 측정 시간(기본 30s)
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 50,
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'p(95)', 'max'],
  // 직접 실행에서도 200 이 아닌 응답이 하나라도 있으면 실패 종료 코드로 끝낸다(검사 실패만으로는 종료 코드가 안 바뀜)
  thresholds: { checks: ['rate==1'] },
};

const url = (__ENV.BASE_URL || 'http://localhost:8080') + (__ENV.TARGET_PATH || '/api/menus/popular');

export default function () {
  const res = http.get(url, { headers: { Accept: 'application/json' } });
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const line = `${url} TPS=${m.http_reqs.values.rate.toFixed(1)} avg=${m.http_req_duration.values.avg.toFixed(2)}ms `
    // failed = 실패한 요청 수(비율은 소수점에서 반올림돼 몇 건이 0.00% 로 가려질 수 있어 건수로 남긴다)
    + `p95=${m.http_req_duration.values['p(95)'].toFixed(2)}ms failed=${m.http_req_failed.values.passes}\n`;
  const out = { stdout: line };
  if (__ENV.OUT) {
    out[__ENV.OUT] = JSON.stringify(data);
  }
  return out;
}
