package splitify;

import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.net.URI;

public class Main {

    public static void main(String[] args) throws Exception {
        String token = System.getenv("BOT_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Не задан токен. Установи переменную окружения BOT_TOKEN и запусти снова.");
            System.exit(1);
        }

        Db.init(); // создаёт splitify.db и таблицы при первом запуске
        Runtime.getRuntime().addShutdownHook(new Thread(Db::close)); // корректно закрыть БД при остановке

        DefaultBotOptions options = new DefaultBotOptions();
        applyProxy(options, System.getenv("PROXY"));

        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(new SplitifyBot(options, token));

        System.out.println("Splitify запущен. Пиши боту в Telegram!");
    }

    /** Прокси из переменной PROXY вида http://127.0.0.1:10809 или socks5://127.0.0.1:10808. Пусто — без прокси. */
    private static void applyProxy(DefaultBotOptions options, String proxy) {
        if (proxy == null || proxy.isBlank()) return;
        URI uri = URI.create(proxy.trim());
        if (uri.getHost() == null || uri.getPort() < 0) {
            System.err.println("PROXY должен быть вида http://127.0.0.1:10809 или socks5://127.0.0.1:10808");
            System.exit(1);
        }
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase();
        options.setProxyHost(uri.getHost());
        options.setProxyPort(uri.getPort());
        options.setProxyType(scheme.startsWith("socks")
                ? DefaultBotOptions.ProxyType.SOCKS5
                : DefaultBotOptions.ProxyType.HTTP);
        System.out.println("Прокси: " + scheme + "://" + uri.getHost() + ":" + uri.getPort());
    }
}
