package com.vsu_schedule.telegram_service.botapi.command;

import com.vsu_schedule.telegram_service.botapi.annotation.botcommand.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@BotCommand(
        commandName = "/register",
        description = "регистрация"
)
public class RegisterCommand implements Command {



    public String getAnswer(Message message) {
        return "Выберете свой факультет";
    }

    public static String getCommandName() {
        return "/register";
    }
}