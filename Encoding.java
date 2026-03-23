import org.opencv.core.*;
import org.opencv.videoio.*;
import java.util.Random;
import java.util.Scanner;

public class p2 {

    static { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Enter secret message: ");
        String message = sc.nextLine();

        char[] letters = message.toCharArray();
        int length = letters.length;

        Random r = new Random();
        int key = r.nextInt(65536);
        System.out.println("Generated key: " + key);

        VideoCapture cap = new VideoCapture("input.mp4");
        if (!cap.isOpened()) {
            System.err.println("ERROR: Cannot open input.mp4");
            return;
        }

        int width       = (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH);
        int height      = (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        double fps      = cap.get(Videoio.CAP_PROP_FPS);
        int totalFrames = (int) cap.get(Videoio.CAP_PROP_FRAME_COUNT);

        System.out.println("Video: " + width + "x" + height + " @ " + fps + "fps, " + totalFrames + " frames");

        if (length >= totalFrames) {
            System.err.println("ERROR: Message too long for this video.");
            cap.release();
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
            return;
        }

        Random rand    = new Random(key);
        boolean[] used = new boolean[totalFrames];
        int[] frames   = new int[length];

        for (int i = 0; i < length; i++) {
            int f;
            do {
                f = rand.nextInt(totalFrames);
            } while (used[f] || f == 0);
            used[f]   = true;
            frames[i] = f;
        }
        java.util.Arrays.sort(frames);

        Mat frame = new Mat();
        int frameIndex = 0;

        while (cap.read(frame)) {
            if (frameIndex == 0) {
                storeKey(frame, key);
                storeLength(frame, length);
            }
            for (int i = 0; i < length; i++) {
                if (frameIndex == frames[i]) {
                    embedLetter(frame, letters[i]);
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

    static void storeKey(Mat frame, int key) {
        int bitIndex = 0;
        outer:
        for (int r = 0; r < frame.rows(); r++) {
            for (int c = 0; c < frame.cols(); c++) {
                double[] pixel = frame.get(r, c);
                for (int i = 0; i < 3 && bitIndex < 16; i++) {
                    pixel[i] = ((int) pixel[i] & 0xFE) | ((key >> (15 - bitIndex)) & 1);
                    bitIndex++;
                }
                frame.put(r, c, pixel);
                if (bitIndex >= 16) break outer;
            }
        }
    }

    static void storeLength(Mat frame, int len) {
        int bitIndex = 0;
        outer:
        for (int r = 0; r < frame.rows(); r++) {
            for (int c = 6; c < frame.cols(); c++) {
                double[] pixel = frame.get(r, c);
                for (int i = 0; i < 3 && bitIndex < 16; i++) {
                    pixel[i] = ((int) pixel[i] & 0xFE) | ((len >> (15 - bitIndex)) & 1);
                    bitIndex++;
                }
                frame.put(r, c, pixel);
                if (bitIndex >= 16) break outer;
            }
        }
    }

    static void embedLetter(Mat frame, char letter) {
        int bitIndex = 0;
        byte data = (byte) letter;
        outer:
        for (int r = 0; r < frame.rows(); r++) {
            for (int c = 12; c < frame.cols(); c++) {
                double[] pixel = frame.get(r, c);
                for (int i = 0; i < 3 && bitIndex < 8; i++) {
                    pixel[i] = ((int) pixel[i] & 0xFE) | ((data >> (7 - bitIndex)) & 1);
                    bitIndex++;
                }
                frame.put(r, c, pixel);
                if (bitIndex >= 8) break outer;
            }
        }
    }
}
