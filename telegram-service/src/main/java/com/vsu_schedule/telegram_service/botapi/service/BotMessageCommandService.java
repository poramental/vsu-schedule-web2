package com.vsu_schedule.telegram_service.botapi.service;


import com.vsu_schedule.telegram_service.botapi.annotation.botcommand.handler.CommandHandler;
import com.vsu_schedule.telegram_service.botapi.callback_query_types.FacultyCallbackQueryTypes;
import com.vsu_schedule.telegram_service.botapi.callback_query_types.ResetRegistrationCallbackQueryTypes;
import com.vsu_schedule.telegram_service.botapi.command.*;
import com.vsu_schedule.telegram_service.dto.LessonResponse;
import com.vsu_schedule.telegram_service.dto.ListLessonResponse;
import com.vsu_schedule.telegram_service.entity.BotUser;
import com.vsu_schedule.telegram_service.feign.LessonFeignClient;
import com.vsu_schedule.telegram_service.repository.BotUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.*;

import static com.vsu_schedule.telegram_service.botapi.keyboard.MessageKeyboards.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class BotMessageCommandService {

    private final BotUserRepository botUserRepository;

    private final LessonFeignClient lessonFeignClient;


    @CommandHandler(StartCommand.class)
    public BotApiMethod<?> handleStartCommand(Message message){
        String chatId = message.getChatId().toString();
        return new SendMessage(chatId,new StartCommand().getAnswer(message));
    }

    @CommandHandler(HelpCommand.class)
    public BotApiMethod<?> handleHelpCommand(Message message) {
        return new SendMessage(message.getChatId().toString(),new HelpCommand().getAnswer(message));
    }


    @CommandHandler(RegisterCommand.class)
    public BotApiMethod<?> handleRegisterCommand(Message message){
        String chatId = message.getChatId().toString();
        if(!botUserRepository.existsByTelegramId(message.getFrom().getId())){
            botUserRepository.save(new BotUser()
                    .setTelegramId(message.getFrom().getId())
                    .setChatId(message.getChatId())
                    .setUsername(message.getFrom().getUserName()));
            SendMessage sendMessage =  new SendMessage(chatId,new RegisterCommand().getAnswer(message));
            sendMessage.setReplyMarkup(getFacultiesInlineKeyboard());
            return sendMessage;

        }else {
            SendMessage sendMessage =  new SendMessage(chatId,"Вы уже были зарегистрированы. Хотите пройти регистрацию заново?");
            sendMessage.setReplyMarkup(getAnswersResetRegistrationInlineKeyboard());
            return sendMessage;
        }
    }

    @CommandHandler(ScheduleCommand.class)
    public BotApiMethod<?> handleScheduleCommand(Message message) {
        String chatId = message.getChatId().toString();
        Optional<BotUser> opt_user = botUserRepository.findByTelegramId(message.getFrom().getId());
        if(opt_user.isPresent()){
            BotUser user = opt_user.get();
            if(user.getGroupId() == null || user.getSubgroupId() == null)
                return new SendMessage(chatId, Command.getAnswerTextForUnregisteredUsers(message.getFrom().getFirstName()));
            ListLessonResponse listLessonResponse = lessonFeignClient.getLessonsByGroupAndSubgroup(user.getGroupId(),user.getSubgroupId());
            SendMessage sendMessage = new SendMessage(chatId,"Выберете день недели");
            sendMessage.setReplyMarkup(getDayOfWeekSelectInlineKeyboard(listLessonResponse));
            return sendMessage;
        }
        return new SendMessage(chatId, Command.getAnswerTextForUnregisteredUsers(message.getFrom().getUserName()));
    }



}
