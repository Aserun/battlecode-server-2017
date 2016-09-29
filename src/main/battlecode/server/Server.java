package battlecode.server;

import battlecode.common.GameConstants;
import battlecode.common.Team;
import battlecode.world.*;
import battlecode.world.control.*;
import com.google.flatbuffers.FlatBufferBuilder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Runs matches. Specifically, this class forms a pipeline connecting match and
 * configuration parameters to the game engine and engine output to an abstract
 * match data sink.
 */
public class Server implements Runnable {
    /**
     * The GameInfo that signals the server to terminate when it is encountered on the game queue.
     */
    private static final GameInfo POISON = new GameInfo(null, null, null, null, null, null, false) {};

    /**
     * The current spec version the server compiles with
     */
    private static final String SPEC_VERSION = "1.0";

    /**
     * The queue of games to run.
     * When the server encounters the GameInfo POISON, it terminates.
     */
    private final BlockingQueue<GameInfo> gameQueue;

    /**
     * The state of the match that the server is running (or about to run).
     */
    private State state;

    /**
     * The round number to run until.
     */
    private int runUntil;

    /**
     * The options provided to the server via config file and command line.
     */
    private final Config options;

    /**
     * Whether to wait for notifications to control match run state, or to just
     * run all matches immediately.
     */
    private final boolean interactive;

    /**
     * The GameWorld the server is currently operating on.
     */
    private GameWorld currentWorld;


    /**
     * The server's mode affects how notifications are handled, whether or not
     * an RPC server is set up, and which controllers are chosen for server
     * operation.
     */
    public enum Mode {
        HEADLESS,
        LOCAL,
        SCRIMMAGE,
        TOURNAMENT
    }

    /**
     * Initializes a new server.
     *
     * @param options the configuration to use
     * @param interactive whether to wait for notifications to control the
     *                    match run state
     */
    public Server(Config options, boolean interactive) {
        this.gameQueue = new LinkedBlockingQueue<>();

        this.interactive = interactive;

        this.options = options;
        this.state = State.NOT_READY;
    }

    // ******************************
    // ***** NOTIFICATIONS **********
    // ******************************

    public void startNotification(){
        state = State.READY;
    }

    public void pauseNotification(){
        state = State.PAUSED;
    }

    public void resumeNotification(){
        if (state == State.PAUSED){
            state = State.RUNNING;
        }
    }

    public void runNotification(){
        if (state != State.PAUSED) {
            state = State.RUNNING;
        }
    }

    public void addGameNotification(GameInfo gameInfo){
        this.gameQueue.add(gameInfo);
    }

    public void terminateNotification(){
        this.gameQueue.add(POISON);
    }

    // ******************************
    // ***** SIMULATION METHODS *****
    // ******************************

    /**
     * Runs the server. The server will wait for some game info (which
     * specifies the teams and set of maps to run) and then begin running
     * matches.
     */
    public void run() {
        // Note that this loop only runs once on the client.
        // Running it multiple times may break things.
        while (true) {
            final GameInfo currentGame;
            try {
                currentGame = gameQueue.take();
            } catch (InterruptedException e) {
                warn("Interrupted while waiting for next game!");
                e.printStackTrace();
                Thread.currentThread().interrupt();
                return;
            }

            // Note: ==, not .equals()
            if (currentGame == POISON) {
                debug("Shutting down server");
                return;
            }

            GameMaker gameMaker = new GameMaker();
            TeamMapping teamMapping = new TeamMapping(currentGame);
            gameMaker.makeGameHeader(SPEC_VERSION, teamMapping); // TODO: Write Game Header

            debug("Running: "+currentGame);

            // Set up our control provider
            final RobotControlProvider prov = createControlProvider(currentGame);

            // We start with zeroed team memories.
            long[][] teamMemory = new long[2][GameConstants.TEAM_MEMORY_LENGTH];

            // Count wins
            int aWins = 0, bWins = 0;

            // Loop through the maps in the current game
            for (int matchIndex = 0; matchIndex < currentGame.getMaps().length; matchIndex++) {

                Team winner;
                try {
                    winner = runMatch(currentGame, matchIndex, prov, teamMemory, teamMapping, gameMaker);
                } catch (Exception e) {
                    ErrorReporter.report(e);
                    this.state = State.ERROR;
                    return;
                }

                switch (winner) {
                    case A:
                        aWins++;
                        break;
                    case B:
                        bWins++;
                        break;
                    default:
                        warn("Team "+winner+" won???");
                }

                teamMemory = currentWorld.getTeamInfo().getTeamMemory();
                currentWorld = null;

                if (currentGame.isBestOfThree()) {
                    if (aWins == 2 || bWins == 2) {
                        break;
                    }
                }
            }
            byte winner = aWins >= bWins ? teamMapping.getTeamAID() : teamMapping.getTeamBID();
            gameMaker.makeGameFooter(winner);
            gameMaker.makeGameWrapper();
            gameMaker.writeGame(currentGame.getSaveFile()); // TODO: Write flatbuffer to file
        }
    }


    /**
     * @return the winner of the match
     * @throws Exception if the match fails to run for some reason
     */
    private Team runMatch(GameInfo currentGame,
                          int matchIndex,
                          RobotControlProvider prov,
                          long[][] teamMemory,
                          TeamMapping teamMapping,
                          GameMaker gameMaker) throws Exception {

        final String mapName = currentGame.getMaps()[matchIndex];

        // Load the map for the match
        final GameMap loadedMap;
        try {
            loadedMap = GameMapIO.loadMap(mapName, new File(options.get("bc.game.map-path")), teamMapping);
            debug("running map " + loadedMap);
        } catch (IOException e) {
            warn("Couldn't load map " + mapName + ", skipping");
            throw e;
        }

        // Create the game world!
        currentWorld = new GameWorld(loadedMap, prov, teamMapping, teamMemory, gameMaker.getBuilder());


        // Get started
        if (interactive) {
            // TODO necessary?
            // Poll for RUNNING, if we're in interactive mode
            while (!State.RUNNING.equals(state)) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {}
            }
        } else {
            // Start the game immediately if we're not in interactive mode
            this.state = State.RUNNING;
            this.runUntil = Integer.MAX_VALUE;
        }


