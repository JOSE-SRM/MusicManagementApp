package sangitam.desktop.playback;

import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.function.*;

/** Bounded pipe to the Windows SMTC companion; native work and pipe I/O stay off Swing. */
public final class WindowsMediaBridge implements AutoCloseable {
    public record Command(String kind, double value) { }
    private final Consumer<Command> action;
    private final Consumer<Boolean> availability;
    private final ArrayBlockingQueue<String> latest = new ArrayBlockingQueue<>(1);
    private volatile boolean closed;
    private volatile Process process;
    private volatile Thread writer;
    public WindowsMediaBridge(Consumer<Command> action, Consumer<Boolean> availability) { this.action = action; this.availability = availability; }
    public void start() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Thread host = new Thread(() -> {
            try {
                Path bin = Path.of(WindowsMediaBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.isRegularFile(bin)) bin = bin.getParent();
                Path executable = bin.resolve("native/SangitamMediaHost.exe");
                if (closed || !Files.isRegularFile(executable)) return;
                Process child = new ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start(); process = child;
                if (closed) { child.destroy(); return; }
                Thread sender = new Thread(() -> {
                    try (var output = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8))) {
                        while (!closed && child.isAlive()) { output.write(latest.take()); output.newLine(); output.flush(); }
                    } catch (IOException | InterruptedException ignored) { }
                }, "sangitam-windows-media-writer"); sender.setDaemon(true); writer = sender; sender.start();
                try (var input = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!closed && (line = input.readLine()) != null) {
                        if (line.equals("READY")) SwingUtilities.invokeLater(() -> { if (!closed) availability.accept(true); });
                        else { Command command = parse(line); if (command != null) SwingUtilities.invokeLater(() -> { if (!closed) action.accept(command); }); }
                    }
                } finally { sender.interrupt(); child.destroy(); }
            } catch (Exception ignored) { /* Optional OS surface; playback remains available. */ }
            finally { SwingUtilities.invokeLater(() -> availability.accept(false)); }
        }, "sangitam-windows-media"); host.setDaemon(true); host.start();
    }
    public void publish(PlaybackState state, boolean shuffle, boolean repeat) {
        if (closed) return; String value = encode(state, shuffle, repeat);
        latest.poll(); latest.offer(value);
    }
    static String encode(PlaybackState state, boolean shuffle, boolean repeat) {
        String status = switch (state.status()) { case PLAYING -> "Playing"; case PAUSED -> "Paused"; case LOADING -> "Changing"; default -> "Stopped"; };
        var track = state.track();
        return String.join("\t", "STATE", status, Double.toString(Math.max(0, state.positionSeconds())), Double.toString(Math.max(0, state.durationSeconds())),
            shuffle ? "1" : "0", repeat ? "1" : "0", text(track == null ? "" : track.title()), text(track == null ? "" : track.artist()), text(track == null ? "" : track.album()));
    }
    private static String text(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    static Command parse(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length == 1 && java.util.Set.of("PLAY", "PAUSE", "STOP", "NEXT", "PREVIOUS").contains(parts[0])) return new Command(parts[0], 0);
        if (parts.length != 2) return null;
        try {
            double value = Double.parseDouble(parts[1]); if (!Double.isFinite(value) || value < 0) return null;
            if (parts[0].equals("SEEK") || (java.util.Set.of("SHUFFLE", "REPEAT").contains(parts[0]) && (value == 0 || value == 1))) return new Command(parts[0], value);
        } catch (NumberFormatException ignored) { }
        return null;
    }
    @Override public void close() { closed = true; if (writer != null) writer.interrupt(); if (process != null) process.destroy(); latest.clear(); }
}
