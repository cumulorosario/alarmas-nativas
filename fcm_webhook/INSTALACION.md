# Vigia FCM Webhook — Instalación en servidor ThingsBoard CE

## Arquitectura

```
ThingsBoard CE → Rule Chain → REST API Call → fcm_webhook.py → Firebase FCM → Teléfono Android
                                                    ↑
                                         Lee fcmTokens de TB via REST API
```

El webhook obtiene los fcmTokens directamente de ThingsBoard,
sin depender de nodos PE/EE. Compatible con Community Edition.

---

## 1. Copiar archivos al servidor

```bash
ssh root@cumuloingenieria.duckdns.org

mkdir -p /opt/vigia
# Copiar desde tu máquina:
scp fcm_webhook.py root@cumuloingenieria.duckdns.org:/opt/vigia/
scp cumulo-vigia-industrial-firebase-adminsdk.json root@cumuloingenieria.duckdns.org:/opt/vigia/
```

## 2. Instalar dependencias Python

```bash
pip3 install flask requests cryptography
```

## 3. Configurar credenciales ThingsBoard

```bash
# Agregar al /etc/environment o al servicio systemd
export TB_USERNAME="tu_admin@thingsboard.org"
export TB_PASSWORD="tu_password_admin"
```

## 4. Probar manualmente

```bash
cd /opt/vigia
TB_USERNAME="admin@tb.org" TB_PASSWORD="password" python3 fcm_webhook.py

# En otra terminal:
curl http://localhost:5555/health
# Debe responder: {"status": "ok", "tb_configured": true}

curl http://localhost:5555/tokens
# Lista los fcmTokens encontrados en ThingsBoard
```

## 5. Instalar como servicio systemd

```bash
# Editar fcm_webhook.service y agregar tus credenciales:
nano /opt/vigia/fcm_webhook.service
# Agregar en [Service]:
# Environment="TB_USERNAME=admin@tb.org"
# Environment="TB_PASSWORD=tu_password"

cp /opt/vigia/fcm_webhook.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable fcm_webhook
systemctl start fcm_webhook
systemctl status fcm_webhook
```

## 6. Verificar logs

```bash
journalctl -u fcm_webhook -f
```

---

## Configurar ThingsBoard CE — Rule Chain

### A. Importar la Rule Chain

1. ThingsBoard → **Rule Chains** → botón **+** → **Import rule chain**
2. Seleccionar `vigia_fcm_rule_chain.json`
3. Guardar

### B. Conectar a la Root Rule Chain

1. Abrir la **Root Rule Chain**
2. Agregar nodo tipo **Rule Chain** 
3. Seleccionar `Vigia FCM Notifications`
4. Conectar desde el nodo **Create Alarm** con tipo `Created`
5. Guardar

### C. Verificar con modo debug

En la Rule Chain `Vigia FCM Notifications`:
1. Activar **Debug mode** en todos los nodos
2. Generar una alarma de prueba en ThingsBoard
3. Ver los mensajes en el panel de debug

---

## Seguridad (recomendado en producción)

Cambiar el secret en `fcm_webhook.py` Y en el body del nodo REST de ThingsBoard:
```python
WEBHOOK_SECRET = "cambiar_por_algo_seguro_aqui"
```

El webhook escucha en `localhost:5555` — solo accesible desde el mismo servidor.
