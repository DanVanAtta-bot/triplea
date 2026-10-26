package games.strategy.triplea.ai.kata;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.ProAiPatch;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.util.TransportUtils;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;

/**
 * The player under test. During the tested turn it runs the variant (a patch set, or a script with
 * the Hard AI filling missing phases) and records every purchase, move and placement it makes.
 * After that turn it plays as {@code laterPatches} Hard AI.
 */
public final class KataAi extends ProAi {
  private final Set<ProAiPatch> turnPatches;
  private final Set<ProAiPatch> laterPatches;
  private @Nullable KataScript script;
  private final List<String> actions = new ArrayList<>();
  private boolean testedTurn;
  private String phase = "";
  // Prefix for recorded actions, so scripted ones stand apart from the AI's.
  private String source = "";

  public KataAi(
      final String name,
      final Set<ProAiPatch> turnPatches,
      final Set<ProAiPatch> laterPatches,
      final @Nullable KataScript script) {
    super(name, "Hard AI (kata)", turnPatches);
    this.turnPatches = turnPatches;
    this.laterPatches = laterPatches;
    this.script = script;
  }

  /** Replaces the script for the next tested turn (null: the variant is the patch set alone). */
  public void setScript(final @Nullable KataScript script) {
    this.script = script;
  }

  /** Switches between the variant (tested turn) and the plain follow-up behaviour. */
  public void setTestedTurn(final boolean tested) {
    testedTurn = tested;
    getProData().setPatches(tested ? turnPatches : laterPatches);
  }

  public List<String> getActions() {
    return List.copyOf(actions);
  }

  @Override
  protected void purchase(
      final boolean bid,
      final int pusToSpend,
      final IPurchaseDelegate purchaseDelegate,
      final GameData data,
      final GamePlayer player) {
    if (!testedTurn) {
      super.purchase(bid, pusToSpend, purchaseDelegate, data, player);
      return;
    }
    phase = bid ? "bid" : "purchase";
    final IPurchaseDelegate recording = recording(IPurchaseDelegate.class, purchaseDelegate);
    if (script == null || script.purchase == null || bid) {
      super.purchase(bid, pusToSpend, recording, data, player);
      return;
    }
    final IntegerMap<ProductionRule> rules = new IntegerMap<>();
    script.purchase.forEach((unit, count) -> rules.add(ruleFor(player, unit), count));
    source = "[script] ";
    final String error = recording.purchase(rules);
    source = "";
    if (error != null) {
      // Unaffordable script (eg lower income than planned for): the AI buys instead.
      super.purchase(false, pusToSpend, recording, data, player);
      return;
    }
    // The AI's stored plans assumed its own purchase; let it re-plan moves and placement.
    discardStoredMovePlans();
    discardStoredPurchasePlan();
  }

  @Override
  protected void move(
      final boolean nonCombat,
      final IMoveDelegate moveDel,
      final GameData data,
      final GamePlayer player) {
    if (!testedTurn) {
      super.move(nonCombat, moveDel, data, player);
      return;
    }
    phase = nonCombat ? "nonCombat" : "combat";
    final IMoveDelegate recording = recording(IMoveDelegate.class, moveDel);
    final List<KataScript.Move> moves =
        script == null ? null : nonCombat ? script.nonCombatMoves : script.combatMoves;
    if (moves == null) {
      super.move(nonCombat, recording, data, player);
      return;
    }
    source = "[script] ";
    for (final KataScript.Move move : moves) {
      scriptedMove(move, recording, data, player);
    }
    source = "";
    discardStoredMovePlans();
    if (script.aiFinishesMoves) {
      super.move(nonCombat, recording, data, player);
    }
  }

  @Override
  protected void place(
      final boolean bid,
      final IAbstractPlaceDelegate placeDelegate,
      final GameState data,
      final GamePlayer player) {
    if (!testedTurn) {
      super.place(bid, placeDelegate, data, player);
      return;
    }
    phase = bid ? "bidPlace" : "place";
    final IAbstractPlaceDelegate recording = recording(IAbstractPlaceDelegate.class, placeDelegate);
    if (script != null && script.place != null && !bid) {
      source = "[script] ";
      for (final KataScript.Placement placement : script.place) {
        final Territory at = territory(data, placement.territory);
        final List<Unit> units = new ArrayList<>();
        placement.units.forEach(
            (type, count) -> {
              final List<Unit> ofType =
                  player.getUnitCollection().getMatches(Matches.unitIsOfType(unitType(data, type)));
              ofType.removeAll(units);
              units.addAll(ofType.subList(0, Math.min(count, ofType.size())));
              if (ofType.size() < count) {
                record(
                    String.format(
                        "place: only %d of %d %s left to place", ofType.size(), count, type));
              }
            });
        recording.placeUnits(units, at);
      }
      source = "";
      // Leftover units go wherever the AI would put them.
      discardStoredPurchasePlan();
    }
    if (!player.getUnits().isEmpty()) {
      super.place(bid, recording, data, player);
    }
  }

