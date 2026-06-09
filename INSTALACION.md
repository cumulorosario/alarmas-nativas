#!/usr/bin/env python3
"""
Vigia Industrial — FCM Webhook Server v2 (Multi-Tenant)
=========================================================
Arquitectura:
- Un único webhook maneja todos los tenants
- Cada alarma llega con su tenantId
- El webhook notifica SOLO a los usuarios de ese tenant
- El SysAdmin recibe alarmas de TODOS los tenants

Instalacion:
  pip3 install flask requests cryptography
  
Variables de entorno requeridas:
  TB_BASE_URL   = http://thingsboard:9090
  TB_SYS_USER   = sysadmin@thingsboard.org
  TB_SYS_PASS   = sysadmin
  WEBHOOK_SECRET = vigia2024secret
"""

import json, time, base64, logging, os
import requests
from flask import Flask, request, jsonify

# ── Configuracion ──────────────────────────────────────────────────────────────
SCRIPT_DIR           = os.path.dirname(os.path.abspath(__file__))
SERVICE_ACCOUNT_FILE = os.path.join(SCRIPT_DIR, "cumulo-vigia-industrial-firebase-adminsdk.json")
FCM_ENDPOINT         = "https://fcm.googleapis.com/v1/projects/cumulo-vigia-industrial/messages:send"
WEBHOOK_PORT         = 5555
WEBHOOK_SECRET       = os.environ.get("WEBHOOK_SECRET", "vigia2024secret")

# Credenciales del SysAdmin (acceso a todos los tenants)
TB_BASE_URL = os.environ.get("TB_BASE_URL", "http://thingsboard:9090")
TB_SYS_USER = os.environ.get("TB_SYS_USER", os.environ.get("TB_USERNAME", ""))
TB_SYS_PASS = os.environ.get("TB_SYS_PASS", os.environ.get("TB_PASSWORD", ""))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("fcm_webhook")

app = Flask(__name__)

# ── Cache ──────────────────────────────────────────────────────────────────────
_fcm_token_cache   = {"token": None, "expires_at": 0}
_sys_tb_token      = {"token": None, "expires_at": 0}
# Cache de fcmTokens por tenantId: {"tenantId": {"tokens": [...], "expires_at": t}}
_tenant_tokens_cache = {}
# Cache de fcmTokens del sysadmin
_sysadmin_tokens_cache = {"tokens": [], "expires_at": 0}


# ── OAuth2 FCM ─────────────────────────────────────────────────────────────────
def get_fcm_access_token():
    now = time.time()
    if _fcm_token_cache["token"] and _fcm_token_cache["expires_at"] > now + 120:
        return _fcm_token_cache["token"]

    with open(SERVICE_ACCOUNT_FILE) as f:
        sa = json.load(f)

    def b64url(data):
        return base64.urlsafe_b64encode(
            json.dumps(data, separators=(",", ":")).encode()
        ).rstrip(b"=").decode()

    iat     = int(time.time())
    header  = {"alg": "RS256", "typ": "JWT"}
    payload = {
        "iss":   sa["client_email"],
        "scope": "https://www.googleapis.com/auth/firebase.messaging",
        "aud":   "https://oauth2.googleapis.com/token",
        "iat":   iat, "exp": iat + 3600,
    }
    signing_input = f"{b64url(header)}.{b64url(payload)}".encode()

    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
    pk  = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    sig = pk.sign(signing_input, rsa_padding.PKCS1v15(), hashes.SHA256())
    jwt = f"{b64url(header)}.{b64url(payload)}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"

    resp = requests.post("https://oauth2.googleapis.com/token", data={
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": jwt,
    }, timeout=15)
    resp.raise_for_status()
    data = resp.json()

    _fcm_token_cache["token"]      = data["access_token"]
    _fcm_token_cache["expires_at"] = time.time() + data.get("expires_in", 3600)
    log.info("Token OAuth2 FCM generado")
    return _fcm_token_cache["token"]


