package megamek.server;

import com.thoughtworks.xstream.XStream;
import megamek.MegaMek;
import megamek.common.IGame;
import megamek.common.net.IConnection;
import megamek.common.net.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.util.SerializationHelper;
import megamek.common.util.StringUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static megamek.server.Server.getServerInstance;

public class GameSaveLoader {
    IGame game;

    GameSaveLoader(IGame game) {
        this.game = game;
    }

    /**
     * automatically save the game
     */
    public void autoSave() {
        String fileName = "autosave";
        if (PreferenceManager.getClientPreferences().stampFilenames()) {
            fileName = StringUtil.addDateTimeStamp(fileName);
        }
        saveGame(fileName, game.getOptions().booleanOption(OptionsConstants.BASE_AUTOSAVE_MSG));
    }

    /**
     * save the game and send it to the specified connection
     *
     * @param connId     The <code>int</code> connection id to send to
     * @param sFile      The <code>String</code> filename to use
     * @param sLocalPath The <code>String</code> path to the file to be used on the
     *                   client
     */
    public void sendSaveGame(int connId, String sFile, String sLocalPath) {
        saveGame(sFile, false);
        String sFinalFile = sFile;
        if (!sFinalFile.endsWith(".sav.gz")) {
            if (sFinalFile.endsWith(".sav")) {
                sFinalFile = sFile + ".gz";
            } else {
                sFinalFile = sFile + ".sav.gz";
            }
        }
        sLocalPath = sLocalPath.replaceAll("\\|", " ");
        String localFile = "savegames" + File.separator + sFinalFile;
        try (InputStream in = new FileInputStream(localFile); InputStream bin = new BufferedInputStream(in)) {
            List<Integer> data = new ArrayList<>();
            int input;
            while ((input = bin.read()) != -1) {
                data.add(input);
            }
            getServerInstance().send(connId, new Packet(Packet.COMMAND_SEND_SAVEGAME, new Object[]{sFinalFile, data, sLocalPath}));
            getServerInstance().sendChat(connId, Server.ORIGIN, "Save game has been sent to you.");
        } catch (Exception e) {
            MegaMek.getLogger().error("Unable to load file: " + localFile, e);
        }
    }

    /**
     * save the game
     *
     * @param sFile    The <code>String</code> filename to use
     * @param sendChat A <code>boolean</code> value whether or not to announce the
     *                 saving to the server chat.
     */
    public void saveGame(String sFile, boolean sendChat) {
        // We need to strip the .gz if it exists,
        // otherwise we'll double up on it.
        if (sFile.endsWith(".gz")) {
            sFile = sFile.replace(".gz", "");
        }
        XStream xstream = new XStream();

        // This will make save games much smaller
        // by using a more efficient means of referencing
        // objects in the XML graph
        xstream.setMode(XStream.ID_REFERENCES);

        String sFinalFile = sFile;
        if (!sFinalFile.endsWith(".sav")) {
            sFinalFile = sFile + ".sav";
        }
        File sDir = new File("savegames");
        if (!sDir.exists()) {
            sDir.mkdir();
        }

        sFinalFile = sDir + File.separator + sFinalFile;

        try (OutputStream os = new FileOutputStream(sFinalFile + ".gz");
             OutputStream gzo = new GZIPOutputStream(os);
             Writer writer = new OutputStreamWriter(gzo, StandardCharsets.UTF_8)) {

            xstream.toXML(this, writer);
        } catch (Exception e) {
            MegaMek.getLogger().error("Unable to save file: " + sFinalFile, e);
        }

        if (sendChat) {
            getServerInstance().sendChat("MegaMek", "Game saved to " + sFinalFile);
        }
    }

    /**
     * save the game
     *
     * @param sFile The <code>String</code> filename to use
     */
    public void saveGame(String sFile) {
        saveGame(sFile, true);
    }

    /**
     * load the game
     *
     * @param f
     *            The <code>File</code> to load
     * @param sendInfo
     *            Determines whether the connections should be updated with
     *            current info. This may be false if some reconnection remapping
     *            needs to be done first.
     * @return A <code>boolean</code> value whether or not the loading was successful
     */
    public boolean loadGame(File f, boolean sendInfo) {
        MegaMek.getLogger().info("s: loading saved game file '" + f + "'");

        IGame newGame;
        try (InputStream is = new FileInputStream(f); InputStream gzi = new GZIPInputStream(is)) {
            XStream xstream = SerializationHelper.getXStream();
            newGame = (IGame) xstream.fromXML(gzi);
        } catch (Exception e) {
            MegaMek.getLogger().error("Unable to load file: " + f, e);
            return false;
        }

        getServerInstance().setGame(newGame);

        if (!sendInfo) {
            return true;
        }

        // update all the clients with the new game info
        Enumeration<IConnection> connectionsEnum = getServerInstance().getConnections();
        while (connectionsEnum.hasMoreElements()) {
            IConnection conn = connectionsEnum.nextElement();
            getServerInstance().sendCurrentInfo(conn.getId());
        }
        return true;
    }

    /**
     * load the game
     *
     * @param f The <code>File</code> to load
     * @return A <code>boolean</code> value whether or not the loading was successful
     */
    public boolean loadGame(File f) {
        return loadGame(f, true);
    }
}
