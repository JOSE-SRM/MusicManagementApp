package sangitam.desktop.storage;

import sangitam.desktop.session.SessionContext;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit taste feedback and timestamped local listening windows, independent of recent playlists. */
public final class TasteStore {
    private final LibraryStore library;
    public TasteStore(LibraryStore library){this.library=library;}
    static void initialize(Connection c)throws SQLException{try(var q=c.createStatement()){
        q.executeUpdate("CREATE TABLE IF NOT EXISTS taste_feedback(path TEXT PRIMARY KEY,rating INTEGER NOT NULL CHECK(rating BETWEEN -2 AND 2))");
        q.executeUpdate("CREATE TABLE IF NOT EXISTS listening_windows(id INTEGER PRIMARY KEY, path TEXT NOT NULL,observed_at INTEGER NOT NULL,seconds REAL NOT NULL)");
        q.executeUpdate("CREATE INDEX IF NOT EXISTS listening_window_time ON listening_windows(observed_at)");
    }}
    static void record(Connection c,Map<Path,Double> seconds)throws SQLException{
        long now=System.currentTimeMillis();try(var q=c.prepareStatement("INSERT INTO listening_windows(path,observed_at,seconds) VALUES(?,?,?)")){
            for(var e:seconds.entrySet())if(Double.isFinite(e.getValue())&&e.getValue()>0){q.setString(1,e.getKey().toString());q.setLong(2,now);q.setDouble(3,e.getValue());q.addBatch();}q.executeBatch();
        }
        try(var q=c.prepareStatement("DELETE FROM listening_windows WHERE observed_at<?")){q.setLong(1,now-180L*86400000);q.executeUpdate();}
    }
    public void rate(List<Path> paths,int rating)throws SQLException{
        if(rating < -2 || rating > 2)throw new IllegalArgumentException("Taste rating must be -2 to 2");
        try(var c=library.connect();var q=c.prepareStatement("INSERT INTO taste_feedback VALUES(?,?) ON CONFLICT(path) DO UPDATE SET rating=excluded.rating")){
            c.setAutoCommit(false);try{for(Path path:paths){q.setString(1,path.toString());q.setInt(2,rating);q.addBatch();}q.executeBatch();c.commit();}catch(SQLException e){c.rollback();throw e;}
        }
    }
    public Map<Path,Integer> ratings() throws SQLException {
        var ratings = new HashMap<Path,Integer>();
        try (var c=library.connect(); var q=c.createStatement(); var r=q.executeQuery("SELECT path,rating FROM taste_feedback")) {
            while (r.next()) ratings.put(Path.of(r.getString(1)),r.getInt(2));
        }
        return Map.copyOf(ratings);
    }
    public SessionContext context(ZonedDateTime now)throws SQLException{
        var feedback=new HashMap<Path,Integer>();var windows=new ArrayList<SessionContext.Listen>();
        try(var c=library.connect();var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT path,rating FROM taste_feedback")){while(r.next())feedback.put(Path.of(r.getString(1)),r.getInt(2));}
            try(var q=c.prepareStatement("SELECT path,observed_at,seconds FROM listening_windows WHERE observed_at>=? AND observed_at<=? AND observed_at>=? UNION ALL SELECT path,MAX(observed_at),SUM(seconds) FROM listening_windows WHERE observed_at>=? AND observed_at<? GROUP BY path,strftime('%Y-%m-%d %H',observed_at/1000,'unixepoch') ORDER BY observed_at DESC LIMIT 12000")){
                q.setLong(1,now.toInstant().toEpochMilli()-180L*86400000);q.setLong(2,now.toInstant().toEpochMilli());q.setLong(3,now.toInstant().toEpochMilli()-90*60000L);q.setLong(4,now.toInstant().toEpochMilli()-180L*86400000);q.setLong(5,now.toInstant().toEpochMilli()-90*60000L);try(var r=q.executeQuery()){while(r.next())windows.add(new SessionContext.Listen(Path.of(r.getString(1)),r.getLong(2),r.getDouble(3)));}
            }
        }
        return new SessionContext(now,feedback,windows);
    }
}
