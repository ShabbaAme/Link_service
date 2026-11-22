import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class LinkService {
    public interface Store {
        Map<String, ShortLink> load();
        void save(Map<String, ShortLink> links);
    }

    @FunctionalInterface
    public interface Browser {
        void open(URI destination) throws Exception;
    }

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;
    private final Map<String, ShortLink> links;
    private final Store store;
    private final Clock clock;
    private final Duration ttl;
    private final int defaultLimit;
    private final String baseUrl;
    private final Browser browser;
    private final Consumer<String> notifications;
    private final SecureRandom random = new SecureRandom();

    public LinkService(Store store, Clock clock, Duration ttl, int defaultLimit,
                       String baseUrl, Browser browser, Consumer<String> notifications) {
        if (store == null || clock == null || ttl == null || ttl.isNegative() || ttl.isZero()
                || defaultLimit < 1 || browser == null || notifications == null) {
            throw new IllegalArgumentException("Некорректная конфигурация сервиса");
        }
        URI base = validateUrl(baseUrl);
        if (base.getQuery() != null || base.getFragment() != null || base.getPath().endsWith("/")) {
            throw new IllegalArgumentException("short.base должен быть URL без / в конце и без параметров");
        }
        this.store = store;
        this.clock = clock;
        this.ttl = ttl;
        this.defaultLimit = defaultLimit;
        this.baseUrl = base.toString();
        this.browser = browser;
        this.notifications = notifications;
        this.links = new HashMap<>(store.load());
        cleanupExpired();
    }

    public synchronized ShortLink create(UUID owner, String url, Integer requestedLimit) {
        requireOwner(owner);
        URI destination = validateUrl(url);
        int limit = requestedLimit == null ? defaultLimit : requestedLimit;
        if (limit < 1) {
            throw new IllegalArgumentException("Лимит должен быть положительным");
        }
        cleanupExpired();
        String code;
        do {
            StringBuilder candidate = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                candidate.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            code = candidate.toString();
        } while (links.containsKey(code));
        Instant now = clock.instant();
        ShortLink link = new ShortLink(code, owner, destination, now, now.plus(ttl), limit, 0);
        links.put(code, link);
        persist();
        return link;
    }

    public synchronized URI open(String input) {
        String code = extractCode(input);
        ShortLink link = findActive(code);
        if (link.visits() >= link.limit()) {
            notifications.accept("Лимит переходов исчерпан для " + shortUrl(code)
                    + ". Владелец может создать новую ссылку.");
            throw new IllegalStateException("Лимит переходов исчерпан");
        }
        try {
            browser.open(link.destination());
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось открыть браузер: " + exception.getMessage(), exception);
        }
        ShortLink updated = link.withVisits(link.visits() + 1);
        links.put(code, updated);
        persist();
        if (updated.visits() == updated.limit()) {
            notifications.accept("Лимит переходов исчерпан для " + shortUrl(code)
                    + ". Создайте новую ссылку.");
        }
        return link.destination();
    }

    public synchronized ShortLink changeLimit(UUID owner, String input, int limit) {
        ShortLink link = owned(owner, input);
        if (limit < 1 || limit < link.visits()) {
            throw new IllegalArgumentException("Новый лимит должен быть положительным и не меньше числа переходов");
        }
        ShortLink updated = link.withLimit(limit);
        links.put(link.code(), updated);
        persist();
        return updated;
    }

    public synchronized ShortLink changeDestination(UUID owner, String input, String url) {
        URI destination = validateUrl(url);
        ShortLink link = owned(owner, input);
        ShortLink updated = link.withDestination(destination);
        links.put(link.code(), updated);
        persist();
        return updated;
    }

    public synchronized void delete(UUID owner, String input) {
        ShortLink link = owned(owner, input);
        links.remove(link.code());
        persist();
    }

    public synchronized List<ShortLink> list(UUID owner) {
        requireOwner(owner);
        cleanupExpired();
        List<ShortLink> result = new ArrayList<>();
        for (ShortLink link : links.values()) {
            if (link.owner().equals(owner)) {
                result.add(link);
            }
        }
        result.sort(Comparator.comparing(ShortLink::createdAt).thenComparing(ShortLink::code));
        return result;
    }

    public synchronized int cleanupExpired() {
        Instant now = clock.instant();
        List<ShortLink> expired = new ArrayList<>();
        for (ShortLink link : links.values()) {
            if (!now.isBefore(link.expiresAt())) {
                expired.add(link);
            }
        }
        for (ShortLink link : expired) {
            links.remove(link.code());
        }
        if (!expired.isEmpty()) {
            persist();
            for (ShortLink link : expired) {
                notifications.accept("Срок действия ссылки " + shortUrl(link.code())
                        + " истёк (владелец " + link.owner() + "). Создайте новую ссылку.");
            }
        }
        return expired.size();
    }

    public String shortUrl(String code) {
        return baseUrl + "/" + code;
    }

    private ShortLink owned(UUID owner, String input) {
        requireOwner(owner);
        ShortLink link = findActive(extractCode(input));
        if (!link.owner().equals(owner)) {
            throw new SecurityException("Изменять или удалять ссылку может только её владелец");
        }
        return link;
    }

    private ShortLink findActive(String code) {
        ShortLink link = links.get(code);
        if (link == null) {
            throw new IllegalArgumentException("Ссылка не найдена или срок её действия истёк");
        }
        if (!clock.instant().isBefore(link.expiresAt())) {
            cleanupExpired();
            throw new IllegalStateException("Срок действия ссылки истёк. Создайте новую ссылку.");
        }
        return link;
    }

    private String extractCode(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Укажите короткую ссылку или код");
        }
        String code = input.startsWith(baseUrl + "/")
                ? input.substring(baseUrl.length() + 1) : input;
        if (!code.matches("[0-9A-Za-z]{8}")) {
            throw new IllegalArgumentException("Ожидается код из 8 символов или короткая ссылка");
        }
        return code;
    }

    private void persist() {
        store.save(Map.copyOf(links));
    }

    private static void requireOwner(UUID owner) {
        if (owner == null) {
            throw new IllegalArgumentException("Не указан UUID пользователя");
        }
    }

    public static URI validateUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http")
                    && !scheme.equalsIgnoreCase("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() < -1 || uri.getFragment() != null) {
                throw new IllegalArgumentException("Нужен полный http(s)-адрес без логина и фрагмента");
            }
            return uri;
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException("Некорректный URL", exception);
        }
    }
}
