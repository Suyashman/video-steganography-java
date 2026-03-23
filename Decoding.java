import org.opencv.core.*;
import org.opencv.videoio.*;
import java.util.Random;

public class p3 {

    static { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    public static void main(String[] args) {
        VideoCapture cap = new VideoCapture("output.avi", Videoio.CAP_FFMPEG);
        if (!cap.isOpened()) {
            System.err.println("ERROR: Cannot open output.avi");
            return;
        }

        int totalFrames = (int) cap.get(Videoio.CAP_PROP_FRAME_COUNT);
        System.out.println("Opened output.avi: " + totalFrames + " frames");

        Mat frame = new Mat();
        if (!cap.read(frame) || frame.empty()) {
            System.err.println("ERROR: Could not read frame 0.");
            cap.release();
            return;
        }

        int key    = readKey(frame);
        int length = readLength(frame);

        System.out.println("Recovered key:    " + key);
        System.out.println("Message length:   " + length);

        if (length <= 0 || length > totalFrames - 1) {
            System.err.println("ERROR: Invalid length " + length);
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

        char[] message = new char[length];
        for (int i = 0; i < length; i++) {
            cap.set(Videoio.CAP_PROP_POS_FRAMES, frames[i]);
            cap.read(frame);
            message[i] = extractLetter(frame);
        }

        cap.release();
        System.out.println("Hidden message: " + new String(message));
    }

    static int readKey(Mat frame) {
        int key = 0, bitIndex = 0;
        outer:
        for (int r = 0; r < frame.rows(); r++) {
            for (int c = 0; c < frame.cols(); c++) {
                double[] pixel = frame.get(r, c);
                for (int i = 0; i < 3 && bitIndex < 16; i++) {
                    key = (key << 1) | (((int) pixel[i]) & 1);
                    bitIndex++;
                }
                if (bitIndex >= 16) break outer;
            }
        }
        return key;
    }

    static int readLength(Mat frame) {
        int value = 0, bitIndex = 0;
        outer:
        for (int r = 0; r < frame.rows(); r++) {
            for (int c = 6; c < frame.cols(); c++) {
                double[] pixel = frame.get(r, c);
                for (int i = 0; i < 3 && bitIndex < 16; i++) {
                    value = (value << 1) | (((int) pixel[i]) & 1);
                    bitIndex++;
                }
                if (bitIndex >= 16) break outer;
            }
        }
        return value;
    }

    static char extractLetter(Mat frame) {
        int value = 0, bitIndex = 0;
        outer:
        for (int r = 0; r < frame.rows(); r++) {
            for (int c = 12; c < frame.cols(); c++) {
                double[] pixel = frame.get(r, c);
                for (int i = 0; i < 3 && bitIndex < 8; i++) {
                    value = (value << 1) | (((int) pixel[i]) & 1);
                    bitIndex++;
                }
                if (bitIndex >= 8) break outer;
            }
        }
        return (char) value;
    }
}