# ── ThingsBoard API ────────────────────────────────────────────────────────────
def get_sys_tb_token():
    """Login como SysAdmin — tiene acceso a todos los tenants."""
    now = time.time()
    if _sys_tb_token["token"] and _sys_tb_token["expires_at"] > now + 120:
        return _sys_tb_token["token"]

    if not TB_SYS_USER or not TB_SYS_PASS:
        log.error("TB_SYS_USER / TB_SYS_PASS no configurados")
        return None

    resp = requests.post(f"{TB_BASE_URL}/api/auth/login",
        json={"username": TB_SYS_USER, "password": TB_SYS_PASS},
        timeout=10)

    if resp.status_code != 200:
        log.error(f"Error login SysAdmin: {resp.status_code} {resp.text[:100]}")
        return None

    token = resp.json().get("token")
    _sys_tb_token["token"]      = token
    _sys_tb_token["expires_at"] = time.time() + 3000
    return token


def get_tenant_tb_token(tenant_id: str):
    """
    Obtiene un token JWT en el contexto de un tenant específico.
    El SysAdmin puede hacer login 'como' cualquier tenant admin.
    """
    sys_token = get_sys_tb_token()
    if not sys_token:
        return None

    # Obtener el tenant admin del tenant
    headers = {"X-Authorization": f"Bearer {sys_token}"}
    
    # Listar usuarios del tenant para encontrar su admin
    resp = requests.get(
        f"{TB_BASE_URL}/api/tenant/{tenant_id}/users?pageSize=10&page=0",
        headers=headers, timeout=10)
    
    if resp.status_code != 200:
        log.warning(f"No se pudo obtener usuarios del tenant {tenant_id}: {resp.status_code}")
        return None

    users = resp.json().get("data", [])
    tenant_admin = next(
        (u for u in users if u.get("authority") == "TENANT_ADMIN"), 
        None
    )
    
    if not tenant_admin:
        log.warning(f"Sin tenant admin en tenant {tenant_id}")
        return None

    # El SysAdmin puede impersonar al tenant admin
    admin_id = tenant_admin["id"]["id"]
    resp2 = requests.get(
        f"{TB_BASE_URL}/api/user/{admin_id}/token",
        headers=headers, timeout=10)
    
    if resp2.status_code != 200:
        log.warning(f"No se pudo obtener token del tenant admin: {resp2.status_code}")
        return None

    return resp2.json().get("token")


def get_fcm_tokens_for_tenant(tenant_id: str) -> list:
    """
    Obtiene todos los fcmTokens registrados en un tenant específico.
    Busca en usuarios y customers del tenant.
    Cachea 2 minutos por tenant.
    """
    now = time.time()
    cached = _tenant_tokens_cache.get(tenant_id, {})
    if cached.get("tokens") is not None and cached.get("expires_at", 0) > now:
        return cached["tokens"]

    sys_token = get_sys_tb_token()
    if not sys_token:
        return []

    headers = {"X-Authorization": f"Bearer {sys_token}"}
    tokens  = set()

    def extract_fcm(attrs):
        for attr in attrs:
            if attr.get("key") == "fcmToken" and attr.get("value"):
                tokens.add(attr["value"])

    try:
        # 1. Buscar en todos los usuarios del tenant
        resp = requests.get(
            f"{TB_BASE_URL}/api/tenant/{tenant_id}/users?pageSize=100&page=0",
            headers=headers, timeout=10)

        if resp.status_code == 200:
            users = resp.json().get("data", [])
            log.info(f"Tenant {tenant_id[:8]}: {len(users)} usuarios")
            for user in users:
                uid = user["id"]["id"]
                for scope in ["SERVER_SCOPE", "CLIENT_SCOPE"]:
                    r = requests.get(
                        f"{TB_BASE_URL}/api/plugins/telemetry/USER/{uid}/values/attributes/{scope}",
                        headers=headers, timeout=10)
                    if r.status_code == 200:
                        extract_fcm(r.json())

        # 2. Buscar en customers del tenant
        resp2 = requests.get(
            f"{TB_BASE_URL}/api/customers?pageSize=100&page=0",
            headers=headers, timeout=10)

        if resp2.status_code == 200:
            customers = resp2.json().get("data", [])
            # Filtrar solo customers de este tenant
            tenant_customers = [c for c in customers 
                              if c.get("tenantId", {}).get("id") == tenant_id]
            log.info(f"Tenant {tenant_id[:8]}: {len(tenant_customers)} customers")
            for customer in tenant_customers:
                cid = customer["id"]["id"]
                for scope in ["SERVER_SCOPE", "CLIENT_SCOPE"]:
                    r = requests.get(
                        f"{TB_BASE_URL}/api/plugins/telemetry/CUSTOMER/{cid}/values/attributes/{scope}",
                        headers=headers, timeout=10)
                    if r.status_code == 200:
                        extract_fcm(r.json())

        # 3. Buscar en el tenant mismo
        for scope in ["SERVER_SCOPE", "CLIENT_SCOPE"]:
            r = requests.get(
                f"{TB_BASE_URL}/api/plugins/telemetry/TENANT/{tenant_id}/values/attributes/{scope}",
                headers=headers, timeout=10)
            if r.status_code == 200:
                extract_fcm(r.json())

    except Exception as e:
        log.error(f"Error obteniendo tokens del tenant {tenant_id}: {e}")

    token_list = list(tokens)
    _tenant_tokens_cache[tenant_id] = {
        "tokens":     token_list,
        "expires_at": time.time() + 120  # 2 min cache
    }
    log.info(f"Tenant {tenant_id[:8]}: {len(token_list)} fcmTokens encontrados")
    return token_list


