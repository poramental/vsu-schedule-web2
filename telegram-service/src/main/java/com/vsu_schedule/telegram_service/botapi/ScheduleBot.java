package com.vsu_schedule.telegram_service.botapi;


import com.vsu_schedule.telegram_service.botapi.cache.LastMessageIdCache;
import com.vsu_schedule.telegram_service.botapi.cache.SendPhotoMessageIdCache;
import com.vsu_schedule.telegram_service.botapi.command.HelpCommand;
import com.vsu_schedule.telegram_service.botapi.command.RegisterCommand;
import com.vsu_schedule.telegram_service.botapi.command.ScheduleCommand;
import com.vsu_schedule.telegram_service.botapi.command.StartCommand;
import com.vsu_schedule.telegram_service.botapi.config.BotConfig;
import com.vsu_schedule.telegram_service.botapi.event.TelegramActionEvent;
import com.vsu_schedule.telegram_service.botapi.event.TelegramSendPhotoEvent;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Setter
@Getter
@Slf4j
@PropertySource("application.yaml")
@Component
public class ScheduleBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {


    private List<BotCommand> botCommandList = new ArrayList<>();
    @Autowired
    private TelegramFacade telegramFacade;

    @Autowired
    private SendPhotoMessageIdCache sendPhotoMessageIdCache;

    @Autowired
    private LastMessageIdCache lastMessageIdCache;

    private final BotConfig botConfig;

    @Autowired
    private TelegramClient telegramClient;

    public ScheduleBot(BotConfig botConfig) {
        this.botConfig = botConfig;
    }

    @EventListener
    public void onTelegramActionEvent(TelegramActionEvent event) {
        try {
            telegramClient.execute(event.getMethod());
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
        }
    }
    @EventListener
    public void onTelegramSendPhotoEvent(TelegramSendPhotoEvent event) {
        try {
            Message message = telegramClient.execute(event.getMethod());
            sendPhotoMessageIdCache.put(message.getChatId(),message.getMessageId());
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public String getBotToken() {
        return botConfig.getBotToken();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            BotApiMethod<?> apiMethod = telegramFacade.handleUpdate(update);
            if (Objects.nonNull(apiMethod)) {
                Message message =((Message)telegramClient.execute(apiMethod));
                lastMessageIdCache.put(message.getChatId(),message.getMessageId());
            }
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
        }
    }
}
