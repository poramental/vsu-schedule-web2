package com.vsu_schedule.telegram_service.commandchain;

import com.vsu_schedule.telegram_service.botapi.annotation.chatstate.ClearChatState;
import lombok.NoArgsConstructor;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;


@NoArgsConstructor
public class TelegramCommandStrategyImpl implements TelegramCommandStrategy{

    private final Map<String, Function<Message, BotApiMethod<?>>> map = new HashMap<>();

    private String command = "";

    @Override
    public TelegramCommandStrategy command(String command) {
        this.command = command;
        return this;
    }

    @Override
    public TelegramCommandStrategy setCallback(Function<Message, BotApiMethod<?>> callback) {
        map.put(command, callback);
        return this;
    }

    @Override
    @ClearChatState
    public BotApiMethod<?> handleMessageCommand(Message message) {
        String botCommand = message.getText();
        if(map.get(botCommand) != null) {
            return map.get(botCommand).apply(message);
        } return null;
    }


}
