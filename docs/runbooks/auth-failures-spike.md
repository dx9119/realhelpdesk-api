# Runbook: AuthFailuresSpike

**Алерт**: `rate(auth_attempts_total{result="failure"}[5m]) > 0.1` (более 6 неудачных
попыток в минуту) в течение 5 минут (`severity: warning`).

## Что это значит

В приложении `auth.attempts{result="failure"}` фиксирует только провалы капчи
(`type="captcha"`): вход и пароли обслуживает Keycloak. Всплеск — это боты,
ломающийся клиент капчи или разъехавшийся контракт challenge/solve. Перебор
паролей виден в метриках Keycloak, а не приложения.

## Диагностика

1. **Метрики Mimir**: `curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode 'query=sum by (type, result) (rate(auth_attempts_total[5m]))'`
   — рост `type="captcha"`, `result="failure"` — автоматика на публичных
   эндпоинтах капчи; разбирайте по `captcha.verification{reason}`.
2. **Причина провала**: `sum by (reason) (rate(captcha_verification_total[5m]))`
   — `pow_failed`/`wrong_answer` — боты или сломанный виджет,
   `expired`/`replayed` — клиент не укладывается в TTL или дублирует solve.
3. **Логи** (без ПДн — email в логи не пишем): `{...="app"} |= "captcha" |= "WARN"`.
4. **Keycloak** (если подозрение на перебор паролей): события входа и
   `login_failures` в метриках Keycloak — приложение их не считает.

## Митигация

- Включить/усилить rate limiting на шлюзе для `/api/v1/captcha/**`.
- При подтверждённой атаке — поднять `CAPTCHA_POW_DIFFICULTY` или включить
  эмодзи-эскалацию (`CAPTCHA_EMOJI_ENABLED=true`, дефолт).
- Сломанный клиент: проверить, что виджет берёт challenge и решает его в
  пределах `CAPTCHA_CHALLENGE_TTL_SECONDS` / `CAPTCHA_TOKEN_TTL_SECONDS`.
- Перебор паролей — настройки brute-force detection в realm'е Keycloak.
