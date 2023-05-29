package megamek.server;

import org.junit.Assert;
import org.junit.Test;
import java.util.*;

public class EnumerationIterationTest {
    @Test
    public void testEmptyCollection() {
        Collection<String> emptyCollection = Collections.emptyList();

        Enumeration<String> enumeration = Collections.enumeration(emptyCollection);
        Iterator<String> iterator = emptyCollection.iterator();

        Assert.assertFalse(enumeration.hasMoreElements());
        Assert.assertFalse(iterator.hasNext());
    }

    @Test
    public void testSingleElementCollection() {
        List<Integer> singleElementList = Collections.singletonList(42);

        Enumeration<Integer> enumeration = Collections.enumeration(singleElementList);
        Iterator<Integer> iterator = singleElementList.iterator();

        Assert.assertEquals(42, enumeration.nextElement().intValue());
        Assert.assertEquals(42, iterator.next().intValue());

        Assert.assertFalse(enumeration.hasMoreElements());
        Assert.assertFalse(iterator.hasNext());
    }

    @Test
    public void testEnumerationAndIteration() {
        List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);

        Enumeration<Integer> enumeration = Collections.enumeration(numbers);
        Iterator<Integer> iterator = numbers.iterator();

        List<Integer> enumerationResults = new ArrayList<>();
        List<Integer> iterationResults = new ArrayList<>();

        // Collect results using enumeration
        while (enumeration.hasMoreElements()) {
            Integer element = enumeration.nextElement();
            enumerationResults.add(element);
        }

        // Collect results using iteration
        while (iterator.hasNext()) {
            Integer element = iterator.next();
            iterationResults.add(element);
        }

        // Compare results
        Assert.assertEquals(numbers, enumerationResults);
        Assert.assertEquals(numbers, iterationResults);
    }

    @Test
    public void testIterationRemoveOperation() {
        List<String> names = new ArrayList<>();
        names.add("Alice");
        names.add("Bob");
        names.add("Charlie");

        Iterator<String> iterator = names.iterator();

        iterator.next(); // Advance to the first element
        iterator.remove(); // Remove the first element

        Assert.assertFalse(names.contains("Alice"));
        Assert.assertEquals(2, names.size());
    }

    @Test
    public void testEnumerationModificationException() {
        Set<Integer> numbers = new HashSet<>(Arrays.asList(1, 2, 3));

        Enumeration<Integer> enumeration = Collections.enumeration(numbers);

        try {
            while (enumeration.hasMoreElements()) {
                Integer element = enumeration.nextElement();
                if (element == 2) {
                    numbers.remove(element); // Modification while enumerating
                }
            }
        } catch (ConcurrentModificationException e) {
            // Expected exception
            return;
        }

        // The test should fail if ConcurrentModificationException is not thrown
        Assert.fail("ConcurrentModificationException should have been thrown");
    }
}