package com.vsu_schedule.telegram_service.botapi.command;

import com.vsu_schedule.telegram_service.botapi.annotation.botcommand.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@BotCommand(
    commandName = "/help",
    description = "Помощь"
)
public class HelpCommand implements Command {



    public String getAnswer(Message message) {
        return "Вот список доступных:\n" +
                "/help - Помощь\n" +
                "/start - Стартовая команда\n" +
                "/register - Регистрация\n" +
                "/schedule - Расписание\n";

    }

    public static String getCommandName() {
        return "/help";
    }

}