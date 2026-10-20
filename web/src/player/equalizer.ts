/* The narrator's voice presets of :core's VoiceEqualizer, as Web Audio filters. */

export type VoicePreset = "OFF" | "DEEP_MALE" | "BRIGHT_FEMALE" | "NO_BASS" | "OLD_RECORDING" | "CLARITY";

export const voicePresets: VoicePreset[] = ["OFF", "DEEP_MALE", "BRIGHT_FEMALE", "NO_BASS", "OLD_RECORDING", "CLARITY"];

interface Filter {
  type: BiquadFilterType;
  frequency: number;
  q?: number;
  gain?: number;
}

const BUTTERWORTH_Q = Math.SQRT1_2;

const filters: Record<VoicePreset, Filter[]> = {
  OFF: [],
  DEEP_MALE: [
    { type: "highpass", frequency: 70 },
    { type: "peaking", frequency: 180, q: 1.0, gain: -4 },
    { type: "peaking", frequency: 400, q: 1.2, gain: -2 },
    { type: "peaking", frequency: 3000, q: 1.0, gain: 3.5 },
    { type: "highshelf", frequency: 8000, gain: 1.5 },
  ],
  BRIGHT_FEMALE: [
    { type: "highpass", frequency: 90 },
    { type: "lowshelf", frequency: 250, gain: 2 },
    { type: "peaking", frequency: 3500, q: 1.4, gain: -3 },
    { type: "peaking", frequency: 7000, q: 2.0, gain: -4 },
    { type: "highshelf", frequency: 10000, gain: -2 },
  ],
  NO_BASS: [
    { type: "highpass", frequency: 160 },
    { type: "highpass", frequency: 160 },
  ],
  OLD_RECORDING: [
    { type: "highpass", frequency: 110 },
    { type: "highpass", frequency: 110 },
    { type: "peaking", frequency: 1800, q: 0.9, gain: 3 },
    { type: "lowpass", frequency: 6000 },
    { type: "lowpass", frequency: 6000 },
  ],
  CLARITY: [
    { type: "highpass", frequency: 80 },
    { type: "lowshelf", frequency: 200, gain: -2.5 },
    { type: "peaking", frequency: 2500, q: 0.8, gain: 5 },
    { type: "highshelf", frequency: 6000, gain: 2 },
  ],
};

/**
 * Routes the audio element through filters. Created on first use only: once an element is attached to an
 * AudioContext it plays through it for good, and some browsers suspend contexts made without a user gesture.
 */
export class Equalizer {
  private context: AudioContext | null = null;
  private source: MediaElementAudioSourceNode | null = null;
  private nodes: AudioNode[] = [];
  private readonly audio: HTMLAudioElement;

  constructor(audio: HTMLAudioElement) {
    this.audio = audio;
  }

  apply(preset: VoicePreset) {
    if (preset === "OFF" && !this.context) return;
    if (!this.context) {
      this.context = new AudioContext();
      this.source = this.context.createMediaElementSource(this.audio);
    }
    const ctx = this.context;
    if (ctx.state === "suspended") void ctx.resume();
    this.source!.disconnect();
    this.nodes.forEach((n) => n.disconnect());
    const boost = Math.max(0, ...filters[preset].map((f) => f.gain ?? 0));
    // Take half the strongest boost back up front, as the phone does, then limit what is left.
    const preGain = ctx.createGain();
    preGain.gain.value = Math.pow(10, -boost / 2 / 20);
    const chain: AudioNode[] = [preGain];
    for (const f of filters[preset]) {
      const node = ctx.createBiquadFilter();
      node.type = f.type;
      node.frequency.value = f.frequency;
      node.Q.value = f.q ?? BUTTERWORTH_Q;
      if (f.gain !== undefined) node.gain.value = f.gain;
      chain.push(node);
    }
    if (preset !== "OFF") {
      const limiter = ctx.createDynamicsCompressor();
      limiter.threshold.value = -1;
      limiter.knee.value = 0;
      limiter.ratio.value = 20;
      limiter.attack.value = 0.003;
      limiter.release.value = 0.1;
      chain.push(limiter);
    }
    let previous: AudioNode = this.source!;
    for (const node of chain) {
      previous.connect(node);
      previous = node;
    }
    previous.connect(ctx.destination);
    this.nodes = chain;
  }

  resume() {
    if (this.context?.state === "suspended") void this.context.resume();
  }
}
