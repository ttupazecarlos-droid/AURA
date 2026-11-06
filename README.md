# AURA - Reproductor de Música Hi-Fi

AURA es un reproductor de música de alta fidelidad (Hi-Fi) y gestión de archivos locales para Android, diseñado con Material 3 y Jetpack Compose. Soporta formatos de audio sin pérdida (Lossless) como FLAC, WAV, ALAC, OGG, AAC y MP3, visualización de letras sincronizadas (LRC), gestión de listas de reproducción y reproducción en segundo plano.

## Características Principales

- **Bloqueo del sistema:** al abrir AURA se solicita la huella o el PIN, patrón o contraseña del teléfono. Android 11+ usa `BiometricPrompt` con `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, sin botón negativo personalizado: Android ofrece la alternativa de credenciales y la abre directamente si no hay biometría disponible. Android 7-10 usa biometría fuerte con una opción para abrir la confirmación nativa de credenciales, o va directamente a esta si no hay huellas disponibles, porque no admite esa combinación de banderas. Sin ningún método de seguridad activo, AURA permanece bloqueada y ofrece abrir `Settings.ACTION_SECURITY_SETTINGS`. No se crean pantallas de PIN propias ni se almacenan huellas o credenciales.
- **Margen de retorno:** tras autenticarse, las salidas de menos de 2 minutos no vuelven a pedir autenticación; al regresar después de 2 minutos o más, sí. El intervalo se mide con el reloj monotónico de Android, incluso con el teléfono suspendido. Si Android destruye el proceso, se requiere autenticar de nuevo. Cancelar o fallar nunca concede acceso; se puede reintentar desde la pantalla bloqueada. El sistema controla los intentos y su bloqueo temporal.
- **Audio de Alta Fidelidad (Hi-Fi):** Soporte para FLAC, WAV, ALAC y pistas sin pérdida de hasta 24-bit/192kHz.
- **Gestión de Archivos Locales:** Selección de carpetas mediante SAF, escaneo recursivo de archivos nuevos y rutas persistentes entre actualizaciones.
- **Letras Sincronizadas:** Visualización de letras con formato LRC.
- **Listas de Reproducción y Favoritos:** Organiza tu música favorita y crea listas personalizadas.
- **Interfaz Moderna Material 3:** Diseño elegante y oscuro adaptado para experiencias musicales inmersivas.

## Ejecución Local

**Prerrequisitos:** [Android Studio](https://developer.android.com/studio)

1. Abre Android Studio.
2. Selecciona **Open** (Abrir) y elige el directorio que contiene este proyecto (`AURA`).
3. Permite que Android Studio resuelva e importe las dependencias y configuración de Gradle.
4. Ejecuta la aplicación en un emulador o dispositivo físico Android con Android 7.0 (API 24) o superior.

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
       │                   Queue, Playlist, Artist, Settings, Welcome
       └─ ui/components/ -> MiniPlayerBar, Dialogs, FileManagerSheet
```

- `service/MusicPlaybackService.kt`: servicio en primer plano y notificación de reproducción.
- `ui/theme/`: colores, tipografía y tema Material 3.
- `model/Models.kt`: modelos de dominio compartidos por UI, datos y reproductor.

El bloqueo protege el acceso a la interfaz, no cifra los archivos de música. La reproducción y los controles multimedia de Android siguen funcionando en segundo plano. Las capturas de pantalla y la vista previa en recientes se deshabilitan para proteger la biblioteca.
