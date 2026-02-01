package com.vsu_schedule.telegram_service.commandchain;

import com.vsu_schedule.telegram_service.botapi.cache.LastMessageIdCache;
import com.vsu_schedule.telegram_service.botapi.cache.SendPhotoMessageIdCache;
import com.vsu_schedule.telegram_service.botapi.cache.TeacherSessionStore;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;


@NoArgsConstructor
public class TelegramCommandStrategyImpl implements TelegramCommandStrategy{

    private final Map<String, Function<Message, BotApiMethod<?>>> map = new HashMap<>();

    @Autowired
    private LastMessageIdCache lastMessageIdCache;

    @Autowired
    private SendPhotoMessageIdCache sendPhotoMessageIdCache;

    @Autowired
    private TeacherSessionStore teacherSessionStore;

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
    public BotApiMethod<?> handleMessageCommand(Message message) {
        String botCommand = message.getText();
        if(teacherSessionStore.get(message.getFrom().getId()) != null) {
            teacherSessionStore.remove(message.getFrom().getId());
        }
        lastMessageIdCache.deleteLastMessageKeyboard(message.getChatId());
        sendPhotoMessageIdCache.deleteLastSendPhotoMessage(message.getChatId());
        if(map.get(botCommand) != null) {
            return map.get(botCommand).apply(message);
        } return null;
    }


}
