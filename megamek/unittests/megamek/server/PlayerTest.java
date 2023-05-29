package megamek.server;

import megamek.client.ui.swing.util.PlayerColour;
import megamek.common.*;
import org.junit.Assert;
import org.junit.Test;
import java.util.*;

public class PlayerTest {
    @Test
    public void idk() {
        Hashtable<Integer, String> connectionIds = new Hashtable<>();

        connectionIds.put(1, "Sam");
        connectionIds.put(5, "Arthur");

        Assert.assertNull(connectionIds.get(3));
        Assert.assertNotNull(connectionIds.get(1));
    }


    // TODO (Sam): nog test schrijven
    /*
    @Test
    public void idk() {
        if (!m.isUsedThisRound()) {
            capHeat += m.hasChargedOrChargingCapacitor() * 5;
        }

        if ((m.hasChargedOrChargingCapacitor() == 1) && !m.isUsedThisRound()) {
            capHeat += 5;
        }
        if ((m.hasChargedOrChargingCapacitor() == 2) && !m.isUsedThisRound()) {
            capHeat += 10;
        }
    }
    */

    @Test
    public void changeCheck() {
        Vector<Integer> toSort = new Vector<>();
        toSort.add(5);
        toSort.add(1);
        toSort.add(10);
        toSort.add(2);
        toSort.add(3);
        toSort.add(5);

        toSort.sort((a, b) -> {
            if (a > b) {
                return -1;
            } else if (a > b) {
                return 1;
            }
            return 0;
        });
    }




    public Report createAndAddReport(int reportId, int subjectId, int indent, int newlines, Object... contents) {
        Report report = new Report(reportId);
        report.subject = subjectId;
        report.newlines = newlines;
        report.indent(indent);
        for (Object content : contents) {
            addContentToReport(report, content);
        }
        return report;
    }

    private void addContentToReport(Report report, Object content) {
        System.out.println("NOOOO");
    }

    private void addContentToReport(Report report, Entity entity) {
        System.out.println("YEEES");
        report.addDesc(entity);
    }

    private void addContentToReport(Report report, String text) {
        System.out.println("YEEES");
        report.add(text);
    }

    private void addContentToReport(Report report, int number) {
        System.out.println("YEEES");
        report.add(number);
    }

    @Test
    public void reportTest() {
        Report r = new Report(4055);
        r.subject = 40;
        r.indent();
        r.add("Sam");
        r.add(20);

        Player p = new Player(1, "Arthur");
        Aero s = new Aero();
        s.setOwner(p);

        r.newlines = 0;

        createAndAddReport(4055, 40, 0, 0, 1);
    }

    public void funct(IPlayer player) {
        player.setColour(PlayerColour.RED);
    }

    @Test
    public void referenceTest() {
        IPlayer newPlayer = new Player(0, "Sam");

        Assert.assertEquals(newPlayer.getColour(), PlayerColour.BLUE);

        funct(newPlayer);

        Assert.assertEquals(newPlayer.getColour(), PlayerColour.RED);
    }

    /*
    @Test
    public void damageTest() {



        int damage = Math.max(0, entity.mpUsed - entity.getJumpMP(false));

        int j = entity.mpUsed;
        int damage = 0;
        while (j > entity.getJumpMP(false)) {
            j--;
            damage++;
        }
    }

     */


    /*
    @Test
    public void baynumberTest() {
        // Default of baynumber in load function is -1 zo no specialif-else statemtn for this
        int bayNumber = -1;

        if (bayNumber == -1) {
            loader.load(unit, checkElevation);
        } else {
            loader.load(unit, checkElevation, bayNumber);
        }
    }
     */

    @Test
    public void nullTest() {
        IHex hex = null;
        if (hex != null && !hex.containsTerrain(Terrains.FIRE)) {
            int i = 10;
        }
        Assert.assertTrue(true);

    }

    @Test
    public void maxTest() {
        for (int crateredElevation = -10; crateredElevation <= 10; crateredElevation++) {
            for (int newDepth = -10; newDepth <= 10; newDepth++) {
                int test1 = crateredElevation;
                if (newDepth > crateredElevation) {
                    test1 = newDepth;
                }
                int test2 = Math.max(crateredElevation, newDepth);;

                Assert.assertEquals(test1, test2);
            }
        }
    }

    @Test
    public void moduloTest() {
        int flipCount = -3;
        int index = flipCount % 3;
        Assert.assertTrue(index >= 0);

    }

    @Test
    public void checkDefaultParamReportTest() {
        // Check if the type is initialised to the default
        Report r = new Report(3100);
        Assert.assertEquals(r.type, Report.HIDDEN);
    }

    /**
     * Test for a string concatenation in Server constructor
     */
    @Test
    public void stringConcatenationTest() {
        StringBuilder sb = new StringBuilder();
        String host = "Sam";
        int port = 9000;
        sb.append("s: hostname = '");
        sb.append(host);
        sb.append("' port = ");
        sb.append(port);
        sb.append("\n");

        sb.append("s: hosting on address = ");
        sb.append(host);
        sb.append("\n");


        String test1 = sb.toString();
        String test2 = String.format("s: hostname = '%s' port = %d%n", host, port);
        test2 = String.format("%ss: hosting on address = %s%n", test2, host);

        Assert.assertEquals(test1, test2);

    }

    @Test
    public void vehicleMotiveDamageTest() {
        boolean jumpDamage = true;
        int test1 = 0;

        test1 += jumpDamage ? -1 : 3;

        int test2 = 0;

        if (jumpDamage) {
            test2 -= 1;
        } else {
            test2 += 3;
        }

        Assert.assertEquals(test1, test2);


    }
}
