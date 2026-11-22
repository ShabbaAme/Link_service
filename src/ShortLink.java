import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record ShortLink(String code, UUID owner, URI destination, Instant createdAt,
                        Instant expiresAt, int limit, int visits) {
    public ShortLink {
        if (code == null || code.isBlank() || owner == null || destination == null
                || createdAt == null || expiresAt == null || limit < 1
                || visits < 0 || visits > limit || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Некорректные данные ссылки");
        }
    }

    public ShortLink withVisits(int count) {
        return new ShortLink(code, owner, destination, createdAt, expiresAt, limit, count);
    }

    public ShortLink withLimit(int newLimit) {
        return new ShortLink(code, owner, destination, createdAt, expiresAt, newLimit, visits);
    }

    public ShortLink withDestination(URI url) {
        return new ShortLink(code, owner, url, createdAt, expiresAt, limit, visits);
    }
}
