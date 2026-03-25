package de.daubli.feedwatch.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class AudioUtils {

    public static ByteBuffer convertPlanarFloatToInterleavedPCM16(ByteBuffer planarFloatBuffer, int numChannels) {
        planarFloatBuffer.order(ByteOrder.nativeOrder());
        FloatBuffer floatBuffer = planarFloatBuffer.asFloatBuffer();

        int totalSamples = floatBuffer.remaining(); // total float samples = numFrames * numChannels
        if (totalSamples % numChannels != 0) {
            throw new IllegalArgumentException("Buffer size is not divisible by channel count. Invalid planar data.");
        }

        int numFrames = totalSamples / numChannels;

        short[] interleavedPCM = new short[numFrames * numChannels];

        for (int frame = 0; frame < numFrames; frame++) {
            for (int channel = 0; channel < numChannels; channel++) {
                float sample = floatBuffer.get(channel * numFrames + frame);
                short pcmSample = (short) Math.max(Math.min(sample * 32767.0f, 32767.0f), -32768.0f);
                interleavedPCM[frame * numChannels + channel] = pcmSample;
            }
        }

        ByteBuffer pcmBuffer = ByteBuffer.allocateDirect(interleavedPCM.length * 2).order(ByteOrder.nativeOrder());
        pcmBuffer.asShortBuffer().put(interleavedPCM);
        pcmBuffer.rewind();

        return pcmBuffer;
    }

    public static boolean isSilentFast(byte[] pcm) {
        for (int i = 0; i < pcm.length; i += 32) { // check every 16th sample
            if (pcm[i] != 0 || pcm[i + 1] != 0) {
                return false;
            }
        }
        return true;
    }
}
