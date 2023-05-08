package megamek.test;

import megamek.common.*;
import megamek.server.Server;

import java.io.IOException;
import java.util.Vector;

public class ServerTest {

    private static Server m_Server;

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

    public static void testDamageEntity() {
        System.out.println("==================Starting testDamageEntity==================");
        Mech mech = new BipedMech();
        Vector<Report> report = damageEntityWrapper(mech);
        System.out.println("Mech damage report: " + report);
        System.out.println("==================Ending testDamageEntity==================");
    }

    public static void main(String[] args) throws IOException {
        m_Server = new Server("Megamek", 2345);
        try {
            testDamageEntity();
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Ending ServerTest");
    }
}
