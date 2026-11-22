import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class FileLinkStore implements LinkService.Store {
    private final Path file;

    public FileLinkStore(Path file) {
        this.file = file;
    }

    @Override
    public synchronized Map<String, ShortLink> load() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            Map<String, ShortLink> result = new HashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\t", -1);
                if (parts.length != 7) throw new IOException("Повреждённая строка хранилища");
                result.put(parts[0], new ShortLink(parts[0], UUID.fromString(parts[1]),
                        LinkService.validateUrl(parts[2]), Instant.parse(parts[3]), Instant.parse(parts[4]),
                        Integer.parseInt(parts[5]), Integer.parseInt(parts[6])));
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось прочитать хранилище: " + file, exception);
        }
    }

    @Override
    public synchronized void save(Map<String, ShortLink> links) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            String content = links.values().stream().map(link -> String.join("\t", link.code(),
                    link.owner().toString(), link.destination().toString(), link.createdAt().toString(),
                    link.expiresAt().toString(), Integer.toString(link.limit()), Integer.toString(link.visits())))
                    .collect(Collectors.joining(System.lineSeparator()));
            Path temporary = Files.createTempFile(parent, "links-", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось записать хранилище: " + file, exception);
        }
    }

}
