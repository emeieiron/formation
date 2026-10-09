import com.android.cli.interact.InteractProto;
import com.android.cli.interact.serialization.Json;
import com.android.cli.interact.server.StreamExtKt;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.EnumSet;

public class AndroidLayoutReader {
  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    var request = InteractProto.ServerRequest.newBuilder();
    request.getLayoutBuilder().setIdleWaitMs(0).setGlobalWaitMs(0);
    var flags = EnumSet.of(Json.Flag.FLATTEN);
    var input = new BufferedReader(new InputStreamReader(System.in));
    while (input.readLine() != null) {
      try (var socket = new Socket("127.0.0.1", port)) {
        socket.setSoTimeout(30_000);
        StreamExtKt.writeProto(socket.getOutputStream(), request.build());
        var response = StreamExtKt.readProto(socket.getInputStream(), InteractProto.ServerResponse.parser()).getLayout();
        System.out.println(response.hasRoot() ? Json.INSTANCE.serialize(response.getRoot(), flags) : "[]");
        System.out.flush();
      }
    }
  }
}
