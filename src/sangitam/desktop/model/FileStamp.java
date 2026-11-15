package sangitam.desktop.model;

/** The size and modification time observed when reading a file's metadata. */
public record FileStamp(long size, long modifiedMillis) { }
