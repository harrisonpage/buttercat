package buttercat;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Encodes a palettized MapImage (2026.03.26 API) to PNG bytes.
 *
 * MapImage fields:
 *   int width, height
 *   int[] palette          — RGBA color lookup table
 *   byte bitsPerIndex      — bits per pixel index
 *   byte[] packedIndices   — bit-packed pixel indices into palette
 */
public class PngEncoder {

    public static byte[] encode(MapImage image, int outputSize) {
        int srcWidth = image.width;
        int srcHeight = image.height;
        int bitsPerIndex = image.bitsPerIndex & 0xFF;

        if (bitsPerIndex == 0 || image.palette == null || image.packedIndices == null) {
            return empty(outputSize);
        }

        // Unpack palettized pixels into RGBA int array
        int pixelCount = srcWidth * srcHeight;
        int[] pixels = unpackPixels(image.palette, image.packedIndices, bitsPerIndex, pixelCount);

        // Scale and convert to BufferedImage
        BufferedImage buffered = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_ARGB);

        float scaleX = (float) srcWidth / outputSize;
        float scaleY = (float) srcHeight / outputSize;

        for (int y = 0; y < outputSize; y++) {
            for (int x = 0; x < outputSize; x++) {
                int srcX = Math.min((int) (x * scaleX), srcWidth - 1);
                int srcY = Math.min((int) (y * scaleY), srcHeight - 1);
                int srcIndex = srcY * srcWidth + srcX;

                if (srcIndex >= pixels.length) continue;

                int rgba = pixels[srcIndex];
                int r = (rgba >> 24) & 0xFF;
                int g = (rgba >> 16) & 0xFF;
                int b = (rgba >> 8) & 0xFF;
                int a = rgba & 0xFF;
                int argb = (a << 24) | (r << 16) | (g << 8) | b;

                buffered.setRGB(x, y, argb);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(buffered, "png", out);
        } catch (IOException e) {
            return new byte[0];
        }
        return out.toByteArray();
    }

    private static int[] unpackPixels(int[] palette, byte[] packed, int bitsPerIndex, int pixelCount) {
        int[] pixels = new int[pixelCount];
        int mask = (1 << bitsPerIndex) - 1;

        for (int i = 0; i < pixelCount; i++) {
            int bitOffset = i * bitsPerIndex;
            int byteOffset = bitOffset / 8;
            int bitShift = bitOffset % 8;

            if (byteOffset >= packed.length) break;

            // Read up to 2 bytes to handle indices that span a byte boundary
            int raw = (packed[byteOffset] & 0xFF);
            if (byteOffset + 1 < packed.length) {
                raw |= (packed[byteOffset + 1] & 0xFF) << 8;
            }

            int index = (raw >> bitShift) & mask;
            if (index < palette.length) {
                pixels[i] = palette[index];
            }
        }

        return pixels;
    }

    public static byte[] empty(int size) {
        BufferedImage buffered = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(buffered, "png", out);
        } catch (IOException e) {
            return new byte[0];
        }
        return out.toByteArray();
    }
}
