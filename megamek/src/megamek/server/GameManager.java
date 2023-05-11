package megamek.server;

import megamek.client.ui.swing.util.PlayerColour;
import megamek.common.*;

import java.util.*;

public class GameManager {
    /**
     * Keeps track of what team a player requested to join.
     */
    private int requestedTeam = IPlayer.TEAM_NONE;

    public int getRequestedTeam() {
        return requestedTeam;
    }

    public void setRequestedTeam(int requestedTeam) {
        this.requestedTeam = requestedTeam;
    }

    public IPlayer getPlayerChangingTeam() {
        return playerChangingTeam;
    }

    public void setPlayerChangingTeam(IPlayer playerChangingTeam) {
        this.playerChangingTeam = playerChangingTeam;
    }

    public boolean isChangePlayersTeam() {
        return changePlayersTeam;
    }

    public void setChangePlayersTeam(boolean changePlayersTeam) {
        this.changePlayersTeam = changePlayersTeam;
    }

    /**
     * Keeps track of what player made a request to change teams.
     */
    private IPlayer playerChangingTeam = null;

    /**
     * Flag that is set to true when all players have voted to allow another
     * player to change teams.
     */
    private boolean changePlayersTeam = false;

    public Set<Coords> getHexUpdateSet() {
        return hexUpdateSet;
    }

    public void setHexUpdateSet(Set<Coords> hexUpdateSet) {
        this.hexUpdateSet = hexUpdateSet;
    }

    /**
     * Stores a set of <code>Coords</code> that have changed during this phase.
     */
    private Set<Coords> hexUpdateSet = new LinkedHashSet<>();

    /**
     * Checks to see if Flawed Cooling is triggered and generates a report of
     * the result.
     *
     * @param reason
     * @param entity
     * @return
     */
    public Vector<Report> doFlawedCoolingCheck(String reason, Entity entity) {
        Vector<Report> out = new Vector<>();
        Report r = new Report(9800);
        r.addDesc(entity);
        r.add(reason);
        int roll = Compute.d6(2);
        r.add(roll);
        out.add(r);
        if (roll >= 10) {
            Report s = new Report(9805);
            ((Mech) entity).setCoolingFlawActive(true);
            out.add(s);
        }

        return out;
    }





}
