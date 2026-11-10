package io.github.gargshishirr.adgate.localinfra;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Exercises the in-process Redis server, including its Lua engine, right after startup, so the first minute of a
 * demo is not dominated by JIT compilation inside the mock. Speaks just enough RESP to send commands and skip replies.
 */
final class RedisPrewarm {

    private static final String SCRIPT = """
            for i = 1, 50 do
              redis.call('SET', KEYS[1], i, 'NX', 'EX', 60)
              redis.call('INCRBY', KEYS[2], i)
              redis.call('EXPIRE', KEYS[2], 60)
            end
            return 1""";
    private static final String[] KEYS = {"adgate:prewarm:a", "adgate:prewarm:b", "adgate:prewarm:c"};

    private RedisPrewarm() {
    }

    static void run(int port, int rounds) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            for (int round = 0; round < rounds; round++) {
                command(out, "EVAL", SCRIPT, "2", KEYS[0], KEYS[1]);
                command(out, "MGET", KEYS[0], KEYS[1], KEYS[2]);
                command(out, "INCR", KEYS[2]);
                command(out, "EXPIRE", KEYS[2], "60");
                out.flush();
                for (int reply = 0; reply < 4; reply++) {
                    skipReply(in);
                }
            }
            command(out, "DEL", KEYS[0], KEYS[1], KEYS[2]);
            out.flush();
            skipReply(in);
        }
    }

    private static void command(OutputStream out, String... args) throws IOException {
        StringBuilder resp = new StringBuilder().append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            resp.append('$').append(arg.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(arg).append("\r\n");
        }
        out.write(resp.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void skipReply(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException("Redis closed the connection during prewarm");
        }
        String line = readLine(in);
        switch (type) {
            case '$' -> {
                int length = Integer.parseInt(line);
                if (length >= 0) {
                    in.readNBytes(length + 2);
                }
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                for (int i = 0; i < count; i++) {
                    skipReply(in);
                }
            }
            case '-' -> throw new IOException("Redis error during prewarm: " + line);
            default -> {
                // '+' simple string or ':' integer: nothing more to read
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != '\r') {
            if (c < 0) {
                throw new EOFException("Redis closed the connection during prewarm");
            }
            line.append((char) c);
        }
        in.read(); // '\n'
        return line.toString();
    }
}
