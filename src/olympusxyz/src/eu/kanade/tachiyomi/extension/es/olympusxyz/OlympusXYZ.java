package eu.kanade.tachiyomi.extension.es.olympusxyz;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import eu.kanade.tachiyomi.source.model.FilterList;
import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.model.Page;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import eu.kanade.tachiyomi.source.online.HttpSource;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rx.Observable;

/**
 * Olympus XYZ (olympusxyz.com). Usa la API JSON del sitio.
 *
 * Reconstruido a partir del APK 1.4.1 con el mismo comportamiento:
 * - Populares: /api/rankings; novedades: /api/new-chapters.
 * - Búsqueda: local, sobre el catálogo completo (/api/series/list, caché de 10 min).
 * - La URL de una serie es su id; la de un capítulo, "idSerie/idCapítulo/número".
 *   El slug se obtiene del catálogo, así que un cambio de slug no rompe la biblioteca.
 * - Capítulos: se recorren todas las páginas de la API y se exige el total anunciado.
 */
public class OlympusXYZ extends HttpSource {

    private static final String BASE = "https://olympusxyz.com";
    private static final long CATALOG_TTL_MS = 10 * 60 * 1000L;
    private static final long MIN_GAP_MS = 250L;
    private static final int SEARCH_PAGE_SIZE = 30;

    private volatile List<JsonObject> catalog = Collections.emptyList();
    private volatile long catalogTime;
    private long lastRequest;

    @Override
    public String getName() {
        return "Olympus XYZ";
    }

    @Override
    public String getLang() {
        return "es";
    }

    @Override
    public String getBaseUrl() {
        return BASE;
    }

    @Override
    public boolean getSupportsLatest() {
        return true;
    }

    /** Identificador fijo de la fuente (no cambiar: la biblioteca depende de él). */
    @Override
    public long getId() {
        return 7037064838447991721L;
    }

    @Override
    public OkHttpClient getClient() {
        return getNetwork().getCloudflareClient();
    }

    @Override
    protected Headers.Builder headersBuilder() {
        return super.headersBuilder()
            .set("Referer", BASE + "/")
            .set("Accept", "application/json, image/avif, image/webp, image/*, */*");
    }

    // ===== Catálogo =====

    @Override
    protected Request popularMangaRequest(int page) {
        return request("/api/rankings?page=" + page + "&period=total_ranking");
    }

    @Override
    protected MangasPage popularMangaParse(Response response) {
        return listings(json(response));
    }

    @Override
    protected Request latestUpdatesRequest(int page) {
        return request("/api/new-chapters?page=" + page);
    }

    @Override
    protected MangasPage latestUpdatesParse(Response response) {
        return listings(json(response));
    }

    @Override
    public Observable<MangasPage> fetchSearchManga(final int page, final String query, FilterList filters) {
        return Observable.fromCallable(new Callable<MangasPage>() {
            @Override
            public MangasPage call() throws Exception {
                return search(catalog(), page, query);
            }
        });
    }

    @Override
    protected Request searchMangaRequest(int page, String query, FilterList filters) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected MangasPage searchMangaParse(Response response) {
        throw new UnsupportedOperationException();
    }

    // ===== Serie =====

    @Override
    public Request mangaDetailsRequest(SManga manga) {
        return request("/api/series/" + slug(manga.getUrl()) + "?type=comic");
    }

    @Override
    public String getMangaUrl(SManga manga) {
        return BASE + "/series/comic-" + slug(manga.getUrl());
    }

    @Override
    protected SManga mangaDetailsParse(Response response) {
        JsonObject data = json(response).getAsJsonObject("data");
        SManga manga = manga(data);
        manga.setDescription(str(data, "summary").trim());

        List<String> genres = new ArrayList<String>();
        if (data.has("genres")) {
            for (JsonElement genre : data.getAsJsonArray("genres")) {
                genres.add(str(genre.getAsJsonObject(), "name").trim());
            }
        }
        manga.setGenre(join(genres));

        String status = data.has("status") && data.get("status").isJsonObject()
            ? normalized(str(data.getAsJsonObject("status"), "name"))
            : "";
        int value;
        if (status.contains("finaliz") || status.contains("complet")) {
            value = 2; // COMPLETED
        } else if (status.contains("cancel")) {
            value = 5; // CANCELLED
        } else if (status.contains("pausa") || status.contains("hiatus")) {
            value = 6; // ON_HIATUS
        } else if (status.contains("activo") || status.contains("emision")) {
            value = 1; // ONGOING
        } else {
            value = 0; // UNKNOWN
        }
        manga.setStatus(value);
        manga.setInitialized(true);
        return manga;
    }

