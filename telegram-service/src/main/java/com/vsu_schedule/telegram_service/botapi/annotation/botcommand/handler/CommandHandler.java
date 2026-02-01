package com.vsu_schedule.telegram_service.botapi.annotation.botcommand.handler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandHandler {
    Class<?> value(); // Принимает класс, помеченный @BotCommand
}
