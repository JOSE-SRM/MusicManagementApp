package sangitam.desktop.playback;

/** Safe to read from Swing; native capabilities are discovered on the decoder thread. */
public record AudioStatus(Backend backend, AudioSettings.ReplayGain activeReplayGain, String error) {
    public enum Backend { CHECKING, VLC, JAVA_FX }
    public static AudioStatus checking() { return new AudioStatus(Backend.CHECKING, AudioSettings.ReplayGain.OFF, null); }
}
