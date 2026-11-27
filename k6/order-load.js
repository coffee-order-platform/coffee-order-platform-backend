// 주문 부하(노션 성능·동작 실험 EXP-2). 가상 사용자 50명이 담기 → 주문을 반복한다. MODE 로 상황을 고른다.
//   basic        : 50명이 각자 자기 계좌, 메뉴는 사람마다 5종 중 하나
//   floor        : basic 과 같되 잔액이 주문 몇 번치뿐. 잔액 부족(INSUFFICIENT_POINT)은 정상 거절. 실패한 주문은 장바구니가 남으므로 다시 담지 않는다
//   multi-device : 계좌 25개에 2명씩(한 사람이 기기 2대). 홀수 VU 는 담기 → 주문, 짝수 VU 는 같은 계좌에 충전 · 환불을 번갈아
//   hot-menu     : basic 과 같되 모두 같은 메뉴(MENU_IDS 첫 번째)
// 보통은 OrderLoadExperimentTest 가 사용자·잔액을 준비하고 이 스크립트를 실행한다. 직접 실행:
//   k6 run -e MODE=basic -e USER_IDS=1,2,... -e MENU_IDS=1,2,3,4,5 k6/order-load.js
//   USER_IDS: VU 순서대로 쓸 사용자 id(basic·floor·hot-menu 50개, multi-device 25개). OUT: 요약 JSON 파일. DURATION: 기본 30s
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const MODE = __ENV.MODE || 'basic';
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USER_IDS = (__ENV.USER_IDS || '').split(',').filter((s) => s).map(Number);
const MENU_IDS = (__ENV.MENU_IDS || '').split(',').filter((s) => s).map(Number);
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json', Accept: 'application/json' } };

export const options = {
  vus: 50,
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'p(95)', 'max'],
  // 정해 둔 결과(성공 · 상황별 허용 거절 · 장바구니 충돌)가 아닌 응답이 하나라도 있으면 실패 종료한다
  thresholds: { checks: ['rate==1'] },
};

// 400 · 409 는 서비스가 정한 거절이라 http_req_failed 에서 뺀다. 그 안에서 정상/사고는 아래 Counter 로 나눈다
http.setResponseCallback(http.expectedStatuses(200, 201, 400, 409));

const orderOk = new Counter('order_ok');
const orderRejected = new Counter('order_rejected'); // floor 의 잔액 부족
const cartConflict = new Counter('cart_conflict'); // 담기 409. 각자 계좌라 나면 안 되는 현상이라 따로 센다(11/27 경화님: 끝까지 재고 발견으로 표시)
const chargeOk = new Counter('charge_ok');
const refundOk = new Counter('refund_ok');
const unexpected = new Counter('unexpected');
// 요청이 서버에 닿기 전에 이 PC 의 연결 번호(포트)가 바닥나 실패한 건. 서버 문제가 아니라 따로 센다(11/27 경화님 결정)
const clientFail = new Counter('client_fail');
const orderUnsent = new Counter('order_unsent'); // client_fail 중 주문 POST 인 것(담기 뒤 주문이 못 나감)
const rejectClose = new Counter('reject_close'); // 거절(400) 응답에 Connection: close 가 붙은 건(서버가 연결을 끊는지 확인)
const orderOkMs = new Trend('order_ok_ms', true); // 주문 POST 만(담기 요청은 뺀다)
const orderRejectedMs = new Trend('order_rejected_ms', true);

let needAdd = true; // VU 마다 따로 있는 상태(k6 는 VU 마다 JS 실행 환경이 따로다)

function errorCode(res) {
  try {
    return res.json('code');
  } catch (e) {
    return null;
  }
}

// Windows 에서 연결 번호가 바닥났을 때 k6 가 내는 오류(요청이 출발하지 못함)
function isClientFail(res) {
  return res.status === 0 && /Only one usage of each socket address/.test(res.error || '');
}

function expect(ok, res, what) {
  if (isClientFail(res)) {
    clientFail.add(1);
    check(res, { 'expected outcome': () => true });
    return;
  }
  if (!ok) {
    unexpected.add(1);
    console.error(`${what} 예상 밖 응답 ${res.status} ${res.body}`);
  }
  check(res, { 'expected outcome': () => ok });
}

