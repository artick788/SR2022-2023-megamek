package megamek.test;

import megamek.common.*;
import megamek.common.loaders.EntityLoadingException;
import megamek.server.Server;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;


public class ServerTest {

    private static Server m_Server;
    private static IPlayer m_Player;

    private static Map<String, Entity> m_EntityNames;
    private static Map<String, Vector<Report>> m_ExpectedEntityDamageReports;

    public static Vector<Report> damageEntityWrapper(Entity te){
        HitData hitData = new HitData(0);
        int damage = 20;
        boolean ammoExplosion = true;
        Server.DamageType damageType = Server.DamageType.FRAGMENTATION;
        boolean damageIs = true;
        boolean areaSatEntry = false;
        boolean throughFront = true;
        boolean underWater = false;
        boolean nukeS2S = false;

        return m_Server.damageEntity(te, hitData, damage, ammoExplosion, damageType, damageIs, areaSatEntry, throughFront, underWater, nukeS2S);
    }

    private static void generateExpectedReports(String file){

    }

    private static void addEntities() throws EntityLoadingException {
        File f;
        MechFileParser mfp;
        Entity e;
        f = new File("data/mechfiles/mechs/3050U/Exterminator EXT-4A.mtf");
        mfp  = new MechFileParser(f);
        e = mfp.getEntity();

        Objects.requireNonNull(m_EntityNames.put("BipedMech", new BipedMech(Mech.GYRO_STANDARD, Mech.COCKPIT_TORSO_MOUNTED))).setOwner(m_Player);
        Objects.requireNonNull(m_ExpectedEntityDamageReports.put("BipedMech", new Vector<>())).add(new Report(6050));

        Objects.requireNonNull(m_EntityNames.put("QuadMech", new QuadMech(Mech.GYRO_HEAVY_DUTY, Mech.COCKPIT_AIMED_SHOT))).setOwner(m_Player);
        Objects.requireNonNull(m_ExpectedEntityDamageReports.put("QuadMech", new Vector<>())).add(new Report(6050));
    }

    public static void testDamageEntity() throws EntityLoadingException {
        System.out.println("==================Starting testDamageEntity==================");
        File f;
        MechFileParser mfp;
        Entity e;
        f = new File("data/mechfiles/mechs/3050U/Exterminator EXT-4A.mtf");
        mfp  = new MechFileParser(f);
        e = mfp.getEntity();
        e.setOwner(m_Player);
        Vector<Report> reports = damageEntityWrapper(e);
        reports.elementAt(0).messageId = 0;
        System.out.println("Mech damage report: " + reports);
        System.out.println("==================Ending testDamageEntity==================");
    }

    public static void main(String[] args) throws IOException {
        m_Server = new Server("Megamek", 2345);
        m_Player = m_Server.getGame().addNewPlayer(5, "NewPlayer1");
        try {
            testDamageEntity();
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Ending ServerTest");
    }
}
