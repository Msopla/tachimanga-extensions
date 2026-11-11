# Extensiones de Msopla para Tachimanga

Repositorio personal de **LectorXD, ManhwaShot y Olympus XYZ**, en español.

## Añadir a Tachimanga

En la sección de repositorios de extensiones de Tachimanga, agrega esta dirección:

```text
https://raw.githubusercontent.com/Msopla/tachimanga-extensions/main/index.min.json
```

Después actualiza la lista de extensiones. Si ya tienes estos APK importados, conservan sus paquetes y firmas originales; no es necesario borrar tu biblioteca. Al publicar una versión con un código mayor, Tachimanga podrá reconocerla como actualización desde el repositorio. La comprobación o instalación puede requerir actualizar la lista y pulsar **Actualizar** en la app.

## Extensiones

| Fuente | Versión | Descargar APK |
|---|---|---|
| LectorXD | 1.4.3 | [Descargar](https://raw.githubusercontent.com/Msopla/tachimanga-extensions/main/apk/tachiyomi-es.lectorxd-v1.4.3.apk) |
| ManhwaShot | 1.4.3 | [Descargar](https://raw.githubusercontent.com/Msopla/tachimanga-extensions/main/apk/tachiyomi-es.manhwashot-v1.4.3.apk) |
| Olympus XYZ | 1.4.1 | [Descargar](https://raw.githubusercontent.com/Msopla/tachimanga-extensions/main/apk/tachiyomi-es.olympusxyz-v1.4.1.apk) |

También puedes guardar un APK en Archivos del iPhone e importarlo desde **Explorar → Extensiones → +**.

## Estado

- **LectorXD 1.4.3:** versión original que corrigió la lista incompleta de capítulos, confirmada por el usuario en Tachimanga.
- **ManhwaShot 1.4.3:** muestra la fecha real de subida de cada capítulo (antes todas salían como "Hoy").
- **ManhwaShot 1.4.2:** reconstruida desde el APK 1.4.1. Añade los capítulos "gratis solo para usuarios": usa la sesión iniciada en el WebView de Tachimanga, pide las páginas protegidas, renueva los enlaces caducados y descifra las imágenes como hace la web. Sin sesión muestra un aviso claro. Catálogo, búsqueda, ficha, capítulos y páginas libres comprobados contra el sitio; las páginas protegidas, solo contra un servidor simulado.
  - **Importante:** se firmó con una clave nueva (la original no estaba disponible). Para pasar de la 1.4.1 hay que desinstalar ManhwaShot y volver a instalarla; la biblioteca se conserva porque el identificador de la fuente no cambia.
- **Olympus XYZ 1.4.1:** búsqueda, paginación, imágenes y listas reales de 188 y 233 capítulos verificadas; funcionamiento confirmado por el usuario en Tachimanga.

Las tres firmas y los metadatos de los APK se verificaron al publicar. Los binarios originales no se recompilaron ni se volvieron a firmar. Las miniaturas del catálogo se guardan por separado.

## Mantenimiento

Los nuevos capítulos se obtienen de cada sitio al actualizar la serie. Este repositorio distribuye actualizaciones de las extensiones; no corrige automáticamente los cambios de las páginas web.

## Código fuente

El código de las tres extensiones está en `src/`. Se reconstruyó a partir de los APK publicados, porque el código original se había perdido:

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

### Compilar

No hace falta el SDK de Android: basta con JDK 17 o superior, Maven y Python 3. El script descarga de Maven Central el compilador de Kotlin, OkHttp, Jsoup, RxJava y `dx` en `src/.tools/`, que está ignorado por git:

```bash
KEYSTORE=/ruta/<extension>-firma.p12 KEYSTORE_PASS=... ./src/build.sh <manhwashot|lectorxd|olympusxyz>
```

El APK resultante queda en `apk/`.

### Publicar una actualización

1. Corregir el código en `src/<extension>/src/` y probarlo.
2. Aumentar `VERSION_CODE` y `VERSION_NAME` en `src/<extension>/ext.conf`. El nombre de versión debe tener 5 caracteres, por ejemplo `1.4.4`.
3. Compilar con `./src/build.sh <extension>` y borrar el APK anterior de `apk/`.
4. Actualizar `index.json` e `index.min.json` con el nuevo nombre de APK, el código y la versión.
5. Actualizar `SHA256SUMS`. Si cambió la clave, actualizar también la huella en `certificates.json`.

No cambies nunca el paquete ni el identificador de la fuente: la biblioteca de Tachimanga depende de ellos.

### Claves de firma

Las claves son privadas y no se incluyen en este repositorio. `certificates.json` contiene solo las huellas públicas de los APK publicados. Las claves originales de LectorXD y Olympus XYZ no estaban disponibles, así que la próxima versión de cada una se firmará con su clave nueva, que guarda el autor. ManhwaShot ya usa su clave nueva desde la 1.4.2. Tachimanga aceptó el cambio de clave de ManhwaShot con el botón **Actualizar**; si en algún caso no lo acepta, basta con desinstalar la extensión y volver a instalarla.

Documentación oficial: [repositorios](https://tachimanga.app/help/guides/repositories.html) e [importación de APK](https://tachimanga.app/help/guides/add-extension-file.html).

Proyecto personal sin afiliación con Tachimanga ni con los sitios de lectura.
