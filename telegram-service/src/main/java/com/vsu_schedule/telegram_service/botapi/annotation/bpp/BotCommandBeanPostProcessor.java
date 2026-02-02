package com.vsu_schedule.telegram_service.botapi.annotation.bpp;

import com.vsu_schedule.telegram_service.botapi.annotation.botcommand.BotCommand;
import com.vsu_schedule.telegram_service.botapi.annotation.botcommand.handler.CommandHandler;
import com.vsu_schedule.telegram_service.command_strategy.TelegramCommandStrategy;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BotCommandBeanPostProcessor implements BeanPostProcessor {

    private final TelegramCommandStrategy strategy;
    private final TelegramClient telegramClient;
    private final Map<Class<?>, String> classToCommandName = new HashMap<>();
    private final List<org.telegram.telegrambots.meta.api.objects.commands.BotCommand> telegramMeta = new ArrayList<>();
    private final Map<Class<?>, HandlerInfo> pendingHandlers = new HashMap<>();

    public BotCommandBeanPostProcessor(TelegramCommandStrategy strategy, TelegramClient telegramClient) {
        this.strategy = strategy;
        this.telegramClient = telegramClient;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, @NotNull String beanName) {
        Class<?> beanClass = bean.getClass();

        if (beanClass.isAnnotationPresent(BotCommand.class)) {
            BotCommand ann = beanClass.getAnnotation(BotCommand.class);
            classToCommandName.put(beanClass, ann.commandName());

            telegramMeta.add(new org.telegram.telegrambots.meta.api.objects.commands.BotCommand(
                    ann.commandName().replace("/", ""),
                    ann.description()
            ));
        }

        ReflectionUtils.doWithMethods(beanClass, method -> {
            if (method.isAnnotationPresent(CommandHandler.class)) {
                Class<?> targetClass = method.getAnnotation(CommandHandler.class).value();
                pendingHandlers.put(targetClass, new HandlerInfo(bean, method));
            }
        });

        return bean;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onApplicationEvent() {
        pendingHandlers.forEach((cmdClass, info) -> {
            String name = classToCommandName.get(cmdClass);
            if (name != null) {
                strategy.command(name).setHandler(message -> {
                    try {
                        info.method.setAccessible(true);
                        return (BotApiMethod<?>) info.method.invoke(info.bean, message);
                    } catch (Exception e) {
                        throw new RuntimeException("Execution failed for " + name, e);
                    }
                });
            }
        });

        if (!telegramMeta.isEmpty()) {
            try {
                telegramClient.execute(new SetMyCommands(telegramMeta));
                System.out.println("Telegram API: Команды зарегистрированы автоматически.");
            } catch (Exception e) {
                System.err.println("Telegram API Error: " + e.getMessage());
            }
        }


        if (!telegramMeta.isEmpty()) {
            try {
                telegramClient.execute(new SetMyCommands(telegramMeta));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }




        private static class HandlerInfo {
            private final Object bean;
            private final Method method;

            public HandlerInfo(Object bean, Method method) {
                this.bean = bean;
                this.method = method;
            }
        }
    }






