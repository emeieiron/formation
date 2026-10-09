import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.request.EventRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A debugger fixture for testing the final screen and settlement, never production gameplay. */
public class CaravanCheckpoint {
  private static Value field(ObjectReference owner, String name) {
    return owner.getValue(owner.referenceType().fieldByName(name));
  }

  private static ObjectReference object(ObjectReference owner, String name) {
    return (ObjectReference) field(owner, name);
  }

  private static ObjectReference copy(
      ObjectReference original,
      ThreadReference thread,
      List<String> fields,
      Map<String, Value> replacements)
      throws Exception {
    Method method =
        original.referenceType().allMethods().stream()
            .filter(
                m ->
                    (m.name().equals("copy") || m.name().startsWith("copy-"))
                        && m.argumentTypeNames().size() == fields.size())
            .findFirst()
            .orElseThrow();
    List<Value> arguments = new ArrayList<>();
    for (String name : fields)
      arguments.add(replacements.getOrDefault(name, field(original, name)));
    return (ObjectReference)
        original.invokeMethod(thread, method, arguments, ObjectReference.INVOKE_SINGLE_THREADED);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected a forwarded debug port");
    AttachingConnector connector =
        Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst()
            .orElseThrow();
    var options = connector.defaultArguments();
    options.get("hostname").setValue("127.0.0.1");
    options.get("port").setValue(args[0]);
    options.get("timeout").setValue("15000");
    VirtualMachine vm = connector.attach(options);
    try {
      vm.suspend();
      if (!vm.canGetInstanceInfo())
        throw new IllegalStateException("Debugger cannot inspect instances");
      var platforms = vm.classesByName("xyz.mcxross.formation.platform.AndroidPlatform");
      if (platforms.size() != 1 || platforms.get(0).instances(2).size() != 1) {
        throw new IllegalStateException("Expected one Android platform instance");
      }
      ObjectReference config = object(platforms.get(0).instances(1).get(0), "config");
      String cluster = ((StringReference) field(config, "cluster")).value();
      boolean developer = ((BooleanValue) field(config, "developer")).value();
      if (!developer || !cluster.equals("devnet")) {
        throw new IllegalStateException(
            "Checkpoint requires the actual app configuration to be debug/devnet");
      }
      var games = vm.classesByName("xyz.mcxross.formation.caravan.CaravanGame");
      if (games.size() != 1 || games.get(0).instances(2).size() != 1) {
        throw new IllegalStateException("Expected one live Caravan game");
      }
      ObjectReference game = games.get(0).instances(1).get(0);
      // Constructors/copy calls require a thread suspended by a debugger event, rather than an
      // arbitrary VM suspension. Stop at the existing host tick; no production hook is added.
      var tick = games.get(0).methodsByName("tick").get(0);
      var request = vm.eventRequestManager().createBreakpointRequest(tick.location());
      request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      request.enable();
      vm.resume();
      ThreadReference thread = null;
      long deadline = System.nanoTime() + 15_000_000_000L;
      while (thread == null && System.nanoTime() < deadline) {
        var events = vm.eventQueue().remove(1000);
        if (events == null) continue;
        for (var event : events) {
          if (event instanceof BreakpointEvent entry && entry.request().equals(request)) {
            thread = entry.thread();
            break;
          }
        }
        if (thread == null) events.resume();
      }
      request.disable();
      if (thread == null) throw new IllegalStateException("No host tick reached the debugger");
      if (!object(game, "status").referenceType().name().contains("GameStatus$Running")) {
        throw new IllegalStateException("Caravan is not running");
      }
      ObjectReference state = object(game, "state");
      int target = ((IntegerValue) field(state, "targetSteps")).value();
      if (target != 15000) throw new IllegalStateException("Unexpected step target");
      ObjectReference walkers = object(state, "walkers");
      if (!walkers.referenceType().name().equals("java.util.ArrayList")) {
        throw new IllegalStateException(
            "Unsupported walker collection; checkpoint was not applied");
      }
      int size = ((IntegerValue) field(walkers, "size")).value();
      if (size < 6 || size > 16) throw new IllegalStateException("Invalid Caravan squad size");
      ArrayReference elements = (ArrayReference) field(walkers, "elementData");
      ClassType listType = (ClassType) walkers.referenceType();
      ObjectReference replacement =
          listType.newInstance(
              thread,
              listType.methodsByName("<init>", "()V").get(0),
              List.of(),
              ObjectReference.INVOKE_SINGLE_THREADED);
      replacement.disableCollection();
      Method add = listType.methodsByName("add", "(Ljava/lang/Object;)Z").get(0);
      ClassType statusType =
          (ClassType) vm.classesByName("xyz.mcxross.formation.caravan.WalkerStatus").get(0);
      Value pacing = statusType.getValue(statusType.fieldByName("Pacing"));
      for (int i = 0; i < size; i++) {
        ObjectReference walker = (ObjectReference) elements.getValue(i);
        if (!walker.referenceType().name().equals("xyz.mcxross.formation.caravan.WalkerState")) {
          throw new IllegalStateException("Unexpected walker type");
        }
        ObjectReference seeded =
            copy(
                walker,
                thread,
                List.of("player", "steps", "lastStepAt", "lastStrideIndex", "cadence", "status"),
                Map.of(
                    "steps",
                    vm.mirrorOf(target - 1),
                    "lastStrideIndex",
                    vm.mirrorOf(target - 1),
                    "lastStepAt",
                    vm.mirrorOf(0L),
                    "status",
                    pacing));
        replacement.invokeMethod(
            thread, add, List.of(seeded), ObjectReference.INVOKE_SINGLE_THREADED);
      }
      ObjectReference seeded =
          copy(
              state,
              thread,
              List.of(
                  "targetSteps",
                  "startAt",
                  "endsAt",
                  "walkers",
                  "minSteps",
                  "maxSteps",
                  "groupCadence",
                  "pausedByPackRule",
                  "timeRemainingMs"),
              Map.of(
                  "walkers",
                  replacement,
                  "minSteps",
                  vm.mirrorOf(target - 1),
                  "maxSteps",
                  vm.mirrorOf(target - 1),
                  "pausedByPackRule",
                  vm.mirrorOf(false)));
      // Only publish the fully constructed replacement; immutable historical states stay intact.
      game.setValue(game.referenceType().fieldByName("state"), seeded);
      replacement.enableCollection();
      System.out.println(
          "CHECKPOINT FIXTURE: " + size + " players at 14999/15000; target and deadline unchanged");
    } finally {
      vm.resume();
      vm.dispose();
    }
  }
}