    // ===== Capítulos =====

    @Override
    protected Request chapterListRequest(SManga manga) {
        return request("/api/series/" + slug(manga.getUrl()) + "/chapters?page=1&direction=desc&type=comic")
            .newBuilder()
            .tag(manga.getUrl())
            .build();
    }

    @Override
    protected List<SChapter> chapterListParse(Response response) {
        String seriesId = (String) response.request().tag();
        HttpUrl url = response.request().url();
        JsonObject page = json(response);
        LinkedHashMap<String, SChapter> chapters = new LinkedHashMap<String, SChapter>();
        int current = 1;

        while (true) {
            boolean added = addChapterPage(page, seriesId, chapters);
            JsonObject meta = page.getAsJsonObject("meta");
            if (meta == null || !meta.has("total") || !meta.has("last_page")) {
                throw new IllegalStateException(
                    "Olympus no indicó el total de capítulos; se canceló la actualización para evitar una lista incompleta.");
            }
            int total = integer(meta, "total", 0);
            int lastPage = integer(meta, "last_page", 1);

            if (chapters.size() >= total) {
                List<SChapter> list = new ArrayList<SChapter>(chapters.values());
                Collections.sort(list, new Comparator<SChapter>() {
                    @Override
                    public int compare(SChapter a, SChapter b) {
                        return Float.compare(b.getChapter_number(), a.getChapter_number());
                    }
                });
                return list;
            }

            if (!added || current >= lastPage) {
                throw new IllegalStateException(
                    "Olympus entregó " + chapters.size() + " de " + total + " capítulos. Actualiza de nuevo más tarde.");
            }

            current++;
            Request next = new Request.Builder()
                .url(url.newBuilder().setQueryParameter("page", String.valueOf(current)).build())
                .headers(getHeaders())
                .build();
            try {
                page = read(next);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("No se pudo completar la lista de capítulos. Vuelve a actualizar.", e);
            }
        }
    }

    static boolean addChapterPage(JsonObject page, String seriesId, LinkedHashMap<String, SChapter> chapters) {
        int before = chapters.size();
        for (JsonElement element : data(page)) {
            JsonObject item = element.getAsJsonObject();
            String id = str(item, "id");
            String number = str(item, "name");
            if (id.isEmpty() || number.isEmpty()) {
                throw new IllegalStateException("Olympus devolvió un capítulo sin identificador.");
            }

            SChapter chapter = SChapter.Companion.create();
            chapter.setUrl(seriesId + "/" + id + "/" + number);
            chapter.setName("Capítulo " + number);
            try {
                chapter.setChapter_number(Float.parseFloat(number));
            } catch (NumberFormatException e) {
                chapter.setChapter_number(-1f);
            }
            if (item.has("team") && item.get("team").isJsonObject()) {
                chapter.setScanlator(str(item.getAsJsonObject("team"), "name").trim());
            }
            String published = str(item, "published_at");
            if (published.length() >= 19) {
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                try {
                    chapter.setDate_upload(format.parse(published.substring(0, 19)).getTime());
                } catch (ParseException ignored) {
                    // Sin fecha: la app usará la de descubrimiento.
                }
            }
            chapters.put(id, chapter);
        }
        return chapters.size() > before;
    }

    // ===== Páginas =====

    @Override
    protected Request pageListRequest(SChapter chapter) {
        String[] parts = chapter.getUrl().split("/", 3);
        return request("/api/capitulo/" + slug(parts[0]) + "/" + encode(parts[2]) + "?type=comic");
    }

    @Override
    public String getChapterUrl(SChapter chapter) {
        String[] parts = chapter.getUrl().split("/", 3);
        return BASE + "/series/comic-" + slug(parts[0]) + "/capitulo/" + encode(parts[2]);
    }

    @Override
    protected List<Page> pageListParse(Response response) {
        return pages(json(response));
    }

