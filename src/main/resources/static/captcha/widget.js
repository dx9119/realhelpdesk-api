/**
 * RealHelpCaptcha — клиентский виджет капчи (PoW и эмодзи-уровень).
 *
 * Использование (форма запроса кода на email):
 *
 *   const token = await window.RealHelpCaptcha.getToken('email-code');
 *   // заголовок X-Captcha-Token для POST /api/v1/email/codes
 *
 * Как работает: 1) POST /api/v1/captcha/challenge получает подписанное задание;
 * 2) уровень выбирает сервер — pow (salt + difficulty; nonce добирается
 * SHA-256 в Web Worker, при отказе воркера — на основном потоке) или emoji
 * (сетка 3×3 глифов, человек отмечает ячейки в оверлее); 3) POST
 * /api/v1/captcha/solve обменивает решение на подписанный captcha-токен.
 * Отмена проверки человеком — Promise отклоняется с error.code='cancelled';
 * «Другое задание» запрашивает новый challenge. Подписи задания не хранятся
 * на сервере — ответ сверяется по HMAC в challenge.
 *
 * Все POSTы несут заголовок X-Requested-With — совместимость с клиентами,
 * которые шлют его на изменяющих запросах; серверу он не нужен.
 */
(function () {
  'use strict';

  var CHALLENGE_URL = '/api/v1/captcha/challenge';
  var SOLVE_URL = '/api/v1/captcha/solve';
  var HEADERS = {
    'Content-Type': 'application/json',
    'X-Requested-With': 'XMLHttpRequest'
  };

  var K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];

  function rotr(x, n) {
    return (x >>> n) | (x << (32 - n));
  }

  /** SHA-256 по байтам → Uint8Array(32). Работает и в воркере, и на главном потоке. */
  function sha256Bytes(input) {
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var length = input.length;
    var paddedLength = ((length + 9 + 63) >> 6) << 6;
    var buf = new Uint8Array(paddedLength);
    buf.set(input);
    buf[length] = 0x80;
    var bitLenHi = Math.floor((length * 8) / 0x100000000);
    var bitLenLo = (length * 8) >>> 0;
    buf[paddedLength - 8] = (bitLenHi >>> 24) & 0xff;
    buf[paddedLength - 7] = (bitLenHi >>> 16) & 0xff;
    buf[paddedLength - 6] = (bitLenHi >>> 8) & 0xff;
    buf[paddedLength - 5] = bitLenHi & 0xff;
    buf[paddedLength - 4] = (bitLenLo >>> 24) & 0xff;
    buf[paddedLength - 3] = (bitLenLo >>> 16) & 0xff;
    buf[paddedLength - 2] = (bitLenLo >>> 8) & 0xff;
    buf[paddedLength - 1] = bitLenLo & 0xff;

    var w = new Int32Array(64);
    for (var off = 0; off < paddedLength; off += 64) {
      for (var i = 0; i < 16; i++) {
        w[i] = (buf[off + i * 4] << 24) | (buf[off + i * 4 + 1] << 16) | (buf[off + i * 4 + 2] << 8) | buf[off + i * 4 + 3];
      }
      for (i = 16; i < 64; i++) {
        var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
        var s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (i = 0; i < 64; i++) {
        var bigS1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        var ch = (e & f) ^ (~e & g);
        var temp1 = (h + bigS1 + ch + K[i] + w[i]) | 0;
        var bigS0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        var maj = (a & b) ^ (a & c) ^ (b & c);
        var temp2 = (bigS0 + maj) | 0;
        h = g; g = f; f = e; e = (d + temp1) | 0;
        d = c; c = b; b = a; a = (temp1 + temp2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }

    var out = new Uint8Array(32);
    for (var j = 0; j < 8; j++) {
      out[j * 4] = (H[j] >>> 24) & 0xff;
      out[j * 4 + 1] = (H[j] >>> 16) & 0xff;
      out[j * 4 + 2] = (H[j] >>> 8) & 0xff;
      out[j * 4 + 3] = H[j] & 0xff;
    }
    return out;
  }

  /** Набрано ли difficulty ведущих нулевых бит (байты-ноль целиком + остаток бит). */
  function meetsDifficulty(hash, difficulty) {
    var full = difficulty >> 3;
    var rest = difficulty & 7;
    for (var i = 0; i < full; i++) {
      if (hash[i] !== 0) {
        return false;
      }
    }
    if (rest !== 0 && (hash[full] >> (8 - rest)) !== 0) {
      return false;
    }
    return true;
  }

  function postJson(url, payload) {
    return fetch(url, {
      method: 'POST',
      headers: HEADERS,
      credentials: 'same-origin',
      body: JSON.stringify(payload)
    }).then(function (response) {
      return response.json().catch(function () {
        return {};
      }).then(function (data) {
        if (!response.ok) {
          var error = new Error(data.detail || 'Captcha request failed: HTTP ' + response.status);
          error.status = response.status;
          error.code = data.code;
          throw error;
        }
        return data;
      });
    });
  }

  function solveOnMainThread(salt, difficulty) {
    var prefix = salt + ':';
    var encoder = new TextEncoder();
    var prefixBytes = encoder.encode(prefix);
    var nonce = 0;
    var CHUNK = 20000;

    function attemptBatch() {
      for (var end = nonce + CHUNK; nonce < end; nonce++) {
        var candidate = String(nonce);
        var nonceBytes = encoder.encode(candidate);
        var data = new Uint8Array(prefixBytes.length + nonceBytes.length);
        data.set(prefixBytes, 0);
        data.set(nonceBytes, prefixBytes.length);
        if (meetsDifficulty(sha256Bytes(data), difficulty)) {
          return candidate;
        }
      }
      return null;
    }

    return new Promise(function (resolve) {
      function pump() {
        var found = attemptBatch();
        if (found !== null) {
          resolve(found);
        } else {
          setTimeout(pump, 0);
        }
      }
      pump();
    });
  }

  function workerSource() {
    // Строки функций склеиваются в тело Blob-воркера: sha256Bytes/meetsDifficulty
    // должны попасть в него как объявления, workerMain — сразу вызван.
    return (
      sha256Bytes.toString() + '\n' +
      rotr.toString() + '\n' +
      meetsDifficulty.toString() + '\n' +
      'var __K = ' + JSON.stringify(K) + ';\n' +
      'var K = __K;\n' +
      '(' + solveInWorker.toString() + ')();\n'
    );
  }

  function solveInWorker() {
    self.onmessage = function (event) {
      var salt = event.data.salt;
      var difficulty = event.data.difficulty;
      var encoder = new TextEncoder();
      var prefixBytes = encoder.encode(salt + ':');
      for (var nonce = 0; ; nonce++) {
        var candidate = String(nonce);
        var nonceBytes = encoder.encode(candidate);
        var data = new Uint8Array(prefixBytes.length + nonceBytes.length);
        data.set(prefixBytes, 0);
        data.set(nonceBytes, prefixBytes.length);
        if (meetsDifficulty(sha256Bytes(data), difficulty)) {
          self.postMessage(candidate);
          return;
        }
      }
    };
  }

  function solveWithWorker(salt, difficulty) {
    return new Promise(function (resolve, reject) {
      var url;
      var worker;
      try {
        url = URL.createObjectURL(new Blob([workerSource()], { type: 'text/javascript' }));
        worker = new Worker(url);
      } catch (e) {
        solveOnMainThread(salt, difficulty).then(resolve, reject);
        return;
      }
      worker.onmessage = function (event) {
        worker.terminate();
        URL.revokeObjectURL(url);
        resolve(event.data);
      };
      worker.onerror = function () {
        worker.terminate();
        URL.revokeObjectURL(url);
        // Воркер недоступен (CSP, старый браузер) — тихо считаем на главном потоке
        solveOnMainThread(salt, difficulty).then(resolve, reject);
      };
      worker.postMessage({ salt: salt, difficulty: difficulty });
    });
  }

  /**
   * Оверлей эмодзи-задания: сетка 3×3, выбор ячеек, «Подтвердить» /
   * «Другое задание» / «Отмена». Возвращает Promise с выбранными индексами
   * ({answer}), запросом нового задания ({retry}) или отказом (cancel).
   */
  function showEmojiOverlay(challenge) {
    return new Promise(function (resolve, reject) {
      var params = challenge.params || {};
      var cells = params.cells || [];
      var selected = {};

      var backdrop = document.createElement('div');
      backdrop.setAttribute('role', 'dialog');
      backdrop.setAttribute('aria-modal', 'true');
      backdrop.setAttribute('aria-label', 'Проверка, что вы человек');
      backdrop.style.cssText =
        'position:fixed;inset:0;z-index:9999;background:rgba(0,0,0,.55);' +
        'display:flex;align-items:center;justify-content:center;font-family:sans-serif';

      var box = document.createElement('div');
      box.style.cssText =
        'background:#fff;color:#111;border-radius:8px;padding:20px;max-width:320px;' +
        'box-shadow:0 8px 32px rgba(0,0,0,.35);text-align:center';

      var prompt = document.createElement('p');
      prompt.textContent = params.prompt || 'Отметьте нужные клетки';
      prompt.style.cssText = 'margin:0 0 12px;font-size:15px;line-height:1.4';
      box.appendChild(prompt);

      var grid = document.createElement('div');
      grid.style.cssText =
        'display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:16px';
      cells.forEach(function (glyph, index) {
        var button = document.createElement('button');
        button.type = 'button';
        // Без подсказок для скринридеров: только порядковый номер варианта
        button.setAttribute('aria-label', 'Вариант ' + (index + 1));
        button.setAttribute('aria-pressed', 'false');
        button.textContent = glyph;
        button.style.cssText =
          'font-size:28px;line-height:1;padding:12px 0;border:2px solid #ccc;' +
          'border-radius:8px;background:#fafafa;cursor:pointer';
        button.addEventListener('click', function () {
          if (selected[index]) {
            delete selected[index];
            button.setAttribute('aria-pressed', 'false');
            button.style.borderColor = '#ccc';
            button.style.background = '#fafafa';
          } else {
            selected[index] = true;
            button.setAttribute('aria-pressed', 'true');
            button.style.borderColor = '#2563eb';
            button.style.background = '#dbeafe';
          }
          confirm.disabled = Object.keys(selected).length === 0;
        });
        grid.appendChild(button);
      });
      box.appendChild(grid);

      var actions = document.createElement('div');
      actions.style.cssText = 'display:flex;gap:8px;justify-content:center;flex-wrap:wrap';

      var confirm = document.createElement('button');
      confirm.type = 'button';
      confirm.textContent = 'Подтвердить';
      confirm.disabled = true;
      confirm.style.cssText =
        'padding:8px 14px;border:0;border-radius:6px;background:#2563eb;color:#fff;' +
        'font-size:14px;cursor:pointer';
      confirm.addEventListener('click', function () {
        close();
        resolve({
          answer: Object.keys(selected)
            .map(function (key) {
              return parseInt(key, 10);
            })
            .sort(function (a, b) {
              return a - b;
            })
        });
      });

      var other = document.createElement('button');
      other.type = 'button';
      other.textContent = 'Другое задание';
      other.style.cssText =
        'padding:8px 14px;border:1px solid #ccc;border-radius:6px;background:#fff;' +
        'color:#111;font-size:14px;cursor:pointer';
      other.addEventListener('click', function () {
        close();
        resolve({ retry: true });
      });

      var cancel = document.createElement('button');
      cancel.type = 'button';
      cancel.textContent = 'Отмена';
      cancel.style.cssText =
        'padding:8px 14px;border:1px solid #ccc;border-radius:6px;background:#fff;' +
        'color:#111;font-size:14px;cursor:pointer';
      cancel.addEventListener('click', function () {
        close();
        var error = new Error('Проверка капчи отменена');
        error.code = 'cancelled';
        reject(error);
      });

      actions.appendChild(confirm);
      actions.appendChild(other);
      actions.appendChild(cancel);
      box.appendChild(actions);
      backdrop.appendChild(box);
      document.body.appendChild(backdrop);
      confirm.focus();

      function close() {
        if (backdrop.parentNode) {
          backdrop.parentNode.removeChild(backdrop);
        }
      }
    });
  }

  /** Полный цикл эмодзи-уровня: оверлей → solve; «другое задание» — новый challenge. */
  function solveEmoji(action, challenge) {
    return showEmojiOverlay(challenge).then(function (result) {
      if (result.retry) {
        return requestToken(action);
      }
      return postJson(SOLVE_URL, { challengeId: challenge.challengeId, answer: result.answer });
    });
  }

  /** Один проход: challenge → решение по уровню (pow или emoji) → solve. */
  function requestToken(action) {
    return postJson(CHALLENGE_URL, { action: action }).then(function (challenge) {
      if (!challenge || !challenge.params) {
        throw new Error('Неожиданный ответ /captcha/challenge');
      }
      if (challenge.type === 'pow') {
        return solveWithWorker(challenge.params.salt, challenge.params.difficulty).then(function (nonce) {
          return postJson(SOLVE_URL, { challengeId: challenge.challengeId, nonce: nonce });
        });
      }
      if (challenge.type === 'emoji') {
        return solveEmoji(action, challenge);
      }
      throw new Error('Неожиданный тип challenge: ' + challenge.type);
    });
  }

  /**
   * Полный цикл: challenge → решение (PoW или эмодзи) → solve. Возвращает
   * подписанный captcha-токен (строка) для бизнес-операции.
   *
   * @param {string} action 'register' | 'email-code'
   * @returns {Promise<string>}
   */
  function getToken(action) {
    return requestToken(action).then(function (solved) {
      if (!solved || !solved.captchaToken) {
        throw new Error('Неожиданный ответ /captcha/solve');
      }
      return solved.captchaToken;
    });
  }

  window.RealHelpCaptcha = { getToken: getToken };
})();
