package megamek.server;

import megamek.common.*;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.util.Iterator;
import java.util.Vector;

public class PlayerTest {
    @Test
    public void randomIntTest() {
        int test = Compute.randomInt(0);
        System.out.println(test);
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

    @Test
    public void testDuplicateNameGhost() {


    }
}
