package megamek.server;

import junit.framework.TestCase;
import megamek.common.*;

import megamek.common.actions.AttackAction;
import megamek.common.actions.ChargeAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.net.Packet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Assert;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.*;
import java.io.File;

@RunWith(JUnit4.class)
public class PacketFactoryTest {

    @Test
    public void testCreateRemoveEntityPacket(){
        List<Integer> ids = new ArrayList<Integer>();
        ids.add(1);
        ids.add(2);
        ids.add(3);

        boolean exceptionThrown = false;
        try {
            Packet p = PacketFactory.createRemoveEntityPacket(ids, 0x0001); // some random number that is not part of the IEntityRemovalConditions interface
        } catch (IllegalArgumentException e) {
            exceptionThrown = true;
        }
        Assert.assertTrue(exceptionThrown);

        Packet p = PacketFactory.createRemoveEntityPacket(ids, IEntityRemovalConditions.REMOVE_CAPTURED);
        List<Integer> result = (List<Integer>) p.getData()[0];
        for (int i = 0; i < ids.size(); i++){
            Assert.assertEquals(ids.get(i), result.get(i));
        }
        Assert.assertEquals(IEntityRemovalConditions.REMOVE_CAPTURED, p.getData()[1]);
        Assert.assertEquals(p.getCommand(), Packet.COMMAND_ENTITY_REMOVE);
    }

    @Test
    public void testCreateHexChangePacket(){
        Coords c = new Coords(5, 2);
        Hex h = new Hex();
        h.setCoords(c);

        Packet p = PacketFactory.createHexChangePacket(c, h);
        Assert.assertEquals(p.getCommand(), Packet.COMMAND_CHANGE_HEX);
        Assert.assertEquals(c, p.getData()[0]);
        Assert.assertEquals(h, p.getData()[1]);
    }

    @Test
    public void testCreateHexesChangePacket(){
        Set<IHex> hexes = new HashSet<>();
        Set<Coords> coords = new HashSet<>();
        coords.add(new Coords(5, 2));
        coords.add(new Coords(1, 3));
        coords.add(new Coords(2, 4));
        for (Coords c : coords){
            Hex h = new Hex();
            h.setCoords(c);
            hexes.add(h);
        }

        Packet p = PacketFactory.createHexesChangePacket(coords, hexes);
        Assert.assertEquals(p.getCommand(), Packet.COMMAND_CHANGE_HEXES);
        Assert.assertEquals(coords, p.getData()[0]);
        Assert.assertEquals(hexes, p.getData()[1]);
    }

    @Test
    public void testCreateAttackEntityPacket(){

    }

    @Test
    public void testCreateCollapseBuildingPacket(){
        Coords c = new Coords(5, 2);
        Packet p = PacketFactory.createCollapseBuildingPacket(c); // single coord
        Assert.assertEquals(p.getCommand(), Packet.COMMAND_BLDG_COLLAPSE);
        Vector<Coords> coords = new Vector<>();
        coords.add(c);
        Assert.assertEquals(coords, p.getData()[0]);

    }

    @Test
    public void testCreateUpdateBuildingPacket(){

    }

    @Test
    public void testCreateGameSettingsPacket(){
        IGame game = new Game();

        Packet p = PacketFactory.createGameSettingsPacket(game);
        Assert.assertEquals(p.getCommand(), Packet.COMMAND_SENDING_GAME_SETTINGS);
        Assert.assertEquals(game.getOptions(), p.getData()[0]);
    }

    @Test
    public void testCreateBoardPacket(){
        IGame game = new Game();

        Packet p = PacketFactory.createBoardPacket(game);
        Assert.assertEquals(p.getCommand(), Packet.COMMAND_SENDING_BOARD);
        Assert.assertEquals(game.getBoard(), p.getData()[0]);
    }
}
