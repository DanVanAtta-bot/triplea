package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProAiPatch;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * FLOW_ALLOCATOR (Dan's notes A19-A27): splits a power's effort across its directions, each judged
 * by front economics. A direction is a route from own factories to a group of enemy factories
 * (A24): per cluster of adjacent own factories, enemy factories by route length in turns (land step
 * 1, sea step 1/2; land routes first, else over water), each joining the first group whose
 * factories its route passes within one step of, else starting a new one (at most three). Own
 * clusters whose groups share an enemy factory merge. Per direction, on the start-of-turn board:
 * own and enemy unit TUV in the corridor (the routes plus one step), and the enemy's next placement
 * there (observed from history by {@link ProOppPurchase}; production capacity before it has
 * history).
 *
 * <p>Fitted on champion-8 self-play with this direction definition (scratch/ai-loop-flow
 * flowfit.py; Big World v3 carries the fit): a land route moves forward half the time at own /
 * (enemy + enemy placement) = 1.13 and holds half the time at 0.39 (routes over water: 1.94 and
 * 0.23). So pushing costs {@code attack ratio x (enemy + placement) - own} and holding {@code hold
 * ratio x (enemy + placement) - own}. After paying every hold, the rest of the turn's PUs goes to
 * the directions with the most enemy production per TUV still needed, distance-discounted, while
 * that reaches the attack ratio within a few turns of spending; with none, the cheapest one within
 * twice that is committed to. A pushed direction that stops gaining (ratio falling, no corridor
 * production taken for two turns) switches to hold for two rounds.
 *
 * <p>The plan only reweights ProAI's own values (place order, unit mix, move values); it never
 * forbids a purchase or a move.
 */
public final class ProFlowAllocator {
  // 50% points refitted on this direction definition (scratch/ai-loop-flow/tools/flowfit.py, Big
  // World v3 champion-8 self-play): land routes 1.13 / 0.39, routes over water 1.94 / 0.23
  public static final double ATTACK_RATIO = 1.13;
  public static final double HOLD_RATIO = 0.39;
  public static final double SEA_ATTACK_RATIO = 1.94;
  public static final double SEA_HOLD_RATIO = 0.23;
  static final int MAX_TURNS_TO_PUSH = 3;
  static final int STALL_TURNS = 2;
  static final int COOLDOWN_ROUNDS = 2;
  static final double UNIT_PU = 3.5;
  static final int MAX_DIRECTIONS = 3;

  public enum Mode {
    PUSH,
    HOLD
  }

  /** One direction as this power sees it this turn. */
  public static final class Direction {
    String key;
    String head = "";
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
      return Math.max(0, (sea ? SEA_HOLD_RATIO : HOLD_RATIO) * against() - own);
    }

    public double attackNeed() {
      return Math.max(0, (sea ? SEA_ATTACK_RATIO : ATTACK_RATIO) * against() - own);
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

  static final double MOVE_PUSH = 1.5;
  static final double MOVE_HOLD = 0.8;
  static final double MOVE_PUSH_STRONG = 2.0;
  static final double MOVE_HOLD_STRONG = 0.6;

  /**
   * FLOW_MOVE: weight on a territory's value in attack and move values, by the flow plan: in a
   * pushed direction's corridor x1.5, in a held one's (and no pushed one's) x0.8, else 1.
   */
  public static double moveFactor(final ProData proData, final Territory t) {
    final Plan plan = proData.getFlowPlan();
    if (plan == null || !proData.hasPatch(ProAiPatch.FLOW_MOVE)) {
      return 1;
    }
    final boolean strong = proData.hasPatch(ProAiPatch.FLOW_MOVE_STRONG);
    final double f;
    if (plan.pushedCorridorOf(t) != null) {
      f = strong ? MOVE_PUSH_STRONG : MOVE_PUSH;
    } else if (!proData.hasPatch(ProAiPatch.FLOW_MOVE_PUSH_ONLY)
        && plan.heldCorridorOf(t) != null) {
      f = strong ? MOVE_HOLD_STRONG : MOVE_HOLD;
    } else {
      return 1;
    }
    proData.isPatchOn(ProAiPatch.FLOW_MOVE);
    return f;
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
    final Plan plan;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final List<Direction> dirs = directions(data, player, enemies, forecast, pus);
      allocate(dirs, pus, round);
      plan = new Plan(dirs, pushWeight);
      mapPlaces(data, player, plan);
    }
    return plan;
  }

  /**
   * Each own factory feeds the direction (of its cluster) whose enemy factories are nearest from
   * it, by land if any is, else over water; each sea zone next to it, the nearest over water.
   */
  private static void mapPlaces(final GameData data, final GamePlayer player, final Plan plan) {
    final GameMap map = data.getMap();
    for (final Territory f : map.getTerritories()) {
      if (f.isWater() || !f.isOwnedBy(player) || !f.anyUnitsMatch(Matches.unitCanProduceUnits())) {
        continue;
      }
      final List<Direction> mine =
          plan.directions.stream().filter(d -> d.myFactories.contains(f.getName())).toList();
      if (mine.isEmpty()) {
        continue;
      }
      final Direction land = nearest(map, f, mine, true);
      plan.landPlace.put(f.getName(), land != null ? land : nearest(map, f, mine, false));
      for (final Territory z : map.getNeighbors(f, Matches.territoryIsWater())) {
        final List<Direction> sea = mine.stream().filter(d -> d.sea).toList();
        final Direction best = nearest(map, z, sea.isEmpty() ? mine : sea, false);
        final Direction prev = plan.seaPlace.get(z.getName());
        if (best != null && (prev == null || best.mode == Mode.PUSH)) {
          plan.seaPlace.put(z.getName(), best);
        }
      }
    }
  }

  @Nullable
  private static Direction nearest(
      final GameMap map, final Territory from, final List<Direction> dirs, final boolean landOnly) {
    final Map<Territory, Double> dist = dijkstra(map, Set.of(from), landOnly).dist();
    Direction best = null;
    double bestDist = Double.MAX_VALUE;
    for (final Direction d : dirs) {
      if (landOnly && d.sea) {
        continue;
      }
      for (final String e : d.enemyFactories) {
        final Double x = dist.get(map.getTerritoryOrNull(e));
        if (x != null && x < bestDist) {
          bestDist = x;
          best = d;
        }
      }
    }
    return best;
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
    Direction commit = null;
    double commitTurns = Double.MAX_VALUE;
    for (final Direction d : order) {
      final Deque<Reading> hist = past.computeIfAbsent(d.key, k -> new ArrayDeque<>());
      if (holdUntil.getOrDefault(d.key, 0) >= round) {
        d.why = "cooldown";
      } else if (d.importance() <= 0) {
        d.why = "no-gain";
      } else if (stalled(hist, d)) {
        d.why = "stalled";
        holdUntil.put(d.key, round + COOLDOWN_ROUNDS);
      } else {
        final double extra = Math.max(0, d.attackNeed() - d.holdNeed());
        final double rate = Math.min(free, d.capacity);
        final double turns = extra <= 0 ? 0 : rate > 0 ? Math.ceil(extra / rate) : 99;
        if (turns <= MAX_TURNS_TO_PUSH) {
          d.mode = Mode.PUSH;
          d.why = extra <= 0 ? "at-ratio" : String.format("turns=%.0f", turns);
          free -= Math.min(extra, Math.max(0, rate));
        } else {
          d.why = String.format("too-dear(%.0f)", turns);
          if (turns < commitTurns) {
            commitTurns = turns;
            commit = d;
          }
        }
      }
    }
    // Committing in a direction beats spreading: with nothing pushable, push the best one whose
    // price can be met in twice the horizon
    if (commit != null
        && commitTurns <= 2 * MAX_TURNS_TO_PUSH
        && dirs.stream().noneMatch(d -> d.mode == Mode.PUSH)) {
      commit.mode = Mode.PUSH;
      commit.why = String.format("commit(%.0f)", commitTurns);
    }
    for (final Direction d : dirs) {
      final Deque<Reading> hist = past.get(d.key);
      hist.addLast(new Reading(round, d.ratio(), d.ownProduction, d.mode));
      while (hist.size() > STALL_TURNS + 1) {
        hist.removeFirst();
      }
    }
  }

  /**
   * Pushed on each of the last turns, yet below the attack ratio, the ratio fell and no corridor
   * production was taken: the front isn't working.
   */
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
    return d.attackNeed() > 0
        && d.ratio() < first.ratio()
        && d.ownProduction <= first.ownProduction();
  }

  // ------------------------------------------------------------------ directions

  /**
   * Per own factory cluster, enemy factories by distance (land routes first, then over water); an
   * enemy factory whose route passes within one step of an earlier group's factories joins that
   * group (a common shortest path is one direction, A24), else it starts a new group. At most
   * {@link #MAX_DIRECTIONS} groups per cluster, none farther than twice the nearest plus two.
   */
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
    final List<Territory> mine = new ArrayList<>();
    final List<Territory> theirs = new ArrayList<>();
    for (final Territory t : map.getTerritories()) {
      if (t.isWater() || !t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
        continue;
      }
      if (t.isOwnedBy(player)) {
        mine.add(t);
      } else if (isEnemy.test(t.getOwner())) {
        theirs.add(t);
      }
    }
    final List<Direction> out = new ArrayList<>();
    if (mine.isEmpty() || theirs.isEmpty()) {
      return out;
    }
    final List<Direction> all = new ArrayList<>();
    final List<Set<Territory>> allPaths = new ArrayList<>();
    for (final Set<Territory> cluster : clusters(map, mine)) {
      final Search land = dijkstra(map, cluster, true);
      final Search any = dijkstra(map, cluster, false);
      record Cand(Territory t, double dist, boolean sea) {}
      final List<Cand> cands = new ArrayList<>();
      for (final Territory e : theirs) {
        final Double dl = land.dist().get(e);
        final Double da = any.dist().get(e);
        if (dl != null) {
          cands.add(new Cand(e, dl, false));
        } else if (da != null) {
          cands.add(new Cand(e, da, true));
        }
      }
      if (cands.isEmpty()) {
        continue;
      }
      cands.sort(Comparator.comparingDouble(Cand::dist).thenComparing(c -> c.t().getName()));
      final double limit = 2 * cands.get(0).dist() + 2;
      final List<List<Territory>> groupPaths = new ArrayList<>();
      final List<Set<Territory>> groupNear = new ArrayList<>();
      final List<Direction> groups = new ArrayList<>();
      final String head = sorted(cluster).get(0).getName();
      for (final Cand c : cands) {
        final List<Territory> path = (c.sea() ? any : land).path(c.t());
        int joined = -1;
        for (int i = 0; i < groups.size() && joined < 0; i++) {
          if (groups.get(i).sea == c.sea() && path.stream().anyMatch(groupNear.get(i)::contains)) {
            joined = i;
          }
        }
        if (joined < 0) {
          if (groups.size() >= MAX_DIRECTIONS || c.dist() > limit) {
            continue;
          }
          final Direction d = new Direction(head, c.sea());
          d.head = c.t().getName();
          cluster.forEach(t -> d.myFactories.add(t.getName()));
          d.pathLength = path.size() - 1;
          groups.add(d);
          groupPaths.add(new ArrayList<>());
          groupNear.add(new HashSet<>());
          joined = groups.size() - 1;
        }
        final Direction d = groups.get(joined);
        d.enemyFactories.add(c.t().getName());
        groupPaths.get(joined).addAll(path);
        groupNear.get(joined).add(c.t());
        groupNear.get(joined).addAll(map.getNeighbors(c.t()));
      }
      for (int i = 0; i < groups.size(); i++) {
        all.add(groups.get(i));
        allPaths.add(new HashSet<>(groupPaths.get(i)));
      }
    }
    // Directions of different own clusters that share an enemy factory are one direction
    final List<Direction> merged = new ArrayList<>();
    final List<Set<Territory>> mergedPaths = new ArrayList<>();
    for (int i = 0; i < all.size(); i++) {
      final Direction d = all.get(i);
      boolean done = false;
      for (int j = 0; j < merged.size() && !done; j++) {
        final Direction o = merged.get(j);
        if (o.sea == d.sea && o.head.equals(d.head)) {
          o.myFactories.addAll(d.myFactories);
          o.enemyFactories.addAll(d.enemyFactories);
          o.pathLength = Math.min(o.pathLength, d.pathLength);
          mergedPaths.get(j).addAll(allPaths.get(i));
          done = true;
        }
      }
      if (!done) {
        merged.add(d);
        mergedPaths.add(allPaths.get(i));
      }
    }
    for (int i = 0; i < merged.size(); i++) {
      final Direction d = merged.get(i);
      d.key = d.myFactories.iterator().next() + (d.sea ? "~>" : "->") + d.head;
      final Set<Territory> corridor = new HashSet<>(mergedPaths.get(i));
      for (final Territory t : mergedPaths.get(i)) {
        corridor.addAll(map.getNeighbors(t, Matches.territoryIsImpassable().negate()));
      }
      corridor.forEach(t -> d.corridor.add(t.getName()));
      final Set<Territory> enemyFactories = new HashSet<>();
      d.enemyFactories.forEach(n -> enemyFactories.add(map.getTerritoryOrNull(n)));
      final Set<Territory> myFactories = new HashSet<>();
      d.myFactories.forEach(n -> myFactories.add(map.getTerritoryOrNull(n)));
      fill(
          d,
          corridor,
          enemyFactories,
          myFactories,
          player,
          isFriend,
          isEnemy,
          enemies,
          forecast,
          pus,
          data);
      out.add(d);
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

  private record Search(Map<Territory, Double> dist, Map<Territory, Territory> prev) {
    List<Territory> path(final Territory to) {
      final List<Territory> path = new ArrayList<>();
      Territory c = to;
      while (c != null) {
        path.add(0, c);
        c = prev.get(c);
      }
      return path;
    }
  }

  /**
   * Shortest routes from {@code from}, in turns (A27): a land step 1, a sea step 0.5 (transports
   * and fleets move two), landing from the sea 1. Land only: land steps through passable land.
   */
  private static Search dijkstra(
      final GameMap map, final Set<Territory> from, final boolean landOnly) {
    final Map<Territory, Double> dist = new HashMap<>();
    final Map<Territory, Territory> prev = new HashMap<>();
    final java.util.PriorityQueue<Map.Entry<Territory, Double>> q =
        new java.util.PriorityQueue<>(
            Comparator.comparingDouble((Map.Entry<Territory, Double> e) -> e.getValue())
                .thenComparing(e -> e.getKey().getName()));
    for (final Territory t : from) {
      dist.put(t, 0.0);
      q.add(Map.entry(t, 0.0));
    }
    final Predicate<Territory> impassable = Matches.territoryIsImpassable();
    while (!q.isEmpty()) {
      final Map.Entry<Territory, Double> e = q.poll();
      final Territory t = e.getKey();
      if (e.getValue() > dist.get(t)) {
        continue;
      }
      for (final Territory n : map.getNeighbors(t)) {
        if (impassable.test(n) || (landOnly && n.isWater())) {
          continue;
        }
        final double step = n.isWater() ? 0.5 : 1.0;
        final double nd = e.getValue() + step;
        final Double old = dist.get(n);
        if (old == null || nd < old - 1e-9) {
          dist.put(n, nd);
          prev.put(n, t);
          q.add(Map.entry(n, nd));
        }
      }
    }
    return new Search(dist, prev);
  }

  private static List<Territory> sorted(final Set<Territory> ts) {
    final List<Territory> out = new ArrayList<>(ts);
    out.sort(Comparator.comparing(Territory::getName));
    return out;
  }
}