def get_sysadmin_fcm_tokens() -> list:
    """
    Obtiene los fcmTokens del SysAdmin.
    El SysAdmin recibe alarmas de TODOS los tenants.
    """
    now = time.time()
    if _sysadmin_tokens_cache["tokens"] and _sysadmin_tokens_cache["expires_at"] > now:
        return _sysadmin_tokens_cache["tokens"]

    sys_token = get_sys_tb_token()
    if not sys_token:
        return []

    headers = {"X-Authorization": f"Bearer {sys_token}"}
    tokens  = set()

    try:
        # Obtener info del sysadmin actual
        me_resp = requests.get(f"{TB_BASE_URL}/api/auth/user", headers=headers, timeout=10)
        if me_resp.status_code == 200:
            user_data = me_resp.json()
            uid = user_data["id"]["id"]
            for scope in ["SERVER_SCOPE", "CLIENT_SCOPE"]:
                r = requests.get(
                    f"{TB_BASE_URL}/api/plugins/telemetry/USER/{uid}/values/attributes/{scope}",
                    headers=headers, timeout=10)
                if r.status_code == 200:
                    for attr in r.json():
                        if attr.get("key") == "fcmToken" and attr.get("value"):
                            tokens.add(attr["value"])
    except Exception as e:
        log.error(f"Error obteniendo tokens del sysadmin: {e}")

    token_list = list(tokens)
    _sysadmin_tokens_cache["tokens"]     = token_list
    _sysadmin_tokens_cache["expires_at"] = time.time() + 120
    log.info(f"SysAdmin: {len(token_list)} fcmTokens")
    return token_list


# ── Envio FCM ──────────────────────────────────────────────────────────────────
def send_push(fcm_token: str, alarm: dict) -> bool:
    def _call(access_token):
        payload = {
            "message": {
                "token":   fcm_token,
                "android": {"priority": "high", "ttl": "86400s"},
                "data": {
                    "alarmId":     str(alarm.get("alarmId", "")),
                    "alarmType":   str(alarm.get("type", "ALARM")),
                    "severity":    str(alarm.get("severity", "CRITICAL")),
                    "status":      str(alarm.get("status", "ACTIVE_UNACK")),
                    "deviceName":  str(alarm.get("originatorName", "Dispositivo")),
                    "createdTime": str(alarm.get("createdTime", int(time.time() * 1000))),
                },
            }
        }
        return requests.post(FCM_ENDPOINT, json=payload,
            headers={"Authorization": f"Bearer {access_token}",
                     "Content-Type": "application/json"},
            timeout=10)

    access_token = get_fcm_access_token()
    resp = _call(access_token)

    if resp.status_code == 401:
        _fcm_token_cache["token"] = None
        resp = _call(get_fcm_access_token())

    if resp.status_code == 200:
        return True

    # Token inválido (app desinstalada) — no es un error crítico
    if resp.status_code == 404:
        log.warning(f"Token FCM inválido (app desinstalada?): {fcm_token[:20]}...")
        return True  # No contar como fallo

    log.error(f"FCM error {resp.status_code}: {resp.text[:200]}")
    return False


