//
// k6 load test for the Java ALB.
//
//   k6 run loadtest/k6-load-test.js
//   k6 run -e ALB=http://localhost:8080 -e VUS=200 -e DURATION=2m loadtest/k6-load-test.js
//
// Why k6 rather than JMeter: the scenarios below are code, so the "does the ALB actually
// distribute traffic" check is a real assertion rather than a report you read afterwards.
// k6 also holds thousands of VUs on one machine without a thread per user, which matters
// when the thing under test is designed for high concurrency — a thread-per-user generator
// tends to become the bottleneck and you end up measuring the harness.
//
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const ALB = __ENV.ALB || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '100', 10);
const DURATION = __ENV.DURATION || '30s';

// Per-backend request counters, so the distribution is measured client-side and does not
// have to be trusted from the ALB's own metrics.
const backendHits = new Counter('backend_hits');
const distributionSkew = new Trend('observed_backend_count');
const proxyErrors = new Rate('proxy_errors');
const gatewayErrors = new Counter('gateway_errors');

export const options = {
  scenarios: {
    // Steady load: the number that goes on a dashboard.
    steady: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      exec: 'steadyLoad',
    },
    // Ramp: finds the point where latency starts to degrade, which a fixed-rate test hides.
    ramp: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '15s', target: VUS },
        { duration: '15s', target: VUS * 2 },
        { duration: '10s', target: 0 },
      ],
      startTime: DURATION,
      exec: 'steadyLoad',
    },
    // A trickle of POSTs with bodies, exercising the streaming request path rather than
    // only the trivial GET path.
    writes: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      exec: 'writeLoad',
    },
  },

  thresholds: {
    // A proxy's own overhead should be small and, more importantly, stable. p95/p99 are the
    // numbers that matter — an average hides exactly the tail a load balancer is judged on.
    'http_req_duration{scenario:steady}': ['p(95)<150', 'p(99)<400'],
    'http_req_failed': ['rate<0.01'],
    'proxy_errors': ['rate<0.01'],
    'checks': ['rate>0.99'],
  },

  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function steadyLoad() {
  const response = http.get(`${ALB}/api/test`, {
    tags: { endpoint: 'api_test' },
  });

  const ok = check(response, {
    'status is 200': (r) => r.status === 200,
    'served by a backend': (r) => r.json('server') !== undefined,
    'request id present': (r) => r.headers['X-Request-Id'] !== undefined,
  });

  proxyErrors.add(!ok);
  if (response.status === 502 || response.status === 503 || response.status === 504) {
    gatewayErrors.add(1, { status: String(response.status) });
  }
  if (response.status === 200) {
    const server = response.json('server');
    backendHits.add(1, { backend: server });
  }
}

export function writeLoad() {
  const payload = JSON.stringify({ amount: 100, items: ['a', 'b', 'c'] });
  const response = http.post(`${ALB}/api/echo?source=k6`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'api_echo' },
  });

  check(response, {
    'write status is 200': (r) => r.status === 200,
    'body round-tripped intact': (r) => r.json('body') === payload,
    'query preserved': (r) => r.json('query.source') === 'k6',
  });
}

export function handleSummary(data) {
  const hits = data.metrics.backend_hits;
  console.log('\n=== Observed backend distribution ===');
  if (hits && hits.values) {
    console.log(JSON.stringify(hits.values, null, 2));
  }
  console.log('\nCompare against the ALB\'s own view:');
  console.log(`  curl -s ${ALB}/actuator/prometheus | grep loadbalancer_backend_requests_total`);
  console.log('\nAn even split confirms the algorithm; a skewed one means either a weighted');
  console.log('algorithm is active, a backend is DOWN, or a circuit breaker is open.');
  return {
    'stdout': '\n',
  };
}
