package sangitam.desktop.discovery;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;

/** Public encyclopedia metadata only. No audio, credentials, or library paths leave the device. */
public final class ArtistEnrichmentClient {
    public record Result(String name, String description, byte[] portrait, String article, String imagePage, String credit) { }
    public interface Transport { byte[] get(URI uri, int limit) throws Exception; }
    private final Transport transport;
    public ArtistEnrichmentClient() { this(ArtistEnrichmentClient::http); }
    public ArtistEnrichmentClient(Transport transport) { this.transport = transport; }
    private static byte[] http(URI uri, int limit) throws Exception {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !Set.of("en.wikipedia.org", "commons.wikimedia.org",
            "upload.wikimedia.org", "thumb.wikimedia.org").contains(uri.getHost())) throw new IOException("Untrusted artist source");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("User-Agent","Sangitam/0.3 (https://github.com/JOSE-SRM/MusicManagementApp)").GET().build();
        var response = client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()) {
            if(response.statusCode()!=200) throw new IOException("Artist source returned HTTP "+response.statusCode());
            byte[] data=stream.readNBytes(limit+1); if(data.length>limit)throw new IOException("Artist response too large");return data;
        }
    }
    private JsonObject json(String host,String parameters) throws Exception {
        return JsonParser.parseString(new String(transport.get(URI.create("https://"+host+"/w/api.php?action=query&format=json&formatversion=2&"+parameters.replace("|","%7C")),1_000_000),StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String text(JsonObject object,String key){try{return object.get(key).getAsString();}catch(Exception ignored){return "";}}
    private static JsonObject object(JsonObject root,String key){return root.has(key)&&root.get(key).isJsonObject()?root.getAsJsonObject(key):new JsonObject();}
    private static List<JsonObject> pages(JsonObject root){var result=new ArrayList<JsonObject>();var query=object(root,"query");if(query.has("pages"))for(var page:query.getAsJsonArray("pages"))result.add(page.getAsJsonObject());return result;}
    private static boolean musician(JsonObject page) {
        if(page.has("missing")||object(page,"pageprops").has("disambiguation"))return false;
        String lead=text(page,"extract");lead=lead.substring(0,Math.min(1600,lead.length())).toLowerCase(Locale.ROOT);
        return lead.matches("(?s).*(composer|conductor|musician|singer|pianist|violinist|cellist|guitarist|orchestra|musical ensemble|rock band|music producer|songwriter|rapper).*" );
    }
    public Result fetch(String name,String role) throws Exception {
        String props="&redirects=1&prop=extracts|pageimages|pageprops|info&inprop=url&explaintext=1&exsectionformat=plain&exlimit=1&piprop=name&pilicense=free";
        List<JsonObject> direct=pages(json("en.wikipedia.org","titles="+encode(name)+props));JsonObject chosen=direct.stream().filter(ArtistEnrichmentClient::musician).findFirst().orElse(null);
        if(chosen==null){
            var candidates=pages(json("en.wikipedia.org","generator=search&gsrlimit=3&gsrnamespace=0&gsrsearch="+encode("\""+name+"\" "+role)+props+"&exintro=1&exlimit=3")).stream()
                .filter(ArtistEnrichmentClient::musician).filter(page->matchesName(name,text(page,"title"))).toList();
            if(candidates.size()!=1)throw new IOException("Artist match is missing or ambiguous. Try a fuller name.");chosen=pages(json("en.wikipedia.org","titles="+encode(text(candidates.getFirst(),"title"))+props)).getFirst();
        }
        String canonical=text(chosen,"title").replaceAll(" \\((composer|musician|conductor|singer|band)\\)$","");
        String extract=text(chosen,"extract"), article=text(chosen,"fullurl"); if(article.isEmpty())article="https://en.wikipedia.org/wiki/"+encode(text(chosen,"title"));
        byte[] portrait=null;String imagePage="",credit="";String image=text(chosen,"pageimage");
        if(!image.isEmpty())try{
            var files=pages(json("commons.wikimedia.org","titles="+encode("File:"+image)+"&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=640"));
            if(!files.isEmpty()&&files.getFirst().has("imageinfo")){
                JsonObject info=files.getFirst().getAsJsonArray("imageinfo").get(0).getAsJsonObject(),meta=object(info,"extmetadata");
                String license=clean(text(object(meta,"LicenseShortName"),"value"));
                if(license.toLowerCase(Locale.ROOT).matches(".*(cc by|cc-by|cc0|public domain|^pd).*")){
                    String imageUrl=text(info,"thumburl");if(!imageUrl.isEmpty())portrait=portrait(transport.get(URI.create(imageUrl),4_000_000));
                    imagePage=text(info,"descriptionurl");credit=clean(text(object(meta,"Artist"),"value"))+" · "+license+" · "+clean(text(object(meta,"Credit"),"value"));
                }
            }
        }catch(Exception ignored){ /* Biography is still useful if an image is unavailable. */ }
        return new Result(canonical,structured(extract),portrait,article,imagePage,credit);
    }
    static boolean matchesName(String alias,String title){String a=alias.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]"," ").strip();String t=title.toLowerCase(Locale.ROOT);return Arrays.stream(a.split("\\s+")).allMatch(t::contains);}
    private static String clean(String text){return text.replaceAll("<[^>]*>","").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").strip();}
    static String structured(String extract){
        String[] paragraphs=extract.split("\\n\\s*\\n");String lead=excerpt(paragraphs.length==0?extract:paragraphs[0],650);
        String music=section(extract,"(?i)(music|musical style|works|compositions|recordings|career)"),life=section(extract,"(?i)(biography|life|early life|life and career)");
        return "About\n"+lead+(music.isBlank()?"":"\n\nMusic and recordings\n"+music)+(life.isBlank()?"":"\n\nLife\n"+life)+"\n\nWikipedia article excerpt (CC BY-SA). Read the source for the complete article.";
    }
    private static String section(String text,String heading){var pattern=java.util.regex.Pattern.compile("(?m)^"+heading+"\\s*$");var matcher=pattern.matcher(text);return matcher.find()?excerpt(text.substring(matcher.end()).strip(),1600):"";}
    private static String excerpt(String text, int limit) {
        if (text.length() <= limit) return text;
        int sentence = text.lastIndexOf(". ", limit - 1);
        if (sentence >= limit / 2) return text.substring(0, sentence + 1);
        int space = text.lastIndexOf(' ', limit);
        return text.substring(0, space > 0 ? space : limit).stripTrailing() + "…";
    }
    private static byte[] portrait(byte[] data)throws IOException{
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unsupported portrait");var reader=readers.next();try{
            reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>12000||h>12000)throw new IOException("Portrait dimensions");
            var params=reader.getDefaultReadParam();int sample=Math.max(1,Math.max(w,h)/640);params.setSourceSubsampling(sample,sample,0,0);var decoded=reader.read(0,params);
            var image=new BufferedImage(320,320,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();double scale=320d/Math.max(decoded.getWidth(),decoded.getHeight());int x=(int)(decoded.getWidth()*scale),y=(int)(decoded.getHeight()*scale);g.setColor(Color.BLACK);g.fillRect(0,0,320,320);g.drawImage(decoded,(320-x)/2,(320-y)/2,x,y,null);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
        }finally{reader.dispose();}}
    }
}
