package com.vsu_schedule.telegram_service.botapi.config;

import com.vsu_schedule.telegram_service.botapi.command.HelpCommand;
import com.vsu_schedule.telegram_service.botapi.command.RegisterCommand;
import com.vsu_schedule.telegram_service.botapi.command.ScheduleCommand;
import com.vsu_schedule.telegram_service.botapi.command.StartCommand;
import com.vsu_schedule.telegram_service.botapi.service.BotMessageCommandService;
import com.vsu_schedule.telegram_service.commandchain.TelegramCommandStrategy;
import com.vsu_schedule.telegram_service.commandchain.TelegramCommandStrategyImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class TelegramCommandStrategyConfigurer {

    private final BotMessageCommandService botMessageCommandService;

    @Bean
    public TelegramCommandStrategy configure() {
        return new TelegramCommandStrategyImpl()
                .command(HelpCommand.getCommandName()).setCallback(botMessageCommandService::handleHelpCommand)
                .command(StartCommand.getCommandName()).setCallback(botMessageCommandService::handleStartCommand)
                .command(RegisterCommand.getCommandName()).setCallback(botMessageCommandService::handleRegisterCommand)
                .command(ScheduleCommand.getCommandName()).setCallback(botMessageCommandService::handleScheduleCommand);
    }

}
