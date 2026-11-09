// Recognize a song (docs/features/ui.md): the microphone records, and after 3, 5, 8 and 12
// seconds what it has so far goes to the host (POST /api/recognize), which asks Shazam and finds the
// song on YouTube Music. The first answer with a song ends it. Measured: 2 seconds is never enough
// for Shazam, 3 usually is; an answer takes 0.4–0.8 s, so most songs are known in under 4 s.
import { ApiRequestError, recognize } from './api';
import type { RecognizeResponse } from './protocol.gen';

/** When to ask, in seconds of sound. */
export const ATTEMPTS = [3, 5, 8, 12];
/** Shazam works with 16 kHz mono. */
export const RATE = 16000;

/** [samples] at [from] Hz, as [to] Hz: each new sample is the average of the old ones it covers (a simple low-pass too). */
export function downsample(samples: Float32Array, from: number, to: number): Float32Array {
	if (from === to) return samples;
	const ratio = from / to;
	const out = new Float32Array(Math.floor(samples.length / ratio));
	for (let i = 0; i < out.length; i++) {
		const start = Math.floor(i * ratio);
		const end = Math.min(samples.length, Math.floor((i + 1) * ratio));
		let sum = 0;
		for (let j = start; j < end; j++) sum += samples[j];
		out[i] = end > start ? sum / (end - start) : 0;
	}
	return out;
}

/** 16-bit mono WAV of [samples] (-1…1) at [rate] Hz. */
export function wav(samples: Float32Array, rate: number): Uint8Array<ArrayBuffer> {
	const bytes = new Uint8Array(44 + samples.length * 2);
	const view = new DataView(bytes.buffer);
	const text = (at: number, s: string) => [...s].forEach((c, i) => view.setUint8(at + i, c.charCodeAt(0)));
	text(0, 'RIFF');
	view.setUint32(4, 36 + samples.length * 2, true);
	text(8, 'WAVE');
	text(12, 'fmt ');
	view.setUint32(16, 16, true);
	view.setUint16(20, 1, true); // PCM
	view.setUint16(22, 1, true); // mono
	view.setUint32(24, rate, true);
	view.setUint32(28, rate * 2, true);
	view.setUint16(32, 2, true);
	view.setUint16(34, 16, true);
	text(36, 'data');
	view.setUint32(40, samples.length * 2, true);
	for (let i = 0; i < samples.length; i++) {
		const s = Math.max(-1, Math.min(1, samples[i]));
		view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
	}
	return bytes;
}

/** Whether this browser can record (a page on http:// other than localhost can't). */
export const canListen = () => typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && typeof AudioContext !== 'undefined';

export type Heard =
	| { kind: 'song'; answer: RecognizeResponse }
	| { kind: 'nothing' }
	| { kind: 'error'; message: string };

/**
 * Listens until the song is known, or [ATTEMPTS] run out. [ask] sends the sound (the host, in
 * tests a fake). Stops when [signal] aborts (Cancel); the microphone is off again when it returns.
 */
export async function listen(signal: AbortSignal, ask: (wav: Uint8Array<ArrayBuffer>) => Promise<RecognizeResponse> = recognize): Promise<Heard> {
	let stream: MediaStream;
	try {
		stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 } });
	} catch {
		return { kind: 'error', message: 'YTMP may not use the microphone. Allow it in the browser (or Android) settings.' };
	}
	const context = new AudioContext();
	const chunks: Float32Array[] = [];
	let recorded = 0;
	const source = context.createMediaStreamSource(stream);
	// ScriptProcessor: old but everywhere (Chrome 90 too), and enough for a few seconds of sound.
	const processor = context.createScriptProcessor(4096, 1, 1);
	processor.onaudioprocess = (e) => {
		const samples = new Float32Array(e.inputBuffer.getChannelData(0));
		chunks.push(samples);
		recorded += samples.length;
	};
	source.connect(processor);
	processor.connect(context.destination);
	const stop = () => {
		processor.disconnect();
		source.disconnect();
		stream.getTracks().forEach((t) => t.stop());
		context.close().catch(() => {});
	};
	const seconds = () => recorded / context.sampleRate;
	const wait = (s: number) =>
		new Promise<void>((resolve) => {
			const check = () => (signal.aborted || seconds() >= s ? resolve() : setTimeout(check, 100));
			check();
		});
	try {
		for (const at of ATTEMPTS) {
			await wait(at);
			if (signal.aborted) return { kind: 'nothing' };
			const all = new Float32Array(recorded);
			let offset = 0;
			for (const c of chunks) all.set(c, (offset += c.length) - c.length);
			try {
				const answer = await ask(wav(downsample(all, context.sampleRate, RATE), RATE));
				// A song, or one that isn't on YouTube Music (then the app searches for it).
				if (answer.song || answer.title) return { kind: 'song', answer };
			} catch (e) {
				// Shazam busy for a moment: the next try has more sound anyway.
				if (!(e instanceof ApiRequestError && e.status === 429)) return { kind: 'error', message: e instanceof Error ? e.message : 'Recognizing failed' };
			}
		}
		return { kind: 'nothing' };
	} finally {
		stop();
	}
}
