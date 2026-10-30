package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * FLOW_ALLOCATOR (Dan's notes A19-A27): splits a power's effort across its directions, each judged
 * by front economics. A direction is a route between opposing factory clusters (adjacent factories
 * merged; every cluster links to its nearest enemy cluster, by land if it can, else by sea; linked
 * clusters form one direction, land and sea links apart). Per direction, on the start-of-turn
 * board: own and enemy unit TUV in the corridor (the link paths plus one step), and the enemy's
 * next placement there (observed from history by {@link ProOppPurchase}; production capacity before
 * it has history).
 *
 * <p>Fitted on Big World v3 self-play (scratch/ai-discipline): a front moves forward half the time
 * at own / (enemy + enemy placement) = 1.39 and holds half the time at 0.44. So pushing costs
 * {@code 1.39 x (enemy + placement) - own} and holding {@code 0.44 x (enemy + placement) - own}.
 * After paying every hold, the rest goes to the directions with the most enemy production per TUV
 * still needed, distance-discounted, while that reaches the attack ratio within a few turns; the
 * others hold. A pushed direction that stops gaining (ratio falling, no corridor production taken
 * for two turns) switches to hold for two rounds.
 *
 * <p>The plan only reweights ProAI's own values (place order, unit mix, move values); it never
 * forbids a purchase or a move.
 */
public final class ProFlowAllocator {
  public static final double ATTACK_RATIO = 1.39;
  public static final double HOLD_RATIO = 0.44;
  static final int MAX_TURNS_TO_PUSH = 3;
  static final int STALL_TURNS = 2;
  static final int COOLDOWN_ROUNDS = 2;
  static final double UNIT_PU = 3.5;

  public enum Mode {
    PUSH,
    HOLD
  }

  /** One direction as this power sees it this turn. */
  public static final class Direction {
    final String key;
    final boolean sea;
    final Set<String> myFactories = new TreeSet<>();
    final Set<String> enemyFactories = new TreeSet<>();
    final Set<String> corridor = new HashSet<>();
    int pathLength = Integer.MAX_VALUE;
    double own;
    double enemy;
    double enemyPlace;
    double gain;
    double capacity;
    int ownProduction;
    Mode mode = Mode.HOLD;
    String why = "";

    Direction(final String key, final boolean sea) {
      this.key = key;
      this.sea = sea;
    }

    public String key() {
      return key;
    }

    public Mode mode() {
      return mode;
    }

    public boolean isSea() {
      return sea;
    }

    public boolean inCorridor(final Territory t) {
      return corridor.contains(t.getName());
    }

    double against() {
      return enemy + enemyPlace;
    }

    public double ratio() {
      return against() <= 0 ? 99 : own / against();
    }

    public double holdNeed() {
      return Math.max(0, HOLD_RATIO * against() - own);
    }

    public double attackNeed() {
      return Math.max(0, ATTACK_RATIO * against() - own);
    }

    double importance() {
      return gain / (1 + (Math.max(1, pathLength) - 1) / 2.0);
    }

    @Override
    public String toString() {
      return String.format(
          "%s%s %s own=%.0f enemy=%.0f place=%.0f ratio=%.2f hold=%.0f attack=%.0f gain=%.0f"
              + " len=%d cap=%.0f mine=%s%s",
          key,
          sea ? "(sea)" : "",
          mode,
          own,
          enemy,
          enemyPlace,
          ratio(),
          holdNeed(),
          attackNeed(),
          gain,
          pathLength,
          capacity,
          myFactories,
          why.isEmpty() ? "" : " " + why);
    }
  }

  /** The turn's allocation: every direction and the weight it gives to places and moves. */
  public static final class Plan {
    private final List<Direction> directions;
    private final Map<String, Direction> landPlace = new HashMap<>();
    private final Map<String, Direction> seaPlace = new HashMap<>();
    private final double pushWeight;

    Plan(final List<Direction> directions, final double pushWeight) {
      this.directions = directions;
      this.pushWeight = pushWeight;
    }

    public List<Direction> getDirections() {
      return directions;
    }

    /** True when the plan splits: some direction pushes and some other holds. */
    public boolean splits() {
      return directions.stream().anyMatch(d -> d.mode == Mode.PUSH)
          && directions.stream().anyMatch(d -> d.mode == Mode.HOLD && d.capacity > 0);
    }

    /** The direction a place territory (own factory, or a sea zone next to one) feeds. */
    @Nullable
    public Direction directionOf(final Territory t) {
      return t.isWater() ? seaPlace.get(t.getName()) : landPlace.get(t.getName());
    }

    /**
     * Weight on a place territory's value: pushed directions up, held directions whose hold is
     * already met down, everything else 1.
     */
    public double placeWeight(final Territory t) {
      final Direction d = directionOf(t);
      if (d == null) {
        return 1;
      }
      if (d.mode == Mode.PUSH) {
        return pushWeight;
      }
      return d.holdNeed() <= 0 ? 1 / pushWeight : 1;
    }

    /** Pushed direction whose corridor holds t, if any (the first by importance). */
    @Nullable
    public Direction pushedCorridorOf(final Territory t) {
      for (final Direction d : directions) {
        if (d.mode == Mode.PUSH && d.inCorridor(t)) {
          return d;
        }
      }
      return null;
    }

    /** Held direction whose corridor holds t, if no pushed one does. */
    @Nullable
    public Direction heldCorridorOf(final Territory t) {
      if (pushedCorridorOf(t) != null) {
        return null;
      }
      for (final Direction d : directions) {
        if (d.mode == Mode.HOLD && d.inCorridor(t)) {
          return d;
        }
      }
      return null;
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder();
      for (final Direction d : directions) {
        sb.append("\n  ").append(d);
      }
      return sb.toString();
    }
  }

  private record Reading(int round, double ratio, int ownProduction, Mode mode) {}

  private final ProOppPurchase predictor = new ProOppPurchase();
  private final Map<String, Deque<Reading>> past = new HashMap<>();
  private final Map<String, Integer> holdUntil = new HashMap<>();

  /** Builds this turn's plan on the start-of-turn board ({@code data} is the real game). */
  public Plan plan(
      final GameData data, final GamePlayer player, final int pus, final double pushWeight) {
    final int round = data.getSequence().getRound();
    final List<GamePlayer> enemies = ProUtils.getPotentialEnemyPlayers(player);
    final Map<String, Map<String, Double>> forecast =
        predictor.forecastPlacement(data, player, enemies);
    final List<Direction> dirs;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      dirs = directions(data, player, enemies, forecast, pus);
    }
    allocate(dirs, pus, round);
    final Plan plan = new Plan(dirs, pushWeight);
    final GameMap map = data.getMap();
    for (final Direction d : dirs) {
      for (final String f : d.myFactories) {
        final Territory t = map.getTerritoryOrNull(f);
        if (t == null) {
          continue;
        }
        if (!d.sea || !plan.landPlace.containsKey(f)) {
          plan.landPlace.put(f, d);
        }
        for (final Territory z : map.getNeighbors(t, Matches.territoryIsWater())) {
          final Direction prev = plan.seaPlace.get(z.getName());
          if (prev == null || (d.sea && !prev.sea)) {
            plan.seaPlace.put(z.getName(), d);
          }
        }
      }
    }
    return plan;
  }

  private void allocate(final List<Direction> dirs, final int pus, final int round) {
    double free = pus;
    for (final Direction d : dirs) {
      free -= Math.min(d.holdNeed(), d.capacity);
    }
    final List<Direction> order = new ArrayList<>(dirs);
    order.sort(
        Comparator.comparingDouble(
                (Direction d) -> d.importance() / Math.max(1, d.attackNeed() - d.holdNeed()))
            .reversed()
            .thenComparing(d -> d.key));
    for (final Direction d : order) {
      final Deque<Reading> hist = past.computeIfAbsent(d.key, k -> new ArrayDeque<>());
      if (d.capacity <= 0) {
        d.mode = Mode.HOLD;
        d.why = "no-capacity";
      } else if (holdUntil.getOrDefault(d.key, 0) >= round) {
        d.mode = Mode.HOLD;
        d.why = "cooldown";
      } else if (d.importance() <= 0) {
        d.mode = Mode.HOLD;
        d.why = "no-gain";
      } else if (stalled(hist, d)) {
        d.mode = Mode.HOLD;
        d.why = "stalled";
        holdUntil.put(d.key, round + COOLDOWN_ROUNDS);
      } else {
        final double extra = Math.max(0, d.attackNeed() - Math.min(d.holdNeed(), d.capacity));
        final double rate = Math.min(Math.max(0, free), d.capacity);
        if (extra <= 0) {
          d.mode = Mode.PUSH;
          d.why = "at-ratio";
        } else if (rate > 0 && Math.ceil(extra / rate) <= MAX_TURNS_TO_PUSH) {
          d.mode = Mode.PUSH;
          d.why = String.format("turns=%.0f", Math.ceil(extra / rate));
          free -= Math.min(extra, rate);
        } else {
          d.mode = Mode.HOLD;
          d.why = "too-dear";
        }
      }
      hist.addLast(new Reading(round, d.ratio(), d.ownProduction, d.mode));
      while (hist.size() > STALL_TURNS + 1) {
        hist.removeFirst();
      }
    }
  }

  /** Pushed on each of the last turns, yet the ratio fell and no corridor production was taken. */
  private static boolean stalled(final Deque<Reading> hist, final Direction d) {
    if (hist.size() < STALL_TURNS) {
      return false;
    }
    final List<Reading> last =
        new ArrayList<>(hist).subList(hist.size() - STALL_TURNS, hist.size());
    if (last.stream().anyMatch(r -> r.mode() != Mode.PUSH)) {
      return false;
    }
    final Reading first = last.get(0);
    return d.ratio() < first.ratio() && d.ownProduction <= first.ownProduction();
  }

  // ------------------------------------------------------------------ directions

  private static List<Direction> directions(
      final GameData data,
      final GamePlayer player,
      final List<GamePlayer> enemies,
      final Map<String, Map<String, Double>> forecast,
      final int pus) {
    final GameMap map = data.getMap();
    final Set<GamePlayer> enemySet = new HashSet<>(enemies);
    final Predicate<GamePlayer> isEnemy = enemySet::contains;
    final Predicate<GamePlayer> isFriend =
        p -> p.equals(player) || data.getRelationshipTracker().isAllied(player, p);
    final List<Territory> ownSide = new ArrayList<>();
    final List<Territory> enemySide = new ArrayList<>();
    for (final Territory t : map.getTerritories()) {
      if (t.isWater() || !t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
        continue;
      }
      if (isFriend.test(t.getOwner())) {
        ownSide.add(t);
      } else if (isEnemy.test(t.getOwner())) {
        enemySide.add(t);
      }
    }
    if (ownSide.stream().noneMatch(t -> t.isOwnedBy(player)) || enemySide.isEmpty()) {
      return List.of();
    }
    final List<Set<Territory>> ownClusters = clusters(map, ownSide);
    final List<Set<Territory>> enemyClusters = clusters(map, enemySide);
    // links: every cluster to its nearest opposing cluster
    record Link(Set<Territory> own, Set<Territory> enemy, List<Territory> path, boolean sea) {}
    final List<Link> links = new ArrayList<>();
    for (int side = 0; side < 2; side++) {
      final List<Set<Territory>> from = side == 0 ? ownClusters : enemyClusters;
      final List<Set<Territory>> to = side == 0 ? enemyClusters : ownClusters;
      final Set<Territory> targets = new HashSet<>();
      to.forEach(targets::addAll);
      for (final Set<Territory> c : from) {
        boolean sea = false;
        List<Territory> path = shortestPath(map, c, targets, true);
        if (path == null) {
          sea = true;
          path = shortestPath(map, c, targets, false);
        }
        if (path == null) {
          continue;
        }
        final Territory end = path.get(path.size() - 1);
        final Set<Territory> other = to.stream().filter(s -> s.contains(end)).findFirst().get();
        links.add(side == 0 ? new Link(c, other, path, sea) : new Link(other, c, path, sea));
      }
    }
    // union-find over clusters, land and sea links apart
    final List<Direction> out = new ArrayList<>();
    for (final boolean sea : new boolean[] {false, true}) {
      final Map<Set<Territory>, Set<Territory>> parent = new HashMap<>();
      final List<Link> ls = links.stream().filter(l -> l.sea() == sea).toList();
      for (final Link l : ls) {
        union(parent, l.own(), l.enemy());
      }
      final Map<Set<Territory>, List<Link>> groups = new LinkedHashMap<>();
      for (final Link l : ls) {
        groups.computeIfAbsent(find(parent, l.own()), k -> new ArrayList<>()).add(l);
      }
      for (final List<Link> g : groups.values()) {
        final Set<Territory> mine = new LinkedHashSet<>();
        final Set<Territory> theirs = new LinkedHashSet<>();
        final Set<Territory> path = new HashSet<>();
        int len = Integer.MAX_VALUE;
        for (final Link l : g) {
          l.own().stream().filter(t -> t.isOwnedBy(player)).forEach(mine::add);
          theirs.addAll(l.enemy());
          path.addAll(l.path());
          len = Math.min(len, l.path().size() - 1);
        }
        if (mine.isEmpty()) {
          continue;
        }
        final Direction d =
            new Direction(
                theirs.stream().map(Territory::getName).sorted().findFirst().orElse("?")
                    + "<-"
                    + mine.stream().map(Territory::getName).sorted().findFirst().orElse("?"),
                sea);
        mine.forEach(t -> d.myFactories.add(t.getName()));
        theirs.forEach(t -> d.enemyFactories.add(t.getName()));
        d.pathLength = len;
        final Set<Territory> corridor = new HashSet<>(path);
        for (final Territory t : path) {
          corridor.addAll(map.getNeighbors(t, Matches.territoryIsImpassable().negate()));
        }
        corridor.forEach(t -> d.corridor.add(t.getName()));
        out.add(d);
        fill(d, corridor, theirs, mine, player, isFriend, isEnemy, enemies, forecast, pus, data);
      }
    }
    return out;
  }

  private static void fill(
      final Direction d,
      final Set<Territory> corridor,
      final Set<Territory> enemyFactories,
      final Set<Territory> myFactories,
      final GamePlayer player,
      final Predicate<GamePlayer> isFriend,
      final Predicate<GamePlayer> isEnemy,
      final List<GamePlayer> enemies,
      final Map<String, Map<String, Double>> forecast,
      final int pus,
      final GameData data) {
    final TuvCostsCalculator tuv = new TuvCostsCalculator();
    for (final Territory t : corridor) {
      for (final Unit u : t.getUnitCollection()) {
        if (Matches.unitIsInfrastructure().test(u)) {
          continue;
        }
        final UnitType type = u.getType();
        final GamePlayer owner = u.getOwner();
        final int cost = tuv.getCostsForTuv(owner).getInt(type);
        if (isFriend.test(owner)) {
          d.own += cost;
        } else if (isEnemy.test(owner)) {
          d.enemy += cost;
        }
      }
      if (!t.isWater()) {
        final int prod = TerritoryAttachment.getProduction(t);
        if (isEnemy.test(t.getOwner())) {
          d.gain += prod;
        } else if (t.isOwnedBy(player)) {
          d.ownProduction += prod;
        }
      }
    }
    // enemy placement serving the direction
    for (final GamePlayer e : enemies) {
      final Map<String, Double> f = forecast.get(e.getName());
      if (f != null) {
        for (final Map.Entry<String, Double> s : f.entrySet()) {
          final String site = s.getKey();
          final boolean in =
              site.startsWith("sea:")
                  ? d.corridor.contains(site.substring(4))
                  : enemyFactories.stream().anyMatch(t -> t.getName().equals(site))
                      || d.corridor.contains(site);
          if (in) {
            d.enemyPlace += s.getValue();
          }
        }
      } else {
        // no history yet: production capacity, capped by income
        int capHere = 0;
        int capAll = 0;
        for (final Territory t : data.getMap().getTerritories()) {
          if (!t.isWater() && t.isOwnedBy(e) && t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
            final int p = TerritoryAttachment.getProduction(t);
            capAll += p;
            if (enemyFactories.contains(t) || corridor.contains(t)) {
              capHere += p;
            }
          }
        }
        if (capAll > 0 && capHere > 0) {
          final double bank = e.getResources().getQuantity(Constants.PUS);
          final double income = ProUtils.getPlayerProduction(e, data);
          final double budget = Math.max(bank, income);
          d.enemyPlace += Math.min(3.0 * capHere, budget * capHere / capAll);
        }
      }
    }
    // own placement capacity this turn, in PUs
    int prod = 0;
    for (final Territory t : myFactories) {
      prod += TerritoryAttachment.getProduction(t);
    }
    d.capacity = Math.min(pus, prod * UNIT_PU);
  }

  private static List<Set<Territory>> clusters(final GameMap map, final List<Territory> fs) {
    final List<Set<Territory>> out = new ArrayList<>();
    final Set<Territory> left = new LinkedHashSet<>(fs);
    while (!left.isEmpty()) {
      final Territory seed = left.iterator().next();
      left.remove(seed);
      final Set<Territory> comp = new LinkedHashSet<>(List.of(seed));
      final Deque<Territory> q = new ArrayDeque<>(List.of(seed));
      while (!q.isEmpty()) {
        final Territory t = q.pop();
        for (final Territory n : map.getNeighbors(t)) {
          if (left.remove(n)) {
            comp.add(n);
            q.add(n);
          }
        }
      }
      out.add(comp);
    }
    return out;
  }

  /** BFS from any of {@code from} to the nearest of {@code targets}; land only if asked. */
  @Nullable
  private static List<Territory> shortestPath(
      final GameMap map,
      final Set<Territory> from,
      final Set<Territory> targets,
      final boolean landOnly) {
    final Map<Territory, Territory> prev = new HashMap<>();
    final Deque<Territory> q = new ArrayDeque<>();
    for (final Territory t : sorted(from)) {
      prev.put(t, t);
      q.add(t);
    }
    while (!q.isEmpty()) {
      final Territory t = q.poll();
      if (targets.contains(t)) {
        final List<Territory> path = new ArrayList<>();
        Territory c = t;
        while (prev.get(c) != c) {
          path.add(0, c);
          c = prev.get(c);
        }
        path.add(0, c);
        return path;
      }
      for (final Territory n : sorted(map.getNeighbors(t))) {
        if (prev.containsKey(n)
            || Matches.territoryIsImpassable().test(n)
            || (landOnly && n.isWater())) {
          continue;
        }
        prev.put(n, t);
        q.add(n);
      }
    }
    return null;
  }

  private static List<Territory> sorted(final Set<Territory> ts) {
    final List<Territory> out = new ArrayList<>(ts);
    out.sort(Comparator.comparing(Territory::getName));
    return out;
  }

  private static Set<Territory> find(
      final Map<Set<Territory>, Set<Territory>> parent, final Set<Territory> x) {
    Set<Territory> c = x;
    while (parent.getOrDefault(c, c) != c) {
      c = parent.get(c);
    }
    return c;
  }

  private static void union(
      final Map<Set<Territory>, Set<Territory>> parent,
      final Set<Territory> a,
      final Set<Territory> b) {
    final Set<Territory> ra = find(parent, a);
    final Set<Territory> rb = find(parent, b);
    if (ra != rb) {
      parent.put(ra, rb);
    }
  }
}
