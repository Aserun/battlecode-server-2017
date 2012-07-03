package battlecode.world;

import battlecode.common.Team;
import battlecode.engine.GenericRobot;
import battlecode.engine.signal.Signal;

import java.util.*;

public class BaseWorld<WorldObject extends BaseObject> {

    protected int currentRound;  // do we need this here?? -- yes
    protected boolean running = true;  // do we need this here?? -- yes
    protected boolean wasBreakpointHit = false;
    protected Team winner = null;
    protected final String teamAName;
    protected final String teamBName;
    protected final Random randGen;
    protected int nextID;
    protected final ArrayList<Signal> signals;
    protected final long[][] teamMemory;
    protected final long[][] oldTeamMemory;
    protected final Map<Integer, WorldObject> gameObjectsByID;
    protected final ArrayList<Integer> randomIDs = new ArrayList<Integer>();

    public BaseWorld(int seed, String teamA, String teamB, long[][] oldTeamMemory) {
        currentRound = -1;
        teamAName = teamA;
        teamBName = teamB;
        gameObjectsByID = new LinkedHashMap<Integer, WorldObject>();
        signals = new ArrayList<Signal>();
        randGen = new Random(seed);
        nextID = 1;
        teamMemory = new long[2][oldTeamMemory[0].length];
        this.oldTeamMemory = oldTeamMemory;
    }

    public void reserveRandomIDs(int num) {
        while (num > 0) {
            randomIDs.add(nextID++);
            num--;
        }
        Collections.shuffle(randomIDs, randGen);
    }

    public void endRandomIDs() {
        randomIDs.clear();
    }

    public int nextID() {
        if (randomIDs.isEmpty())
            return nextID++;
        else
            return randomIDs.remove(randomIDs.size() - 1);
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public String getTeamName(Team t) {
        switch (t) {
            case A:
                return teamAName;
            case B:
                return teamBName;
            case NEUTRAL:
                return "neutralplayer";
            default:
                return null;
        }
    }

    public Team getWinner() {
        return winner;
    }

    public boolean isRunning() {
        return running;
    }

    public WorldObject getObjectByID(int id) {
        return gameObjectsByID.get(id);
    }

    public GenericRobot getRobotByID(int id) {
        return (GenericRobot) getObjectByID(id);
    }

    public void addSignal(Signal s) {
        signals.add(s);
    }

    public void clearAllSignals() {
        signals.clear();
    }

    public Random getRandGen() {
        return randGen;
    }

    public void notifyBreakpoint() {
        wasBreakpointHit = true;
    }

    public boolean wasBreakpointHit() {
        return wasBreakpointHit;
    }

    public long[][] getTeamMemory() {
        return teamMemory;
    }

    public long[][] getOldTeamMemory() {
        return oldTeamMemory;
    }

    public void setTeamMemory(Team t, int ID, long state) {
        teamMemory[t.ordinal()][ID] = state;
    }

    public void setTeamMemory(Team t, int ID, long state, long mask) {
        long n = teamMemory[t.ordinal()][ID];
        n &= ~mask;
        n |= (state & mask);
        teamMemory[t.ordinal()][ID] = n;
    }
}