        // Print an
        long startTime = System.currentTimeMillis();
        say("-------------------- Match Starting --------------------");
        say(String.format("%s vs. %s on %s", currentGame.getTeamA(), currentGame.getTeamB(), mapName));

        // Used to count throttles
        int count = 0;

        final String throttle = options.get("bc.server.throttle");
        final int throttleCount = options.getInt("bc.server.throttle-count");
        final boolean doYield = "yield".equals(throttle);
        final boolean doSleep = "sleep".equals(throttle);

        // If there are more rounds to be run, run them and
        // and send the round (and optionally stats) bytes to
        // recipients.
        while (currentWorld.isRunning()) {

            // If not paused/stopped:
            switch (this.state) {

                case RUNNING:

                    if (currentWorld.getCurrentRound() + 1 == runUntil) {
                        Thread.sleep(25);
                        break;
                    }

                    GameState state = currentWorld.runRound();

                    if (GameState.BREAKPOINT.equals(state)) {
                        this.state = State.PAUSED;
                    } else if (GameState.DONE.equals(state)) {
                        this.state = State.FINISHED;
                        break;
                    }

                    if (count++ == throttleCount) {
                        if (doYield)
                            Thread.yield();
                        else if (doSleep)
                            Thread.sleep(1);
                        count = 0;
                    }

                    break;

                case PAUSED:
                    Thread.sleep(250);
                    break;
            }
        }

        say(getWinnerString(currentGame, currentWorld.getWinner(), currentWorld.getCurrentRound()));
        say("-------------------- Match Finished --------------------");

        double timeDiff = (System.currentTimeMillis() - startTime) / 1000.0;
        debug(String.format("match completed in %.4g seconds", timeDiff));

        this.state = State.FINISHED;

        // Add match info to game info for flatbuffer
        gameMaker.addMatchInfo(currentWorld.getMatchMaker().getEvents());

        return currentWorld.getWinner();
    }

    // ******************************
    // ***** CREATOR METHODS ********
    // ******************************

    /**
     * Create a RobotControlProvider for a game.
     *
     * @param game the game to provide control for
     * @return a fresh control provider for the game
     */
    private RobotControlProvider createControlProvider(GameInfo game) {
        // Strictly speaking, this should probably be somewhere in battlecode.world
        // Whatever

        final TeamControlProvider teamProvider = new TeamControlProvider();

        teamProvider.registerControlProvider(
                Team.A,
                new PlayerControlProvider(game.getTeamA(), game.getTeamAClasses())
        );
        teamProvider.registerControlProvider(
                Team.B,
                new PlayerControlProvider(game.getTeamB(), game.getTeamBClasses())
        );
        teamProvider.registerControlProvider(
                Team.NEUTRAL,
                new NullControlProvider()
        );

        return teamProvider;
    }

    // ******************************
    // ***** GETTER METHODS *********
    // ******************************

    /**
     * @return the state of the game
     */
    public State getState() {
        return this.state;
    }

    /**
     * Produces a string for the winner of the match.
     *
     * @return A string representing the match's winner.
     */
    public String getWinnerString(GameInfo game, Team winner, int roundNumber) {

        String teamName;

        switch (winner) {
            case A:
                teamName = game.getTeamA() + " (A)";
                break;

            case B:
                teamName = game.getTeamB() + " (B)";
                break;

            default:
                teamName = "nobody";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < (50 - teamName.length()) / 2; i++)
            sb.append(' ');
        sb.append(teamName);
        sb.append(" wins (round ").append(roundNumber).append(")");

        sb.append("\nReason: ");
        GameStats stats = currentWorld.getGameStats();
        DominationFactor dom = stats.getDominationFactor();

        switch (dom) {
            case DESTROYED:
                sb.append("The winning team won by destruction.");
                break;
            case PWNED:
                sb.append("The winning team won on tiebreakers (more Archons remaining).");
                break;
            case OWNED:
                sb.append("The winning team won on tiebreakers (more Archon health).");
                break;
            case BARELY_BEAT:
                sb.append("The winning team won on tiebreakers (more Parts)");
                break;
            case WON_BY_DUBIOUS_REASONS:
                sb.append("The winning team won arbitrarily.");
                break;
        }

        return sb.toString();
    }

    /**
     * @return whether we are actively running a match
     */
    public boolean isRunningMatch() {
        return currentWorld != null && currentWorld.isRunning();
    }


    // ******************************
    // ***** CONSOLE MESSAGES *******
    // ******************************


    /**
     * This method is used to display warning messages with formatted output.
     *
     * @param msg the warning message to display
     */
    public static void warn(String msg) {
        for (String line : msg.split("\n")) {
            System.out.printf("[server:warning] %s\n", line);
        }
    }

    /**
     * This method is used to display "official" formatted messages from the
     * server.
     *
     * @param msg the message to display
     */
    public static void say(String msg) {
        for (String line : msg.split("\n")) {
            System.out.printf("[server] %s\n", line);
        }

    }

    /**
     * This method is used to display debugging messages with formatted output.
     *
     * @param msg the debug message to display
     */
    public void debug(String msg) {
        if (options.getBoolean("bc.server.debug")) {
            for (String line : msg.split("\n")) {
                System.out.printf("[server:debug] %s\n", line);
            }
        }
    }
}
