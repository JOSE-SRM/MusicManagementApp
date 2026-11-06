import sangitam.desktop.ui.AppIcons;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Writes a Windows ICO from the same vector used for the Swing window. */
class WriteLauncherIcon {
    public static void main(String[] args) throws Exception {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        var images = new ArrayList<byte[]>();
        for (int size : sizes) {
            var png = new ByteArrayOutputStream();
            ImageIO.write(AppIcons.applicationIcon(size), "png", png);
            images.add(png.toByteArray());
        }
        int headerSize = 6 + sizes.length * 16;
        var header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = headerSize;
        for (int i = 0; i < sizes.length; i++) {
            header.put((byte) sizes[i]).put((byte) sizes[i]).putShort((short) 0);
            header.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        try (var file = Files.newOutputStream(Path.of(args[0]))) {
            file.write(header.array());
            for (byte[] image : images) file.write(image);
        }
    }
}
