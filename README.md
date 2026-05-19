# Vigia Industrial — App Android Nativa

App Android 100% nativa en **Kotlin + Jetpack Compose**. Sin WebView, sin Capacitor.

---

## ¿Qué hace?

- **Login** con usuario/contraseña ThingsBoard + biometría (huella/rostro)
- **Dashboard** con resumen de alarmas activas y dispositivos online
- **Pantalla de Alarmas** con filtros (Todas / Activas / Resueltas), reconocer y resolver
- **Pantalla de Dispositivos** con estado online/offline en tiempo real
- **Servicio en segundo plano** que consulta ThingsBoard cada 15 segundos
- **Arranca con el teléfono** automáticamente (BOOT_COMPLETED)
- **Alertas de pantalla completa** sobre la pantalla de bloqueo cuando hay alarma crítica
- **Notificaciones críticas** con botones para reconocer/resolver directamente

---

## Cómo subir a GitHub y compilar con Codemagic

### 1. Subir el proyecto a GitHub

```bash
git init
git add .
git commit -m "feat: app Android nativa Vigia Industrial"
git remote add origin https://github.com/TU_USUARIO/vigia-android.git
git push -u origin main
```

### 2. Conectar Codemagic

1. Ir a [codemagic.io](https://codemagic.io) → **Add application**
2. Conectar tu cuenta de GitHub y seleccionar el repositorio
3. Codemagic detecta el `codemagic.yaml` automáticamente
4. Ejecutar el workflow **"Vigia Industrial — Android Nativo (Debug APK)"**
5. Cuando termine, el APK llega por email a `cumulorosario@gmail.com`

### 3. Instalar el APK en el teléfono

1. Activar **"Instalar apps de origen desconocido"** en Ajustes del teléfono
2. Abrir el APK del email e instalar

---

## Ajustes importantes en el teléfono tras instalar

Para que las alarmas funcionen aunque el teléfono esté bloqueado:

| Ajuste | Valor |
|--------|-------|
| Inicio automático | ✅ Activado |
| Optimización de batería | Sin restricciones |
| Apps en recientes | Bloquear con candado |

La app incluye un botón en **Ajustes → Servicio en segundo plano** que abre directamente la pantalla de batería del sistema.

---

## Estructura del proyecto

```
app/src/main/java/com/cumulo/vigia/
├── VigiaApplication.kt          # App class, inicia el servicio
├── model/
│   └── Models.kt                # Alarm, Device, AuthResponse, etc.
├── data/
│   ├── api/ThingsBoardApi.kt    # HTTP client (OkHttp)
│   ├── local/SessionStore.kt    # Credenciales persistentes (DataStore)
│   └── VigiaRepository.kt      # Lógica de negocio
├── service/
│   ├── AlarmPollingService.kt   # Foreground service — polling c/15s
│   ├── AlarmNotificationManager.kt  # Notificaciones críticas
│   ├── BootReceiver.kt          # Arranca con el teléfono
│   └── AlarmActionReceiver.kt   # Reconocer/resolver desde notificación
└── ui/
    ├── MainActivity.kt          # Nav host + bottom bar
    ├── VigiaViewModel.kt        # Estado global
    ├── Components.kt            # AlarmCard, DeviceCard, StatCard
    ├── AlarmFullScreenActivity.kt  # Pantalla de alarma sobre lock screen
    ├── theme/Theme.kt           # Colores industriales dark
    ├── login/LoginScreen.kt
    ├── dashboard/DashboardScreen.kt
    ├── alarms/AlarmsScreen.kt
    ├── devices/DevicesScreen.kt
    └── settings/SettingsScreen.kt
```

---

## Servidor ThingsBoard configurado

URL por defecto: `http://cumuloingenieria.duckdns.org:9090`

Se puede cambiar en el código → `SessionStore.kt` → `DEFAULT_BASE_URL`
