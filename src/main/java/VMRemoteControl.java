import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE;

public class VMRemoteControl {


    private static final String DLL = "VoicemeeterRemote64.dll";

    private static final String valueName = "UninstallString";

    private static final String vmRegPath =
            "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\VB:Voicemeeter {17359A74-1236-5467}";

    private static final String DLL_PATH =
            Paths.get(Advapi32Util.registryGetStringValue(HKEY_LOCAL_MACHINE,
                    vmRegPath, valueName)).getParent().resolve(DLL).toString();

    interface VoicemeeterRemoteAPI extends Library {
        VoicemeeterRemoteAPI INSTANCE = Native.load(DLL_PATH, VoicemeeterRemoteAPI.class);

        int VBVMR_Login();

        int VBVMR_GetVoicemeeterVersion(Pointer version);

        int VBVMR_IsParametersDirty();

        int VBVMR_SetParameterFloat(Pointer paramName, float value);

        int VBVMR_GetParameterFloat(Pointer paramName, Pointer value);

        int VBVMR_GetLevel(int type, int channel, Pointer value);
    }

    private static Pointer getPointer(int size) {
        return new Memory(size);
    }

    private static Pointer getStringPointer(String str) {
        int size = str.getBytes().length + 1;
        Memory m = new Memory(size);
        m.setString(0, str);
        return m;
    }

    public static void login() {
        VoicemeeterRemoteAPI.INSTANCE.VBVMR_Login();
    }

    public static float getParameterFloat(String parameterName) {
        Pointer paramName = getStringPointer(parameterName);
        Pointer paramValue = getPointer(4);
        int val = VoicemeeterRemoteAPI.INSTANCE.VBVMR_GetParameterFloat(paramName, paramValue);
        if (val != 0) throw new RuntimeException("error=" + val);
        return paramValue.getFloat(0);
    }

    public static float getLevel(int type, int channel) {
        Pointer levelValue = getPointer(4);
        int val = VoicemeeterRemoteAPI.INSTANCE.VBVMR_GetLevel(type, channel, levelValue);
        if (val != 0) throw new RuntimeException("error=" + val);
        return levelValue.getFloat(0);
    }

    static void setVolume(int chan, float val) {
        int res = VoicemeeterRemoteAPI.INSTANCE.VBVMR_SetParameterFloat(
                getStringPointer(String.format("Bus[%d].gain", chan)),
                val);
        if (res != 0) throw new RuntimeException("error=" + res);
    }

    static float getVolume(int chan) {
        Pointer paramValue = getPointer(4);
        int res = VoicemeeterRemoteAPI.INSTANCE.VBVMR_GetParameterFloat(
                getStringPointer(String.format("Bus[%d].gain", chan)),
                paramValue);
        if (res != 0) throw new RuntimeException("error=" + res);
        return paramValue.getFloat(0);
    }

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static class Volume {
        float value;

        public float getValue() {
            return value;
        }

        public void setValue(float value) {
            this.value = value;
        }
    }


    static class HttpRequest {
        final HttpExchange exchange;
        Map<String, String> pathParameters;
        Map<String, String> queryParameters = new HashMap<>();

        HttpRequest(HttpExchange exchange, PathMatcher matcher) {
            this.exchange = exchange;
            this.pathParameters = matcher.parsePathParameters(exchange);
        }

        int getIntPathParam(String name) {
            return Integer.parseInt(pathParameters.get(name));
        }

        float getFloatPathParam(String name) {
            return Float.parseFloat(pathParameters.get(name));
        }

