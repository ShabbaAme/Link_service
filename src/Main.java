import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Path configPath = args.length == 0 ? Path.of("config.properties") : Path.of(args[0]);
        Config config = Config.load(configPath);
        LinkService.Browser browser = uri -> {
            if (!Desktop.isDesktopSupported()) {
                throw new IllegalStateException("Графический браузер недоступен");
            }
            Desktop.getDesktop().browse(uri);
        };
        LinkService service = new LinkService(new FileLinkStore(config.dataFile()), Clock.systemUTC(),
                config.ttl(), config.defaultLimit(), config.baseUrl(), browser,
                message -> System.out.println("[УВЕДОМЛЕНИЕ] " + message));
        var cleanup = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread worker = new Thread(task, "expired-link-cleanup");
            worker.setDaemon(true);
            return worker;
        });
        cleanup.scheduleAtFixedRate(() -> {
            try {
                service.cleanupExpired();
            } catch (RuntimeException exception) {
                System.err.println("Ошибка автоочистки: " + exception.getMessage());
            }
        }, config.cleanupInterval().toSeconds(), config.cleanupInterval().toSeconds(), TimeUnit.SECONDS);
        Path userFile = config.dataFile().toAbsolutePath().getParent().resolve("current-user.txt");
        Scanner scanner = new Scanner(System.in);
        UUID user = loadUser(userFile);
        if (user != null) {
            System.out.println("Ваш UUID: " + user);
        }
        System.out.println("Short Link Service. Введите help для списка команд.");
        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+", 4);
            try {
                switch (parts[0].toLowerCase()) {
                    case "help" -> printHelp();
                    case "user" -> {
                        requireParts(parts, 2);
                        UUID selected = UUID.fromString(parts[1]);
                        saveUser(userFile, selected);
                        user = selected;
                        System.out.println("Текущий пользователь: " + user);
                    }
                    case "new" -> {
                        requireParts(parts, 2);
                        UUID activeUser = user == null ? UUID.randomUUID() : user;
                        Integer limit = parts.length == 3 ? Integer.valueOf(parts[2]) : null;
                        ShortLink link = service.create(activeUser, parts[1], limit);
                        if (user == null) {
                            saveUser(userFile, activeUser);
                            user = activeUser;
                        }
                        System.out.println("UUID пользователя: " + user + "\nКороткая ссылка: " + service.shortUrl(link.code()));
                    }
                    case "open" -> {
                        requireParts(parts, 2);
                        System.out.println("Открывается: " + service.open(parts[1]));
                    }
                    case "list" -> {
                        requireUser(user);
                        printLinks(service.list(user), service);
                    }
                    case "limit" -> {
                        requireUser(user);
                        requireParts(parts, 3);
                        System.out.println("Обновлено: " + service.changeLimit(user, parts[1], Integer.parseInt(parts[2])));
                    }
                    case "edit" -> {
                        requireUser(user);
                        requireParts(parts, 3);
                        System.out.println("Обновлено: " + service.changeDestination(user, parts[1], parts[2]));
                    }
                    case "delete" -> {
                        requireUser(user);
                        requireParts(parts, 2);
                        service.delete(user, parts[1]);
                        System.out.println("Ссылка удалена");
                    }
                    case "cleanup" -> System.out.println("Удалено просроченных ссылок: " + service.cleanupExpired());
                    case "exit", "quit" -> {
                        cleanup.shutdownNow();
                        return;
                    }
                    default -> System.out.println("Неизвестная команда. Введите help.");
                }
            } catch (Exception exception) {
                System.out.println("Ошибка: " + exception.getMessage());
            }
        }
        cleanup.shutdownNow();
    }

    private static void printHelp() {
        System.out.println("user <UUID>                         выбрать пользователя");
        System.out.println("new <URL> [лимит]                   создать ссылку (UUID создаётся автоматически)");
        System.out.println("open <код|короткая-ссылка>          открыть URL и увеличить счётчик");
        System.out.println("list                                список своих ссылок");
        System.out.println("limit <код> <число>                 изменить лимит своей ссылки");
        System.out.println("edit <код> <URL>                    изменить URL своей ссылки");
        System.out.println("delete <код>                        удалить свою ссылку");
        System.out.println("cleanup                             удалить истёкшие ссылки");
        System.out.println("exit                                выйти");
    }

    private static void printLinks(List<ShortLink> links, LinkService service) {
        if (links.isEmpty()) {
            System.out.println("Ссылок нет");
            return;
        }
        links.forEach(link -> System.out.printf("%s -> %s (%d/%d, до %s)%n", service.shortUrl(link.code()),
                link.destination(), link.visits(), link.limit(), link.expiresAt()));
    }

    private static void requireParts(String[] parts, int count) {
        if (parts.length < count) {
            throw new IllegalArgumentException("Недостаточно аргументов. Введите help.");
        }
    }

    private static void requireUser(UUID user) {
        if (user == null) {
            throw new IllegalArgumentException("Сначала создайте ссылку или задайте user <UUID>");
        }
    }

    private static UUID loadUser(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать UUID", exception);
        }
    }

    private static void saveUser(Path file, UUID user) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, user.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить UUID", exception);
        }
    }
}
