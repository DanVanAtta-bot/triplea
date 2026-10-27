package games.strategy.engine.random;

import java.util.SplittableRandom;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Test hook for reproducible AI-vs-AI games: when seeded, each named stream hands out seeded dice
 * sources in call order, so the code that draws from one stream replays identically and other
 * streams can't perturb it. Unseeded (the default, and always in a real game) every {@link #next}
 * returns {@code null} and callers keep their own unseeded source.
 */
public final class TestSeeds {
  /** The AI's battle calculator (one source per worker share). */
  public static final String CALC = "calc";

  /** ProAI's simulated battles in its purchase turn-sim. */
  public static final String PRO_SIM = "pro-sim";

  private static final Object LOCK = new Object();
  @Nullable private static Long baseSeed;
  private static final java.util.Map<String, SplittableRandom> streams = new java.util.HashMap<>();

  private TestSeeds() {}

  /** Seeds every stream from {@code seed}; {@code null} turns seeding off. */
  public static void seed(@Nullable final Long seed) {
    synchronized (LOCK) {
      baseSeed = seed;
      streams.clear();
    }
  }

  public static boolean isSeeded() {
    synchronized (LOCK) {
      return baseSeed != null;
    }
  }

  /**
   * A new unit id: random normally, from a seeded stream when seeded. Units live in a map keyed by
   * id whose iteration order follows the ids, so random ids alone make a seeded game diverge.
   */
  public static UUID nextUnitId() {
    synchronized (LOCK) {
      if (baseSeed == null) {
        return UUID.randomUUID();
      }
      final SplittableRandom r =
          streams.computeIfAbsent(
              "unit-id", s -> new SplittableRandom(baseSeed * 31 + s.hashCode()));
      return new UUID(r.nextLong(), r.nextLong());
    }
  }

  /** The stream's next seeded dice source, or {@code null} when unseeded. */
  @Nullable
  public static IRandomSource next(final String stream) {
    synchronized (LOCK) {
      if (baseSeed == null) {
        return null;
      }
      final SplittableRandom r =
          streams.computeIfAbsent(stream, s -> new SplittableRandom(baseSeed * 31 + s.hashCode()));
      return new PlainRandomSource(r.nextLong());
    }
  }
}
