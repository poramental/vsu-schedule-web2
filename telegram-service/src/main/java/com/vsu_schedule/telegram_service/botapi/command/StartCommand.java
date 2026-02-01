package com.vsu_schedule.telegram_service.botapi.command;

import com.vsu_schedule.telegram_service.botapi.annotation.botcommand.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;


@BotCommand(
        commandName = "/start",
        description = "стартовая команда"
)
public class StartCommand implements Command {


    public String getAnswer(Message message) {
        return String.format("👋 Здравствуйте, %s!\n\n" +
                        "Рад вас видеть. Выберите нужную команду в меню, чтобы начать работу ⬇️",
                message.getFrom().getFirstName());
    }

    public static String getCommandName(){
        return "/start";
    }
}
