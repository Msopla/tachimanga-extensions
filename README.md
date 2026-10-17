# Extensiones de Matías para Tachimanga

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
| ManhwaShot | 1.4.1 | [Descargar](https://raw.githubusercontent.com/Msopla/tachimanga-extensions/main/apk/tachiyomi-es.manhwashot-v1.4.1.apk) |
| Olympus XYZ | 1.4.1 | [Descargar](https://raw.githubusercontent.com/Msopla/tachimanga-extensions/main/apk/tachiyomi-es.olympusxyz-v1.4.1.apk) |

También puedes guardar un APK en Archivos del iPhone e importarlo desde **Explorar → Extensiones → +**.

## Estado

- **LectorXD 1.4.3:** versión original que corrigió la lista incompleta de capítulos, confirmada por el usuario en Tachimanga.
- **ManhwaShot 1.4.1:** APK original recuperado del proyecto anterior; no se volvió a comprobar su conexión al sitio en esta publicación.
- **Olympus XYZ 1.4.1:** búsqueda, paginación, imágenes y listas reales de 188 y 233 capítulos verificadas; funcionamiento confirmado por el usuario en Tachimanga.

Las tres firmas y los metadatos de los APK se verificaron al publicar. Los binarios originales no se recompilaron ni se volvieron a firmar. Las miniaturas del catálogo se guardan por separado.

## Mantenimiento

Los nuevos capítulos se obtienen de cada sitio al actualizar la serie. Este repositorio distribuye actualizaciones de las extensiones; no corrige automáticamente los cambios de las páginas web.

Para publicar una actualización:

1. Corregir y probar la extensión.
2. Conservar el paquete, identificador de fuente y clave de firma originales; aumentar el código y nombre de versión.
3. Añadir el APK firmado en `apk/`.
4. Actualizar `index.json` e `index.min.json` con la nueva versión y nombre del APK.
5. Actualizar `SHA256SUMS` y comprobar que la firma coincide con `certificates.json`.

Las claves de firma son privadas y no se incluyen en este repositorio. `certificates.json` contiene únicamente huellas públicas de los certificados. Las claves originales de LectorXD y ManhwaShot deberán conservarse o recuperarse de sus proyectos para firmar futuras actualizaciones compatibles.

Documentación oficial: [repositorios](https://tachimanga.app/help/guides/repositories.html) e [importación de APK](https://tachimanga.app/help/guides/add-extension-file.html).

Proyecto personal sin afiliación con Tachimanga ni con los sitios de lectura.
