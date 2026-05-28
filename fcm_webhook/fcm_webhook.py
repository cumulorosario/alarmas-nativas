#!/usr/bin/env python3
"""
Vigia Industrial — FCM Webhook Server (compatible ThingsBoard CE)
=================================================================
Arquitectura CE-compatible:
- La Rule Chain llama este webhook con los datos de la alarma
- El webhook consulta ThingsBoard para obtener los fcmTokens de TODOS
  los usuarios/clientes registrados
- Envía el push FCM a cada dispositivo registrado

Instalacion:
  pip3 install flask requests cryptography
  python3 fcm_webhook.py

Puerto: 5555
"""

import json, time, base64, logging, os, threading
import requests
from flask import Flask, request, jsonify

# ── Configuracion ──────────────────────────────────────────────────────────────
SCRIPT_DIR           = os.path.dirname(os.path.abspath(__file__))
SERVICE_ACCOUNT_FILE = os.path.join(SCRIPT_DIR, "cumulo-vigia-industrial-firebase-adminsdk.json")
FCM_ENDPOINT         = "https://fcm.googleapis.com/v1/projects/cumulo-vigia-industrial/messages:send"
WEBHOOK_PORT         = 5555
WEBHOOK_SECRET       = "vigia2024secret"

# ThingsBoard connection — ajustar si cambia la URL
TB_BASE_URL  = "http://localhost:9090"
TB_USERNAME  = os.environ.get("TB_USERNAME", "")   # admin email
TB_PASSWORD  = os.environ.get("TB_PASSWORD", "")   # admin password

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("fcm_webhook")

app = Flask(__name__)

# ── Cache de tokens ────────────────────────────────────────────────────────────
_fcm_token_cache  = {"token": None, "expires_at": 0}
_tb_token_cache   = {"token": None, "expires_at": 0}
_fcm_tokens_cache = {"tokens": [], "expires_at": 0}  # Lista de fcmTokens de usuarios


# ── OAuth2 para FCM v1 ─────────────────────────────────────────────────────────
def get_fcm_access_token():
    now = time.time()
    if _fcm_token_cache["token"] and _fcm_token_cache["expires_at"] > now + 120:
        return _fcm_token_cache["token"]

    log.info("Generando access token OAuth2 para FCM...")

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
        "iat":   iat,
        "exp":   iat + 3600,
    }

    signing_input = f"{b64url(header)}.{b64url(payload)}".encode()

    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
    pk  = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    sig = pk.sign(signing_input, rsa_padding.PKCS1v15(), hashes.SHA256())
    jwt = f"{b64url(header)}.{b64url(payload)}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"

    resp = requests.post("https://oauth2.googleapis.com/token", data={
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion":   jwt,
    }, timeout=15)
    resp.raise_for_status()
    data = resp.json()

    _fcm_token_cache["token"]      = data["access_token"]
    _fcm_token_cache["expires_at"] = time.time() + data.get("expires_in", 3600)
    log.info("Token OAuth2 FCM generado")
    return _fcm_token_cache["token"]


# ── ThingsBoard API ────────────────────────────────────────────────────────────
def get_tb_token():
    """Login en ThingsBoard y obtener JWT."""
    now = time.time()
    if _tb_token_cache["token"] and _tb_token_cache["expires_at"] > now + 120:
        return _tb_token_cache["token"]

    if not TB_USERNAME or not TB_PASSWORD:
        log.error("TB_USERNAME y TB_PASSWORD no configurados como variables de entorno")
        return None

    resp = requests.post(f"{TB_BASE_URL}/api/auth/login",
        json={"username": TB_USERNAME, "password": TB_PASSWORD},
        timeout=10)

    if resp.status_code != 200:
        log.error(f"Error login ThingsBoard: {resp.status_code}")
        return None

    token = resp.json().get("token")
    _tb_token_cache["token"]      = token
    _tb_token_cache["expires_at"] = time.time() + 3600  # ~1 hora
    return token


