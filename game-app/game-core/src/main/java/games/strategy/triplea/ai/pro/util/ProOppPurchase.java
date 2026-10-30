package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.history.Event;
import games.strategy.engine.history.HistoryNode;
import games.strategy.engine.history.Round;
import games.strategy.engine.history.Step;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.PlacementDescription;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.triplea.java.collections.IntegerMap;

/**
 * OPP_PURCHASE_LOG: an observational predictor of each enemy's next purchase and placement, read
 * from game history only (never by running the purchase AI for the enemy). Log only: prints each
 * prediction and, on the next turn, its error against the enemy's actual buy, next to the "repeat
 * last turn" and 3-turn-average baselines. Validated offline in scratch/ai-opp-purchase (forum
 * human games and champion self-play).
 *
 * <p>Model: spend = bank x mean spend ratio of the last 3 turns; unit-type PU mix = exponential
 * average (alpha 0.5) of past buys; placement = per factory (sea placements folded into the
 * adjacent own factory) the mean PU share over the last 4 turns in which that factory was
 * available, lost factories dropped; a factory new since our last look is filled toward its cap (at
 * most 40% of the spend); every factory capped at its production (or the most it ever took).
 */
public final class ProOppPurchase {
  private static final Pattern REMAINING = Pattern.compile("Remaining resources: (\\d+) PUs");
  private static final String TAG = "OPP_PURCHASE_LOG";
  private static final double NEW_FACTORY_MAX_SHARE = 0.4;

  /** Enemy -> factory territory -> round we first saw it owning it (0 = at our first look). */
  private final Map<String, Map<String, Integer>> firstSeen = new HashMap<>();

  /** Enemy -> its predictions waiting for its next turn. */
  private final Map<String, Pending> pending = new HashMap<>();

  private final Map<String, double[]> totals = new TreeMap<>();
  private int checks;
  private long nanos;
  private int runs;

  /** One player turn read from history. */
  private static final class TurnRec {
    final int round;
    final IntegerMap<UnitType> bought = new IntegerMap<>();
    final Map<Territory, IntegerMap<UnitType>> placed = new LinkedHashMap<>();
    int remaining = -1;

    TurnRec(final int round) {
      this.round = round;
    }
  }

  private record Prediction(
      Map<String, Double> cls, Map<String, Double> sites, double power, double hp) {}

  private record Pending(int turnIndex, Map<String, Prediction> byPredictor) {}

