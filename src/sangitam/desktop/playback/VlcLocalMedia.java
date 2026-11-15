package sangitam.desktop.playback;

import uk.co.caprica.vlcj.media.callback.seekable.RandomAccessFileMedia;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

import java.nio.file.Path;

/** Opens long Windows paths through Java's seekable file API. Keep this object until VLC releases the media. */
final class VlcLocalMedia {
    private final Path path;
    private final RandomAccessFileMedia callback;

    VlcLocalMedia(Path path) {
        this.path = path;
        callback = System.getProperty("os.name", "").startsWith("Windows") && path.toString().length() >= 248
            ? new RandomAccessFileMedia(path.toFile()) : null;
    }

    boolean play(MediaPlayer player, double seconds) {
        String[] options = {":no-video", ":no-sub-autodetect-file", ":start-time=" + seconds};
        return callback == null ? player.media().play(path.toString(), options) : player.media().play(callback, options);
    }
}
