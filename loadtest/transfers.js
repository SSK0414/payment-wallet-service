import http from 'k6/http';
import { check } from 'k6';

export const options = { vus: 100, duration: '60s', thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500'] } };
const base = __ENV.BASE_URL || 'http://localhost:8080';
const token = __ENV.TOKEN;
const source = __ENV.SOURCE_WALLET;
const target = __ENV.TARGET_WALLET;
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

export function setup() {
  const res = http.post(`${base}/v1/wallets/${source}/deposit`, JSON.stringify({ amountMinor: 1_000_000_000 }), {
    headers: { ...headers, 'Idempotency-Key': `k6-fund-${Date.now()}` },
  });
  check(res, { 'source wallet funded': (r) => r.status === 200 });
}

export default function () {
  const res = http.post(`${base}/v1/transfers`, JSON.stringify({ fromWallet: source, toWallet: target, amountMinor: 1 }), {
    headers: { ...headers, 'Idempotency-Key': `k6-${__VU}-${__ITER}` },
  });
  check(res, { 'transfer created': (r) => r.status === 201 });
}
