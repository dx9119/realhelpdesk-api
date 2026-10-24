#!/usr/bin/env node
// Решает PoW-капчу RealHelpDesk и печатает captchaToken в stdout.
//
// Использование (из scripts/load/run.sh):
//     node scripts/load/solve-pow.js http://localhost:8080 register
//
// XFF (опционально) — заголовок X-Forwarded-For: у каждого пулового
// пользователя свой bucket рейт-лимита /captcha/*.
//
// Тот же алгоритм, что в виджете и PowHash: ведущие нулевые биты
// SHA-256("salt:nonce") >= difficulty. Тип challenge обязан быть pow —
// load-стек работает с локальным риск-движком, но сидирование всегда
// идёт со свежего IP (XFF), поэтому эскалации на emoji не бывает.

'use strict';

const crypto = require('crypto');

const base = (process.argv[2] || 'http://localhost:8080').replace(/\/$/, '');
const action = process.argv[3] || 'register';
const XFF = process.env.XFF || null;

const headers = {
  'Content-Type': 'application/json',
  'X-Requested-With': 'XMLHttpRequest',
};
if (XFF) {
  headers['X-Forwarded-For'] = XFF;
}

/** Набрано ли difficulty ведущих нулевых бит SHA-256 (байты-ноль целиком + остаток бит). */
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

async function main() {
  const challengeRes = await fetch(`${base}/api/v1/captcha/challenge`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ action }),
  });
  if (!challengeRes.ok) {
    throw new Error(`challenge HTTP ${challengeRes.status}: ${await challengeRes.text()}`);
  }
  const challenge = await challengeRes.json();
  if (challenge.type !== 'pow') {
    throw new Error(`неожиданный тип challenge: ${challenge.type} (для сидирования ожидается pow)`);
  }
  const { salt, difficulty } = challenge.params;

  let nonce = 0;
  for (;;) {
    const hash = crypto.createHash('sha256').update(`${salt}:${nonce}`).digest();
    if (meetsDifficulty(hash, difficulty)) {
      break;
    }
    nonce++;
  }

  const solveRes = await fetch(`${base}/api/v1/captcha/solve`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ challengeId: challenge.challengeId, nonce: String(nonce) }),
  });
  if (!solveRes.ok) {
    throw new Error(`solve HTTP ${solveRes.status}: ${await solveRes.text()}`);
  }
  const solved = await solveRes.json();
  if (!solved.captchaToken) {
    throw new Error('solve не вернул captchaToken');
  }
  process.stdout.write(solved.captchaToken);
}

main().catch((error) => {
  console.error(error.message || error);
  process.exit(1);
});
