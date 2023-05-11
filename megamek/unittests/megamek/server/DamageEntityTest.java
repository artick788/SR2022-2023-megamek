package megamek.server;

import junit.framework.TestCase;
import megamek.common.Entity;
import megamek.common.IPlayer;
import megamek.common.MechFileParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.io.File;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

@RunWith(JUnit4.class)
public class DamageEntityTest {

    private static Server m_Server;
    private static IPlayer m_Player;

    public DamageEntityTest() throws IOException {
        m_Server = new Server("Megamek", 1234);
        m_Player = m_Server.getGame().addNewPlayer(5, "NewPlayer1");
    }

    @Test
    public void testDamageEntity(){
        System.out.println("testDamageEntity");
    }
}
