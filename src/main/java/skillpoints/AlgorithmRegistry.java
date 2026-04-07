package skillpoints;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
/**
 * Single source of truth for all skillpoint algorithms.
 *
 * To add a new algorithm, add one {@code .put()} call below.
 * Tests and benchmarks discover algorithms from this registry automatically.
 */
public final class AlgorithmRegistry {

    private static final LinkedHashMap<String, Supplier<SkillpointChecker>> REGISTRY = new LinkedHashMap<>();

    static {
        REGISTRY.put("WynnAlgorithm",      WynnAlgorithm::new);
        REGISTRY.put("SCCGraphAlgorithm",   SCCGraphAlgorithm::new);
        REGISTRY.put("WynnSolver",          WynnSolverAlgorithm::new);
        REGISTRY.put("CascadeBound",        CascadeBoundChecker::new);
        REGISTRY.put("MyFirstAlgorithm",    MyFirstAlgorithm::new);
        REGISTRY.put("MySecondAlgorithm",   MySecondAlgorithm::new);
        REGISTRY.put("TheThirdAlgorithm",   TheThirdAlgorithm::new);
        REGISTRY.put("OurSecondAlgorithm",  OurSecondAlgorithm::new);
        REGISTRY.put("CachingThirdAlgorithm", CachingThirdAlgorithm::new);
    }

    /** Create a checker by its registered name. */
    public static SkillpointChecker create(String name) {
        Supplier<SkillpointChecker> factory = REGISTRY.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown algorithm: " + name
                    + ". Registered: " + REGISTRY.keySet());
        }
        return factory.get();
    }

    /** All registered names, in insertion order. */
    public static String[] names() {
        return REGISTRY.keySet().toArray(String[]::new);
    }

    /** All entries (name → factory), in insertion order. */
    public static Map<String, Supplier<SkillpointChecker>> all() {
        return REGISTRY;
    }
}
