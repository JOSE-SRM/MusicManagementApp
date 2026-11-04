package sangitam.desktop.model;

import java.nio.file.Path;
import java.util.Objects;

/** Last selected local track and position; restored paused at the next launch. */
public record PlaybackMemory(Path path, double positionSeconds) {
    public PlaybackMemory {
        path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (!Double.isFinite(positionSeconds) || positionSeconds < 0) positionSeconds = 0;
    }
}
