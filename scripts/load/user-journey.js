// Нагрузочный сценарий «полный user journey» (k6).
//
// Что гоняет каждая VU-итерация (steady state):
//   1. сессия: GET /users/profile, при 401 — повторный выпуск токена в Keycloak
//   2. портал: ленивое создание POST /portals на первой итерации, далее GET /portals
//   3. заявка: POST /portals/{id}/tickets → ответ POST .../messages → GET .../messages
//   4. поиск: GET /tickets?mine=true&search=Load  (горячий путь N+1)
//   5. уведомления: GET /notifications/unread-count + long polling GET /notifications/wait
//   6. обновление токена: direct access grant в Keycloak каждую 10-ю итерацию
//   7. капча: каждую 10-ю итерацию POST /captcha/challenge → PoW (k6/crypto)
//      → POST /captcha/solve — нагрузка на эндпоинты капчи; load-стек
//      с локальным риск-движком: свежий challenge с unique XFF всегда pow
//
// Аутентификация — Bearer-токен Keycloak: приложение не выпускает токены и
// локального /auth/login не имеет. Пользователи НЕ регистрируются в сценарии:
// пул load-pool-{RUN_ID}-pN@load.test заводит scripts/load/run.sh через Admin API
// Keycloak (роль ROLE_USER), а учётная запись в БД появляется при первом запросе
// (keycloak.auto-create-users). Порталы создают только верифицированные
// пользователи — email в Keycloak уже подтверждён.
//
// Рейт-лимиты: каждый запрос несёт уникальный X-Forwarded-For по номеру VU,
// поэтому у каждой VU свой bucket лимита. Это работает только когда стек
// поднят с load-оверлеем (TRUST_PROXY_HEADERS=true): make load-full / make load-smoke.
// Без него все VU делят 127.0.0.1.
//
// Параметры через окружение: TARGET_VUS, DURATION, POOL_SIZE, THINK_TIME,
// WAIT_TIMEOUT_SEC, RUN_ID, KEYCLOAK_URL, REALM (см. Makefile и run.sh).

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { sha256 } from 'k6/crypto';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const API = `${BASE}/api/v1`;
const KEYCLOAK = __ENV.KEYCLOAK_URL || 'http://localhost:8180';
const REALM = __ENV.REALM || 'realhelpdesk';
const CLIENT = __ENV.CLIENT || 'realhelpdesk-api';
const RUN_ID = __ENV.RUN_ID || 'local';
const POOL_SIZE = Number(__ENV.POOL_SIZE || 8);
const TARGET_VUS = Number(__ENV.TARGET_VUS || 10);
const DURATION = __ENV.DURATION || '5m';
const THINK_TIME = Number(__ENV.THINK_TIME || 1);
const WAIT_TIMEOUT_SEC = Number(__ENV.WAIT_TIMEOUT_SEC || 3);
const PASSWORD = 'Password123!';

