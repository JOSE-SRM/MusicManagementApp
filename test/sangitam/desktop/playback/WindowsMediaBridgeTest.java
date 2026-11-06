package sangitam.desktop.playback;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class WindowsMediaBridgeTest {
    @Test void protocolKeepsMetadataSeparateFromCommandsAndRejectsInvalidRequests() {
        var track = new Track(Path.of("song.mp3"), "Bach\tSuite\n♫", "Artist", "Album", 300, "MP3");
        String[] parts = WindowsMediaBridge.encode(new PlaybackState(track, PlaybackState.Status.PLAYING, 72, 300, null), true, false).split("\t", -1);
        assertEquals(9, parts.length); assertEquals("Playing", parts[1]); assertEquals("1", parts[4]);
        assertEquals(track.title(), new String(Base64.getDecoder().decode(parts[6]), StandardCharsets.UTF_8));
        assertEquals(72d, WindowsMediaBridge.parse("SEEK\t72").value()); assertEquals("PLAY", WindowsMediaBridge.parse("PLAY").kind());
        for (String bad : new String[]{"SEEK\tNaN", "SEEK\tInfinity", "SEEK\t-1", "SHUFFLE\t2", "RUN\t1", "PLAY\t1"}) assertNull(WindowsMediaBridge.parse(bad));
    }
    @Test void compiledCompanionUnderstandsUnicodeStateWithoutCreatingAWindowOrSession() throws Exception {
        Path executable = Path.of("bin/native/SangitamMediaHost.exe").toAbsolutePath();
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win") && Files.exists(executable));
        var track = new Track(Path.of("song.mp3"), "Chopin · Étude\n♫", "Artist", "Album", 50, "MP3");
        var process = new ProcessBuilder(executable.toString(), "--protocol-check").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) { input.write((WindowsMediaBridge.encode(new PlaybackState(track, PlaybackState.Status.PAUSED, 12, 50, null), false, true) + "\n").getBytes(StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
            String reply = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals("OK\t" + Base64.getEncoder().encodeToString(track.title().getBytes(StandardCharsets.UTF_8)), reply);
        } finally { process.destroyForcibly(); }
    }
}
