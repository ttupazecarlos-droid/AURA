# AURA - Reproductor de Música Hi-Fi

AURA es un reproductor de música local para Android, diseñado con Material 3 y Jetpack Compose. Reproduce formatos sin pérdida como FLAC, WAV y ALAC, y formatos con pérdidas como MP3 y AAC, según los decodificadores disponibles en el teléfono. Incluye letras sincronizadas (LRC), listas de reproducción y reproducción en segundo plano.

## Características Principales

- **Bloqueo del sistema:** al abrir AURA se solicita la huella o el PIN, patrón o contraseña del teléfono. Android 11+ usa `BiometricPrompt` con `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, sin botón negativo personalizado: Android ofrece la alternativa de credenciales y la abre directamente si no hay biometría disponible. Android 7-10 usa biometría fuerte con una opción para abrir la confirmación nativa de credenciales, o va directamente a esta si no hay huellas disponibles, porque no admite esa combinación de banderas. Sin ningún método de seguridad activo, AURA permanece bloqueada y ofrece abrir `Settings.ACTION_SECURITY_SETTINGS`. No se crean pantallas de PIN propias ni se almacenan huellas o credenciales.
- **Margen de retorno:** tras autenticarse, las salidas de menos de 2 minutos no vuelven a pedir autenticación; al regresar después de 2 minutos o más, sí. El intervalo se mide con el reloj monotónico de Android, incluso con el teléfono suspendido. Si Android destruye el proceso, se requiere autenticar de nuevo. Cancelar o fallar nunca concede acceso; se puede reintentar desde la pantalla bloqueada. El sistema controla los intentos y su bloqueo temporal.
- **Calidad del archivo:** los datos técnicos se leen del archivo cuando están disponibles; los desconocidos no se inventan. No se garantiza salida bit-perfect ni 24-bit/192 kHz en el DAC: depende de Android, el teléfono y la salida utilizada.
- **Gestión de Archivos Locales:** Selección de carpetas mediante SAF, escaneo recursivo de archivos nuevos y rutas persistentes entre actualizaciones.
- **Letras sincronizadas:** archivos `.lrc` junto al audio en carpetas SAF autorizadas, con varias marcas de tiempo por verso. Las letras se limitan a 512 KiB.
- **Listas de Reproducción y Favoritos:** Organiza tu música favorita y crea listas personalizadas.
- **Interfaz Moderna Material 3:** Diseño elegante y oscuro adaptado para experiencias musicales inmersivas.

## Ejecución Local

**Prerrequisitos:** [Android Studio](https://developer.android.com/studio)

1. Abre Android Studio.
2. Selecciona **Open** (Abrir) y elige el directorio que contiene este proyecto (`AURA`).
3. Permite que Android Studio resuelva e importe las dependencias y configuración de Gradle.
4. Ejecuta la aplicación en un emulador o dispositivo físico Android con Android 7.0 (API 24) o superior.

### Verificaciones

Con un JDK 25 y Android SDK 36.1 instalados:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`. La integración continua ejecuta estas mismas comprobaciones; no firma ni publica versiones de producción. La firma de publicación se proporciona externamente mediante `KEYSTORE_PATH`, `STORE_PASSWORD` y `KEY_PASSWORD`.

### Comportamiento y privacidad

- Se excluyen audios de menos de 60 segundos, notas de voz identificadas por nombre/ruta y tonos. El filtro también se aplica a la importación manual.
- Favoritas y Lossless son listas automáticas. El menú de añadir canciones solo ofrece listas personalizadas.
- Un escaneo incompleto no elimina canciones. Los cambios de metadatos conservan favoritos, fechas y listas; las actualizaciones de la base usan migraciones, nunca un reinicio destructivo.
- La reproducción continúa al pasar a segundo plano, pero cerrar definitivamente la actividad o quitar la tarea detiene la música. La cola y la posición no se restauran tras morir el proceso.
- La biblioteca y las preferencias no se incluyen en las copias automáticas ni en las transferencias entre dispositivos. Al reinstalar o cambiar de teléfono hay que volver a autorizar las carpetas; los archivos originales no se copian ni se borran.
- No hay streaming, nube, IA ni ecualizador. Las carátulas son representaciones de color e icono.
- La compatibilidad con proveedores SAF externos, biometría, Bluetooth, suspensión y DAC debe comprobarse en dispositivos reales.

## Mapa de Navegación del Código

Cada archivo incluye un encabezado `ARCHIVO / FUNCIÓN / NAVEGACIÓN` para localizar rápidamente su propósito y sus dependencias. Flujo principal:

```
MainActivity
  └─ AuraApp (router de pantallas Compose)
       ├─ AuraViewModel (estado de la UI y navegación)
       │    ├─ AudioPlayerManager (reproducción)
       │    ├─ LocalFileManager (escaneo y archivos locales)
       │    └─ Room: Entities / Daos / AuraDatabase
       ├─ ui/screens/  -> Home, Search, Library, Player, Lyrics,
       │                   Queue, Playlist, Artist, Settings
       └─ ui/components/ -> MiniPlayerBar, Dialogs, FileManagerSheet
```

- `service/MusicPlaybackService.kt`: servicio en primer plano y notificación de reproducción.
- `ui/theme/`: colores, tipografía y tema Material 3.
- `model/Models.kt`: modelos de dominio compartidos por UI, datos y reproductor.

El bloqueo protege el acceso a la interfaz, no cifra los archivos de música. La reproducción y los controles multimedia de Android siguen funcionando en segundo plano. Las capturas de pantalla y la vista previa en recientes se deshabilitan para proteger la biblioteca.
