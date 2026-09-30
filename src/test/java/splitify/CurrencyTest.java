package splitify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CurrencyTest {

    @ParameterizedTest
    @ValueSource(strings = {"RUB", "rub", "Rub", "рубли", "Рубли", "рублей", "рубль", "руб", "руб.", "₽", "  rub  "})
    void rubles_in_any_spelling_become_RUB(String input) {
        assertEquals(Currency.RUB, Currency.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "eur", "евро", "Евро", "€"})
    void euro_in_any_spelling_becomes_EUR(String input) {
        assertEquals(Currency.EUR, Currency.parse(input));
    }

    @Test
    void words_and_symbols_for_other_currencies() {
        assertEquals(Currency.USD, Currency.parse("$"));
        assertEquals(Currency.USD, Currency.parse("доллары"));
        assertEquals(Currency.THB, Currency.parse("бат"));
        assertEquals(Currency.TRY, Currency.parse("лиры"));
        assertEquals(Currency.KZT, Currency.parse("тенге"));
    }

    @Test
    void every_code_parses_to_itself() {
        for (Currency c : Currency.values()) {
            assertEquals(c, Currency.parse(c.name()), c.name());
            assertEquals(c, Currency.parse(c.name().toLowerCase()), c.name());
        }
    }

    @Test
    void unknown_currency_gives_helpful_error() {
        var e = assertThrows(IllegalArgumentException.class, () -> Currency.parse("XYZ"));
        assertTrue(e.getMessage().contains("XYZ"), "в ошибке должно быть то, что ввёл пользователь");
        assertTrue(e.getMessage().contains("RUB"), "в ошибке должны быть примеры валют");
    }

    @Test
    void typo_is_not_silently_accepted() {
        assertThrows(IllegalArgumentException.class, () -> Currency.parse("RBU"));
        assertThrows(IllegalArgumentException.class, () -> Currency.parse(""));
    }
}
