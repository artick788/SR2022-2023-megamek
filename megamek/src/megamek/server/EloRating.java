package megamek.server;

import megamek.common.IGame;
import megamek.common.IPlayer;

import java.util.HashMap;
import java.util.Vector;

public class EloRating {
    // TODO (sam): Nog testen
    HashMap<IPlayer, Integer> elo;
    private void updateELO(IGame game) {
        Vector<IPlayer> winningPlayers = new Vector<>();
        Vector<IPlayer> losingPlayers = new Vector<>();
        if (game.getVictoryTeam() == IPlayer.TEAM_NONE) {
            // Individual victory
            IPlayer winning = game.getPlayer(game.getVictoryPlayerId());
            if (winning != null) {
                winningPlayers.add(winning);
                for (IPlayer player : game.getPlayersVector()) {
                    if (player != winning) {
                        losingPlayers.add(player);
                    }
                }
            } else {
                //Draw
            }
        } else {
            // Team victory
            int winningTeam = game.getVictoryTeam();
            Vector<IPlayer> players = game.getPlayersVector();
            for (IPlayer player : players) {
                if (game.getTeamForPlayer(player).getId() == winningTeam) {
                    winningPlayers.add(player);
                } else {
                    losingPlayers.add(player);
                }
            }
        }
        updateRatings(winningPlayers, losingPlayers);
    }

    private int getPlayerRating(IPlayer player) {
        // Retrieve player's rating from storage
        Integer rating = elo.get(player);
        if (rating == null) {
            elo.put(player, 0);
            return 0;
        }
        return rating;
    }

    public void updateRatings(Vector<IPlayer> winningPlayers, Vector<IPlayer> losingPlayers) {
        for (IPlayer player : winningPlayers) {
            int rating = getPlayerRating(player);
            elo.put(player, rating + 1);
        }

        for (IPlayer player : losingPlayers) {
            int rating = getPlayerRating(player);
            elo.put(player, rating - 1);
        }
    }
}
