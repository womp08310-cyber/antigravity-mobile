class AudioStreamingProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    if (input && input.length > 0) {
      const channelData = input[0];
      if (channelData && channelData.length > 0) {
        this.port.postMessage(channelData.buffer, [channelData.buffer]);
      }
    }
    return true;
  }
}

class VoiceMicProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    if (input && input.length > 0) {
      const channelData = input[0];
      if (channelData && channelData.length > 0) {
        this.port.postMessage(channelData.buffer, [channelData.buffer]);
      }
    }
    return true;
  }
}

try {
  registerProcessor("audio-streaming-processor", AudioStreamingProcessor);
} catch (e) {}

try {
  registerProcessor("voice-mic-processor", VoiceMicProcessor);
} catch (e) {}
