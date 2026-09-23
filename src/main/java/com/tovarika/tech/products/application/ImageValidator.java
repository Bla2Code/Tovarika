package com.tovarika.tech.products.application;

import com.tovarika.tech.shared.application.ApiFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

@Component
public class ImageValidator {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public ImageInfo validate(byte[] bytes) {
        if (bytes.length > MAX_BYTES) { throw new ApiFailure(413, "FILE_TOO_LARGE", "Image exceeds 10 MiB"); }
        if (bytes.length == 0) { throw new ApiFailure(400, "UPLOAD_VALIDATION_ERROR", "Image is required"); }
        String type;
        if (starts(bytes, new byte[]{(byte)0xff, (byte)0xd8, (byte)0xff})) type = "image/jpeg";
        else if (starts(bytes, new byte[]{(byte)137,80,78,71,13,10,26,10})) type = "image/png";
        else if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) type = "image/webp";
        else throw new ApiFailure(415, "UNSUPPORTED_FORMAT", "Only JPEG, PNG and WEBP are supported");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw corrupt();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 25_000_000L) {
                    throw new ApiFailure(422, "UPLOAD_VALIDATION_ERROR", "Image dimensions exceed the supported limit");
                }
                // Decode for validation only. The stored bytes are always the untouched original.
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
                if (reader.read(0) == null || warning[0]) throw corrupt();
                return new ImageInfo(type, width, height);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException failure) { throw corrupt(); }
    }
    private boolean starts(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }
    private boolean ascii(byte[] bytes, int offset, String value) {
        for (int i = 0; i < value.length(); i++) if (bytes[offset+i] != value.charAt(i)) return false;
        return true;
    }
    private ApiFailure corrupt() { return new ApiFailure(422, "CORRUPTED_IMAGE", "Image cannot be decoded"); }
    public record ImageInfo(String mediaType, int width, int height) {}
}
