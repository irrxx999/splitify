package splitify;

import org.junit.jupiter.api.Test;
import splitify.SettlementService.Expense;
import splitify.SettlementService.Participant;
import splitify.SettlementService.Transfer;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SettlementServiceTest {

    // Участники: id, имя, валюта, в которой им возвращают долги.
    private static final Participant ANYA = new Participant(1, "Аня", "RUB");
    private static final Participant PETYA = new Participant(2, "Петя", "EUR");
    private static final Participant MASHA = new Participant(3, "Маша", "EUR");

    private static final Map<String, BigDecimal> RATES = Map.of(
            "RUB", new BigDecimal("0.01"),
            "THB", new BigDecimal("0.026"));

    private final SettlementService service = new SettlementService("EUR", RATES);

    private static Expense expense(Participant payer, String amount, String currency, Participant... shared) {
        return expense(payer, amount, currency, null, shared);
    }

    private static Expense expense(Participant payer, String amount, String currency,
                                   String frozenRate, Participant... shared) {
        return new Expense(payer.id(), new BigDecimal(amount), currency,
                frozenRate == null ? null : new BigDecimal(frozenRate),
                Arrays.stream(shared).map(Participant::id).toList());
    }

    /** «Петя → Аня: 50.00 EUR» — удобно сравнивать независимо от порядка переводов. */
    private static List<String> show(List<Transfer> transfers) {
        return transfers.stream()
                .map(t -> t.from() + " → " + t.to() + ": " + t.amount().toPlainString() + " " + t.currency())
                .sorted()
                .toList();
    }

    @Test
    void two_people_split_one_expense() {
        var result = service.settle(List.of(PETYA, MASHA),
                List.of(expense(PETYA, "100", "EUR", PETYA, MASHA)));

        assertEquals(List.of("Маша → Петя: 50.00 EUR"), show(result));
    }

    @Test
    void nobody_owes_when_everyone_paid_equally() {
        var result = service.settle(List.of(PETYA, MASHA), List.of(
                expense(PETYA, "40", "EUR", PETYA, MASHA),
                expense(MASHA, "40", "EUR", PETYA, MASHA)));

        assertTrue(result.isEmpty());
    }

    @Test
    void no_expenses_means_no_transfers() {
        assertTrue(service.settle(List.of(ANYA, PETYA, MASHA), List.of()).isEmpty());
    }

    @Test
    void payer_who_did_not_share_gets_everything_back() {
        // Аня заплатила за Петю и Машу, сама в трате не участвует.
        var result = service.settle(List.of(ANYA, PETYA, MASHA),
                List.of(expense(ANYA, "3000", "RUB", PETYA, MASHA)));

        // 3000 RUB = 30 EUR; каждый должен 15 EUR = 1500 RUB (Аня получает в рублях).
        assertEquals(List.of("Маша → Аня: 1500.00 RUB", "Петя → Аня: 1500.00 RUB"), show(result));
    }

    @Test
    void transfer_is_converted_to_the_recipients_currency() {
        var result = service.settle(List.of(ANYA, PETYA, MASHA),
                List.of(expense(ANYA, "3000", "RUB", ANYA, PETYA, MASHA)));

        // 30 EUR на троих — по 10 EUR; Аня получает в рублях: 10 EUR = 1000 RUB.
        assertEquals(List.of("Маша → Аня: 1000.00 RUB", "Петя → Аня: 1000.00 RUB"), show(result));
    }

    @Test
    void expense_in_a_foreign_currency_is_converted_to_base() {
        var result = service.settle(List.of(PETYA, MASHA),
                List.of(expense(PETYA, "1000", "THB", PETYA, MASHA)));

        // 1000 THB × 0.026 = 26 EUR, пополам — Маша должна 13 EUR.
        assertEquals(List.of("Маша → Петя: 13.00 EUR"), show(result));
    }

    @Test
    void frozen_rate_beats_the_current_rate() {
        // Курс в момент траты был 0.02, сейчас в карте 0.01 — считаем по замороженному.
        var frozen = service.settle(List.of(PETYA, MASHA),
                List.of(expense(PETYA, "1000", "RUB", "0.02", PETYA, MASHA)));
        assertEquals(List.of("Маша → Петя: 10.00 EUR"), show(frozen));

        // Без замороженного курса берётся актуальный.
        var live = service.settle(List.of(PETYA, MASHA),
                List.of(expense(PETYA, "1000", "RUB", PETYA, MASHA)));
        assertEquals(List.of("Маша → Петя: 5.00 EUR"), show(live));
    }

    @Test
    void missing_rate_fails_loudly_instead_of_guessing() {
        var e = assertThrows(IllegalArgumentException.class, () ->
                service.settle(List.of(PETYA, MASHA),
                        List.of(expense(PETYA, "100", "JPY", PETYA, MASHA))));

        assertTrue(e.getMessage().contains("JPY"), "в ошибке должна быть валюта без курса");
    }

    @Test
    void missing_rate_for_recipients_currency_fails_too() {
        // Аня платила в EUR, получать долг хочет в рублях, а курса RUB нет.
        var noRub = new SettlementService("EUR", Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                noRub.settle(List.of(ANYA, PETYA), List.of(expense(ANYA, "100", "EUR", ANYA, PETYA))));
    }

    @Test
    void uneven_division_rounds_to_cents() {
        var lena = new Participant(4, "Лена", "EUR");
        var result = service.settle(List.of(PETYA, MASHA, lena),
                List.of(expense(PETYA, "100", "EUR", PETYA, MASHA, lena)));

        // 100 / 3 = 33.33..., каждому должнику — 33.33
        assertTrue(show(result).stream().allMatch(s -> s.endsWith(": 33.33 EUR")), show(result).toString());
    }

    @Test
    void dust_below_one_cent_is_ignored() {
        var result = service.settle(List.of(PETYA, MASHA),
                List.of(expense(PETYA, "0.01", "EUR", PETYA, MASHA)));

        assertTrue(result.isEmpty());
    }

    @Test
    void chain_of_debts_is_collapsed() {
        // Петя заплатил за Машу 10, Маша за Аню 10 (в EUR у Ани тоже EUR): у Маши нулевой баланс,
        // поэтому вместо цепочки Аня → Маша → Петя должен быть один перевод Аня → Петя.
        var anya = new Participant(1, "Аня", "EUR");
        var result = service.settle(List.of(anya, PETYA, MASHA), List.of(
                expense(PETYA, "10", "EUR", MASHA),
                expense(MASHA, "10", "EUR", anya)));

        assertEquals(List.of("Аня → Петя: 10.00 EUR"), show(result));
    }

    @Test
    void random_expenses_are_settled_exactly_with_few_transfers() {
        // Свойство: после всех переводов у каждого баланс сходится в ноль (с точностью до копеек),
        // а переводов не больше, чем участников минус один.
        var people = List.of(
                new Participant(1, "A", "EUR"), new Participant(2, "B", "EUR"),
                new Participant(3, "C", "EUR"), new Participant(4, "D", "EUR"),
                new Participant(5, "E", "EUR"));
        var random = new Random(42);

        for (int round = 0; round < 50; round++) {
            List<Expense> expenses = new ArrayList<>();
            Map<String, Double> balance = new HashMap<>();
            people.forEach(p -> balance.put(p.name(), 0.0));

            for (int i = 0; i < 1 + random.nextInt(15); i++) {
                Participant payer = people.get(random.nextInt(people.size()));
                List<Participant> shared = new ArrayList<>(people);
                Collections.shuffle(shared, random);
                shared = shared.subList(0, 1 + random.nextInt(people.size()));
                double amount = 1 + random.nextInt(50_000) / 100.0;

                expenses.add(new Expense(payer.id(), BigDecimal.valueOf(amount), "EUR", null,
                        shared.stream().map(Participant::id).toList()));
                balance.merge(payer.name(), amount, Double::sum);
                for (Participant p : shared) balance.merge(p.name(), -amount / shared.size(), Double::sum);
            }

            var transfers = service.settle(people, expenses);
            for (Transfer t : transfers) {
                balance.merge(t.from(), t.amount().doubleValue(), Double::sum);
                balance.merge(t.to(), -t.amount().doubleValue(), Double::sum);
            }

            for (var entry : balance.entrySet()) {
                assertEquals(0.0, entry.getValue(), 0.06,
                        "раунд " + round + ", у " + entry.getKey() + " остался долг");
            }
            assertTrue(transfers.size() <= people.size() - 1,
                    "раунд " + round + ": " + transfers.size() + " переводов");
        }
    }
}
