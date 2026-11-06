package sangitam.desktop.storage;

import sangitam.desktop.discovery.ArtistEnrichmentClient.Result;
import sangitam.desktop.model.*;
import java.sql.*;
import java.nio.file.Path;
import java.util.*;

/** Cached sourced profiles and confirmed aliases. File tags and playlist paths are not rewritten. */
public final class ArtistEnrichmentStore {
    public record Cached(String name,String description,byte[] portrait,String source,String imagePage,String credit,long fetched) { }
    private final LibraryStore library;
    public ArtistEnrichmentStore(LibraryStore library){this.library=library;}
    public boolean onlineEnabled() throws SQLException {
        try (var c=library.connect(); var q=c.prepareStatement("SELECT value FROM app_state WHERE key='artist_online'" ); var r=q.executeQuery()) {
            return !r.next() || Boolean.parseBoolean(r.getString(1));
        }
    }
    public void setOnlineEnabled(boolean enabled) throws SQLException {
        try (var c=library.connect(); var q=c.prepareStatement("INSERT INTO app_state VALUES('artist_online',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            q.setString(1,Boolean.toString(enabled)); q.executeUpdate();
        }
    }
    static void initialize(Connection c)throws SQLException{try(var sql=c.createStatement()){
        sql.executeUpdate("CREATE TABLE IF NOT EXISTS artist_aliases(alias TEXT PRIMARY KEY, canonical TEXT NOT NULL)");
        sql.executeUpdate("CREATE TABLE IF NOT EXISTS artist_enrichment(name_key TEXT PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL,portrait BLOB,source TEXT NOT NULL,image_page TEXT NOT NULL,credit TEXT NOT NULL,fetched INTEGER NOT NULL)");
    }}
    public Map<String,String> aliases()throws SQLException{try(var c=library.connect()){return aliases(c);}}
    static Map<String,String> aliases(Connection c)throws SQLException{var result=new HashMap<String,String>();try(var q=c.createStatement();var row=q.executeQuery("SELECT alias,canonical FROM artist_aliases")){while(row.next())result.put(row.getString(1),row.getString(2));}return Map.copyOf(result);}
    public static String canonical(String names,Map<String,String> aliases){return Arrays.stream(names.split(";|\\s/\\s")).map(name->{String result=name.strip();var seen=new HashSet<String>();while(seen.add(ArtistCredits.key(result))&&aliases.containsKey(ArtistCredits.key(result))){String next=aliases.get(ArtistCredits.key(result));if(next.equals(result))break;result=next;}return result;}).distinct().collect(java.util.stream.Collectors.joining("; "));}
    public static Track canonical(Track track,Map<String,String> aliases){
        if (aliases.isEmpty()) return track;
        var fields=new EnumMap<TrackMetadata.Field,TrackMetadata.Value>(TrackMetadata.Field.class);fields.putAll(track.metadata().fields());
        for(var f:List.of(TrackMetadata.Field.COMPOSER,TrackMetadata.Field.CONDUCTOR,TrackMetadata.Field.SOLOIST,TrackMetadata.Field.PERFORMERS,TrackMetadata.Field.ORCHESTRA,TrackMetadata.Field.ALBUM_ARTIST)){
            var value=fields.get(f);if(value!=null)fields.put(f,new TrackMetadata.Value(canonical(value.text(),aliases),value.origin()));
        }
        var m=track.metadata();var metadata=new TrackMetadata(fields,m.tags(),m.bitrateKbps(),m.sampleRateHz(),m.bitsPerSample(),m.channels(),m.embeddedArtwork(),m.extractionVersion());
        return new Track(track.path(),track.title(),canonical(track.artist(),aliases),track.album(),track.durationSeconds(),track.format(),track.genre(),metadata);
    }
    public Cached load(String name)throws SQLException{try(var c=library.connect()){
        String full=canonical(name,aliases(c));try(var q=c.prepareStatement("SELECT * FROM artist_enrichment WHERE name_key=?")){q.setString(1,ArtistCredits.key(full));try(var r=q.executeQuery()){return r.next()?new Cached(r.getString("name"),r.getString("description"),r.getBytes("portrait"),r.getString("source"),r.getString("image_page"),r.getString("credit"),r.getLong("fetched")):null;}}
    }}
    public void save(String requested,Result result)throws Exception{
        try(var c=library.connect()){c.setAutoCommit(false);try{
            try(var q=c.prepareStatement("INSERT INTO artist_aliases VALUES(?,?) ON CONFLICT(alias) DO UPDATE SET canonical=excluded.canonical")){
                for(String alias:List.of(requested,result.name())){q.setString(1,ArtistCredits.key(alias));q.setString(2,result.name());q.executeUpdate();}
            }
            try(var q=c.prepareStatement("""
                INSERT INTO artist_enrichment VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(name_key) DO UPDATE SET
                    name=excluded.name, description=excluded.description, source=excluded.source, fetched=excluded.fetched,
                    portrait=COALESCE(excluded.portrait,artist_enrichment.portrait),
                    image_page=CASE WHEN excluded.portrait IS NULL THEN artist_enrichment.image_page ELSE excluded.image_page END,
                    credit=CASE WHEN excluded.portrait IS NULL THEN artist_enrichment.credit ELSE excluded.credit END
                """)){
                q.setString(1,ArtistCredits.key(result.name()));q.setString(2,result.name());q.setString(3,result.description());q.setBytes(4,result.portrait());q.setString(5,result.article());q.setString(6,result.imagePage());q.setString(7,result.credit());q.setLong(8,System.currentTimeMillis());q.executeUpdate();
            }
            var aliases=aliases(c);
            var albumPins=new ArrayList<String>();try(var q=c.createStatement();var rows=q.executeQuery("SELECT item FROM library_pins WHERE kind='ALBUM'")){while(rows.next())albumPins.add(rows.getString(1));}
            for(String pin:albumPins){int split=pin.lastIndexOf('\u001f');if(split<0)continue;String updated=pin.substring(0,split+1)+ArtistCredits.key(canonical(pin.substring(split+1),aliases));if(updated.equals(pin))continue;
                try(var q=c.prepareStatement("INSERT OR IGNORE INTO library_pins VALUES('ALBUM',?)")){q.setString(1,updated);q.executeUpdate();}
                try(var q=c.prepareStatement("DELETE FROM library_pins WHERE kind='ALBUM' AND item=?")){q.setString(1,pin);q.executeUpdate();}
            }
            String old=ArtistCredits.key(requested),key=ArtistCredits.key(result.name());
            if (!requested.equals(result.name())) mergeCatalogCredits(c,old,aliases);
            if(!old.equals(key)){
                try(var q=c.prepareStatement("INSERT OR IGNORE INTO artist_profiles SELECT ?,description,portrait FROM artist_profiles WHERE name_key=?")){q.setString(1,key);q.setString(2,old);q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT OR IGNORE INTO listening_credits SELECT path,? FROM listening_credits WHERE name_key=?")){q.setString(1,key);q.setString(2,old);q.executeUpdate();}
                try(var q=c.prepareStatement("DELETE FROM listening_credits WHERE name_key=?")){q.setString(1,old);q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT OR IGNORE INTO library_pins VALUES('ARTIST',?)")){ // only transfer an existing pin
                    try(var exists=c.prepareStatement("SELECT 1 FROM library_pins WHERE kind='ARTIST' AND item=?")){exists.setString(1,old);try(var r=exists.executeQuery()){if(r.next()){q.setString(1,key);q.executeUpdate();}}}
                }
                try(var q=c.prepareStatement("DELETE FROM library_pins WHERE kind='ARTIST' AND item=?")){q.setString(1,old);q.executeUpdate();}
            }
            c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }

    /** One catalog read per new alias. Update only rows whose actual credits change. */
    private static void mergeCatalogCredits(Connection connection, String alias, Map<String,String> aliases) throws SQLException {
        var changed = new ArrayList<Track>();
        try (var query=connection.createStatement(); var rows=query.executeQuery("""
            SELECT t.path,t.artist,m.payload,m.search_text FROM tracks t LEFT JOIN track_metadata m ON m.path=t.path
            """)) {
            while (rows.next()) {
                String artist=rows.getString("artist"), search=rows.getString("search_text");
                if (!ArtistCredits.key(artist).contains(alias) && (search==null || !ArtistCredits.key(search).contains(alias))) continue;
                var original=new Track(Path.of(rows.getString("path")),"",artist,"",0,"","",MetadataStore.decode(rows.getString("payload")));
                var normalized=canonical(original,aliases);
                if (!original.artist().equals(normalized.artist()) || !original.metadata().equals(normalized.metadata())) changed.add(normalized);
            }
        }
        try (var update=connection.prepareStatement("UPDATE tracks SET artist=? WHERE path=?")) {
            for (var track : changed) {
                update.setString(1,track.artist()); update.setString(2,track.path().toString()); update.executeUpdate();
                MetadataStore.canonicalize(connection,track.path(),aliases);
            }
        }
    }
}