export const options = {
  scenarios: {
    user_journey: {
      executor: 'ramping-vus',
      stages: [
        { duration: '30s', target: TARGET_VUS },
        { duration: DURATION, target: TARGET_VUS },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    //429 от рейт-лимитов сюда же: если TRUST_PROXY_HEADERS не включён,
    // порог упадёт с первыми же параллельными запросами — это сигнал, а не шум
    http_req_failed: ['rate<0.01'],
    // глобальный порог мягкий: notifications_wait — намеренный long-poll (timeoutSec=WAIT_TIMEOUT_SEC,
    // по умолчанию 3s) и доминирует в p(95); точные пороги — ниже, по endpoint'ам
    http_req_duration: ['p(95)<5000'],
    'http_req_duration{endpoint:search}': ['p(95)<500'],
    'http_req_duration{endpoint:ticket_create}': ['p(95)<1200'],
    'http_req_duration{endpoint:notifications_wait}': ['p(95)<6000'],
    checks: ['rate>0.99'],
  },
};

// per-VU состояние: живёт между итерациями в рамках одной VU.
let vuEmail = null;
let accessToken = null;
let portalId = null;

function clientIpHeaders() {
  const octet = 1 + (__VU % 250);
  const net = 10 + Math.floor(__VU / 250);
  return {
    'Content-Type': 'application/json',
    'X-Requested-With': 'XMLHttpRequest',
    // уникальный клиентский IP на VU — изоляция рейт-лимитов (см. шапку)
    'X-Forwarded-For': `10.77.${net}.${octet}`,
  };
}

function headers() {
  const result = clientIpHeaders();
  if (accessToken !== null) {
    result.Authorization = `Bearer ${accessToken}`;
  }
  return result;
}

function tag(endpoint) {
  return { headers: headers(), tags: { endpoint } };
}

function emailForVU() {
  // VU1 → p1, VU2 → p2, ... VU9 → p1 — пользователей в пуле меньше, чем VU
  const n = ((__VU - 1) % POOL_SIZE) + 1;
  return `load-pool-${RUN_ID}-p${n}@load.test`;
}

/** Direct access grant: приложение токенов не выпускает, источник — Keycloak. */
function grantToken() {
  const res = http.post(
    `${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`,
    {
      grant_type: 'password',
      client_id: CLIENT,
      username: vuEmail,
      password: PASSWORD,
      scope: 'openid',
    },
    { tags: { endpoint: 'keycloak_token' } },
  );
  check(res, { 'keycloak token: 200': (r) => r.status === 200 });
  if (res.status === 200) {
    accessToken = res.json('access_token');
  }
}

function ensureSession() {
  if (accessToken === null) {
    grantToken();
  }
  const res = http.get(`${API}/users/profile`, tag('profile'));
  if (res.status === 200) {
    check(res, { 'profile: 200': () => true });
    return;
  }
  check(res, { 'profile: 200 или повторный grant': (r) => r.status === 401 });
  grantToken();
}

function ensurePortal() {
  if (portalId !== null) {
    const res = http.get(`${API}/portals`, tag('portals_list'));
    check(res, { 'portals: 200': (r) => r.status === 200 });
    return;
  }
  const res = http.post(
    `${API}/portals`,
    JSON.stringify({ name: `load-portal-${__VU}`, description: 'нагрузочный портал' }),
    tag('portal_create'),
  );
  check(res, { 'portal: 201': (r) => r.status === 201 });
  if (res.status === 201) {
    portalId = res.json('id');
  }
}

function ticketJourney() {
  const created = http.post(
    `${API}/portals/${portalId}/tickets`,
    JSON.stringify({
      title: `Load ticket v${__VU} i${__ITER}`,
      body: 'Нагрузочная заявка пользовательского сценария',
      ticketPriority: 'MEDIUM',
      ticketAccessStatus: 'ALL_USERS',
    }),
    tag('ticket_create'),
  );
  check(created, { 'ticket: 201': (r) => r.status === 201 });
  if (created.status !== 201) return;
  const ticketId = created.json('id');

  const message = http.post(
    `${API}/portals/${portalId}/tickets/${ticketId}/messages`,
    JSON.stringify({ messageText: `Ответ на заявку, итерация ${__ITER}` }),
    tag('message_create'),
  );
  check(message, { 'message: 201': (r) => r.status === 201 });

  const list = http.get(
    `${API}/portals/${portalId}/tickets/${ticketId}/messages`,
    tag('messages_list'),
  );
  check(list, { 'messages: 200': (r) => r.status === 200 });
}

function searchTickets() {
  const res = http.get(`${API}/tickets?mine=true&search=Load&size=10`, tag('search'));
  check(res, { 'search: 200': (r) => r.status === 200 });
}

function notifications() {
  const unread = http.get(`${API}/notifications/unread-count`, tag('unread_count'));
  check(unread, { 'unread-count: 200': (r) => r.status === 200 });

  // long polling: часто пустой ответ по таймауту — это нормальный путь
  const wait = http.get(
    `${API}/notifications/wait?timeoutSec=${WAIT_TIMEOUT_SEC}&afterId=0&size=10`,
    tag('notifications_wait'),
  );
  check(wait, { 'wait: 200': (r) => r.status === 200 });
}

function maybeRefreshToken() {
  if (__ITER > 0 && __ITER % 10 === 0) {
    grantToken();
  }
}

/** Набрано ли difficulty ведущих нулевых бит SHA-256 (как PowHash на сервере). */
function meetsDifficulty(hash, difficulty) {
  const full = difficulty >> 3;
  const rest = difficulty & 7;
  for (let i = 0; i < full; i++) {
    if (hash[i] !== 0) {
      return false;
    }
  }
  if (rest !== 0 && (hash[full] >> (8 - rest)) !== 0) {
    return false;
  }
  return true;
}

// Каждая 10-я итерация гоняет полный флоу капчи — нагрузка на /captcha/*;
// PoW решается в k6/crypto (Go, миллионы хэшей в секунду — доли секунды).
// Действие email-code — единственный живой потребитель капчи в приложении.
function maybeExerciseCaptcha() {
  if (__ITER === 0 || __ITER % 10 !== 0) {
    return;
  }
  const challenge = http.post(
    `${API}/captcha/challenge`,
    JSON.stringify({ action: 'email-code' }),
    tag('captcha_challenge'),
  );
  if (!check(challenge, { 'captcha challenge: 200': (r) => r.status === 200 })) {
    return;
  }
  if (challenge.json('type') !== 'pow') {
    // load-стек: свежий challenge с unique XFF не эскалирует — фиксируем отклонение
    check(challenge, { 'captcha: в load-стеке ожидается pow-уровень': () => false });
    return;
  }
  const salt = challenge.json('params.salt');
  const difficulty = challenge.json('params.difficulty');

  let nonce = 0;
  let solved = null;
  while (solved === null) {
    const hash = new Uint8Array(sha256(`${salt}:${nonce}`));
    if (meetsDifficulty(hash, difficulty)) {
      solved = String(nonce);
    }
    nonce++;
  }

  const result = http.post(
    `${API}/captcha/solve`,
    JSON.stringify({ challengeId: challenge.json('challengeId'), nonce: solved }),
    tag('captcha_solve'),
  );
  check(result, { 'captcha solve: 200': (r) => r.status === 200 });
}

export default function () {
  if (vuEmail === null) {
    vuEmail = emailForVU();
  }

  group('journey', () => {
    ensureSession();
    ensurePortal();
    ticketJourney();
    searchTickets();
    notifications();
    maybeRefreshToken();
    maybeExerciseCaptcha();
  });

  sleep(THINK_TIME);
}
