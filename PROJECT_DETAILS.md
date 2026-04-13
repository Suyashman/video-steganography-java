# Video Steganography in Java - Project Details

## 1. Overview
This project hides a text message inside a video by modifying the Least Significant Bit (LSB) of pixel color channels.

The implementation uses:
- Java
- OpenCV Java bindings
- FFV1 lossless codec for output video preservation

Core idea:
- Metadata (ciphertext length + salt + nonce) is stored in frame 0.
- Message plaintext is first encrypted using AES-GCM.
- Ciphertext bits are randomly distributed across non-zero frames.
- Decoding regenerates the exact same random placements using a passphrase-derived seed.

---

## 2. Files in the Project
- Encoding.java: Reads input video, embeds metadata and payload, writes output video.
- Decoding.java: Reads encoded video, recovers metadata, reconstructs payload, prints hidden text.
- README.md: Basic usage and setup.
- PROJECT_DETAILS.md: This detailed technical description.

---

## 3. Steganography Strategy

### 3.1 LSB Substitution
Each color channel value is 8-bit (0-255). Only the least significant bit is changed.

Example:
- Original channel value: 202 (binary 11001010)
- Embed bit 1 -> New value: 203 (binary 11001011)

Visual impact is negligible because the channel changes by at most 1.

### 3.2 Metadata Placement
Frame 0 stores:
- Ciphertext length (32 bits)
- PBKDF2 salt (16 bytes)
- AES-GCM nonce (12 bytes)

This allows the decoder to:
- Know how many encrypted bytes to recover
- Derive the same AES key from passphrase + salt
- Decrypt using the stored nonce

### 3.3 Payload Distribution
Payload is not stored sequentially in one region.
Instead, each payload bit is placed into a unique random slot defined by:
- frameIndex (1 to totalFrames-1)
- row
- col
- channel (B/G/R index)

Seed derivation uses SHA-256(passphrase), so both encoder and decoder generate the same slot sequence without storing any random key in the video.

### 3.4 Cryptographic Protection
Before embedding:
- Key derivation: PBKDF2WithHmacSHA256 (65,536 iterations, 256-bit key)
- Encryption: AES/GCM/NoPadding
- Encoding: UTF-8 plaintext to bytes, then encrypt

Benefits:
- Confidentiality: extracted payload is ciphertext without passphrase
- Integrity: AES-GCM detects wrong passphrase and tampering

---

## 4. Encoding Workflow (Encoding.java)
1. Read secret message from console.
2. Read passphrase from console.
3. Generate random salt and nonce.
4. Derive AES key using PBKDF2(passphrase, salt).
5. Encrypt UTF-8 message using AES-GCM.
4. Open input.mp4 and query width, height, fps, frame count.
5. Validate capacity:
  - Metadata requires frame 0.
  - Payload bits required = encryptedLength * 8.
   - Available slots = (totalFrames - 1) * height * width * 3.
6. Derive pseudorandom seed from passphrase.
7. Generate unique random payload slots using that seed.
7. While iterating frames:
  - frame 0: embed ciphertext length, salt, nonce.
  - other frames: embed encrypted payload bits assigned to that frame.
8. Write frames to output.avi with FFV1.

---

## 5. Decoding Workflow (Decoding.java)
1. Open output.avi.
2. Read passphrase from console.
2. Read frame 0.
3. Extract ciphertext length, salt, and nonce from frame 0 LSBs.
4. Validate message length and capacity.
5. Derive pseudorandom seed from passphrase.
6. Regenerate identical random payload slots.
7. Read encrypted payload bits from assigned frame/pixel/channel locations.
8. Decrypt with AES-GCM and print recovered UTF-8 message.

---

## 6. Determinism and Correctness
Decoding works only when placement generation matches encoding exactly.

Required invariants:
- Same passphrase-derived seed
- Same random call order
- Same uniqueness rule for slots
- Same frame dimensions and frame count in encoded file

Any mismatch breaks reconstruction.

---

## 7. Capacity Model

Definitions:
- F = total frames
- W = width
- H = height
- C = channels per pixel = 3

Bit capacity for payload:
- payloadBits = (F - 1) * W * H * C

Character capacity (ASCII):
- payloadChars = floor(payloadBits / 8)

Length field bound in this project:
- ciphertext length is stored as 32-bit integer.

Plaintext limit depends on encrypted payload expansion (GCM authentication tag).

---

## 8. Why Lossless Output Is Mandatory
Lossy codecs (such as H.264/XVID) quantize and transform pixel values, which destroys LSB integrity.

FFV1 is lossless, so exact per-channel values survive and hidden bits remain decodable.

---

## 9. Security Notes
This is steganography, not strong cryptography.

Current design properties:
- Good visual stealth (small pixel changes).
- Better dispersion than simple sequential embedding.
- No random seed key is stored in frame 0.
- Encrypted payload requires passphrase for recovery.
- AES-GCM detects tampering or wrong passphrase.

---

## 10. Runtime Requirements
- Java JDK/JRE 11+
- opencv-4120.jar
- opencv_java4120.dll
- opencv_videoio_ffmpeg4120_64.dll
- input.mp4 for encoding

---

## 11. Compile and Run

Compile:
```bash
javac -cp ".;opencv-4120.jar" Encoding.java
javac -cp ".;opencv-4120.jar" Decoding.java
```

Encode:
```bash
java --enable-native-access=ALL-UNNAMED -cp ".;opencv-4120.jar" -Djava.library.path=. Encoding
```

Decode:
```bash
java --enable-native-access=ALL-UNNAMED -cp ".;opencv-4120.jar" -Djava.library.path=. Decoding
```

---

## 12. Troubleshooting
- If VideoWriter fails:
  - Ensure FFmpeg DLL is present and OpenCV can access it.
- If decoder prints invalid length:
  - Output may have been re-encoded or corrupted during transfer.
- If javac is not recognized:
  - JDK is missing from PATH; install JDK and reopen terminal.

---

## 13. Suggested Future Improvements
- Add message integrity check (CRC or hash).
- Support larger metadata/header with version and algorithm IDs.
- Add memory-hard KDF option (Argon2 or scrypt) for stronger offline attack resistance.
- Add a lightweight header version field for format evolution.
