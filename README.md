# Vigía Suite Android 0.7

Aplicación Android de Vigía Suite para conectar un NodeMCU ESP‑32S mediante USB‑OTG, ejecutar análisis Wi‑Fi y Bluetooth LE, revisar hallazgos y compartir un informe local.

## Descargar APK

La versión instalable está disponible en [GitHub Releases](https://github.com/LuisNafate/vigia-suite-android/releases/tag/v0.7.0-beta).

> La versión 0.7.0-beta se distribuye como APK de prueba. Android puede solicitar autorización para instalar aplicaciones desde el navegador o gestor de archivos.

## Requisitos

- Android con soporte USB host/OTG.
- Adaptador USB‑OTG y cable USB de datos.
- NodeMCU ESP‑32S con firmware VIGIA/2.
- Convertidor CP210x o CH340 integrado en la placa.

El firmware y la aplicación web para preparar el sensor están en [Vigía Suite Web](https://github.com/LuisNafate/vigia-suite-web).

## Funciones

- Conexión USB serial a 115200 baudios.
- Inventario Wi‑Fi 2.4 GHz y Bluetooth Low Energy.
- Puntuación contextual y recomendaciones defensivas.
- Registro local de evaluaciones.
- Informe para guardar o compartir desde Android.
- Procesamiento local sin envío de resultados a Internet.

## Compilar sin Android Studio

Haz doble clic en `COMPILAR-ANDROID.bat`, o ejecuta:

```powershell
.\scripts\build-android.ps1
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

Para instalarlo mediante depuración USB, conecta y autoriza el teléfono y ejecuta `INSTALAR-ANDROID.bat`. Para usar el sensor después de instalar, conecta el ESP32 al teléfono mediante USB‑OTG.

## Stack

- Kotlin y Jetpack Compose.
- usb-serial-for-android.
- Protocolo serial VIGIA/2 con eventos JSON Lines.

## Uso responsable

Utiliza las funciones de descubrimiento exclusivamente en redes, espacios y dispositivos propios o con autorización explícita.
