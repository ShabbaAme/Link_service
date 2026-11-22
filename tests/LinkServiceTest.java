import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LinkServiceTest {
    private static int passed;

    private LinkServiceTest() {}

    public static void main(String[] args) throws Exception {
        test("создание и короткий адрес", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org/a", 2);
            check(f.service.shortUrl(link.code()).equals("https://short.local/" + link.code()));
        });
        test("разные пользователи — разные коды", () -> {
            Fixture f = new Fixture();
            check(!f.create("https://example.org", 1).code().equals(
                    f.service.create(UUID.randomUUID(), "https://example.org", 1).code()));
        });
        test("один пользователь — несколько ссылок", () -> {
            Fixture f = new Fixture();
            f.create("https://example.org/a", 1);
            f.create("https://example.org/b", 1);
            check(f.service.list(f.owner).size() == 2);
        });
        test("одинаковый URL не создаёт коллизию", () -> {
            Fixture f = new Fixture();
            check(!f.create("https://example.org", 1).code().equals(f.create("https://example.org", 1).code()));
        });
        test("валидируются схемы URL", () -> fails(IllegalArgumentException.class,
                () -> new Fixture().create("file:///etc/passwd", 1)));
        test("невалидный URL отклоняется", () -> fails(IllegalArgumentException.class,
                () -> new Fixture().create("нет ссылки", 1)));
        test("лимит должен быть положительным", () -> fails(IllegalArgumentException.class,
                () -> new Fixture().create("https://example.org", 0)));
        test("переход открывает исходный URL", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org/a", 2);
            f.service.open(f.service.shortUrl(link.code()));
            check(f.opened.equals(List.of(URI.create("https://example.org/a"))));
        });
        test("лимит учитывается ровно", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org", 2);
            f.service.open(link.code());
            f.service.open(link.code());
            fails(IllegalStateException.class, () -> f.service.open(link.code()));
            check(f.opened.size() == 2 && f.service.list(f.owner).get(0).visits() == 2);
        });
        test("уведомление об исчерпании лимита", () -> {
            Fixture f = new Fixture();
            f.service.open(f.create("https://example.org", 1).code());
            check(f.messages.stream().anyMatch(s -> s.contains("Лимит переходов исчерпан")));
        });
        test("ошибка браузера не расходует переход", () -> {
            var store = new InMemoryLinkStore();
            var service = new LinkService(store, Clock.systemUTC(), Duration.ofHours(1), 1,
                    "https://short.local", uri -> { throw new IOException("нет браузера"); }, message -> {});
            UUID owner = UUID.randomUUID();
            ShortLink link = service.create(owner, "https://example.org", 1);
            fails(IllegalStateException.class, () -> service.open(link.code()));
            check(service.list(owner).get(0).visits() == 0);
        });
        test("TTL действует до точной границы", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org", 2);
            f.clock.advance(Duration.ofHours(1).minusNanos(1));
            f.service.open(link.code());
            f.clock.advance(Duration.ofNanos(1));
            fails(IllegalStateException.class, () -> f.service.open(link.code()));
        });
        test("автоочистка удаляет просроченные ссылки", () -> {
            Fixture f = new Fixture();
            f.create("https://example.org", 1);
            f.clock.advance(Duration.ofHours(1));
            check(f.service.cleanupExpired() == 1 && f.service.list(f.owner).isEmpty());
            check(f.messages.stream().anyMatch(s -> s.contains("истёк")));
        });
        test("чужой UUID не может менять лимит", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org", 1);
            fails(SecurityException.class, () -> f.service.changeLimit(UUID.randomUUID(), link.code(), 5));
        });
        test("чужой UUID не может менять адрес", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org", 1);
            fails(SecurityException.class, () -> f.service.changeDestination(UUID.randomUUID(), link.code(), "https://new.org"));
        });
        test("чужой UUID не может удалить", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org", 1);
            fails(SecurityException.class, () -> f.service.delete(UUID.randomUUID(), link.code()));
            check(f.service.list(f.owner).size() == 1);
        });
        test("список изолирован по владельцу", () -> {
            Fixture f = new Fixture();
            f.create("https://example.org", 1);
            check(f.service.list(UUID.randomUUID()).isEmpty());
        });
        test("владелец редактирует URL и лимит", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org", 1);
            f.service.changeDestination(f.owner, link.code(), "https://new.org");
            f.service.changeLimit(f.owner, link.code(), 3);
            f.service.open(link.code());
            check(f.opened.equals(List.of(URI.create("https://new.org"))));
            check(f.service.list(f.owner).get(0).limit() == 3);
        });
        test("лимит нельзя уменьшить ниже числа переходов", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org", 3);
            f.service.open(link.code());
            fails(IllegalArgumentException.class, () -> f.service.changeLimit(f.owner, link.code(), 0));
        });
        test("владелец удаляет свою ссылку", () -> {
            Fixture f = new Fixture();
            ShortLink link = f.create("https://example.org", 1);
            f.service.delete(f.owner, link.code());
            fails(IllegalArgumentException.class, () -> f.service.open(link.code()));
        });
        test("данные сохраняются между запусками", () -> {
            Path file = Files.createTempDirectory(Path.of("build"), "links-").resolve("links.tsv");
            var store = new FileLinkStore(file);
            Fixture f = new Fixture(store);
            ShortLink link = f.create("https://example.org", 3);
            f.service.open(link.code());
            var reloaded = new LinkService(new FileLinkStore(file), f.clock, Duration.ofHours(1), 10,
                    "https://short.local", f.opened::add, f.messages::add);
            check(reloaded.list(f.owner).get(0).visits() == 1);
        });
        test("просроченное удаляется после перезапуска", () -> {
            Path file = Files.createTempDirectory(Path.of("build"), "links-").resolve("links.tsv");
            Fixture f = new Fixture(new FileLinkStore(file));
            f.create("https://example.org", 1);
            f.clock.advance(Duration.ofHours(1));
            var reloaded = new LinkService(new FileLinkStore(file), f.clock, Duration.ofHours(1), 10,
                    "https://short.local", f.opened::add, f.messages::add);
            check(reloaded.list(f.owner).isEmpty());
            check(new FileLinkStore(file).load().isEmpty());
        });
        test("конфигурация читается из файла", () -> {
            Config config = Config.load(Path.of("config.properties"));
            check(config.ttl().equals(Duration.ofHours(24)) && config.defaultLimit() == 10);
        });
        System.out.println("Пройдено тестов: " + passed);
    }

    private static void test(String name, Checked action) throws Exception {
        action.run();
        passed++;
        System.out.println("OK " + name);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Проверка не пройдена");
    }

    private static void fails(Class<? extends Throwable> expected, Checked action) {
        try { action.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Ожидалось " + expected + ", получено " + error, error);
        }
        throw new AssertionError("Ожидалась ошибка " + expected);
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static final class Fixture {
        private final MutableClock clock = new MutableClock();
        private final UUID owner = UUID.randomUUID();
        private final List<URI> opened = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private final LinkService service;

        private Fixture() { this(new InMemoryLinkStore()); }

        private Fixture(LinkService.Store store) {
            service = new LinkService(store, clock, Duration.ofHours(1), 10,
                    "https://short.local", opened::add, messages::add);
        }

        private ShortLink create(String url, int limit) { return service.create(owner, url, limit); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        private void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class InMemoryLinkStore implements LinkService.Store {
        private final Map<String, ShortLink> links = new HashMap<>();
        @Override public Map<String, ShortLink> load() { return Map.copyOf(links); }
        @Override public void save(Map<String, ShortLink> updated) {
            links.clear();
            links.putAll(updated);
        }
    }
}
