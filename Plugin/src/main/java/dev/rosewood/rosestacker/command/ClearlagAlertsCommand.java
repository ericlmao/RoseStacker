package dev.rosewood.rosestacker.command;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.framework.BaseRoseCommand;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosestacker.manager.LocaleManager;
import dev.rosewood.rosestacker.manager.StackManager;

public class ClearlagAlertsCommand extends BaseRoseCommand {

    private final RosePlugin rosePlugin;

    public ClearlagAlertsCommand(RosePlugin rosePlugin) {
        super(rosePlugin);

        this.rosePlugin = rosePlugin;
    }

    @RoseExecutable
    public void execute(CommandContext context) {
        StackManager stackManager = this.rosePlugin.getManager(StackManager.class);
        LocaleManager localeManager = this.rosePlugin.getManager(LocaleManager.class);

        boolean enabled = stackManager.toggleClearLagAlerts();
        localeManager.sendMessage(context.getSender(), enabled ? "command-clearlagalerts-enabled" : "command-clearlagalerts-disabled");
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("clearlagalerts")
                .descriptionKey("command-clearlagalerts-description")
                .permission("rosestacker.clearlagalerts")
                .build();
    }

}