    static List<Page> pages(JsonObject root) {
        if (!root.has("chapter") || !root.get("chapter").isJsonObject()) {
            throw new IllegalStateException("El capítulo no está disponible.");
        }
        JsonArray urls = root.getAsJsonObject("chapter").getAsJsonArray("pages");
        if (urls == null || urls.size() == 0) {
            throw new IllegalStateException("Olympus no entregó imágenes para este capítulo.");
        }
        List<Page> pages = new ArrayList<Page>();
        for (JsonElement element : urls) {
            String url = element.getAsString();
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw new IllegalStateException("Olympus devolvió una dirección de imagen inválida.");
            }
            pages.add(new Page(pages.size(), "", url, null));
        }
        return pages;
    }

    @Override
    protected String imageUrlParse(Response response) {
        throw new UnsupportedOperationException("Las imágenes se obtienen directamente.");
    }

    // ===== Utilidades =====

    private Request request(String path) {
        return new Request.Builder().url(BASE + path).headers(getHeaders()).build();
    }

    /** Petición con espaciado mínimo entre llamadas (para no saturar la API). */
    protected JsonObject read(Request request) throws Exception {
        pace();
        Response response = getClient().newCall(request).execute();
        try {
            return json(response);
        } finally {
            response.close();
        }
    }

    private synchronized void pace() throws InterruptedException {
        long wait = MIN_GAP_MS - (System.currentTimeMillis() - lastRequest);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequest = System.currentTimeMillis();
    }

    /** Catálogo completo (solo cómics), guardado 10 minutos. */
    private synchronized List<JsonObject> catalog() throws Exception {
        if (!catalog.isEmpty() && System.currentTimeMillis() - catalogTime < CATALOG_TTL_MS) {
            return catalog;
        }
        List<JsonObject> list = new ArrayList<JsonObject>();
        for (JsonElement element : data(read(request("/api/series/list")))) {
            JsonObject item = element.getAsJsonObject();
            if ("comic".equals(str(item, "type"))) {
                list.add(item);
            }
        }
        catalog = list;
        catalogTime = System.currentTimeMillis();
        return list;
    }

    private String slug(String id) {
        try {
            for (JsonObject item : catalog()) {
                if (id.equals(str(item, "id"))) {
                    return str(item, "slug");
                }
            }
            throw new IllegalStateException("Esta serie ya no aparece en el catálogo de Olympus.");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo consultar el catálogo de Olympus.", e);
        }
    }

    static MangasPage search(List<JsonObject> catalog, int page, String query) {
        String needle = normalized(query);
        List<SManga> matches = new ArrayList<SManga>();
        for (JsonObject item : catalog) {
            if (normalized(str(item, "name")).contains(needle)) {
                matches.add(manga(item));
            }
        }
        int from = Math.min(Math.max(0, page - 1) * SEARCH_PAGE_SIZE, matches.size());
        int to = Math.min(from + SEARCH_PAGE_SIZE, matches.size());
        return new MangasPage(new ArrayList<SManga>(matches.subList(from, to)), to < matches.size());
    }

    static MangasPage listings(JsonObject root) {
        List<SManga> mangas = new ArrayList<SManga>();
        for (JsonElement element : data(root)) {
            JsonObject item = element.getAsJsonObject();
            if ("comic".equals(str(item, "type"))) {
                mangas.add(manga(item));
            }
        }
        boolean hasNext = root.has("next_page_url") && !root.get("next_page_url").isJsonNull();
        if (root.has("last_page")) {
            hasNext = integer(root, "current_page", 1) < integer(root, "last_page", 1);
        }
        return new MangasPage(mangas, hasNext);
    }

    static SManga manga(JsonObject item) {
        SManga manga = SManga.Companion.create();
        manga.setUrl(str(item, "id"));
        manga.setTitle(str(item, "name").trim());
        manga.setThumbnail_url(str(item, "cover"));
        return manga;
    }

    static JsonObject json(Response response) {
        if (!response.isSuccessful()) {
            throw new IllegalStateException(
                "Olympus respondió HTTP " + response.code() + ". Intenta abrir la fuente en WebView y volver a actualizar.");
        }
        Exception cause;
        try {
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (!root.has("error")) {
                return root;
            }
            throw new IllegalStateException("Olympus no pudo entregar los datos. Intenta de nuevo más tarde.");
        } catch (IOException e) {
            cause = e;
        } catch (JsonParseException e) {
            cause = e;
        } catch (UnsupportedOperationException e) {
            cause = e;
        }
        throw new IllegalStateException(
            "Olympus no devolvió datos válidos. Abre la fuente en WebView y comprueba si está en mantenimiento.", cause);
    }

    static JsonArray data(JsonObject root) {
        if (root.has("data") && root.get("data").isJsonArray()) {
            return root.getAsJsonArray("data");
        }
        throw new IllegalStateException("Olympus cambió el formato de su catálogo.");
    }

    static String str(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    static String normalized(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .trim();
    }

    private static String join(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(item);
        }
        return out.toString();
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
