package sangitam.desktop.storage;

import com.google.gson.Gson;
import sangitam.desktop.model.*;
import java.sql.*;
import java.nio.file.Path;

final class AudioFeatureStore {
    private static final Gson JSON = new Gson();
    static void initialize(Connection c)throws SQLException{try(var s=c.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS audio_features(path TEXT PRIMARY KEY,payload TEXT NOT NULL)");s.executeUpdate("CREATE TRIGGER IF NOT EXISTS remove_audio_features AFTER DELETE ON tracks BEGIN DELETE FROM audio_features WHERE path=OLD.path; END");}}
    static AudioFeatures decode(String json)throws SQLException{if(json==null)return AudioFeatures.empty();try{return JSON.fromJson(json,AudioFeatures.class);}catch(RuntimeException e){throw new SQLException("Stored audio features could not be read",e);}}
    static void save(Connection c,Path path,TrackTags tags)throws SQLException{
        if(tags.features().version()==0)return;
        try(var s=c.prepareStatement("INSERT INTO audio_features SELECT path,? FROM tracks WHERE path=? AND (metadata_size<0 OR(metadata_size=? AND metadata_modified=?)) ON CONFLICT(path) DO UPDATE SET payload=excluded.payload")){
            s.setString(1,JSON.toJson(tags.features()));s.setString(2,path.toString());s.setLong(3,tags.fileSize());s.setLong(4,tags.modifiedMillis());s.executeUpdate();
        }
    }
}
