# WhatsClean

Aplicación Android local para inventariar vídeos recibidos y ayudar a detectar contenido prescindible sin poner en riesgo vídeos personales.

## Principios de seguridad

- El análisis es local: la aplicación no necesita Internet ni sube archivos.
- La primera versión solo inventaría vídeos; no elimina ni modifica archivos.
- La procedencia de un vídeo no se considera prueba de que sea basura.
- Ante cualquier duda, el vídeo se conserva.

## Desarrollo

- Kotlin, Jetpack Compose y Material 3.
- Android 10 (API 29) como mínimo.
- APK Debug firmado con el `app/debug.keystore` estable compartido con el flujo de desarrollo del usuario. No sustituirlo ni regenerarlo.
- Compilación: `gradle :app:assembleDebug`.

## Versionado

Cada actualización debe incrementar `versionCode` y actualizar `versionName` en `app/build.gradle.kts`.


## Análisis visual local (1.1.0)

La aplicación incorpora el modelo ML Kit Image Labeling dentro de la APK. Analiza hasta tres fotogramas representativos por vídeo y muestra las etiquetas visuales detectadas. El procesamiento se realiza en el dispositivo y no requiere descargar el modelo al primer uso.

La detección de personas o mascotas genera una señal conservadora de protección. Si no aparecen esas señales, el vídeo queda para revisión manual: **no se clasifica como basura**. WhatsClean no elimina ni modifica archivos.
