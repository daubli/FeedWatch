package de.daubli.feedwatch.audio;

public class AudioRingBuffer {

    private final byte[][] buffer;

    private final int capacity;

    private int writeIndex = 0;

    private int readIndex = 0;

    public AudioRingBuffer(int capacity, int frameSize) {
        this.capacity = capacity;
        buffer = new byte[capacity][frameSize];
    }

    // write returns false if full
    public boolean write(byte[] data) {
        int nextWrite = (writeIndex + 1) % capacity;
        if (nextWrite == readIndex) {
            return false; // full
        }
        System.arraycopy(data, 0, buffer[writeIndex], 0, data.length);
        writeIndex = nextWrite;
        return true;
    }

    // read returns null if empty
    public byte[] read() {
        if (readIndex == writeIndex) {
            return null; // empty
        }
        byte[] data = buffer[readIndex];
        readIndex = (readIndex + 1) % capacity;
        return data;
    }
}
