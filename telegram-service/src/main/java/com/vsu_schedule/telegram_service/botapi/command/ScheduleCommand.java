package com.vsu_schedule.telegram_service.botapi.command;

import com.vsu_schedule.telegram_service.botapi.annotation.botcommand.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;


@BotCommand(
        commandName = "/schedule",
        description = "расписание"
)
public class ScheduleCommand implements Command {


    public String getAnswer(Message message) {
        return "Здравствуйте,"+message.getFrom().getFirstName()+"!\n"
                + "Выберете команду в меню.";
    }

    public static String getCommandName(){
        return "/schedule";
    }
}
