package com.vsu_schedule.telegram_service.botapi.annotation.chatstate;

import com.vsu_schedule.telegram_service.botapi.cache.LastMessageIdCache;
import com.vsu_schedule.telegram_service.botapi.cache.SendPhotoMessageIdCache;
import com.vsu_schedule.telegram_service.botapi.cache.TeacherSessionStore;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@Aspect
@Component
@RequiredArgsConstructor
public class ChatStateAspect {

    private final LastMessageIdCache lastMessageIdCache;
    private final SendPhotoMessageIdCache sendPhotoMessageIdCache;
    private final TeacherSessionStore teacherSessionStore;

    @Before("@annotation(ClearChatState) && args(message)")
    public void clearState(Message message) {
        Long userId = message.getFrom().getId();
        Long chatId = message.getChatId();

        if (teacherSessionStore.get(userId) != null) {
            teacherSessionStore.remove(userId);
        }
        lastMessageIdCache.deleteLastMessageKeyboard(chatId);
        sendPhotoMessageIdCache.deleteLastSendPhotoMessage(chatId);
    }
}