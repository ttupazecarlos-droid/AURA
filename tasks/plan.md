# Plan: estabilización de AURA

## Alcance
Corregir los 16 hallazgos del informe del 19/11/2026, los tres defectos menores de cola/letras/sesión y los riesgos asociados de migración, escaneo y compilación. No añadir streaming, nube, ecualizador ni garantías de hardware no verificadas.

## Contratos y conservación de datos
- Mantener canciones, favoritos, fechas y relaciones al actualizar la base; nunca usar migraciones destructivas.
- Identidad SAF: `songIdForUri(uri)` devuelve el mismo ID para el mismo documento independientemente del árbol. Los alias anteriores se fusionan dentro de una transacción.
- `Song` y `SongEntity` incorporan `sourceModifiedAt: Long = 0` y `fileSizeBytes: Long = 0` para reutilizar metadatos cuando el proveedor informa cambios fiables.
- `scanFolderFromTree(treeUri, knownSongs: Map<String, Song> = emptyMap())` falla ante cualquier enumeración incompleta; los URI descubiertos siguen distinguiéndose de errores de metadatos.
- `AuraDatabase.storeSong(song, aliasIds = emptyList())` actualiza metadatos y URI conservando favoritos/fecha y fusiona las asociaciones anteriores.
- Borrados de canciones por bloques de 900, con cascada de relaciones; inserciones y migración verificadas.
- Play y pausa son idempotentes; una parada invalida cualquier preparación o foco pendiente.

## Secuencia
1. Escribir regresiones antes de las correcciones.
2. Reparar escaneo, letras, clasificación y metadatos.
3. Proteger migraciones y relaciones; integrar identidad y sincronización.
4. Reparar preparación, foco, shuffle y sesión multimedia.
5. Reparar filtros, listas automáticas, LRC y cola visible, conservando el bloqueo.
6. Completar Wrapper, retirar dependencias sin uso y automatizar las verificaciones.
7. Ejecutar pruebas, compilación y Lint; revisión independiente y commits por bloque.

## Riesgos y verificación
- La migración debe probarse desde el esquema v1 con favoritos, listas y relaciones existentes.
- Las carpetas solapadas o proveedores temporalmente inaccesibles no deben provocar bajas falsas.
- No relajar autenticación ni desactivar reglas de Lint para conseguir un resultado verde.
- No actualizar masivamente versiones por avisos informativos.
- Prueba final: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
- Pendiente de hardware: biometría real, proveedores SAF de terceros, Bluetooth, suspensión y DAC.
