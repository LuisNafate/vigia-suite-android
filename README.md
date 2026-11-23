<div align="center">
  <h1>Vigía Suite Android</h1>
  <p><strong>Cliente móvil defensivo para NodeMCU ESP-32S mediante USB-OTG.</strong></p>
  <p>
    <a href="https://github.com/LuisNafate/vigia-suite-android/releases/tag/v0.7.0-beta"><img alt="Descargar APK" src="https://img.shields.io/badge/ANDROID-DESCARGAR%20APK-3DDC84?style=for-the-badge&logo=android&logoColor=white"></a>
    <a href="https://vigia-suite-web-pc-lab.vercel.app"><img alt="Abrir versión web" src="https://img.shields.io/badge/VERCEL-ABRIR%20WEB-000000?style=for-the-badge&logo=vercel&logoColor=white"></a>
    <a href="https://github.com/LuisNafate/vigia-suite-android"><img alt="Repositorio Android" src="https://img.shields.io/badge/GITHUB-C%C3%93DIGO-181717?style=for-the-badge&logo=github&logoColor=white"></a>
  </p>
  <p>
    <img alt="Versión beta 0.7" src="https://img.shields.io/badge/VERSI%C3%93N-0.7%20BETA-202020?style=flat-square">
    <img alt="Android" src="https://img.shields.io/badge/ANDROID-USB%20HOST-3DDC84?style=flat-square&logo=android&logoColor=white">
    <img alt="Kotlin" src="https://img.shields.io/badge/KOTLIN-2.3.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
    <img alt="Jetpack Compose" src="https://img.shields.io/badge/JETPACK-COMPOSE-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white">
    <img alt="USB OTG" src="https://img.shields.io/badge/USB-OTG-1679A7?style=flat-square&logo=usb&logoColor=white">
    <img alt="Protocolo VIGIA 2" src="https://img.shields.io/badge/PROTOCOLO-VIGIA%2F2-4B5563?style=flat-square">
    <img alt="Último commit" src="https://img.shields.io/github/last-commit/LuisNafate/vigia-suite-android?style=flat-square&logo=git&logoColor=white">
  </p>
</div>

---

## Descripción

Aplicación Android de Vigía Suite para conectar un NodeMCU ESP‑32S mediante USB‑OTG, ejecutar análisis Wi‑Fi y Bluetooth LE, revisar hallazgos y compartir un informe local.

## Enlaces rápidos

| Recurso | Acceso |
|---|---|
| APK de prueba | [Descargar 0.7.0 Beta](https://github.com/LuisNafate/vigia-suite-android/releases/tag/v0.7.0-beta) |
| Aplicación web | [Abrir Vigía Suite en Vercel](https://vigia-suite-web-pc-lab.vercel.app) |
| Repositorio web | [Vigía Suite Web](https://github.com/LuisNafate/vigia-suite-web) |
| Historial Android | [Commits de main](https://github.com/LuisNafate/vigia-suite-android/commits/main) |

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
- Evaluación guiada que solicita la identidad del sensor (`INFO`) y después el inventario Wi‑Fi 2.4 GHz y Bluetooth Low Energy.
- Puntuación contextual y recomendaciones defensivas, con la misma fórmula que la versión web (incluida la regla de congestión: +5 si un canal concentra 12 o más redes). Los datos que el sensor no informa, o que llegan inválidos, se muestran como «No informada» y no cuentan como redes abiertas ni cercanas.
- Historial local en Room: cada evaluación con sus redes Wi‑Fi y dispositivos BLE, detalle, comparación entre dos evaluaciones, borrado individual o total y política de conservación. Las evaluaciones guardadas conservan su puntuación original; al compararlas con otras puntuadas con la fórmula actual, la app advierte si la regla de congestión habría cambiado el resultado. Un cambio de autenticación del mismo BSSID se califica como más débil, sin pérdida de protección o sin calificar según la familia del modo (abierta, WEP, WPA/WPA2/WPA3 personal, Enterprise, OWE, WAPI, DPP; modos 0-16 del núcleo 3.3.12): sólo se declara un debilitamiento con evidencia técnica, nunca por el número del modo.
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
