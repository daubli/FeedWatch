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
    public synchronized boolean write(byte[] data) {
        int nextWrite = (writeIndex + 1) % capacity;
        if (nextWrite == readIndex) {
            return false; // full
        }
        System.arraycopy(data, 0, buffer[writeIndex], 0, data.length);
        writeIndex = nextWrite;
        return true;
    }

    // read returns null if empty
    public synchronized byte[] read() {
        if (readIndex == writeIndex) {
            return null; // empty
        }
        // copy so the writer can't overwrite the slot while it is being played
        byte[] data = buffer[readIndex].clone();
        readIndex = (readIndex + 1) % capacity;
        return data;
    }
}
