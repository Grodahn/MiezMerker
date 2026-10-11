package org.miezmerker.backend.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Untrusted-image validation and normalization for org-local cat photos
 * (#95). Uses only JDK {@link ImageIO} so self-hosting needs no extra
 * native libraries. Every accepted image is decoded, bounds-checked and
 * re-encoded into a fresh JPEG without any source metadata: EXIF, GPS,
 * embedded thumbnails, comments and color profiles of the upload do not
 * survive. Uploaded filenames are never used.
 */
final class CatPhotoImage {
    static final String OUTPUT_CONTENT_TYPE = "image/jpeg";
    static final String OUTPUT_EXTENSION = ".jpg";

    /** Normalized, metadata-free image ready for persistent storage. */
    record Normalized(byte[] bytes, int width, int height) {}

    private CatPhotoImage() {}

    static Normalized normalize(byte[] upload, long maxUploadBytes, int maxDimension,
            long maxPixels, int targetLongEdge, float jpegQuality) {
        if (upload == null || upload.length == 0) {
            throw invalid();
        }
        if (upload.length > maxUploadBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "photo exceeds the configured maximum upload size");
        }
        String kind = detectKind(upload);
        if (kind == null) {
            // SVG, HTML, text and unknown binaries are rejected before decoding.
            throw invalid();
        }
        BufferedImage decoded = decode(upload, kind, maxDimension, maxPixels);
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        if (width <= 0 || height <= 0) {
            // The header check above already enforced the caps; a decoder
            // disagreeing with its own header is not trusted.
            throw invalid();
        }
        BufferedImage framed = scaleDown(decoded, targetLongEdge);
        return new Normalized(encodeJpeg(framed, jpegQuality),
                framed.getWidth(), framed.getHeight());
    }

    /**
     * Magic-byte allowlist: JPEG and PNG only. Anything else, including text
     * markers ({@code <}) used by SVG/HTML polyglots, is rejected outright so
     * unsupported formats never reach the decoder.
     */
    static String detectKind(byte[] upload) {
        if (upload.length >= 3
                && (upload[0] & 0xFF) == 0xFF && (upload[1] & 0xFF) == 0xD8
                && (upload[2] & 0xFF) == 0xFF) {
            return "JPEG";
        }
        if (upload.length >= 8
                && (upload[0] & 0xFF) == 0x89 && upload[1] == 0x50 && upload[2] == 0x4E
                && upload[3] == 0x47 && upload[4] == 0x0D && upload[5] == 0x0A
                && upload[6] == 0x1A && upload[7] == 0x0A) {
            return "PNG";
        }
        return null;
    }

    private static BufferedImage decode(byte[] upload, String kind, int maxDimension,
            long maxPixels) {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(upload))) {
            if (input == null) {
                throw invalid();
            }
            Iterator<ImageReader> readers = kind.equals("PNG")
                    ? ImageIO.getImageReadersByFormatName("PNG")
                    : ImageIO.getImageReadersByFormatName("JPEG");
            if (!readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                // Bounds BEFORE pixels: header-only metadata, so a small
                // file declaring huge dimensions (decompression bomb) is
                // rejected without allocating the raster.
                int width;
                int height;
                try {
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } catch (IndexOutOfBoundsException | IOException | IllegalArgumentException e) {
                    throw invalid();
                }
                if (width <= 0 || height <= 0 || width > maxDimension || height > maxDimension
                        || (long) width * (long) height > maxPixels) {
                    throw invalid();
                }
                BufferedImage image;
                try {
                    image = reader.read(0);
                } catch (IndexOutOfBoundsException | IOException | IllegalArgumentException e) {
                    // Malformed images and truncated streams.
                    throw invalid();
                }
                if (image == null) {
                    throw invalid();
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw invalid();
        }
    }

    private static BufferedImage scaleDown(BufferedImage source, int targetLongEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge <= targetLongEdge) {
            return flatten(source);
        }
        double scale = (double) targetLongEdge / (double) longEdge;
        int nextWidth = Math.max(1, (int) Math.round(width * scale));
        int nextHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage scaled = new BufferedImage(nextWidth, nextHeight,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, nextWidth, nextHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /** Drops alpha and any attached metadata by painting onto fresh RGB. */
    private static BufferedImage flatten(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB
                && source.getColorModel().getNumComponents() == 3) {
            BufferedImage copy = new BufferedImage(source.getWidth(),
                    source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = copy.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return copy;
        }
        BufferedImage flat = new BufferedImage(source.getWidth(),
                source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = flat.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, java.awt.Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        return flat;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("JPEG");
        if (!writers.hasNext()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "photo storage is unavailable");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(Math.min(1.0f, Math.max(0.1f, quality)));
            }
            // A fresh IIOImage carries no metadata: no EXIF, GPS, thumbnail,
            // comment or color-profile segments are written.
            writer.write(null, new IIOImage(image, null, null), params);
            stream.flush();
            return output.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "photo storage is unavailable");
        } finally {
            writer.dispose();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "unsupported or malformed photo; JPEG or PNG required");
    }
}
