package megamek.server;

import megamek.server.commands.*;

import java.util.Enumeration;
import java.util.Hashtable;

public class CommandHash {
    // commands
    private Hashtable<String, ServerCommand> commandsHash = new Hashtable<>();

    /**
     * Registers new commands in the server command table
     */
    public void registerCommands(Server server) {
        registerCommand(new DefeatCommand(server));
        registerCommand(new ExportListCommand(server));
        registerCommand(new FixElevationCommand(server));
        registerCommand(new HelpCommand(server));
        registerCommand(new KickCommand(server));
        registerCommand(new ListSavesCommand(server));
        registerCommand(new LocalSaveGameCommand(server));
        registerCommand(new LocalLoadGameCommand(server));
        registerCommand(new ResetCommand(server));
        registerCommand(new RollCommand(server));
        registerCommand(new SaveGameCommand(server));
        registerCommand(new LoadGameCommand(server));
        registerCommand(new SeeAllCommand(server));
        registerCommand(new SkipCommand(server));
        registerCommand(new VictoryCommand(server));
        registerCommand(new WhoCommand(server));
        registerCommand(new TeamCommand(server));
        registerCommand(new ShowTileCommand(server));
        registerCommand(new ShowEntityCommand(server));
        registerCommand(new RulerCommand(server));
        registerCommand(new ShowValidTargetsCommand(server));
        registerCommand(new AddBotCommand(server));
        registerCommand(new CheckBVCommand(server));
        registerCommand(new CheckBVTeamCommand(server));
        registerCommand(new NukeCommand(server));
        registerCommand(new TraitorCommand(server));
        registerCommand(new ListEntitiesCommand(server));
        registerCommand(new AssignNovaNetServerCommand(server));
        registerCommand(new AllowTeamChangeCommand(server));
        registerCommand(new JoinTeamCommand(server));
    }

    /**
     * Registers a new command in the server command table
     */
    private void registerCommand(ServerCommand command) {
        commandsHash.put(command.getName(), command);
    }

    /**
     * Returns the command associated with the specified name
     */
    public ServerCommand getCommand(String name) {
        return commandsHash.get(name);
    }

    /**
     * Returns an enumeration of all the command names
     */
    public Enumeration<String> getAllCommandNames() {
        return commandsHash.keys();
    }

    /**
     * Process an in-game command
     */
    public void processCommand(int connId, String commandString) {
        String[] args;
        String commandName;
        // all tokens are read as strings; if they're numbers, string-ize 'em.
        // replaced the tokenizer with the split function.
        args = commandString.split("\\s+");

        // figure out which command this is
        commandName = args[0].substring(1);

        // process it
        ServerCommand command = getCommand(commandName);
        if (command != null) {
            command.run(connId, args);
        } else {
            // TODO (Sam): Quick fix not beautifull
            Server.getServerInstance().sendServerChat(connId, "Command not recognized.  Type /help for a list of commands.");
        }
    }


}
