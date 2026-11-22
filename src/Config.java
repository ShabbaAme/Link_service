import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record Config(Duration ttl, int defaultLimit, Duration cleanupInterval,
                     String baseUrl, Path dataFile) {
    public static Config load(Path file) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось загрузить конфигурацию " + file, exception);
        }
        long ttlHours = Long.parseLong(properties.getProperty("link.ttl.hours", "24"));
        int limit = Integer.parseInt(properties.getProperty("link.default-limit", "10"));
        long cleanupSeconds = Long.parseLong(properties.getProperty("cleanup.interval.seconds", "30"));
        if (ttlHours < 1 || limit < 1 || cleanupSeconds < 1) {
            throw new IllegalArgumentException("TTL, лимит и интервал очистки должны быть положительными");
        }
        String base = properties.getProperty("short.base", "http://short.local");
        Path data = Path.of(properties.getProperty("storage.file", "data/links.tsv"));
        return new Config(Duration.ofHours(ttlHours), limit, Duration.ofSeconds(cleanupSeconds), base, data);
    }
}