  /** Predicts every enemy's next buy and checks the previous predictions; prints both. */
  public void run(final GameData data, final GamePlayer player) {
    final long start = System.nanoTime();
    final int round = data.getSequence().getRound();
    final Map<String, List<TurnRec>> turns;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      turns = readHistory(data);
    }
    final TuvCostsCalculator tuv = new TuvCostsCalculator();
    for (final GamePlayer enemy : ProUtils.getEnemyPlayers(player)) {
      final List<TurnRec> ts = turns.getOrDefault(enemy.getName(), List.of());
      final IntegerMap<UnitType> costs = tuv.getCostsForTuv(enemy);
      final Map<Territory, Integer> factories = factories(data, enemy);
      final Map<String, Integer> seen =
          firstSeen.computeIfAbsent(enemy.getName(), k -> new HashMap<>());
      final boolean firstLook = seen.isEmpty() && !pending.containsKey(enemy.getName());
      final List<Territory> fresh = new ArrayList<>();
      for (final Territory f : factories.keySet()) {
        if (!seen.containsKey(f.getName())) {
          seen.put(f.getName(), firstLook ? 0 : round);
          if (!firstLook) {
            fresh.add(f);
          }
        }
      }
      seen.keySet()
          .removeIf(n -> factories.keySet().stream().noneMatch(t -> t.getName().equals(n)));
      check(enemy, ts, costs, factories);
      if (ts.isEmpty()) {
        continue;
      }
      final int bank = enemy.getResources().getQuantity(Constants.PUS);
      final Map<String, Prediction> preds = new LinkedHashMap<>();
      final double spend = spend(ts, costs, bank);
      preds.put("model", model(enemy, ts, costs, factories, seen, fresh, spend));
      final TurnRec last = ts.get(ts.size() - 1);
      preds.put(
          "last",
          predictionOf(
              enemy, costs, typeShare(last, costs), siteShare(last, costs, factories), spend));
      final List<TurnRec> last3 = ts.subList(Math.max(0, ts.size() - 3), ts.size());
      preds.put(
          "avg3",
          predictionOf(
              enemy,
              costs,
              mean(last3.stream().map(t -> typeShare(t, costs)).toList()),
              mean(last3.stream().map(t -> siteShare(t, costs, factories)).toList()),
              spend));
      pending.put(enemy.getName(), new Pending(ts.size(), preds));
      final Prediction m = preds.get("model");
      System.out.printf(
          "%s predict r%d %s->%s bank=%d spend=%.1f power=%.1f hp=%.1f cls=%s sites=%s%n",
          TAG,
          round,
          player.getName(),
          enemy.getName(),
          bank,
          spend,
          m.power(),
          m.hp(),
          fmt(m.cls()),
          fmt(m.sites()));
    }
    nanos += System.nanoTime() - start;
    runs++;
    System.out.printf(
        "%s cpu %s r%d ms=%.2f mean_ms=%.2f%n",
        TAG, player.getName(), round, (System.nanoTime() - start) / 1e6, nanos / 1e6 / runs);
  }

  /**
   * The model's forecast of each enemy's next placement, in PUs per site (an own land factory's
   * name, or {@code sea:<zone>} for a sea zone next to none), with no logging and no error
   * tracking. Enemies without purchase history are left out. Keeps its own factory bookkeeping, so
   * use a separate instance from the one {@link #run} logs with.
   */
  public Map<String, Map<String, Double>> forecastPlacement(
      final GameData data, final GamePlayer player, final List<GamePlayer> enemies) {
    final int round = data.getSequence().getRound();
    final Map<String, List<TurnRec>> turns;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      turns = readHistory(data);
    }
    final TuvCostsCalculator tuv = new TuvCostsCalculator();
    final Map<String, Map<String, Double>> out = new HashMap<>();
    for (final GamePlayer enemy : enemies) {
      final List<TurnRec> ts = turns.getOrDefault(enemy.getName(), List.of());
      final IntegerMap<UnitType> costs = tuv.getCostsForTuv(enemy);
      final Map<Territory, Integer> factories = factories(data, enemy);
      final Map<String, Integer> seen =
          firstSeen.computeIfAbsent(enemy.getName(), k -> new HashMap<>());
      final boolean firstLook = seen.isEmpty();
      final List<Territory> fresh = new ArrayList<>();
      for (final Territory f : factories.keySet()) {
        if (!seen.containsKey(f.getName())) {
          seen.put(f.getName(), firstLook ? 0 : round);
          if (!firstLook) {
            fresh.add(f);
          }
        }
      }
      seen.keySet()
          .removeIf(n -> factories.keySet().stream().noneMatch(t -> t.getName().equals(n)));
      if (ts.isEmpty()) {
        continue;
      }
      final double spend = spend(ts, costs, enemy.getResources().getQuantity(Constants.PUS));
      final Prediction m = model(enemy, ts, costs, factories, seen, fresh, spend);
      final Map<String, Double> pus = new HashMap<>();
      m.sites().forEach((k, v) -> pus.put(k, v * spend));
      out.put(enemy.getName(), pus);
    }
    return out;
  }

  private void check(
      final GamePlayer enemy,
      final List<TurnRec> ts,
      final IntegerMap<UnitType> costs,
      final Map<Territory, Integer> factories) {
    final Pending p = pending.get(enemy.getName());
    if (p == null || ts.size() <= p.turnIndex()) {
      return;
    }
    pending.remove(enemy.getName());
    final TurnRec actual = ts.get(p.turnIndex());
    final Map<UnitType, Double> q = typeShare(actual, costs);
    if (q.isEmpty()) {
      return;
    }
    final Map<String, Double> cls = classes(enemy, q, costs);
    final Map<String, Double> sites = siteShare(actual, costs, factories);
    double power = 0;
    double hp = 0;
    for (final Map.Entry<UnitType, Integer> e : actual.bought.entrySet()) {
      final UnitAttachment ua = e.getKey().getUnitAttachment();
      power += e.getValue() * (ua.getAttack(enemy) + ua.getDefense(enemy));
      hp += e.getValue() * ua.getHitPoints();
    }
    checks++;
    final StringBuilder sb = new StringBuilder();
    for (final Map.Entry<String, Prediction> e : p.byPredictor().entrySet()) {
      final Prediction pr = e.getValue();
      final double[] err = {
        sites.isEmpty() ? 0 : tv(pr.sites(), sites),
        tv(pr.cls(), cls),
        power > 0 ? Math.abs(pr.power() - power) / power : 0,
        hp > 0 ? Math.abs(pr.hp() - hp) / hp : 0
      };
      final double[] tot = totals.computeIfAbsent(e.getKey(), k -> new double[4]);
      for (int i = 0; i < 4; i++) {
        tot[i] += err[i];
      }
      sb.append(
          String.format(
              " | %s site=%.3f cls=%.3f pow=%.3f hp=%.3f (mean %.3f %.3f %.3f %.3f)",
              e.getKey(),
              err[0],
              err[1],
              err[2],
              err[3],
              tot[0] / checks,
              tot[1] / checks,
              tot[2] / checks,
              tot[3] / checks));
    }
    System.out.printf(
        "%s check %s r%d n=%d actual power=%.1f hp=%.1f%s%n",
        TAG, enemy.getName(), actual.round, checks, power, hp, sb);
  }

  private Prediction model(
      final GamePlayer enemy,
      final List<TurnRec> ts,
      final IntegerMap<UnitType> costs,
      final Map<Territory, Integer> factories,
      final Map<String, Integer> seen,
      final List<Territory> fresh,
      final double spend) {
    // unit-type mix: exponential average of past buys
    Map<UnitType, Double> q = null;
    for (final TurnRec t : ts) {
      final Map<UnitType, Double> s = typeShare(t, costs);
      if (s.isEmpty()) {
        continue;
      }
      if (q == null) {
        q = new HashMap<>(s);
      } else {
        final Map<UnitType, Double> next = new HashMap<>();
        for (final UnitType u : union(q.keySet(), s.keySet())) {
          next.put(u, 0.5 * q.getOrDefault(u, 0.0) + 0.5 * s.getOrDefault(u, 0.0));
        }
        q = next;
      }
    }
    if (q == null) {
      q = Map.of();
    }
    // placement: mean share per factory over the window turns in which it was available
    final List<TurnRec> window = new ArrayList<>();
    for (final TurnRec t : ts.subList(Math.max(0, ts.size() - 4), ts.size())) {
      if (!t.placed.isEmpty()) {
        window.add(t);
      }
    }
    final Map<String, Double> shares = new HashMap<>();
    final List<Map<String, Double>> windowShares =
        window.stream().map(t -> siteShare(t, costs, factories)).toList();
    for (final String site : union(windowShares)) {
      final Territory ft = byName(factories, site);
      final boolean water = ft == null;
      if (water && !site.startsWith("sea:")) {
        continue; // a land site that is no longer the enemy's factory
      }
      double sum = 0;
      int n = 0;
      for (int i = 0; i < window.size(); i++) {
        if (water || window.get(i).round >= seen.getOrDefault(site, 0)) {
          sum += windowShares.get(i).getOrDefault(site, 0.0);
          n++;
        }
      }
      if (n == 0) {
        for (final Map<String, Double> s : windowShares) {
          sum += s.getOrDefault(site, 0.0);
        }
        n = windowShares.size();
      }
      shares.put(site, sum / n);
    }
    normalize(shares);
    final double landCost = avgLandCost(enemy, ts, costs);
    if (spend > 0) {
      for (final Territory f : fresh) {
        final double want =
            Math.min(factories.get(f) * landCost, NEW_FACTORY_MAX_SHARE * spend) / spend;
        final double rest = shares.values().stream().mapToDouble(Double::doubleValue).sum();
        if (rest > 0) {
          shares.replaceAll((k, v) -> v * (1 - want) / rest);
        }
        shares.put(f.getName(), want);
      }
      applyCaps(shares, ts, factories, landCost, spend);
    }
    return predictionOf(enemy, costs, q, shares, spend);
  }

  private static void applyCaps(
      final Map<String, Double> shares,
      final List<TurnRec> ts,
      final Map<Territory, Integer> factories,
      final double landCost,
      final double spend) {
    final Map<String, Double> cap = new HashMap<>();
    for (final Map.Entry<Territory, Integer> f : factories.entrySet()) {
      int most = 0;
      for (final TurnRec t : ts) {
        final IntegerMap<UnitType> p = t.placed.get(f.getKey());
        most = Math.max(most, p == null ? 0 : p.totalValues());
      }
      cap.put(f.getKey().getName(), Math.max(f.getValue(), most) * Math.max(1.0, landCost) / spend);
    }
    for (int iter = 0; iter < 4; iter++) {
      double over = 0;
      for (final Map.Entry<String, Double> e : shares.entrySet()) {
        final Double c = cap.get(e.getKey());
        if (c != null && e.getValue() > c) {
          over += e.getValue() - c;
        }
      }
      if (over <= 1e-9) {
        break;
      }
      double free = 0;
      for (final Map.Entry<String, Double> e : shares.entrySet()) {
        final Double c = cap.get(e.getKey());
        if (c == null || c - e.getValue() > 1e-9) {
          free += e.getValue();
        }
      }
      if (free <= 0) {
        break;
      }
      final Map<String, Double> next = new HashMap<>();
      for (final Map.Entry<String, Double> e : shares.entrySet()) {
        final Double c = cap.get(e.getKey());
        if (c != null && e.getValue() > c) {
          next.put(e.getKey(), c);
        } else if (c == null || c - e.getValue() > 1e-9) {
          next.put(e.getKey(), e.getValue() + over * e.getValue() / free);
        } else {
          next.put(e.getKey(), e.getValue());
        }
      }
      shares.putAll(next);
    }
    normalize(shares);
  }

  private static Prediction predictionOf(
      final GamePlayer enemy,
      final IntegerMap<UnitType> costs,
      final Map<UnitType, Double> q,
      final Map<String, Double> sites,
      final double spend) {
    double power = 0;
    double hp = 0;
    for (final Map.Entry<UnitType, Double> e : q.entrySet()) {
      final int cost = costs.getInt(e.getKey());
      if (cost <= 0) {
        continue;
      }
      final double n = spend * e.getValue() / cost;
      final UnitAttachment ua = e.getKey().getUnitAttachment();
      power += n * (ua.getAttack(enemy) + ua.getDefense(enemy));
      hp += n * ua.getHitPoints();
    }
    return new Prediction(classes(enemy, q, costs), sites, power, hp);
  }

  /** Bank times the mean spent/bank ratio of the last 3 turns whose bank is known. */
  private static double spend(
      final List<TurnRec> ts, final IntegerMap<UnitType> costs, final int bank) {
    double sum = 0;
    int n = 0;
    for (final TurnRec t : ts.subList(Math.max(0, ts.size() - 3), ts.size())) {
      final double spent = pus(t.bought, costs);
      if (t.remaining >= 0 && spent + t.remaining > 0) {
        sum += spent / (spent + t.remaining);
        n++;
      }
    }
    return bank * (n == 0 ? 1.0 : sum / n);
  }

  private static double avgLandCost(
      final GamePlayer enemy, final List<TurnRec> ts, final IntegerMap<UnitType> costs) {
    double c = 0;
    int n = 0;
    for (final TurnRec t : ts.subList(Math.max(0, ts.size() - 3), ts.size())) {
      for (final Map.Entry<UnitType, Integer> e : t.bought.entrySet()) {
        if (costs.getInt(e.getKey()) > 0 && landCombat(enemy, e.getKey())) {
          c += (double) e.getValue() * costs.getInt(e.getKey());
          n += e.getValue();
        }
      }
    }
    return n == 0 ? 3.5 : c / n;
  }

  /** Classes: inf (cheapest land combat unit), land, air, warship, transport, infra. */
  private static Map<String, Double> classes(
      final GamePlayer enemy, final Map<UnitType, Double> q, final IntegerMap<UnitType> costs) {
    int cheapest = Integer.MAX_VALUE;
    for (final Map.Entry<UnitType, Integer> e : costs.entrySet()) {
      if (e.getValue() > 0 && landCombat(enemy, e.getKey())) {
        cheapest = Math.min(cheapest, e.getValue());
      }
    }
    final Map<String, Double> out = new TreeMap<>();
    for (final Map.Entry<UnitType, Double> e : q.entrySet()) {
      final UnitType u = e.getKey();
      final UnitAttachment ua = u.getUnitAttachment();
      final String c;
      if (ua.isAir()) {
        c = "air";
      } else if (ua.isSea()) {
        c = ua.getTransportCapacity() > 0 && ua.getCarrierCapacity() <= 0 ? "transport" : "warship";
      } else if (!landCombat(enemy, u)) {
        c = "infra";
      } else {
        c = costs.getInt(u) > 0 && costs.getInt(u) <= cheapest ? "inf" : "land";
      }
      out.merge(c, e.getValue(), Double::sum);
    }
    return out;
  }

  private static boolean landCombat(final GamePlayer enemy, final UnitType u) {
    final UnitAttachment ua = u.getUnitAttachment();
    return !ua.isAir()
        && !ua.isSea()
        && ua.getAttack(enemy) + ua.getDefense(enemy) > 0
        && !Matches.unitTypeCanProduceUnits().test(u);
  }

  private static Map<UnitType, Double> typeShare(
      final TurnRec t, final IntegerMap<UnitType> costs) {
    final Map<UnitType, Double> out = new HashMap<>();
    for (final Map.Entry<UnitType, Integer> e : t.bought.entrySet()) {
      if (e.getValue() > 0) {
        out.put(e.getKey(), (double) e.getValue() * costs.getInt(e.getKey()));
      }
    }
    normalize(out);
    return out;
  }

  /**
   * Placement PU share by site; a sea zone folds into the adjacent own factory (most production).
   */
  private static Map<String, Double> siteShare(
      final TurnRec t, final IntegerMap<UnitType> costs, final Map<Territory, Integer> factories) {
    final Map<String, Double> out = new HashMap<>();
    for (final Map.Entry<Territory, IntegerMap<UnitType>> e : t.placed.entrySet()) {
      double pus = 0;
      for (final Map.Entry<UnitType, Integer> u : e.getValue().entrySet()) {
        pus += (double) Math.max(0, u.getValue()) * costs.getInt(u.getKey());
      }
      if (pus > 0) {
        out.merge(site(e.getKey(), factories), pus, Double::sum);
      }
    }
    normalize(out);
    return out;
  }

  private static String site(final Territory t, final Map<Territory, Integer> factories) {
    if (!t.isWater()) {
      return t.getName();
    }
    Territory best = null;
    for (final Territory n : t.getData().getMap().getNeighbors(t)) {
      final Integer prod = factories.get(n);
      if (prod != null
          && (best == null
              || prod > factories.get(best)
              || (prod.equals(factories.get(best)) && n.getName().compareTo(best.getName()) > 0))) {
        best = n;
      }
    }
    return best == null ? "sea:" + t.getName() : best.getName();
  }

  private static Map<Territory, Integer> factories(final GameData data, final GamePlayer enemy) {
    final Map<Territory, Integer> out = new LinkedHashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(enemy) && t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
        out.put(t, TerritoryAttachment.getProduction(t));
      }
    }
    return out;
  }

  /** Every player's purchase turns (units bought, PUs left, placements), in history order. */
  private static Map<String, List<TurnRec>> readHistory(final GameData data) {
    final Map<String, List<TurnRec>> out = new HashMap<>();
    final Enumeration<?> en = ((HistoryNode) data.getHistory().getRoot()).preorderEnumeration();
    int round = 0;
    String stepName = "";
    GamePlayer stepPlayer = null;
    final Map<String, TurnRec> current = new HashMap<>();
    while (en.hasMoreElements()) {
      final Object o = en.nextElement();
      if (o instanceof Round r) {
        round = r.getRoundNo();
      } else if (o instanceof Step s) {
        stepName = s.getStepName() == null ? "" : s.getStepName();
        stepPlayer = s.getPlayerId().orElse(null);
      } else if (o instanceof Event e && stepPlayer != null && !stepName.contains("Bid")) {
        final String desc = e.getDescription() == null ? "" : e.getDescription();
        final Object rd = e.getRenderingData();
        final String name = stepPlayer.getName();
        if (stepName.endsWith("Purchase") && desc.contains(" buy ")) {
          TurnRec t = current.get(name);
          if (t == null || t.round != round) {
            t = new TurnRec(round);
            current.put(name, t);
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(t);
          }
          if (rd instanceof Collection<?> units) {
            for (final Object u : units) {
              if (u instanceof Unit unit) {
                t.bought.add(unit.getType(), 1);
              }
            }
          }
          final Matcher m = REMAINING.matcher(desc);
          if (m.find()) {
            t.remaining = Integer.parseInt(m.group(1));
          }
        } else if (stepName.contains("Place") && rd instanceof PlacementDescription pd) {
          final TurnRec t = current.get(name);
          if (t == null || t.round != round) {
            continue;
          }
          final int sign = desc.contains("undo") ? -1 : 1;
          final IntegerMap<UnitType> m =
              t.placed.computeIfAbsent(pd.getTerritory(), k -> new IntegerMap<>());
          for (final Unit u : pd.getUnits()) {
            if (u.isOwnedBy(stepPlayer)) {
              m.add(u.getType(), sign);
            }
          }
        }
      }
    }
    return out;
  }

  private static double pus(final IntegerMap<UnitType> units, final IntegerMap<UnitType> costs) {
    double s = 0;
    for (final Map.Entry<UnitType, Integer> e : units.entrySet()) {
      s += (double) e.getValue() * costs.getInt(e.getKey());
    }
    return s;
  }

  private static <K> void normalize(final Map<K, Double> m) {
    final double s = m.values().stream().mapToDouble(Double::doubleValue).sum();
    if (s > 0) {
      m.replaceAll((k, v) -> v / s);
    }
  }

  private static <K> Map<K, Double> mean(final List<Map<K, Double>> ms) {
    final List<Map<K, Double>> nonEmpty = ms.stream().filter(m -> !m.isEmpty()).toList();
    final Map<K, Double> out = new HashMap<>();
    for (final Map<K, Double> m : nonEmpty) {
      m.forEach((k, v) -> out.merge(k, v / nonEmpty.size(), Double::sum));
    }
    return out;
  }

  private static <K> double tv(final Map<K, Double> a, final Map<K, Double> b) {
    double s = 0;
    for (final K k : union(a.keySet(), b.keySet())) {
      s += Math.abs(a.getOrDefault(k, 0.0) - b.getOrDefault(k, 0.0));
    }
    return s / 2;
  }

  private static <K> List<K> union(final Collection<K> a, final Collection<K> b) {
    final List<K> out = new ArrayList<>(a);
    for (final K k : b) {
      if (!a.contains(k)) {
        out.add(k);
      }
    }
    return out;
  }

  private static List<String> union(final List<Map<String, Double>> ms) {
    final List<String> out = new ArrayList<>();
    for (final Map<String, Double> m : ms) {
      for (final String k : m.keySet()) {
        if (!out.contains(k)) {
          out.add(k);
        }
      }
    }
    out.sort(String::compareTo);
    return out;
  }

  private static Territory byName(final Map<Territory, Integer> factories, final String name) {
    for (final Territory t : factories.keySet()) {
      if (t.getName().equals(name)) {
        return t;
      }
    }
    return null;
  }

  private static String fmt(final Map<String, Double> m) {
    final StringBuilder sb = new StringBuilder("{");
    new TreeMap<>(m)
        .forEach(
            (k, v) -> {
              if (v >= 0.005) {
                sb.append(sb.length() > 1 ? "," : "")
                    .append(k)
                    .append('=')
                    .append(String.format("%.2f", v));
              }
            });
    return sb.append('}').toString();
  }
}
