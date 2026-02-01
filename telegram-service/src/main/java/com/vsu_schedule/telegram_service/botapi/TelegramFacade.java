package com.vsu_schedule.telegram_service.botapi;

import com.vsu_schedule.telegram_service.botapi.handler.BotCallbackQueryHandler;
import com.vsu_schedule.telegram_service.commandchain.TelegramCommandStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramFacade {

    private final BotCallbackQueryHandler callbackQueryHandler;
    private final TelegramCommandStrategy TelegramCommandStrategy;

    public BotApiMethod<?> handleUpdate(Update req) {
        if (req.hasMessage()) {
            Message message = req.getMessage();
            if (message.getText().toCharArray()[0] == '/') {
                return TelegramCommandStrategy.handleMessageCommand(message);
            }
        }
        if (req.hasCallbackQuery()) {
            log.info("req.hasCallbackQuery");
            return callbackQueryHandler.handle(req.getCallbackQuery());
        }
        return null;
    }
}
