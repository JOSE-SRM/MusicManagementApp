package sangitam.desktop.discovery;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ArtistEnrichmentClientTest {
    public static String article(){return "{\"query\":{\"pages\":[{\"title\":\"Ludwig van Beethoven\",\"fullurl\":\"https://en.wikipedia.org/wiki/Ludwig_van_Beethoven\",\"extract\":\"Ludwig van Beethoven was a German composer and pianist.\\n\\nMusic\\nHis compositions span several forms.\\n\\nLife\\nBiographical notes from the source.\"}]}}";}
    @Test void redirectedFullNameAndStructuredSourcedSectionsAreKept() throws Exception {
        var client=new ArtistEnrichmentClient((uri,limit)->{assertTrue(uri.getQuery().contains("redirects=1"));assertFalse(uri.getQuery().contains("exchars=12000"));return article().getBytes(StandardCharsets.UTF_8);});
        var result=client.fetch("Beethoven","Composer");assertEquals("Ludwig van Beethoven",result.name());assertTrue(result.description().contains("Music and recordings"));assertTrue(result.description().contains("Life"));assertTrue(result.article().startsWith("https://en.wikipedia.org/"));
    }
    @Test void ambiguousMusiciansAreNotAutomaticallyMerged() {
        var client=new ArtistEnrichmentClient((uri,limit)->(uri.getQuery().contains("generator=")?
            "{\"query\":{\"pages\":[{\"title\":\"John Williams (composer)\",\"extract\":\"John Williams is a composer.\"},{\"title\":\"John Williams (guitarist)\",\"extract\":\"John Williams is a guitarist.\"}]}}":
            "{\"query\":{\"pages\":[{\"title\":\"John Williams\",\"pageprops\":{\"disambiguation\":\"\"}}]}}").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->client.fetch("John Williams","musician"));
    }
    @Test void downloadsFreeCommonsPortraitWithAttributionAndRejectsNonFreeImage() throws Exception {
        var image=new java.awt.image.BufferedImage(640,400,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        var calls=new java.util.ArrayList<String>();
        var client=new ArtistEnrichmentClient((uri,limit)->{
            calls.add(uri.getHost());
            if(uri.getHost().equals("upload.wikimedia.org"))return bytes.toByteArray();
            if(uri.getHost().equals("commons.wikimedia.org"))return ("{\"query\":{\"pages\":[{\"imageinfo\":[{\"thumburl\":\"https://upload.wikimedia.org/test.png\",\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:Portrait\",\"extmetadata\":{\"LicenseShortName\":{\"value\":\"CC BY-SA 4.0\"},\"Artist\":{\"value\":\"<b>Painter</b>\"}}}]}]}}").getBytes(StandardCharsets.UTF_8);
            return article().replace("\"fullurl\"","\"pageimage\":\"Portrait.png\",\"fullurl\"").getBytes(StandardCharsets.UTF_8);
        });
        var result=client.fetch("Beethoven","composer");
        assertEquals(320,javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(result.portrait())).getWidth());
        assertTrue(result.credit().contains("Painter · CC BY-SA"));assertFalse(result.credit().contains("<b>"));
        assertEquals(java.util.List.of("en.wikipedia.org","commons.wikimedia.org","upload.wikimedia.org"),calls);
        var nonFree=new ArtistEnrichmentClient((uri,limit)->{
            if(uri.getHost().equals("upload.wikimedia.org"))throw new AssertionError("Must not download a non-free image");
            if(uri.getHost().equals("commons.wikimedia.org"))return "{\"query\":{\"pages\":[{\"imageinfo\":[{\"thumburl\":\"https://upload.wikimedia.org/image.png\",\"extmetadata\":{\"LicenseShortName\":{\"value\":\"Fair use\"}}}]}]}}".getBytes(StandardCharsets.UTF_8);
            return article().replace("\"fullurl\"","\"pageimage\":\"Portrait.png\",\"fullurl\"").getBytes(StandardCharsets.UTF_8);
        });
        assertNull(nonFree.fetch("Beethoven","composer").portrait());
    }
}
