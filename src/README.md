# Código fuente de las extensiones

El código de las tres extensiones está en esta carpeta. Se reconstruyó a partir de los APK publicados, porque el código original se había perdido:

| Carpeta | Lenguaje | Incluye en el APK |
|---|---|---|
| `src/manhwashot/` | Kotlin | — |
| `src/lectorxd/` | Kotlin | minimal-json 0.9.5 |
| `src/olympusxyz/` | Java | Gson 2.10.1 |

Comprobaciones de la reconstrucción:

- **LectorXD y Olympus XYZ:** se ejecutaron el APK original y el reconstruido con las mismas entradas y dieron resultados idénticos. En Olympus XYZ se comparó contra el sitio real: catálogo, búsqueda, novedades, fichas, más de 1.000 capítulos y sus páginas. En LectorXD, contra el sitio real en catálogo, búsqueda y novedades, y contra páginas de prueba en ficha, capítulos (incluidos paginación y errores) y lector.
- **LectorXD y Olympus XYZ publicadas:** siguen siendo los APK originales. El código sirve para futuras actualizaciones.

Cada extensión tiene en su carpeta:

- `ext.conf`: paquete, versión y nombre de la firma.
- `src/`: el código.
- `base/`: manifiesto binario, `resources.arsc` y archivos originales del APK.
- `bundle.pom.xml`: solo si la extensión incluye librerías propias.

Los stubs de la API de extensiones y las herramientas comunes están en `src/common/`.

## Compilar

No hace falta el SDK de Android: basta con JDK 17 o superior, Maven y Python 3. El script descarga de Maven Central el compilador de Kotlin, OkHttp, Jsoup, RxJava y `dx` en `src/.tools/`, que está ignorado por git:

```bash
KEYSTORE=/ruta/<extension>-firma.p12 KEYSTORE_PASS=... ./src/build.sh <manhwashot|lectorxd|olympusxyz>
```

El APK resultante queda en `apk/`.

## Publicar una actualización

1. Corregir el código en `src/<extension>/src/` y probarlo.
2. Aumentar `VERSION_CODE` y `VERSION_NAME` en `src/<extension>/ext.conf`. El nombre de versión debe tener 5 caracteres, por ejemplo `1.4.4`.
3. Compilar con `./src/build.sh <extension>` y borrar el APK anterior de `apk/`.
4. Actualizar `index.json` e `index.min.json` con el nuevo nombre de APK, el código y la versión.
5. Actualizar `SHA256SUMS`. Si cambió la clave, actualizar también la huella en `certificates.json`.

No cambies nunca el paquete ni el identificador de la fuente: la biblioteca de Tachimanga depende de ellos.

## Claves de firma

Las claves son privadas y no se incluyen en este repositorio. `certificates.json` contiene solo las huellas públicas de los APK publicados. Las claves originales de LectorXD y Olympus XYZ no estaban disponibles, así que la próxima versión de cada una se firmará con su clave nueva, que guarda el autor. ManhwaShot ya usa su clave nueva desde la 1.4.2. Tachimanga aceptó el cambio de clave de ManhwaShot con el botón **Actualizar**; si en algún caso no lo acepta, basta con desinstalar la extensión y volver a instalarla.