  private void scriptedMove(
      final KataScript.Move move,
      final IMoveDelegate moveDel,
      final GameData data,
      final GamePlayer player) {
    final Territory from = territory(data, move.from);
    final List<Unit> units = new ArrayList<>();
    move.units.forEach(
        (type, count) -> {
          final List<Unit> candidates =
              from.getUnitCollection()
                  .getMatches(
                      Matches.unitIsOwnedBy(player)
                          .and(Matches.unitIsOfType(unitType(data, type))));
          candidates.removeAll(units);
          candidates.sort(Comparator.comparing(Unit::getMovementLeft).reversed());
          units.addAll(candidates.subList(0, Math.min(count, candidates.size())));
          if (candidates.size() < count) {
            record(
                String.format(
                    "%s: script wants %d %s in %s, found %d",
                    phase, count, type, from.getName(), candidates.size()));
          }
        });
    if (units.isEmpty()) {
      record(phase + ": SKIPPED (no units) " + describeScripted(move));
      return;
    }
    final Optional<Route> route = route(move, from, units, data, player);
    if (route.isEmpty() || !route.get().hasSteps()) {
      record(phase + ": SKIPPED (no route) " + describeScripted(move));
      return;
    }
    final Map<Unit, Unit> transports;
    if (route.get().isSeaLoad()) {
      transports =
          TransportUtils.mapTransportsToLoad(
              units,
              route.get().getEnd().getUnitCollection().getMatches(Matches.unitIsOwnedBy(player)));
    } else if (route.get().isSeaUnload()) {
      transports = TransportUtils.mapTransportsAlreadyLoaded(units, from.getUnits());
    } else {
      transports = Map.of();
    }
    moveDel.performMove(new MoveDescription(units, route.get(), transports));
  }

  private static Optional<Route> route(
      final KataScript.Move move,
      final Territory from,
      final Collection<Unit> units,
      final GameData data,
      final GamePlayer player) {
    if (move.route != null) {
      final List<Territory> path = move.route.stream().map(name -> territory(data, name)).toList();
      if (!path.get(0).equals(from)) {
        throw new IllegalArgumentException("route must start at 'from': " + move.route);
      }
      return Optional.of(new Route(path));
    }
    if (move.to == null) {
      throw new IllegalArgumentException("move needs 'to' or 'route': from " + move.from);
    }
    final Predicate<Territory> through;
    if (units.stream().allMatch(Matches.unitIsAir())) {
      through = t -> true;
    } else if (units.stream().allMatch(Matches.unitIsSea())) {
      through = Matches.territoryIsWater();
    } else {
      through = Matches.territoryIsLand();
    }
    return data.getMap().getRouteForUnits(from, territory(data, move.to), through, units, player);
  }

  private static String describeScripted(final KataScript.Move move) {
    return move.units + " " + move.from + " -> " + (move.route != null ? move.route : move.to);
  }

  /** Wraps a remote delegate so every action the AI or script takes is logged with its result. */
  private <T> T recording(final Class<T> type, final T target) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.isDefault()) {
                // Run the default body against this proxy so its abstract call gets recorded; the
                // remote delegate proxies reject default methods.
                return InvocationHandler.invokeDefault(proxy, method, args);
              }
              final Object result;
              try {
                result = method.invoke(target, args);
              } catch (final InvocationTargetException e) {
                throw e.getCause();
              }
              switch (method.getName()) {
                case "purchase" -> record(describePurchase(args[0], result));
                case "purchaseRepair" -> record(phase + ": repair " + outcome(result));
                case "performMove" ->
                    record(
                        String.format(
                            "%s: %s %s%s",
                            phase,
                            units(((MoveDescription) args[0]).getUnits()),
                            ((MoveDescription) args[0])
                                .getRoute().getAllTerritories().stream()
                                    .map(Territory::getName)
                                    .collect(Collectors.joining(" -> ")),
                            outcome(result)));
                case "placeUnits" ->
                    record(
                        String.format(
                            "%s: %s @ %s%s",
                            phase,
                            units((Collection<?>) args[0]),
                            ((Territory) args[1]).getName(),
                            outcome(result)));
                default -> {
                  // queries are not actions
                }
              }
              return result;
            }));
  }

  private void record(final String action) {
    actions.add(source + action);
  }

  @SuppressWarnings("unchecked")
  private String describePurchase(final Object rules, final Object result) {
    final Map<String, Integer> bought = new TreeMap<>();
    ((IntegerMap<ProductionRule>) rules)
        .entrySet()
        .forEach(
            e ->
                bought.put(
                    e.getKey().getResults().keySet().stream()
                        .map(r -> r.getName())
                        .collect(Collectors.joining("+")),
                    e.getValue()));
    return phase + ": " + bought + outcome(result);
  }

  private static String outcome(final Object result) {
    if (result instanceof Optional<?> optional) {
      return optional.map(error -> " FAILED: " + error).orElse("");
    }
    return result == null ? "" : " FAILED: " + result;
  }

  static String units(final Collection<?> units) {
    final Map<String, Integer> byType = new TreeMap<>();
    for (final Object unit : units) {
      byType.merge(((Unit) unit).getType().getName(), 1, Integer::sum);
    }
    return byType.entrySet().stream()
        .map(e -> e.getValue() + " " + e.getKey())
        .collect(Collectors.joining(", "));
  }

  private static ProductionRule ruleFor(final GamePlayer player, final String unit) {
    return player.getProductionFrontier().getRules().stream()
        .filter(
            rule ->
                rule.getName().equals(unit)
                    || rule.getResults().keySet().stream().anyMatch(r -> r.getName().equals(unit)))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    player.getName()
                        + " cannot buy "
                        + unit
                        + "; frontier has "
                        + player.getProductionFrontier().getRules().stream()
                            .map(ProductionRule::getName)
                            .toList()));
  }

  static Territory territory(final GameState data, final String name) {
    final Territory t = data.getMap().getTerritoryOrNull(name);
    if (t == null) {
      throw new IllegalArgumentException("No territory named '" + name + "'");
    }
    return t;
  }

  private static UnitType unitType(final GameState data, final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }
}
