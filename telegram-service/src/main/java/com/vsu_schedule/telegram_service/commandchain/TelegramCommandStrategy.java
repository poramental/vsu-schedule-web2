package com.vsu_schedule.telegram_service.commandchain;

import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.function.Function;

public interface TelegramCommandStrategy {

    TelegramCommandStrategy command(String command);

    TelegramCommandStrategy setCallback(Function<Message, BotApiMethod<?>> callback);

    BotApiMethod<?> handleMessageCommand(Message message);
}
