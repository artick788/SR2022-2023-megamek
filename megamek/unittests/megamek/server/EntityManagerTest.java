package megamek.server;

import megamek.common.*;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.List;
import java.util.Vector;

@RunWith(JUnit4.class)
public class EntityManagerTest {
    private static Server m_Server;
    private static IPlayer m_Player;

    @BeforeClass
    public static void init() throws IOException {
        m_Server = new Server("MegaMek", 1234);
        m_Player = m_Server.getGame().addNewPlayer(5, "TestPlayer");
    }

    private List<Entity> createEntities(){
        // create all entity types (more or less)
        Aero a = new Aero();
        Infantry i = new Infantry();
        BipedMech bm = new BipedMech();
        Protomech pm = new Protomech();
        Tank t = new Tank();

        Vector<Entity> entities = new Vector<>();
        entities.add(a);
        entities.add(i);
        entities.add(bm);
        entities.add(pm);
        entities.add(t);

        for (Entity e : entities){
            e.setOwner(m_Player);
        }

        return entities;
    }

    @Test
    public void destroyEntityTest(){
        List<Entity> entities = createEntities();
        m_Server.getGame().addEntities(createEntities());

        EntityManager em = m_Server.getEntityManager();
        String reason = "Test";

        for (Entity e : entities){
            Vector<Report> reports = em.destroyEntity(e, reason);

            // first check reports
            assert(reports.size() == 1);
            Report r = reports.get(0);
            assert(r.messageId == 6365);

            assert(e.isDoomed());
        }
    }

    @Test
    public void processMovementTest(){
        Aero a = new Aero();
        Infantry i = new Infantry();
        BipedMech bm = new BipedMech();
        Protomech pm = new Protomech();
        Tank t = new Tank();

        Vector<Entity> entities = new Vector<>();
        entities.add(a);
        entities.add(i);
        entities.add(bm);
        entities.add(pm);
        entities.add(t);

        for (Entity e : entities){
            e.setOwner(m_Player);
        }
        m_Server.getGame().addEntities(entities);

        EntityManager em = m_Server.getEntityManager();
        em.processMovement(a, new MovePath(m_Server.getGame(), a), null);
        assert(a.isDone());

    }

}
