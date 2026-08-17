// k6 load test: simulate 10,000+ concurrent users creating and
// reading tasks, verifying latency and error-rate stay within SLO.
// Run: k6 run --vus 10000 --duration 5m load_test.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const duplicateTasks = new Counter('duplicate_task_creation');

export const options = {
  scenarios: {
    ramping: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 2000 },
        { duration: '2m', target: 10000 },
        { duration: '5m', target: 10000 }, // sustained peak
        { duration: '1m', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'], // ms
    http_req_failed: ['rate<0.01'],                  // <1% errors
  },
};

const BASE_URL = __ENV.BASE_URL || 'https://staging.taskmgmt.example.com';
const TOKEN = __ENV.AUTH_TOKEN;

export default function () {
  const idemKey = `vu-${__VU}-iter-${__ITER}`;

  const createRes = http.post(
    `${BASE_URL}/api/v1/tasks`,
    JSON.stringify({ title: `Load test task ${idemKey}`, description: 'k6 load test' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${TOKEN}`,
        'Idempotency-Key': idemKey,
      },
    }
  );
  check(createRes, { 'create status 201': (r) => r.status === 201 });

  // Retry the SAME request with the same Idempotency-Key to prove
  // the server does not create a duplicate task under client retries.
  const retryRes = http.post(
    `${BASE_URL}/api/v1/tasks`,
    JSON.stringify({ title: `Load test task ${idemKey}`, description: 'k6 load test' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${TOKEN}`,
        'Idempotency-Key': idemKey,
      },
    }
  );
  if (createRes.json('data.id') !== retryRes.json('data.id')) {
    duplicateTasks.add(1);
  }

  const taskId = createRes.json('data.id');
  const getRes = http.get(`${BASE_URL}/api/v1/tasks/${taskId}`, {
    headers: { 'Authorization': `Bearer ${TOKEN}` },
  });
  check(getRes, { 'get status 200': (r) => r.status === 200 });

  sleep(1);
}
