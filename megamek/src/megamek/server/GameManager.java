package megamek.server;

import megamek.MegaMek;
import megamek.client.ui.swing.util.PlayerColour;
import megamek.common.*;
import megamek.common.actions.EntityAction;
import megamek.common.net.Packet;
import megamek.common.util.StringUtil;
import megamek.common.util.fileUtils.MegaMekFile;

import java.io.File;
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

    public void send(Packet p) {
        Server.getServerInstance().send(p);
    }

    public void send(int connId, Packet p) {
        Server.getServerInstance().send(connId, p);
    }

    /**
     * Creates a packet detailing the removal of an entity. Maintained for
     * backwards compatibility.
     *
     * @param entityId - the <code>int</code> ID of the entity being removed.
     * @return A <code>Packet</code> to be sent to clients.
     */
    public Packet createRemoveEntityPacket(int entityId) {
        return createRemoveEntityPacket(entityId, IEntityRemovalConditions.REMOVE_SALVAGEABLE);
    }

    /**
     * Creates a packet detailing the removal of an entity.
     *
     * @param entityId  - the <code>int</code> ID of the entity being removed.
     * @param condition - the <code>int</code> condition the unit was in. This value
     *                  must be one of constants in
     *                  <code>IEntityRemovalConditions</code>, or an
     *                  <code>IllegalArgumentException</code> will be thrown.
     * @return A <code>Packet</code> to be sent to clients.
     */
    public Packet createRemoveEntityPacket(int entityId, int condition) {
        List<Integer> ids = new ArrayList<>(1);
        ids.add(entityId);
        return createRemoveEntityPacket(ids, condition);
    }

    /**
     * Creates a packet detailing the removal of a list of entities.
     *
     * @param entityIds - the <code>int</code> ID of each entity being removed.
     * @param condition - the <code>int</code> condition the units were in. This value
     *                  must be one of constants in
     *                  <code>IEntityRemovalConditions</code>, or an
     *                  <code>IllegalArgumentException</code> will be thrown.
     * @return A <code>Packet</code> to be sent to clients.
     */
    public Packet createRemoveEntityPacket(List<Integer> entityIds, int condition) {
        if ((condition != IEntityRemovalConditions.REMOVE_UNKNOWN)
                && (condition != IEntityRemovalConditions.REMOVE_IN_RETREAT)
                && (condition != IEntityRemovalConditions.REMOVE_PUSHED)
                && (condition != IEntityRemovalConditions.REMOVE_SALVAGEABLE)
                && (condition != IEntityRemovalConditions.REMOVE_EJECTED)
                && (condition != IEntityRemovalConditions.REMOVE_CAPTURED)
                && (condition != IEntityRemovalConditions.REMOVE_DEVASTATED)
                && (condition != IEntityRemovalConditions.REMOVE_NEVER_JOINED)) {
            throw new IllegalArgumentException("Unknown unit condition: " + condition);
        }
        Object[] array = new Object[2];
        array[0] = entityIds;
        array[1] = condition;
        return new Packet(Packet.COMMAND_ENTITY_REMOVE, array);
    }

    // TODO (Sam): set this in a board class somewhere
    /**
     * Scans the boards directory for map boards of the appropriate size and
     * returns them.
     *
     * @return A list of relative paths to the board files, without the '.board'
     * extension.
     */
    private List<String> scanForBoardsInDir(final File boardDir, final String basePath,
                                            final BoardDimensions dimensions, List<String> boards) {
        if (boardDir == null) {
            throw new IllegalArgumentException("must provide searchDir");
        } else if (basePath == null) {
            throw new IllegalArgumentException("must provide basePath");
        } else if (dimensions == null) {
            throw new IllegalArgumentException("must provide dimensions");
        } else if (boards == null) {
            throw new IllegalArgumentException("must provide boards");
        }

        String[] fileList = boardDir.list();
        if (fileList != null) {
            for (String filename : fileList) {
                File filePath = new MegaMekFile(boardDir, filename).getFile();
                if (filePath.isDirectory()) {
                    scanForBoardsInDir(new MegaMekFile(boardDir, filename).getFile(),
                            basePath.concat(File.separator).concat(filename), dimensions, boards);
                } else {
                    if (filename.endsWith(".board")) { //$NON-NLS-1$
                        if (Board.boardIsSize(filePath, dimensions)) {
                            boards.add(basePath.concat(File.separator)
                                    .concat(filename.substring(0, filename.lastIndexOf("."))));
                        }
                    }
                }
            }
        }
        return boards;
    }

    /**
     * Get a list of the available board sizes from the boards data directory.
     *
     * @return A Set containing all the available board sizes.
     */
    public Set<BoardDimensions> getBoardSizes() {
        TreeSet<BoardDimensions> board_sizes = new TreeSet<>();

        File boards_dir = Configuration.boardsDir();
        // Slightly overkill sanity check...
        if (boards_dir.isDirectory()) {
            getBoardSizesInDir(boards_dir, board_sizes);
        }
        boards_dir = new File(Configuration.userdataDir(), Configuration.boardsDir().toString());
        if (boards_dir.isDirectory()) {
            getBoardSizesInDir(boards_dir, board_sizes);
        }

        return board_sizes;
    }

    /**
     * Recursively scan the specified path to determine the board sizes
     * available.
     *
     * @param searchDir The directory to search below this path (may be null for all
     *                  in base path).
     * @param sizes     Where to store the discovered board sizes
     */
    public void getBoardSizesInDir(final File searchDir, TreeSet<BoardDimensions> sizes) {
        if (searchDir == null) {
            throw new IllegalArgumentException("must provide searchDir");
        }

        if (sizes == null) {
            throw new IllegalArgumentException("must provide sizes");
        }

        String[] file_list = searchDir.list();

        if (file_list != null) {
            for (String filename : file_list) {
                File query_file = new File(searchDir, filename);

                if (query_file.isDirectory()) {
                    getBoardSizesInDir(query_file, sizes);
                } else {
                    try {
                        if (filename.endsWith(".board")) { //$NON-NLS-1$
                            BoardDimensions size = Board.getSize(query_file);
                            if (size == null) {
                                throw new Exception();
                            }
                            sizes.add(Board.getSize(query_file));
                        }
                    } catch (Exception e) {
                        MegaMek.getLogger().error("Error parsing board: " + query_file.getAbsolutePath(), e);
                    }
                }
            }
        }
    }

    // TODO (Sam): set this in a board class somewhere
    /**
     * Scan for map boards with the specified dimensions.
     *
     * @param dimensions The desired board dimensions.
     * @return A list of path names, minus the '.board' extension, relative to
     * the boards data directory.
     */
    public ArrayList<String> scanForBoards(final BoardDimensions dimensions) {
        ArrayList<String> boards = new ArrayList<>();

        File boardDir = Configuration.boardsDir();
        boards.add(MapSettings.BOARD_GENERATED);
        // just a check...
        if (!boardDir.isDirectory()) {
            return boards;
        }

        // scan files
        List<String> tempList = new ArrayList<>();
        Comparator<String> sortComp = StringUtil.stringComparator();
        scanForBoardsInDir(boardDir, "", dimensions, tempList);
        // Check boards in userData dir
        boardDir = new File(Configuration.userdataDir(), Configuration.boardsDir().toString());
        if (boardDir.isDirectory()) {
            scanForBoardsInDir(boardDir, "", dimensions, tempList);
        }
        // if there are any boards, add these:
        if (tempList.size() > 0) {
            boards.add(MapSettings.BOARD_RANDOM);
            boards.add(MapSettings.BOARD_SURPRISE);
            tempList.sort(sortComp);
            boards.addAll(tempList);
        }

        return boards;
    }



    public void sendSmokeCloudAdded(SmokeCloud cloud) {
        final Object[] data = new Object[1];
        data[0] = cloud;
        send(new Packet(Packet.COMMAND_ADD_SMOKE_CLOUD, data));
    }

    /**
     * Creates a packet containing a hex, and the coordinates it goes at.
     */
    public Packet createHexChangePacket(Coords coords, IHex hex) {
        final Object[] data = new Object[2];
        data[0] = coords;
        data[1] = hex;
        return new Packet(Packet.COMMAND_CHANGE_HEX, data);
    }

    /**
     * Creates a packet containing a hex, and the coordinates it goes at.
     */
    public Packet createHexesChangePacket(Set<Coords> coords, Set<IHex> hex) {
        final Object[] data = new Object[2];
        data[0] = coords;
        data[1] = hex;
        return new Packet(Packet.COMMAND_CHANGE_HEXES, data);
    }

    public void sendVisibilityIndicator(Entity e) {
        final Object[] data = new Object[6];
        data[0] = e.getId();
        data[1] = e.isEverSeenByEnemy();
        data[2] = e.isVisibleToEnemy();
        data[3] = e.isDetectedByEnemy();
        data[4] = e.getWhoCanSee();
        data[5] = e.getWhoCanDetect();
        send(new Packet(Packet.COMMAND_ENTITY_VISIBILITY_INDICATOR, data));
    }

    /**
     * Creates a packet for an attack
     */
    public Packet createAttackPacket(List<?> vector, int charges) {
        final Object[] data = new Object[2];
        data[0] = vector;
        data[1] = charges;
        return new Packet(Packet.COMMAND_ENTITY_ATTACK, data);
    }

    /**
     * Creates a packet for an attack
     */
    public Packet createAttackPacket(EntityAction ea, int charge) {
        Vector<EntityAction> vector = new Vector<>(1);
        vector.addElement(ea);
        Object[] data = new Object[2];
        data[0] = vector;
        data[1] = charge;
        return new Packet(Packet.COMMAND_ENTITY_ATTACK, data);
    }






}
