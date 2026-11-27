# Controles de seguridad de Vigía Suite Android

**Última revisión:** 27 de noviembre de 2026

**Alcance:** aplicación Android, conexión USB‑OTG con el ESP32, historial local en Room y consumo de NVD API 2.0.

Esta matriz relaciona los cinco controles solicitados en la revisión académica con la arquitectura real del cliente móvil. Un control marcado como **No aplica** incluye la justificación y la condición que obligaría a implementarlo; no debe interpretarse como un control omitido.

## Resumen

| # | Control evaluado | Aplicabilidad | Estado | Evidencia principal |
|---:|---|---|---|---|
| 1 | Validación de entradas | Aplica | Implementado | Rangos estrictos para eventos seriales, normalización antes de guardar y validación de búsquedas y respuestas de NVD. |
| 2 | Autenticación y sesiones | No aplica actualmente | Justificado | No existen cuentas, sesiones remotas, cookies ni recursos privados de un backend. Android exige permiso para acceder al USB. |
| 3 | Hashing de contraseñas | No aplica actualmente | Justificado | La aplicación no solicita, transmite ni almacena contraseñas. |
| 4 | Rate limiting | Aplica a NVD | Implementado | Una petición cada 6 s, exclusión mutua, deduplicación, timeout, caché y espera adicional ante HTTP 429/503. |
| 5 | Cabeceras HTTP de seguridad | No aplica como servidor | Justificado con controles móviles equivalentes | La aplicación no publica respuestas HTTP. Sólo realiza consultas HTTPS a NVD y bloquea tráfico en texto claro. |

## 1. Validación de entradas

La aplicación trata como no confiables tanto los eventos recibidos del ESP32 como las respuestas remotas:

- [`UsbSerialController.kt`](../app/src/main/java/com/vigiasuite/app/UsbSerialController.kt) acepta RSSI, canal y autenticación únicamente como números enteros dentro de los rangos definidos por VIGIA/2. Un campo ausente, decimal, textual o fuera de rango queda como no informado; nunca se convierte accidentalmente en red abierta o señal máxima.
- [`HistoryLogic.kt`](../app/src/main/java/com/vigiasuite/app/HistoryLogic.kt) vuelve a normalizar puntuaciones, niveles, MAC/BSSID, observaciones Wi‑Fi y Bluetooth antes de guardarlas en Room.
- [`NvdModels.kt`](../app/src/main/java/com/vigiasuite/app/NvdModels.kt) valida identificadores CVE, longitud de búsquedas, CPE, fechas, números, rangos y referencias. Las direcciones MAC/BSSID no se envían como búsquedas y las referencias sólo admiten `http://` o `https://`.
- Jetpack Compose presenta cadenas mediante componentes de texto; no interpreta SSID, nombres Bluetooth ni descripciones de NVD como HTML ejecutable.

Las pruebas incluyen campos nulos, textuales, decimales, fuera de rango y respuestas NVD incompletas o con formato inesperado.

## 2. Autenticación y sesiones

No aplica actualmente. Vigía Suite Android no tiene registro de usuarios, inicio de sesión, JWT, OAuth2, cookies ni recursos privados alojados en un backend. Añadir autenticación sin una identidad remota no protegería ningún activo y aumentaría innecesariamente la superficie de ataque.

Los recursos locales sí tienen controles de acceso proporcionados por Android:

- El usuario debe autorizar el acceso al dispositivo USB conectado.
- La aplicación no puede acceder a otro puerto o dispositivo sin el permiso correspondiente.
- Los resultados se guardan en la base privada de la aplicación.
- `android:allowBackup="false"` evita incluir los datos de la aplicación en respaldos ordinarios.

**Condición de cambio:** si se agregan cuentas, sincronización en la nube o un panel remoto, deberán implementarse autenticación estándar, sesiones con expiración y autorización por recurso antes de publicar esa función.

## 3. Hashing de contraseñas

No aplica porque la aplicación no solicita ni almacena contraseñas. Tampoco existe una base de datos de credenciales.

**Condición de cambio:** si se incorporan credenciales propias, el hashing deberá realizarse en un backend confiable mediante Argon2id o un algoritmo equivalente con sal y parámetros de costo actuales. Nunca se guardarán contraseñas reversibles ni hashes rápidos como MD5, SHA‑1 o SHA‑256 sin un esquema especializado para contraseñas.

## 4. Control de frecuencia

La única API remota consumida es NVD. [`NvdClient.kt`](../app/src/main/java/com/vigiasuite/app/NvdClient.kt) implementa:

- Una petición cada 6 segundos, respetando el límite público de cinco solicitudes en 30 segundos.
- Un `Mutex` para que sólo exista una petición real a la vez.
- Deduplicación de consultas idénticas simultáneas.
- Timeout de conexión y lectura de 30 segundos.
- Un reintento con espera mínima de 30 segundos ante HTTP 429.
- Un reintento con espera de 10 segundos ante HTTP 503.
- Caché Room de 24 horas para evitar consultas repetidas y conservar resultados sin conexión.

La aplicación no expone endpoints HTTP ni una API pública propia, por lo que no existe una ruta de autenticación susceptible a fuerza bruta.

## 5. Cabeceras HTTP y controles móviles equivalentes

Las cabeceras como CSP, HSTS, `X-Content-Type-Options` y Helmet son controles de respuestas servidas por una aplicación Web. Vigía Suite Android es un cliente nativo y no publica documentos HTML ni respuestas HTTP; por ello, este control **no aplica como cabecera de servidor**.

Se aplican los controles equivalentes que corresponden a Android:

| Control móvil | Evidencia |
|---|---|
| Sólo tráfico cifrado | [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) establece `android:usesCleartextTraffic="false"`. |
| Sin redirecciones automáticas | `NvdClient.kt` establece `instanceFollowRedirects = false`. |
| Sin cookies ni credenciales | Las solicitudes sólo envían `Accept` y un agente genérico; no se usa clave de API. |
| Destino restringido | Las URL se construyen para `https://services.nvd.nist.gov/rest/json/`. |
| Timeout de red | Conexión y lectura se cancelan a los 30 segundos. |
| Datos locales fuera de respaldos | El manifiesto establece `android:allowBackup="false"` y `android:fullBackupContent="false"`. |

Si en el futuro el repositorio incorpora un backend propio o una WebView con contenido servido, ese componente deberá implementar sus cabeceras HTTP y CSP de forma independiente.

## Evidencia de verificación

Comando ejecutado con JDK 17:

```powershell
.\gradlew.bat test --no-daemon
```

Resultado del 27 de noviembre de 2026:

| Evidencia | Resultado |
|---|---:|
| Suites de prueba | 24 |
| Pruebas ejecutadas | 122 |
| Fallos | 0 |
| Errores | 0 |
| Omitidas | 0 |

La evaluación correcta del repositorio es: **dos controles aplicables e implementados y tres controles no aplicables con justificación y medidas equivalentes**. Una revisión estática que busque exclusivamente middleware de backend puede marcarlos como ausentes, pero no corresponde a la arquitectura de este cliente Android local.
