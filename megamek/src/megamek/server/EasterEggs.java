package megamek.server;

public class EasterEggs {
    // Easter eggs. Happy April Fool's Day!!
    private static final String DUNE_CALL = "They tried and failed?";
    private static final String DUNE_RESPONSE = "They tried and died!";
    private static final String STAR_WARS_CALL = "I'd just as soon kiss a Wookiee.";
    private static final String STAR_WARS_RESPONSE = "I can arrange that!";
    private static final String INVADER_ZIM_CALL = "What does the G stand for?";
    private static final String INVADER_ZIM_RESPONSE = "I don't know.";
    private static final String WARGAMES_CALL = "Shall we play a game?";
    private static final String WARGAMES_RESPONSE = "Let's play global thermonuclear war.";

    public static String sendEasterEgg(String chat) {
        // Easter eggs. Happy April Fool's Day!!
        String response = "";
        if (DUNE_CALL.equalsIgnoreCase(chat)) {
            response = DUNE_RESPONSE;
        } else if (STAR_WARS_CALL.equalsIgnoreCase(chat)) {
            response = STAR_WARS_RESPONSE;
        } else if (INVADER_ZIM_CALL.equalsIgnoreCase(chat)) {
            response = INVADER_ZIM_RESPONSE;
        } else if (WARGAMES_CALL.equalsIgnoreCase(chat)) {
            response = WARGAMES_RESPONSE;
        }
        return response;
    }

}
