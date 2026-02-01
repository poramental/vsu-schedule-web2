package com.vsu_schedule.telegram_service.botapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
@RequiredArgsConstructor
public class TelegramClientConfig {

    private final BotConfig botConfig;

    @Bean
    public TelegramClient getTelegramClient() {
        return new OkHttpTelegramClient(botConfig.getBotToken());
    }
}