        void sendResponseJson(String json) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-cache, no-store, must-revalidate"); // HTTP 1.1.
            headers.set("Pragma", "no-cache"); // HTTP 1.0.
            headers.set("Expires", "0"); // Proxies.
            // headers.set("Cache-Control", "private, no-store, no-cache, must-revalidate");
            headers.set(HEADER_CONTENT_TYPE, String.format("application/json; charset=%s", CHARSET));
            exchange.sendResponseHeaders(200, json.length());
            exchange.getResponseBody().write(json.getBytes());
            exchange.close();
        }
    }

    static class PathMatcher {
        final String pattern;
        final String httpMethod;
        final String[] segments;

        PathMatcher(String pattern, String httpMethod) {
            this.pattern = pattern;
            this.httpMethod = httpMethod;
            this.segments = pattern.split("/");
        }

        boolean match(HttpExchange exchange) {
            URI requestURI = exchange.getRequestURI();
            String[] pathSegments = requestURI.getPath().split("/");
            if (segments.length != pathSegments.length) return false;
            for (int i = 0; i < segments.length; i++) {
                if (segments[i].startsWith("{") && segments[i].endsWith("}")) continue;
                if (!segments[i].equals(pathSegments[i])) return false;
            }
            return true;
        }

        Map<String, String> parsePathParameters(HttpExchange exchange) {
            Map<String, String> params = new HashMap<>();
            URI requestURI = exchange.getRequestURI();
            String[] pathSegments = requestURI.getPath().split("/");
            if (segments.length != pathSegments.length) throw new RuntimeException("invalid path");
            for (int i = 0; i < segments.length; i++) {
                if (segments[i].startsWith("{") && segments[i].endsWith("}")) {
                    params.put(segments[i].substring(1, segments[i].length() - 1), pathSegments[i]);
                }

            }
            return params;
        }
    }

    private static final String HEADER_CONTENT_TYPE = "Content-Type";

    private static final Charset CHARSET = StandardCharsets.UTF_8;

    static abstract class HttpRequestHandler {
        abstract PathMatcher matcher();


        boolean canHandle(HttpExchange exchange) {
            return matcher().match(exchange);
        }

        void handle(HttpExchange exchange) throws Exception {
            handle(new HttpRequest(exchange, matcher()));
        }

        abstract void handle(HttpRequest request) throws Exception;
    }

    static class SetOutputLevelHandler extends HttpRequestHandler {
        private final PathMatcher matcher =
                new PathMatcher("/output/gain/{chan}/{value}", "GET");


        @Override
        PathMatcher matcher() {
            return matcher;
        }

        @Override
        void handle(HttpRequest request) throws Exception {
            int chan = request.getIntPathParam("chan");
            float value = request.getFloatPathParam("value");
            setVolume(chan, value);
            Volume volume = new Volume();
            volume.value = value;
            System.out.printf("output channel %d gain has been set to %.1f\n", chan, value);
            request.sendResponseJson(OBJECT_MAPPER.writeValueAsString(volume));
        }
    }

    static class GetOutputLevel extends HttpRequestHandler {
        private final PathMatcher matcher =
                new PathMatcher("/output/gain/{chan}", "GET");

        @Override
        PathMatcher matcher() {
            return matcher;
        }

        @Override
        void handle(HttpRequest request) throws Exception {
            for (int i = 0; i < 3; i++) {
                VoicemeeterRemoteAPI.INSTANCE.VBVMR_IsParametersDirty();
            }
            int chan = request.getIntPathParam("chan");
            float val = getVolume(chan);
            Volume volume = new Volume();
            volume.value = val;
            System.out.printf("out channel %d gain=%.1f\n", chan, val);
            String json = OBJECT_MAPPER.writeValueAsString(volume);
            request.sendResponseJson(json);
        }
    }

    public static void main(String[] args) throws IOException {
        int port = 8001;
        if (args.length != 0) {
            port = Integer.parseInt(args[0]);
        }
        System.out.println(VoicemeeterRemoteAPI.INSTANCE.VBVMR_Login());
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        ExecutorService threadPoolExecutor = Executors.newSingleThreadExecutor();

        List<HttpRequestHandler> handlers = new ArrayList<>();
        handlers.add(new SetOutputLevelHandler());
        handlers.add(new GetOutputLevel());

        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                for (HttpRequestHandler handler : handlers) {
                    if (handler.canHandle(exchange)) {
                        try {
                            handler.handle(exchange);
                        } catch (Exception e) {
                            throw new IOException(e);
                        }
                        break;
                    }
                }
            }
        });

        server.setExecutor(threadPoolExecutor);

        server.start();

        System.out.printf(" Server started on port %d\n", port);
    }


}
