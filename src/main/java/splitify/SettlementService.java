package splitify;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Ядро бота: считает, кто кому сколько должен.
 *
 * Логика:
 * 1. Каждая трата переводится в базовую валюту поездки по курсу.
 * 2. Для каждого участника считается баланс: сколько заплатил минус его доля.
 * 3. Жадный алгоритм сводит балансы к минимальному числу переводов.
 * 4. Сумма каждого перевода конвертируется в валюту, которую выбрал ПОЛУЧАТЕЛЬ.
 */
public class SettlementService {

    public record Participant(long id, String name, String preferredCurrency) {}

    /**
     * @param rateAtTime курс валюты к базовой, замороженный в момент /spend.
     *                   Если null — курс берётся из актуальной карты (для трат, записанных без курса).
     */
    public record Expense(long payerId, BigDecimal amount, String currency,
                          BigDecimal rateAtTime, List<Long> sharedBetween) {}

    /** Итоговый перевод: from должен to сумму amount в валюте currency. */
    public record Transfer(String from, String to, BigDecimal amount, String currency) {}

    private final Map<String, BigDecimal> ratesToBase; // "THB" -> 0.026 (в EUR)
    private final String baseCurrency;

    public SettlementService(String baseCurrency, Map<String, BigDecimal> ratesToBase) {
        this.baseCurrency = baseCurrency;
        this.ratesToBase = new HashMap<>(ratesToBase);
        this.ratesToBase.put(baseCurrency, BigDecimal.ONE);
    }

    public List<Transfer> settle(List<Participant> participants, List<Expense> expenses) {
        Map<Long, Participant> byId = new HashMap<>();
        Map<Long, BigDecimal> balance = new HashMap<>(); // в базовой валюте
        for (Participant p : participants) {
            byId.put(p.id(), p);
            balance.put(p.id(), BigDecimal.ZERO);
        }

        // 1-2. Балансы в базовой валюте
        for (Expense e : expenses) {
            BigDecimal inBase = toBase(e.amount(), e.currency(), e.rateAtTime());
            balance.merge(e.payerId(), inBase, BigDecimal::add);

            BigDecimal share = inBase.divide(
                    BigDecimal.valueOf(e.sharedBetween().size()), 10, RoundingMode.HALF_UP);
            for (Long personId : e.sharedBetween()) {
                balance.merge(personId, share.negate(), BigDecimal::add);
            }
        }

        // 3. Жадное упрощение: должники платят кредиторам
        Deque<Map.Entry<Long, BigDecimal>> debtors = new ArrayDeque<>();
        Deque<Map.Entry<Long, BigDecimal>> creditors = new ArrayDeque<>();
        balance.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue()
                        .thenComparing(Map.Entry::getKey))
                .forEach(en -> {
                    if (en.getValue().signum() < 0) debtors.add(Map.entry(en.getKey(), en.getValue().negate()));
                    else if (en.getValue().signum() > 0) creditors.addFirst(en);
                });

        List<Transfer> result = new ArrayList<>();
        Map.Entry<Long, BigDecimal> d = debtors.poll();
        Map.Entry<Long, BigDecimal> c = creditors.poll();

        while (d != null && c != null) {
            BigDecimal amount = d.getValue().min(c.getValue());

            if (amount.compareTo(new BigDecimal("0.01")) >= 0) { // отсекаем копейки от округления
                Participant creditor = byId.get(c.getKey());
                // 4. Конвертируем в валюту получателя
                BigDecimal inPreferred = fromBase(amount, creditor.preferredCurrency())
                        .setScale(2, RoundingMode.HALF_UP);
                result.add(new Transfer(
                        byId.get(d.getKey()).name(), creditor.name(),
                        inPreferred, creditor.preferredCurrency()));
            }

            BigDecimal dLeft = d.getValue().subtract(amount);
            BigDecimal cLeft = c.getValue().subtract(amount);
            d = dLeft.signum() > 0 ? Map.entry(d.getKey(), dLeft) : debtors.poll();
            c = cLeft.signum() > 0 ? Map.entry(c.getKey(), cLeft) : creditors.poll();
        }
        return result;
    }

    /**
     * Переводит сумму траты в базовую валюту.
     * Приоритет — у замороженного курса из момента /spend; если его нет
     * (трата записана до назначения курса), берём актуальный курс.
     */
    private BigDecimal toBase(BigDecimal amount, String currency, BigDecimal frozenRate) {
        BigDecimal rate = frozenRate != null ? frozenRate : ratesToBase.get(currency);
        if (rate == null) throw new IllegalArgumentException("Нет курса для " + currency
                + " — добавь командой /rate " + currency + " <курс к " + baseCurrency + ">");
        return amount.multiply(rate);
    }

    private BigDecimal fromBase(BigDecimal amountInBase, String currency) {
        BigDecimal rate = ratesToBase.get(currency);
        if (rate == null) throw new IllegalArgumentException("Нет курса для " + currency
                + " — добавь командой /rate " + currency + " <курс к " + baseCurrency + ">");
        return amountInBase.divide(rate, 10, RoundingMode.HALF_UP);
    }
}
