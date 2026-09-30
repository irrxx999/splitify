package splitify;

import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SplitifyBot extends TelegramLongPollingBot {

    private static final String HELP = """
            💸 Splitify — бот подсчёта долгов в путешествии

            /newtrip <название> <валюта> — начать поездку
                напр.: /newtrip Таиланд EUR
            /add <имя> <валюта> — добавить участника
                (валюта — в какой ему возвращать долги)
                напр.: /add Аня RUB
            /rate <валюта> <курс> — курс к базовой валюте
                напр.: /rate THB 0.026  (1 THB = 0.026 EUR)
            /spend — записать трату по шагам, кнопками
                (/cancel — отменить)
            /spend <кто> <сумма> <валюта> [описание] [| имена]
                напр.: /spend Аня 3000 THB ужин
                (без списка — делится на всех)
                напр.: /spend Аня 3000 THB ужин | Петя, Маша
                (делится только на перечисленных)
            /list — список трат
            /total — итог: кто, кому и сколько
                (после /close показывает зафиксированный итог)
            /close — закрыть поездку и зафиксировать итог
            /paid <номер> — отметить перевод оплаченным
            /forgive <номер> — простить долг""";

    public SplitifyBot(DefaultBotOptions options, String token) {
        super(options, token);
    }

    @Override
    public String getBotUsername() {
        return "splitify_travel_bot";
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            onCallback(update.getCallbackQuery());
            return;
        }
        if (!update.hasMessage() || !update.getMessage().hasText()) return;
        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText().trim();
        // В группах с темами отвечаем в ту же тему, откуда пришла команда.
        Integer threadId = update.getMessage().getMessageThreadId();
        Long userId = update.getMessage().getFrom() == null ? null : update.getMessage().getFrom().getId();

        // Обычный текст (не команда) — возможно, ответ в диалоге /spend.
        if (!text.startsWith("/")) {
            if (userId != null) onDialogText(chatId, threadId, userId, text);
            return;
        }

        // Команда адресована другому боту (/help@other_bot) — молчим.
        String[] p = text.split("\\s+");
        String first = p[0];
        int at = first.indexOf('@');
        if (at > 0 && !first.substring(at + 1).equalsIgnoreCase(getBotUsername())) return;
        String cmd = (at > 0 ? first.substring(0, at) : first).toLowerCase();

        String reply;
        try {
            if (cmd.equals("/spend") && p.length == 1 && userId != null) {
                startSpendDialog(chatId, threadId, userId); // /spend без параметров — диалог с кнопками
                return;
            }
            if (cmd.equals("/cancel")) {
                reply = drafts.remove(draftKey(chatId, userId)) != null ? "Отменила 👌" : "Нечего отменять.";
            } else {
                reply = handle(chatId, text);
            }
        } catch (Exception e) {
            reply = "⚠️ " + e.getMessage();
        }
        send(chatId, threadId, reply);
    }

    private String handle(long chatId, String text) {
        String[] p = text.split("\\s+");
        String cmd = p[0].toLowerCase();
        int at = cmd.indexOf('@'); // в группах команды приходят как /total@SplitifyBot
        if (at > 0) cmd = cmd.substring(0, at);

        return switch (cmd) {
            case "/start", "/help" -> HELP;
            case "/newtrip" -> newTrip(chatId, p);
            case "/add" -> addParticipant(chatId, p);
            case "/rate" -> setRate(chatId, p);
            case "/spend" -> spend(chatId, p);
            case "/list" -> list(chatId);
            case "/total" -> total(chatId);
            case "/close" -> close(chatId);
            case "/paid" -> setStatus(chatId, p, "paid", "✅ Отметила оплату");
            case "/forgive" -> setStatus(chatId, p, "forgiven", "🕊 Долг прощён");
            default -> "Не знаю такую команду 🤔 /help — список команд";
        };
    }

    // ---------- Команды ----------

    private String newTrip(long chatId, String[] p) {
        if (p.length < 3) return "Формат: /newtrip <название> <валюта>\nНапример: /newtrip Таиланд EUR";
        String base = Currency.parse(p[p.length - 1]).name();
        String name = String.join(" ", List.of(p).subList(1, p.length - 1));
        Db.newTrip(chatId, name, base);
        return "🌍 Поездка «" + name + "» создана! Базовая валюта: " + base +
                "\n\nТеперь добавь участников: /add <имя> <валюта>";
    }

    private String addParticipant(long chatId, String[] p) {
        Db.Trip trip = trip(chatId);
        if (p.length != 3) return "Формат: /add <имя> <валюта возврата>\nНапример: /add Аня RUB";
        String name = p[1];
        String cur = Currency.parse(p[2]).name();

        boolean exists = Db.participants(trip.id()).stream()
                .anyMatch(x -> x.name().equalsIgnoreCase(name));
        if (exists) return "Участник " + name + " уже есть в поездке.";

        Db.addParticipant(trip.id(), name, cur);
        String warn = needsRate(trip, cur)
                ? "\n\n⚠️ Не забудь задать курс: /rate " + cur + " <курс к " + trip.base() + ">"
                : "";
        return "✅ " + name + " в игре! Долги получает в " + cur + warn;
    }

    private String setRate(long chatId, String[] p) {
        Db.Trip trip = trip(chatId);
        if (p.length != 3) return "Формат: /rate <валюта> <курс к " + trip.base() + ">\nНапример: /rate THB 0.026";
        String cur = Currency.parse(p[1]).name();
        BigDecimal rate;
        try {
            rate = new BigDecimal(p[2].replace(',', '.'));
        } catch (NumberFormatException e) {
            return "Не понимаю курс «" + p[2] + "» — нужно число, например 0.026";
        }
        if (rate.signum() <= 0) return "Курс должен быть больше нуля 🙂";
        int frozen = Db.setRate(trip.id(), cur, rate);
        String note = frozen > 0
                ? "\n🔒 Зафиксировала этот курс в " + frozen + " " + spendWord(frozen)
                    + " — прошлые долги теперь не сдвинутся."
                : "";
        return "💱 Запомнила: 1 " + cur + " = " + rate.toPlainString() + " " + trip.base() + note;
    }

    /** «трате / тратах / тратах» — согласование числительного. */
    private String spendWord(int n) {
        int n100 = n % 100, n10 = n % 10;
        if (n10 == 1 && n100 != 11) return "трате";
        return "тратах";
    }

    private String spend(long chatId, String[] p) {
        Db.Trip trip = trip(chatId);
        if (p.length < 4) return "Формат: /spend <кто> <сумма> <валюта> [описание]\nНапример: /spend Аня 3000 THB ужин";

        var participants = Db.participants(trip.id());
        if (participants.isEmpty()) return "Сначала добавь участников: /add <имя> <валюта>";

        String payerName = p[1];
        var payer = participants.stream()
                .filter(x -> x.name().equalsIgnoreCase(payerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Не знаю участника «" + payerName + "». Сейчас в поездке: " + names(participants)));

        BigDecimal amount;
        try {
            amount = new BigDecimal(p[2].replace(',', '.'));
        } catch (NumberFormatException e) {
            return "Не понимаю сумму «" + p[2] + "»";
        }
        if (amount.signum() <= 0) return "Сумма должна быть больше нуля 🙂";
        String cur = Currency.parse(p[3]).name();
        String rest = p.length > 4 ? String.join(" ", List.of(p).subList(4, p.length)) : "";

        // Необязательный список участников после «|»: /spend Аня 3000 THB ужин | Петя, Маша
        List<SettlementService.Participant> sharers = participants;
        int bar = rest.indexOf('|');
        if (bar >= 0) {
            String namesPart = rest.substring(bar + 1).trim();
            rest = rest.substring(0, bar).trim();
            if (namesPart.isEmpty()) return "После «|» перечисли, на кого делим: | Петя, Маша";

            sharers = new java.util.ArrayList<>();
            for (String n : namesPart.split("[,\\s]+")) {
                if (n.isEmpty()) continue;
                var found = participants.stream()
                        .filter(x -> x.name().equalsIgnoreCase(n))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Не знаю участника «" + n + "». Сейчас в поездке: " + names(participants)));
                if (!sharers.contains(found)) sharers.add(found);
            }
        }
        String desc = rest.isEmpty() ? "без описания" : rest;
        return saveExpense(trip, participants, payer, amount, cur, desc, sharers);
    }

    /** Записывает трату и формирует подтверждение. Общий код для текстовой команды и диалога с кнопками. */
    private String saveExpense(Db.Trip trip, List<SettlementService.Participant> participants,
                               SettlementService.Participant payer, BigDecimal amount, String cur,
                               String desc, List<SettlementService.Participant> sharers) {
        // Замораживаем курс в момент траты: базовая валюта = 1, иначе — текущий курс (может быть null).
        BigDecimal rateAtTime = cur.equals(trip.base())
                ? BigDecimal.ONE
                : Db.rates(trip.id()).get(cur);

        List<Long> shareIds = sharers.stream().map(SettlementService.Participant::id).toList();
        Db.addExpense(trip.id(), payer.id(), amount, cur, desc, rateAtTime, shareIds);

        String warn = needsRate(trip, cur)
                ? "\n⚠️ Для " + cur + " ещё нет курса — задай его до /total: /rate " + cur + " <курс>"
                : "";
        return "📝 Записала: " + payer.name() + " — " + amount.toPlainString() + " " + cur +
                " (" + desc + "), " + (sharers.size() == participants.size()
                        ? "делим на всех (" + sharers.size() + ")"
                        : "делим на: " + names(sharers)) + warn;
    }

    private String list(long chatId) {
        Db.Trip trip = trip(chatId);
        var expenses = Db.expenseList(trip.id());
        if (expenses.isEmpty()) return "Трат пока нет. Записывай: /spend <кто> <сумма> <валюта> [описание]";

        StringBuilder sb = new StringBuilder("🧾 Траты в поездке «" + trip.name() + "»:\n\n");
        int i = 1;
        for (var e : expenses) {
            sb.append(i++).append(". ").append(e.payer()).append(" — ")
              .append(e.amount().toPlainString()).append(" ").append(e.currency())
              .append(" (").append(e.description()).append(")\n");
        }
        return sb.toString();
    }

    private String total(long chatId) {
        // Пока поездка активна — считаем вживую (предварительный итог).
        var active = Db.activeTrip(chatId);
        if (active.isPresent()) {
            Db.Trip trip = active.get();
            var expenses = Db.expenses(trip.id());
            if (expenses.isEmpty()) return "Трат пока нет — считать нечего 🙂";

            var service = new SettlementService(trip.base(), Db.rates(trip.id()));
            var transfers = service.settle(Db.participants(trip.id()), expenses);
            if (transfers.isEmpty()) return "🎉 Все в расчёте, никто никому не должен!";

            StringBuilder sb = new StringBuilder("💰 Предварительный итог «" + trip.name() + "»:\n\n");
            for (var t : transfers) {
                sb.append(t.from()).append(" → ").append(t.to()).append(": ")
                  .append(t.amount().toPlainString()).append(" ").append(t.currency()).append("\n");
            }
            sb.append("\n(зафиксируется при /close)");
            return sb.toString();
        }

        // Активной нет — показываем зафиксированный итог последней поездки.
        var closed = Db.lastClosedTrip(chatId);
        if (closed.isEmpty()) return "Сначала создай поездку: /newtrip <название> <валюта>";
        var settlements = Db.settlements(closed.get().id());
        if (settlements.isEmpty()) return "🎉 В поездке «" + closed.get().name() + "» все были в расчёте.";

        StringBuilder sb = new StringBuilder("💰 Итог поездки «" + closed.get().name() + "» (зафиксирован):\n\n");
        appendSettlements(sb, settlements);
        sb.append("\nОтметить: /paid <номер> · простить: /forgive <номер>");
        return sb.toString();
    }

    private String close(long chatId) {
        Db.Trip trip = trip(chatId);
        var participants = Db.participants(trip.id());
        var expenses = Db.expenses(trip.id());

        var service = new SettlementService(trip.base(), Db.rates(trip.id()));
        List<SettlementService.Transfer> transfers = expenses.isEmpty()
                ? List.of()
                : service.settle(participants, expenses); // бросит, если нет курса — поездка не закроется

        Db.saveSettlements(trip.id(), transfers);
        Db.closeTrip(trip.id());

        StringBuilder sb = new StringBuilder("✈️ Поездка «" + trip.name() + "» закрыта!\n\n");
        if (transfers.isEmpty()) {
            sb.append("🎉 Все в расчёте, никто никому не должен!");
        } else {
            sb.append("💰 Итог зафиксирован:\n\n");
            appendSettlements(sb, Db.settlements(trip.id()));
            int naive = participants.size() * (participants.size() - 1) / 2;
            if (naive > transfers.size()) {
                sb.append("\n🎯 Свёл всё к ").append(transfers.size()).append(" ")
                  .append(transferWord(transfers.size())).append(" вместо ").append(naive).append("!");
            }
            sb.append("\n\nОтметить оплату: /paid <номер> · простить: /forgive <номер>");
        }
        sb.append("\n\nНовая поездка: /newtrip");
        return sb.toString();
    }

    private String setStatus(long chatId, String[] p, String status, String verb) {
        var closed = Db.lastClosedTrip(chatId);
        if (closed.isEmpty()) return "Нет закрытых поездок — итог фиксируется при /close.";
        var settlements = Db.settlements(closed.get().id());
        if (settlements.isEmpty()) return "В последней поездке долгов не было — отмечать нечего 🙂";
        if (p.length != 2) return "Формат: " + p[0] + " <номер>\nНомер бери из /total";

        int n;
        try {
            n = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return "Нужен номер перевода из /total, например " + p[0] + " 1";
        }
        if (n < 1 || n > settlements.size())
            return "Нет перевода №" + n + " — в итоге их " + settlements.size() + ". Смотри /total";

        var target = settlements.get(n - 1);
        Db.setSettlementStatus(target.id(), status);

        StringBuilder sb = new StringBuilder(verb + ": " + target.from() + " → " + target.to()
                + " " + target.amount().toPlainString() + " " + target.currency() + "\n\n");
        appendSettlements(sb, Db.settlements(closed.get().id()));
        return sb.toString();
    }

    // ---------- Диалог /spend с кнопками ----------
    // Шаги: кто платил → валюта → сумма и описание (текстом) → на кого делим → запись.
    // Состояние хранится в памяти: у каждого человека в чате свой черновик (ключ чат+пользователь).

    private enum Step { PAYER, CURRENCY, AMOUNT, SHARERS }

    private static final class Draft {
        Step step = Step.PAYER;
        long tripId;
        Integer threadId;              // тема группы, где начат диалог
        int messageId;              // сообщение с кнопками, на которое сейчас можно нажимать
        Long payerId;
        String currency;
        BigDecimal amount;
        String description;
        final Set<Long> sharers = new LinkedHashSet<>();
    }

    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();

    private static String draftKey(long chatId, Long userId) {
        return chatId + ":" + userId;
    }

    private void startSpendDialog(long chatId, Integer threadId, long userId) {
        Db.Trip trip = trip(chatId);
        var participants = Db.participants(trip.id());
        if (participants.isEmpty()) {
            send(chatId, threadId, "Сначала добавь участников: /add <имя> <валюта>");
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (var p : participants) buttons.add(btn(p.name(), "sp:p:" + p.id()));

        Draft d = new Draft();
        d.tripId = trip.id();
        d.threadId = threadId;
        d.messageId =sendWithKeyboard(chatId, threadId, "💳 Кто платил?", keyboard(buttons, 2));
        drafts.put(draftKey(chatId, userId), d);
    }

    private void onCallback(CallbackQuery q) {
        String data = q.getData();
        if (data == null || !data.startsWith("sp:") || q.getMessage() == null) return;
        long chatId = q.getMessage().getChatId();
        String key = draftKey(chatId, q.getFrom().getId());

        Draft d = drafts.get(key);
        if (d == null || d.messageId != q.getMessage().getMessageId()) {
            answer(q, "Эти кнопки чужие или устарели. Начни свои: /spend", true);
            return;
        }
        try {
            synchronized (d) {
                handleClick(chatId, key, d, data.substring(3));
            }
            answer(q, null, false);
        } catch (Exception e) {
            answer(q, "⚠️ " + e.getMessage(), true);
        }
    }

    private void handleClick(long chatId, String key, Draft d, String action) {
        String[] a = action.split(":", 2);
        if (a[0].equals("x")) {
            drafts.remove(key);
            edit(chatId, d.messageId, "Отменила 👌", null);
            return;
        }
        Db.Trip trip = tripFor(chatId, d, key);
        var participants = Db.participants(trip.id());

        switch (a[0]) {
            case "p" -> {
                requireStep(d, Step.PAYER);
                d.payerId = Long.parseLong(a[1]);
                d.step = Step.CURRENCY;

                java.util.Set<String> codes = new LinkedHashSet<>();
                codes.add(trip.base());
                codes.addAll(Db.rates(trip.id()).keySet());
                participants.forEach(p -> codes.add(p.preferredCurrency()));
                List<InlineKeyboardButton> buttons = new ArrayList<>();
                for (String code : codes) buttons.add(btn(code, "sp:c:" + code));

                edit(chatId, d.messageId, "💳 Платил(а): " + nameOf(participants, d.payerId)
                        + "\nВ какой валюте?", keyboard(buttons, 3));
            }
            case "c" -> {
                requireStep(d, Step.CURRENCY);
                d.currency = Currency.parse(a[1]).name();
                d.step = Step.AMOUNT;
                edit(chatId, d.messageId, "💳 Платил(а): " + nameOf(participants, d.payerId)
                        + ", валюта: " + d.currency, null);
                send(chatId, d.threadId, AMOUNT_PROMPT);
            }
            case "t" -> {
                requireStep(d, Step.SHARERS);
                long id = Long.parseLong(a[1]);
                if (!d.sharers.remove(id)) d.sharers.add(id);
                editKeyboard(chatId, d.messageId, sharersKeyboard(participants, d));
            }
            case "ok" -> {
                requireStep(d, Step.SHARERS);
                if (d.sharers.isEmpty()) throw new IllegalStateException("Отметь хотя бы одного участника");
                var payer = participants.stream().filter(p -> p.id() == d.payerId).findFirst().orElseThrow();
                var sharers = participants.stream().filter(p -> d.sharers.contains(p.id())).toList();
                String reply = saveExpense(trip, participants, payer, d.amount, d.currency, d.description, sharers);
                drafts.remove(key);
                edit(chatId, d.messageId, reply, null);
            }
            default -> { }
        }
    }

    private static final String AMOUNT_PROMPT = """
            ✍️ Напиши сумму и, если хочешь, описание — ответом (Reply) на это сообщение:
            3000 ужин
            Другая валюта: 3000 THB ужин
            Отмена: /cancel""";

    /** Ответ текстом на шаге «сумма и описание». */
    private void onDialogText(long chatId, Integer threadId, long userId, String text) {
        String key = draftKey(chatId, userId);
        Draft d = drafts.get(key);
        if (d == null) return;
        synchronized (d) {
            if (d.step != Step.AMOUNT) return;
            try {
                tripFor(chatId, d, key);
                String[] t = text.split("\\s+");
                BigDecimal amount;
                try {
                    amount = new BigDecimal(t[0].replace(',', '.'));
                } catch (NumberFormatException e) {
                    send(chatId, threadId, "Не понимаю сумму «" + t[0] + "» 🤔\n\n" + AMOUNT_PROMPT);
                    return;
                }
                if (amount.signum() <= 0) {
                    send(chatId, threadId, "Сумма должна быть больше нуля 🙂\n\n" + AMOUNT_PROMPT);
                    return;
                }
                String cur = d.currency;
                int from = 1;
                if (t.length > 1) { // второе слово может быть валютой: «3000 THB ужин»
                    try {
                        cur = Currency.parse(t[1]).name();
                        from = 2;
                    } catch (IllegalArgumentException ignored) { }
                }
                String desc = String.join(" ", List.of(t).subList(Math.min(from, t.length), t.length));

                d.amount = amount;
                d.currency = cur;
                d.description = desc.isBlank() ? "без описания" : desc;
                d.step = Step.SHARERS;

                var participants = Db.participants(d.tripId);
                participants.forEach(p -> d.sharers.add(p.id()));
                d.messageId = sendWithKeyboard(chatId, threadId,
                        "👥 На кого делим? " + nameOf(participants, d.payerId) + " платил(а) "
                                + amount.toPlainString() + " " + cur + " (" + d.description + ")",
                        sharersKeyboard(participants, d));
            } catch (Exception e) {
                send(chatId, threadId, "⚠️ " + e.getMessage());
            }
        }
    }

    private InlineKeyboardMarkup sharersKeyboard(List<SettlementService.Participant> participants, Draft d) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (var p : participants) {
            buttons.add(btn((d.sharers.contains(p.id()) ? "☑ " : "☐ ") + p.name(), "sp:t:" + p.id()));
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 2) {
            rows.add(new ArrayList<>(buttons.subList(i, Math.min(i + 2, buttons.size()))));
        }
        rows.add(List.of(btn("✅ Готово", "sp:ok"), btn("❌ Отмена", "sp:x")));
        return new InlineKeyboardMarkup(rows);
    }

    private static InlineKeyboardMarkup keyboard(List<InlineKeyboardButton> buttons, int perRow) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += perRow) {
            rows.add(new ArrayList<>(buttons.subList(i, Math.min(i + perRow, buttons.size()))));
        }
        rows.add(List.of(btn("❌ Отмена", "sp:x")));
        return new InlineKeyboardMarkup(rows);
    }

    private static InlineKeyboardButton btn(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton(text);
        b.setCallbackData(data);
        return b;
    }

    private static void requireStep(Draft d, Step expected) {
        if (d.step != expected) throw new IllegalStateException("Этот шаг уже пройден");
    }

    private Db.Trip tripFor(long chatId, Draft d, String key) {
        Db.Trip trip = trip(chatId);
        if (trip.id() != d.tripId) {
            drafts.remove(key);
            throw new IllegalStateException("Поездка изменилась — начни заново: /spend");
        }
        return trip;
    }

    private static String nameOf(List<SettlementService.Participant> participants, long id) {
        return participants.stream().filter(p -> p.id() == id).map(SettlementService.Participant::name)
                .findFirst().orElse("?");
    }

    private int sendWithKeyboard(long chatId, Integer threadId, String text, InlineKeyboardMarkup markup) {
        try {
            SendMessage msg = new SendMessage(String.valueOf(chatId), text);
            if (threadId != null) msg.setMessageThreadId(threadId);
            msg.setReplyMarkup(markup);
            return execute(msg).getMessageId();
        } catch (TelegramApiException e) {
            throw new RuntimeException("Не удалось отправить сообщение: " + e.getMessage(), e);
        }
    }

    private void edit(long chatId, int messageId, String text, InlineKeyboardMarkup markup) {
        try {
            EditMessageText em = new EditMessageText();
            em.setChatId(String.valueOf(chatId));
            em.setMessageId(messageId);
            em.setText(text);
            em.setReplyMarkup(markup);
            execute(em);
        } catch (TelegramApiException e) {
            System.err.println("Не удалось изменить сообщение: " + e.getMessage());
        }
    }

    private void editKeyboard(long chatId, int messageId, InlineKeyboardMarkup markup) {
        try {
            EditMessageReplyMarkup em = new EditMessageReplyMarkup();
            em.setChatId(String.valueOf(chatId));
            em.setMessageId(messageId);
            em.setReplyMarkup(markup);
            execute(em);
        } catch (TelegramApiException e) {
            System.err.println("Не удалось обновить кнопки: " + e.getMessage());
        }
    }

    private void answer(CallbackQuery q, String text, boolean alert) {
        try {
            AnswerCallbackQuery a = new AnswerCallbackQuery(q.getId());
            a.setText(text);
            a.setShowAlert(alert);
            execute(a);
        } catch (TelegramApiException e) {
            System.err.println("Не удалось ответить на нажатие: " + e.getMessage());
        }
    }

    // ---------- Вспомогательное ----------

    private Db.Trip trip(long chatId) {
        return Db.activeTrip(chatId).orElseThrow(() ->
                new IllegalStateException("Сначала создай поездку: /newtrip <название> <валюта>"));
    }

    private boolean needsRate(Db.Trip trip, String currency) {
        return !currency.equals(trip.base()) && !Db.rates(trip.id()).containsKey(currency);
    }

    private String names(List<SettlementService.Participant> list) {
        return list.stream().map(SettlementService.Participant::name).collect(Collectors.joining(", "));
    }

    /** Нумерованный список переводов со статусами. */
    private void appendSettlements(StringBuilder sb, List<Db.Settlement> settlements) {
        int i = 1;
        for (var s : settlements) {
            sb.append(i++).append(". ").append(s.from()).append(" → ").append(s.to())
              .append(": ").append(s.amount().toPlainString()).append(" ").append(s.currency());
            if ("paid".equals(s.status())) sb.append("  ✅ оплачено");
            else if ("forgiven".equals(s.status())) sb.append("  🕊 прощён");
            sb.append("\n");
        }
    }

    /** «переводу / переводам» — дательный падеж. */
    private String transferWord(int n) {
        return (n % 10 == 1 && n % 100 != 11) ? "переводу" : "переводам";
    }

    private void send(long chatId, Integer threadId, String text) {
        try {
            SendMessage msg = new SendMessage(String.valueOf(chatId), text);
            if (threadId != null) msg.setMessageThreadId(threadId);
            execute(msg);
        } catch (TelegramApiException e) {
            System.err.println("Не удалось отправить сообщение: " + e.getMessage());
        }
    }
}