def get_all_fcm_tokens():
    """
    Obtiene todos los fcmTokens registrados en ThingsBoard.
    Los fcmTokens se guardan como atributo CLIENT_SCOPE del customer/tenant.
    Cachea el resultado 5 minutos para no saturar TB.
    """
    now = time.time()
    if _fcm_tokens_cache["tokens"] and _fcm_tokens_cache["expires_at"] > now:
        return _fcm_tokens_cache["tokens"]

    tb_token = get_tb_token()
    if not tb_token:
        return []

    headers = {"X-Authorization": f"Bearer {tb_token}"}
    tokens  = []

    try:
        # Buscar en todos los customers
        resp = requests.get(
            f"{TB_BASE_URL}/api/customers?pageSize=100&page=0",
            headers=headers, timeout=10)

        if resp.status_code == 200:
            customers = resp.json().get("data", [])
            for customer in customers:
                cid = customer["id"]["id"]
                attr_resp = requests.get(
                    f"{TB_BASE_URL}/api/plugins/telemetry/CUSTOMER/{cid}/values/attributes/CLIENT_SCOPE",
                    headers=headers, timeout=10)
                if attr_resp.status_code == 200:
                    attrs = attr_resp.json()
                    for attr in attrs:
                        if attr.get("key") == "fcmToken" and attr.get("value"):
                            tokens.append(attr["value"])
                            log.debug(f"fcmToken encontrado en customer {cid}")

        # Tambien buscar a nivel tenant (si el usuario es tenant admin)
        resp2 = requests.get(
            f"{TB_BASE_URL}/api/plugins/telemetry/TENANT/values/attributes/CLIENT_SCOPE",
            headers=headers, timeout=10)
        if resp2.status_code == 200:
            for attr in resp2.json():
                if attr.get("key") == "fcmToken" and attr.get("value"):
                    if attr["value"] not in tokens:
                        tokens.append(attr["value"])

    except Exception as e:
        log.error(f"Error obteniendo fcmTokens de TB: {e}")

    # Eliminar duplicados
    tokens = list(set(tokens))

    _fcm_tokens_cache["tokens"]     = tokens
    _fcm_tokens_cache["expires_at"] = time.time() + 300  # 5 min cache

    log.info(f"fcmTokens encontrados: {len(tokens)}")
    return tokens


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
        log.info(f"FCM OK — alarm={alarm.get('alarmId','?')} device={alarm.get('originatorName','?')}")
        return True

    log.error(f"FCM error {resp.status_code}: {resp.text[:300]}")
    return False


# ── Endpoints ──────────────────────────────────────────────────────────────────
@app.route("/alarm", methods=["POST"])
def alarm_webhook():
    """
    Llamado por ThingsBoard Rule Chain cuando ocurre una alarma.
    La Rule Chain solo envía datos de la alarma — el webhook obtiene
    los fcmTokens consultando ThingsBoard directamente.

    Body JSON enviado por TB Rule Chain:
    {
      "secret":        "vigia2024secret",
      "alarmId":       "${msg.id}",
      "type":          "${msg.type}",
      "severity":      "${msg.severity}",
      "status":        "${msg.status}",
      "originatorName":"${metadata.originatorName}",
      "createdTime":   "${msg.createdTime}"
    }
    """
    try:
        data = request.get_json(force=True) or {}

        if data.get("secret") != WEBHOOK_SECRET:
            log.warning("Secret incorrecto")
            return jsonify({"error": "Unauthorized"}), 401

        # No notificar alarmas resueltas
        status = str(data.get("status", ""))
        if status.startswith("CLEARED"):
            return jsonify({"ok": True, "info": "alarma resuelta — omitida"}), 200

        # Obtener todos los fcmTokens registrados
        fcm_tokens = get_all_fcm_tokens()

        if not fcm_tokens:
            log.warning("Sin fcmTokens registrados — la app no hizo login aun?")
            return jsonify({"ok": False, "error": "Sin fcmTokens registrados"}), 200

        # Enviar a todos los dispositivos registrados (multi-device)
        sent = 0
        failed = 0
        for token in fcm_tokens:
            ok = send_push(token, data)
            if ok:
                sent += 1
            else:
                failed += 1

        log.info(f"Push enviado a {sent}/{len(fcm_tokens)} dispositivos")
        return jsonify({"ok": True, "sent": sent, "failed": failed}), 200

    except Exception as e:
        log.exception("Error en webhook")
        return jsonify({"error": str(e)}), 500


@app.route("/health")
def health():
    return jsonify({
        "status": "ok",
        "service": "Vigia FCM Webhook",
        "tb_configured": bool(TB_USERNAME and TB_PASSWORD)
    }), 200


@app.route("/tokens")
def list_tokens():
    """Debug: lista los fcmTokens encontrados en TB."""
    tokens = get_all_fcm_tokens()
    return jsonify({"count": len(tokens), "tokens": [t[:20]+"..." for t in tokens]}), 200


if __name__ == "__main__":
    log.info(f"Vigia FCM Webhook iniciando en puerto {WEBHOOK_PORT}")
    if not TB_USERNAME:
        log.warning("ATENCION: TB_USERNAME no configurado. Setear con:")
        log.warning("  export TB_USERNAME='admin@thingsboard.org'")
        log.warning("  export TB_PASSWORD='tu_password'")
    app.run(host="0.0.0.0", port=WEBHOOK_PORT)
