package megamek.server;

import megamek.client.ui.swing.util.PlayerColour;
import megamek.common.Coords;
import megamek.common.Game;
import megamek.common.IGame;
import megamek.common.IPlayer;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

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


}