function orderFlow(userId, menuId) {
  if (needAdd) {
    const add = http.post(`${BASE}/api/users/${userId}/cart/items`, JSON.stringify({ menuId, quantity: 1 }), JSON_HEADERS);
    if (add.status === 409 && errorCode(add) === 'CART_UPDATE_CONFLICT') {
      cartConflict.add(1);
      check(add, { 'expected outcome': () => true });
      return;
    }
    expect(add.status === 200, add, '담기');
    if (add.status !== 200) {
      return;
    }
    // 담긴 뒤로는 주문이 성공할 때까지 다시 담지 않는다. 주문 요청이 서버에 못 닿아도(k6 연결 실패) 수량이 2 로 쌓이지 않게(리뷰)
    needAdd = false;
  }
  const res = http.post(`${BASE}/api/users/${userId}/orders`, null, JSON_HEADERS);
  if (res.status === 201) {
    orderOk.add(1);
    orderOkMs.add(res.timings.duration);
    needAdd = true;
    expect(true, res, '주문');
  } else if (MODE === 'floor' && res.status === 400 && errorCode(res) === 'INSUFFICIENT_POINT') {
    orderRejected.add(1);
    orderRejectedMs.add(res.timings.duration);
    if ((res.headers.Connection || '').toLowerCase() === 'close') {
      rejectClose.add(1);
    }
    // needAdd 는 false 그대로: 실패한 주문은 롤백돼 장바구니가 남는다. 또 담으면 수량이 쌓여 99 를 넘는다
    expect(true, res, '주문');
  } else {
    if (isClientFail(res)) {
      orderUnsent.add(1); // 담긴 장바구니가 남는다. 검증은 장바구니가 남은 사람 수를 이 건수 이하로만 허용한다
    }
    expect(false, res, '주문');
  }
}

function sideFlow(userId) {
  if (__ITER % 2 === 0) {
    const amount = (1 + Math.floor(Math.random() * 500)) * 1000; // 1,000 ~ 500,000 원, 1,000 원 단위
    const res = http.post(`${BASE}/api/users/${userId}/points/charge`, JSON.stringify({ amount }), JSON_HEADERS);
    if (res.status === 200) {
      chargeOk.add(1);
    }
    expect(res.status === 200, res, '충전');
    return;
  }
  const list = http.get(`${BASE}/api/users/${userId}/orders?size=20`, JSON_HEADERS);
  expect(list.status === 200, list, '주문 목록');
  if (list.status !== 200) {
    return;
  }
  const paid = list.json('data.content').filter((o) => o.status === 'PAID');
  if (paid.length === 0) {
    return;
  }
  const target = paid[Math.floor(Math.random() * paid.length)];
  const res = http.post(`${BASE}/api/users/${userId}/orders/${target.orderId}/refund`, null, JSON_HEADERS);
  if (res.status === 201) {
    refundOk.add(1);
  }
  expect(res.status === 201, res, '환불');
}

export default function () {
  const vu = __VU - 1;
  if (MODE === 'multi-device') {
    const userId = USER_IDS[Math.floor(vu / 2)];
    if (vu % 2 === 0) {
      orderFlow(userId, MENU_IDS[Math.floor(vu / 2) % MENU_IDS.length]);
    } else {
      sideFlow(userId);
    }
    return;
  }
  const menuId = MODE === 'hot-menu' ? MENU_IDS[0] : MENU_IDS[vu % MENU_IDS.length];
  orderFlow(USER_IDS[vu], menuId);
}

export function handleSummary(data) {
  const m = data.metrics;
  // 한 번도 더해지지 않은 지표는 요약에 없다
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const rate = (name) => (m[name] ? m[name].values.rate : 0).toFixed(1);
  const trend = (name, stat) => (m[name] ? m[name].values[stat] : 0).toFixed(2);
  const line = `MODE=${MODE} order_tps=${rate('order_ok')} order_avg=${trend('order_ok_ms', 'avg')}ms `
    + `order_p95=${trend('order_ok_ms', 'p(95)')}ms rejected_tps=${rate('order_rejected')} `
    + `rejected_p95=${trend('order_rejected_ms', 'p(95)')}ms order_ok=${count('order_ok')} `
    + `order_rejected=${count('order_rejected')} cart_conflict=${count('cart_conflict')} charge_ok=${count('charge_ok')} `
    + `refund_ok=${count('refund_ok')} unexpected=${count('unexpected')} client_fail=${count('client_fail')} `
    + `order_unsent=${count('order_unsent')} `
    + `reject_close=${count('reject_close')}\n`;
  const out = { stdout: line };
  if (__ENV.OUT) {
    out[__ENV.OUT] = JSON.stringify(data);
  }
  return out;
}