# ── Endpoints ──────────────────────────────────────────────────────────────────
@app.route("/alarm", methods=["POST"])
def alarm_webhook():
    """
    Llamado por ThingsBoard Rule Chain cuando ocurre una alarma.

    Body JSON:
    {
      "secret":        "vigia2024secret",
      "tenantId":      "uuid-del-tenant",
      "alarmId":       "uuid-de-la-alarma",
      "type":          "HIGH_TEMPERATURE",
      "severity":      "CRITICAL",
      "status":        "ACTIVE_UNACK",
      "originatorName":"Sensor-01",
      "createdTime":   1234567890000
    }
    """
    try:
        data = request.get_json(force=True) or {}

        if data.get("secret") != WEBHOOK_SECRET:
            log.warning("Secret incorrecto")
            return jsonify({"error": "Unauthorized"}), 401

        # No notificar alarmas resueltas
        if str(data.get("status", "")).startswith("CLEARED"):
            return jsonify({"ok": True, "info": "alarma resuelta — omitida"}), 200

        tenant_id = data.get("tenantId", "")
        if not tenant_id:
            log.warning("Sin tenantId en el payload — usando todos los tokens")

        # Obtener tokens del tenant + tokens del sysadmin
        tenant_tokens  = get_fcm_tokens_for_tenant(tenant_id) if tenant_id else []
        sysadmin_tokens = get_sysadmin_fcm_tokens()

        # Unir evitando duplicados
        all_tokens = list(set(tenant_tokens + sysadmin_tokens))

        if not all_tokens:
            log.warning(f"Sin fcmTokens para tenant {tenant_id[:8] if tenant_id else '?'}")
            return jsonify({"ok": False, "error": "Sin fcmTokens registrados"}), 200

        # Enviar a todos
        sent = failed = 0
        for token in all_tokens:
            if send_push(token, data):
                sent += 1
            else:
                failed += 1

        log.info(f"Alarma {data.get('type','?')} — tenant {tenant_id[:8] if tenant_id else '?'} — "
                 f"enviado a {sent}/{len(all_tokens)} dispositivos")
        return jsonify({"ok": True, "sent": sent, "failed": failed}), 200

    except Exception as e:
        log.exception("Error en webhook")
        return jsonify({"error": str(e)}), 500


@app.route("/health")
def health():
    sys_ok = get_sys_tb_token() is not None
    return jsonify({
        "status":        "ok" if sys_ok else "degraded",
        "tb_connected":  sys_ok,
        "tb_url":        TB_BASE_URL,
    }), 200


@app.route("/tokens")
def list_tokens():
    """Debug: muestra tokens por tenant."""
    sys_token = get_sys_tb_token()
    if not sys_token:
        return jsonify({"error": "Sin conexión a ThingsBoard"}), 500

    headers = {"X-Authorization": f"Bearer {sys_token}"}
    result  = {}

    # Listar todos los tenants
    resp = requests.get(f"{TB_BASE_URL}/api/tenants?pageSize=100&page=0",
                        headers=headers, timeout=10)
    if resp.status_code == 200:
        tenants = resp.json().get("data", [])
        for tenant in tenants:
            tid   = tenant["id"]["id"]
            tname = tenant.get("name", tid[:8])
            tokens = get_fcm_tokens_for_tenant(tid)
            result[tname] = {
                "tenantId": tid,
                "count":    len(tokens),
                "tokens":   [t[:20] + "..." for t in tokens]
            }

    sysadmin_tokens = get_sysadmin_fcm_tokens()
    result["__sysadmin__"] = {
        "count":  len(sysadmin_tokens),
        "tokens": [t[:20] + "..." for t in sysadmin_tokens]
    }

    return jsonify(result), 200


@app.route("/invalidate-cache")
def invalidate_cache():
    """Invalida el cache de tokens — útil cuando se registra un nuevo dispositivo."""
    _tenant_tokens_cache.clear()
    _sysadmin_tokens_cache["tokens"]     = []
    _sysadmin_tokens_cache["expires_at"] = 0
    return jsonify({"ok": True, "message": "Cache invalidado"}), 200


if __name__ == "__main__":
    log.info(f"Vigia FCM Webhook v2 (Multi-Tenant) — puerto {WEBHOOK_PORT}")
    if not TB_SYS_USER:
        log.warning("TB_SYS_USER no configurado")
    app.run(host="0.0.0.0", port=WEBHOOK_PORT)
