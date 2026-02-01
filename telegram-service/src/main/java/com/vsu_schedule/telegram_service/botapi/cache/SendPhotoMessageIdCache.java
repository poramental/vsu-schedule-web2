package com.vsu_schedule.telegram_service.botapi.cache;

import com.vsu_schedule.telegram_service.botapi.event.TelegramActionEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Component
@RequiredArgsConstructor
public class SendPhotoMessageIdCache {

    private final ApplicationEventPublisher eventPublisher;

    private final Map<Long, Integer> storage = new ConcurrentHashMap<>();

    public void put(Long telegramId, Integer messageId) {
        storage.put(telegramId,messageId);
    }

    public void remove(Long chatId) {
        storage.remove(chatId);
    }

    public Integer get(Long chatId) {
        return storage.get(chatId);
    }

    public void deleteLastSendPhotoMessage(Long chatId) {
        Integer messageId = this.get(chatId);
        if(messageId != null) {
            DeleteMessage deleteMessage = DeleteMessage.builder()
                    .messageId(messageId)
                    .chatId(chatId)
                    .build();
            eventPublisher.publishEvent(new TelegramActionEvent(this, deleteMessage));
        }
    }
}
