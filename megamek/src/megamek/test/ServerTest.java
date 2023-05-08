package megamek.test;

import megamek.common.Entity;
import megamek.common.HitData;
import megamek.common.Report;
import megamek.server.Server;

import java.io.IOException;
import java.util.Vector;

public class ServerTest {

    private static Server m_Server;

    public static Vector<Report> damageEntityWrapper(Entity te){
        HitData hitData = new HitData(0);
        return m_Server.damageEntity(te, hitData);
    }

    public static void testDamageEntity() {


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
