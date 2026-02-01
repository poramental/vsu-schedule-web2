package com.vsu_schedule.telegram_service.command_strategy;

import com.vsu_schedule.telegram_service.botapi.annotation.chatstate.ClearChatState;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
@NoArgsConstructor
public class TelegramCommandStrategyImpl implements TelegramCommandStrategy{

    private final Map<String, Function<Message, BotApiMethod<?>>> routes = new HashMap<>();

    private String command = "";

    @Override
    public TelegramCommandStrategy command(String command) {
        this.command = command;
        return this;
    }

    @Override
    public TelegramCommandStrategy setHandler(Function<Message, BotApiMethod<?>> callback) {
        routes.put(command, callback);
        return this;
    }

    @Override
    @ClearChatState
    public BotApiMethod<?> handleMessageCommand(Message message) {
        String botCommand = message.getText();
        if(routes.get(botCommand) != null) {
            return routes.get(botCommand).apply(message);
        } return null;
    }


}
