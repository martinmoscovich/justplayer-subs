# Publicar una actualización OTA

La versión canónica está en `app/build.gradle`: actualmente `versionCode 215` y
`versionName "0.${versionCode}"`. No hay que repetir esos valores en ningún otro archivo.

Los builds DEBUG usan el `versionCode` configurado como parte mayor y seis dígitos del timestamp
Unix en segundos como parte menor. La app compara la parte mayor primero, por lo que dos builds
consecutivos se pueden actualizar aunque el número base no haya cambiado, y cualquier build de 0.5
siempre queda por debajo de cualquier build de 0.6, sea DEBUG o RELEASE. Los builds RELEASE y su
`version.json` conservan exclusivamente el `versionCode` configurado en Gradle. Para evitar
reutilizar un timestamp anterior, Gradle no usa la caché de configuración en tareas DEBUG; las
tareas RELEASE sí pueden usarla.

En el mismo archivo también están centralizados los valores OTA: `otaReleaseBaseUrl`,
`otaApkFileName` y `otaVersionFileName`. Por defecto apuntan a la GitHub Release del fork y a
`SubPlayer.apk` / `version.json`. Para un build puntual se pueden sobrescribir, por ejemplo:

```powershell
.\justplayer\gradlew.bat :app:prepareOtaReleaseAssets -PotaApkFileName=otro.apk
```

Para cambiarla, desde el root `player` ejecutá:

```powershell
.\set-version.bat 216
```

También acepta `0.216`. El script modifica solamente `versionCode`; `versionName` continúa
derivándose de él.

Para preparar una release DEBUG, desde el root `player` ejecutá:

```powershell
.\justplayer\gradlew.bat :app:prepareOtaReleaseAssets
```

El comando compila `latestUniversalDebug` y deja estos dos archivos en
`justplayer/app/build/ota-release-assets/`:

- `SubPlayer.apk`
- `version.json`

Adjuntá ambos, sin renombrarlos, a la misma GitHub Release de
`martinmoscovich/justplayer-subs`. La app consulta `version.json` y abre el enlace estable de
`SubPlayer.apk` cuando encuentra un `versionCode` mayor.

Cuando se configure la firma de producción, el mismo proceso sirve para RELEASE:

```powershell
.\justplayer\gradlew.bat :app:prepareOtaReleaseAssets -PotaBuildType=release
```
