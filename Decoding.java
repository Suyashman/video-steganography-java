import org.opencv.core.*;
import org.opencv.videoio.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class Decoding {

    static final int PBKDF2_ITERATIONS = 65536;
    static final int AES_KEY_BITS = 256;
    static final int SALT_BYTES = 16;
    static final int NONCE_BYTES = 12;
    static final int GCM_TAG_BITS = 128;

    static class BitPlacement {
        final int bitIndex;
        final int row;
        final int col;
        final int channel;

        BitPlacement(int bitIndex, int row, int col, int channel) {
            this.bitIndex = bitIndex;
            this.row = row;
            this.col = col;
            this.channel = channel;
        }
    }

    static { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Enter passphrase: ");
        String passphrase = sc.nextLine();

        if (passphrase.isEmpty()) {
            System.err.println("ERROR: Passphrase cannot be empty.");
            sc.close();
            return;
        }

        VideoCapture cap = new VideoCapture("output.avi", Videoio.CAP_FFMPEG);
        if (!cap.isOpened()) {
            System.err.println("ERROR: Cannot open output.avi");
            sc.close();
            return;
        }

        int totalFrames = (int) cap.get(Videoio.CAP_PROP_FRAME_COUNT);
        int width       = (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH);
        int height      = (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        System.out.println("Opened output.avi: " + totalFrames + " frames");

        Mat frame = new Mat();
        if (!cap.read(frame) || frame.empty()) {
            System.err.println("ERROR: Could not read frame 0.");
            cap.release();
            sc.close();
            return;
        }

        Metadata metadata = readMetadata(frame);
        int cipherLength = metadata.cipherLength;

        System.out.println("Cipher bytes:     " + cipherLength);

        if (cipherLength <= 0) {
            System.err.println("ERROR: Invalid encrypted length " + cipherLength);
            cap.release();
            sc.close();
            return;
        }

        int totalBits = cipherLength * 8;
        long availableSlots = (long) (totalFrames - 1) * height * width * 3L;
        if (totalBits > availableSlots) {
            System.err.println("ERROR: Message length exceeds recoverable bit capacity.");
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
            sc.close();
            return;
        }

        Random rand = new Random(seed);
        HashSet<Long> usedSlots = new HashSet<>(Math.max(16, totalBits * 2));
        Map<Integer, List<BitPlacement>> framePayload = new HashMap<>();

        for (int bitIndex = 0; bitIndex < totalBits; bitIndex++) {
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
                .add(new BitPlacement(bitIndex, row, col, channel));
        }

        int[] bits = new int[totalBits];
        for (Map.Entry<Integer, List<BitPlacement>> entry : framePayload.entrySet()) {
            cap.set(Videoio.CAP_PROP_POS_FRAMES, entry.getKey());
            if (!cap.read(frame) || frame.empty()) {
                System.err.println("ERROR: Could not read payload frame " + entry.getKey());
                cap.release();
                sc.close();
                return;
            }

            for (BitPlacement p : entry.getValue()) {
                double[] pixel = frame.get(p.row, p.col);
                bits[p.bitIndex] = ((int) pixel[p.channel]) & 1;
            }
        }

        byte[] encrypted = new byte[cipherLength];
        for (int i = 0; i < cipherLength; i++) {
            int value = 0;
            for (int b = 0; b < 8; b++) {
                value = (value << 1) | bits[i * 8 + b];
            }
            encrypted[i] = (byte) value;
        }

        byte[] plain;
        try {
            plain = decryptMessage(encrypted, passphrase, metadata.salt, metadata.nonce);
        } catch (AEADBadTagException e) {
            System.err.println("ERROR: Authentication failed. Wrong passphrase or tampered video.");
            cap.release();
            sc.close();
            return;
        } catch (GeneralSecurityException e) {
            System.err.println("ERROR: Decryption failed: " + e.getMessage());
            cap.release();
            sc.close();
            return;
        }

        cap.release();
        sc.close();
        System.out.println("Hidden message: " + new String(plain, StandardCharsets.UTF_8));
    }

    static class Metadata {
        final int cipherLength;
        final byte[] salt;
        final byte[] nonce;

        Metadata(int cipherLength, byte[] salt, byte[] nonce) {
            this.cipherLength = cipherLength;
            this.salt = salt;
            this.nonce = nonce;
        }
    }

    static Metadata readMetadata(Mat frame) {
        int bitOffset = 0;
        byte[] lengthBytes = readBytesLSB(frame, bitOffset, 4);
        bitOffset += 32;
        byte[] salt = readBytesLSB(frame, bitOffset, SALT_BYTES);
        bitOffset += SALT_BYTES * 8;
        byte[] nonce = readBytesLSB(frame, bitOffset, NONCE_BYTES);
        int cipherLength = ByteBuffer.wrap(lengthBytes).getInt();
        return new Metadata(cipherLength, salt, nonce);
    }

    static byte[] readBytesLSB(Mat frame, int startBit, int byteCount) {
        int channelsPerFrame = frame.rows() * frame.cols() * 3;
        if ((long) startBit + (long) byteCount * 8L > channelsPerFrame) {
            throw new IllegalArgumentException("Metadata exceeds frame capacity.");
        }

        byte[] out = new byte[byteCount];
        int bitOffset = startBit;
        for (int i = 0; i < byteCount; i++) {
            int value = 0;
            for (int b = 0; b < 8; b++) {
                int channelIndex = bitOffset;
                int pixelIndex = channelIndex / 3;
                int channel = channelIndex % 3;
                int row = pixelIndex / frame.cols();
                int col = pixelIndex % frame.cols();

                double[] pixel = frame.get(row, col);
                value = (value << 1) | (((int) pixel[channel]) & 1);
                bitOffset++;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    static long deriveSeed(String passphrase) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(passphrase.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    }

    static byte[] decryptMessage(byte[] encrypted, String passphrase, byte[] salt, byte[] nonce)
        throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        SecretKeySpec aesKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(encrypted);
    }
}
