package splitify;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Поддерживаемые валюты. Пользователь может написать код (RUB), слово (рубли) или символ (₽) —
 * всё приводится к одному значению. В базе хранится {@link #name()} — трёхбуквенный код.
 * Чтобы добавить валюту, допиши строку в список ниже.
 */
public enum Currency {
    RUB("рубль", "рубли", "рублей", "руб", "₽"),
    EUR("евро", "€"),
    USD("доллар", "доллары", "долларов", "бакс", "баксы", "$"),
    THB("бат", "бата", "баты", "батов"),
    GEL("лари"),
    TRY("лира", "лиры", "лир"),
    AED("дирхам", "дирхамы", "дирхамов"),
    JPY("йена", "йены", "иен", "¥"),
    CNY("юань", "юани", "юаней"),
    KRW("вона", "воны", "вон"),
    VND("донг", "донги"),
    IDR,
    INR("рупия", "рупии", "рупий", "₹"),
    LKR,
    MYR("ринггит"),
    SGD,
    HKD,
    GBP("фунт", "фунты", "фунтов", "£"),
    CHF("франк", "франки", "франков"),
    CZK("крон"),
    PLN("злотый", "злотых", "злотые"),
    HUF("форинт", "форинты", "форинтов"),
    SEK,
    NOK,
    DKK,
    RSD("динар", "динары", "динаров"),
    MAD,
    EGP,
    ILS("шекель", "шекели", "шекелей", "₪"),
    KZT("тенге", "₸"),
    UZS("сум"),
    KGS("сом"),
    AMD("драм", "драмы", "драмов"),
    AZN("манат", "маната", "манаты"),
    BYN,
    UAH("гривна", "гривны", "гривен", "грн", "₴"),
    AUD,
    CAD,
    MXN("песо"),
    BRL("реал", "реалы");

    private static final Map<String, Currency> LOOKUP = new HashMap<>();

    static {
        for (Currency c : values()) {
            LOOKUP.put(c.name().toLowerCase(), c);
            for (String a : c.aliases) LOOKUP.put(a.toLowerCase(), c);
        }
    }

    private final String[] aliases;

    Currency(String... aliases) {
        this.aliases = aliases;
    }

    /** Понимает «RUB», «rub», «Рубли», «руб.», «₽». Неизвестное — понятная ошибка. */
    public static Currency parse(String input) {
        String key = input.trim().toLowerCase();
        if (key.endsWith(".")) key = key.substring(0, key.length() - 1);
        Currency c = LOOKUP.get(key);
        if (c == null) {
            throw new IllegalArgumentException("Не знаю валюту «" + input + "». Пиши код или название: "
                    + Stream.of(RUB, EUR, USD, THB, JPY, GEL, TRY).map(Enum::name).collect(Collectors.joining(", "))
                    + " и т.д. (например: RUB, рубли, ₽)");
        }
        return c;
    }
}
