import org.opencv.core.*;
import org.opencv.videoio.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class Encoding {

    static final int PBKDF2_ITERATIONS = 65536;
    static final int AES_KEY_BITS = 256;
    static final int SALT_BYTES = 16;
    static final int NONCE_BYTES = 12;
    static final int GCM_TAG_BITS = 128;

    static class BitPlacement {
        final int row;
        final int col;
        final int channel;
        final int bit;

        BitPlacement(int row, int col, int channel, int bit) {
            this.row = row;
            this.col = col;
            this.channel = channel;
            this.bit = bit;
        }
    }

    static { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Enter secret message: ");
        String message = sc.nextLine();
        System.out.print("Enter passphrase: ");
        String passphrase = sc.nextLine();

        if (passphrase.isEmpty()) {
            System.err.println("ERROR: Passphrase cannot be empty.");
            sc.close();
            return;
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] encrypted;

        try {
            SecureRandom sr = new SecureRandom();
            sr.nextBytes(salt);
            sr.nextBytes(nonce);
            encrypted = encryptMessage(message, passphrase, salt, nonce);
        } catch (GeneralSecurityException e) {
            System.err.println("ERROR: Encryption failed: " + e.getMessage());
            sc.close();
            return;
        }

        int cipherLength = encrypted.length;
        int totalBits = cipherLength * 8;

        VideoCapture cap = new VideoCapture("input.mp4");
        if (!cap.isOpened()) {
            System.err.println("ERROR: Cannot open input.mp4");
            sc.close();
            return;
        }

        int width       = (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH);
        int height      = (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        double fps      = cap.get(Videoio.CAP_PROP_FPS);
        int totalFrames = (int) cap.get(Videoio.CAP_PROP_FRAME_COUNT);

        System.out.println("Video: " + width + "x" + height + " @ " + fps + "fps, " + totalFrames + " frames");

        int metadataBits = 32 + (SALT_BYTES * 8) + (NONCE_BYTES * 8);
        long frame0CapacityBits = (long) width * height * 3L;
        if (metadataBits > frame0CapacityBits) {
            System.err.println("ERROR: Frame 0 cannot fit metadata.");
            cap.release();
            sc.close();
            return;
        }

        long availableSlots = (long) (totalFrames - 1) * height * width * 3L;
        if (totalBits > availableSlots) {
            System.err.println("ERROR: Message too long for bit capacity of this video.");
            cap.release();
            sc.close();
            return;
        }

        VideoWriter writer = new VideoWriter(
            "output.avi",
            Videoio.CAP_FFMPEG,
            VideoWriter.fourcc('F','F','V','1'),
            fps,
            new Size(width, height),
            true
        );

        if (!writer.isOpened()) {
            System.err.println("ERROR: VideoWriter failed to open.");
            cap.release();
            sc.close();
            return;
        }

        long seed;
        try {
            seed = deriveSeed(passphrase);
        } catch (NoSuchAlgorithmException e) {
            System.err.println("ERROR: Could not derive random seed: " + e.getMessage());
            cap.release();
            writer.release();
            sc.close();
            return;
        }

        Random rand = new Random(seed);
        HashSet<Long> usedSlots = new HashSet<>(Math.max(16, totalBits * 2));
        Map<Integer, List<BitPlacement>> framePayload = new HashMap<>();

        for (int byteIndex = 0; byteIndex < cipherLength; byteIndex++) {
            int value = encrypted[byteIndex] & 0xFF;
            for (int b = 0; b < 8; b++) {
                int bit = (value >> (7 - b)) & 1;

                int frameIndex;
                int row;
                int col;
                int channel;
                long slotId;

                do {
                    frameIndex = rand.nextInt(totalFrames - 1) + 1;
                    row = rand.nextInt(height);
                    col = rand.nextInt(width);
                    channel = rand.nextInt(3);
                    slotId = (((long) frameIndex * height + row) * width + col) * 3L + channel;
                } while (!usedSlots.add(slotId));

                framePayload
                    .computeIfAbsent(frameIndex, k -> new ArrayList<>())
                    .add(new BitPlacement(row, col, channel, bit));
            }
        }

        Mat frame = new Mat();
        int frameIndex = 0;

        while (cap.read(frame)) {
            if (frameIndex == 0) {
                storeMetadata(frame, cipherLength, salt, nonce);
            }
            List<BitPlacement> placements = framePayload.get(frameIndex);
            if (placements != null) {
                for (BitPlacement p : placements) {
                    double[] pixel = frame.get(p.row, p.col);
                    pixel[p.channel] = ((int) pixel[p.channel] & 0xFE) | p.bit;
                    frame.put(p.row, p.col, pixel);
                }
            }
            writer.write(frame);
            frameIndex++;
        }

        cap.release();
        writer.release();
        sc.close();
        System.out.println("Done. Encoded " + frameIndex + " frames into output.avi");
    }

    static byte[] encryptMessage(String message, String passphrase, byte[] salt, byte[] nonce)
        throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        SecretKeySpec aesKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    static long deriveSeed(String passphrase) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(passphrase.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    }

    static void storeMetadata(Mat frame, int cipherLength, byte[] salt, byte[] nonce) {
        byte[] lengthBytes = ByteBuffer.allocate(4).putInt(cipherLength).array();
        int bitOffset = 0;
        bitOffset = writeBytesLSB(frame, bitOffset, lengthBytes);
        bitOffset = writeBytesLSB(frame, bitOffset, salt);
        writeBytesLSB(frame, bitOffset, nonce);
    }

    static int writeBytesLSB(Mat frame, int startBit, byte[] data) {
        int bitOffset = startBit;
        int channelsPerFrame = frame.rows() * frame.cols() * 3;
        if ((long) bitOffset + (long) data.length * 8L > channelsPerFrame) {
            throw new IllegalArgumentException("Metadata exceeds frame capacity.");
        }

        for (byte b : data) {
            int value = b & 0xFF;
            for (int i = 7; i >= 0; i--) {
                int bit = (value >> i) & 1;
                int channelIndex = bitOffset;
                int pixelIndex = channelIndex / 3;
                int channel = channelIndex % 3;
                int row = pixelIndex / frame.cols();
                int col = pixelIndex % frame.cols();

                double[] pixel = frame.get(row, col);
                pixel[channel] = ((int) pixel[channel] & 0xFE) | bit;
                frame.put(row, col, pixel);
                bitOffset++;
            }
        }
        return bitOffset;
    }
}
